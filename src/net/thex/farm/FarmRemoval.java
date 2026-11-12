package net.thex.farm;

import static net.thex.farm.FarmStore.*;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.*;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

final class FarmRemoval {
    private final FarmStore store;
    private final ClaimGuard guard;
    private final Deque<Job> queue = new ArrayDeque<>();
    private final Set<String> active = new HashSet<>();
    private static final List<BlockFace> FACES =
            List.of(
                    BlockFace.NORTH,
                    BlockFace.SOUTH,
                    BlockFace.EAST,
                    BlockFace.WEST,
                    BlockFace.UP,
                    BlockFace.DOWN);

    FarmRemoval(FarmStore store, ClaimGuard guard) {
        this.store = store;
        this.guard = guard;
    }

    private record Target(Owned row, List<Block> neighbors) {}

    private static final class Job {
        Farm farm;
        UUID player;
        int cleared, kept;
    }

    void start(Farm farm, Player player) {
        if (!active.add(farm.id)) {
            player.sendMessage("That farm is already being removed.");
            return;
        }
        Job job = new Job();
        job.farm = farm;
        job.player = player.getUniqueId();
        queue.add(job);
        player.sendMessage("Removing tracked farm blocks...");
    }

    void tick() {
        Job j = queue.poll();
        if (j == null) return;
        Player p = Bukkit.getPlayer(j.player);
        try {
            if (p == null
                    || !j.farm.in(p.getWorld())
                    || (!p.hasPermission("treefarm.admin")
                            && !p.getUniqueId().equals(j.farm.owner))) {
                if (p != null) p.sendMessage("Farm removal paused. Return to the plot to resume.");
                return;
            }
            List<Owned> rows = store.page(j.farm.id, 64);
            if (rows.isEmpty()) {
                store.remove(j.farm.id);
                p.sendMessage(
                        "Farm removed. Cleared "
                                + j.cleared
                                + " blocks and kept "
                                + j.kept
                                + " changed or supporting blocks."
                                + (j.farm.legacy
                                        ? " This legacy farm had no verified growth records; its"
                                              + " world blocks were preserved."
                                        : ""));
                return;
            }
            World world = p.getWorld();
            List<Pos> forget = new ArrayList<>();
            List<Target> clear = new ArrayList<>();
            long deadline = System.nanoTime() + 2_000_000L;
            boolean denied = false;
            for (Owned row : rows) {
                Block b = row.pos().block(world);
                if (!GrowthTracker.signature(b.getBlockData()).equals(row.data())
                        || supportsBuild(b)) {
                    j.kept++;
                } else if (!guard.canBuild(p, b.getLocation(), b.getType())) {
                    denied = true;
                    break;
                } else clear.add(new Target(row, dependentNeighbors(b, row.farm())));
                forget.add(row.pos());
                if (System.nanoTime() >= deadline) break;
            }
            // Retire first so an interrupted removal cannot later delete a replacement or refund
            // twice.
            store.change(forget, List.of());
            int torches = 0;
            for (Target target : clear) {
                Owned row = target.row();
                Block b = row.pos().block(world);
                if (!GrowthTracker.signature(b.getBlockData()).equals(row.data())) {
                    j.kept++;
                    continue;
                }
                boolean torch = b.getType() == Material.TORCH;
                BlockData previous = b.getBlockData();
                b.setBlockData(Bukkit.createBlockData(row.restore()), false);
                // Suppress neighbor physics until survival checks have run. Restore the support if
                // removing it would leave an unowned neighbor unable to survive at its location.
                if (target.neighbors().stream().anyMatch(n -> !n.getBlockData().isSupported(n))) {
                    b.setBlockData(previous, false);
                    j.kept++;
                    continue;
                }
                j.cleared++;
                if (torch) torches++;
            }
            if (torches > 0)
                for (ItemStack left :
                        p.getInventory().addItem(new ItemStack(Material.TORCH, torches)).values())
                    world.dropItemNaturally(p.getLocation(), left);
            if (denied) {
                p.sendMessage(
                        "Removal paused at a protected block. Remaining blocks are still tracked.");
                return;
            }
            queue.add(j);
            return;
        } finally {
            if (!queue.contains(j)) active.remove(j.farm.id);
        }
    }

    private List<Block> dependentNeighbors(Block b, String farm) {
        List<Block> neighbors = new ArrayList<>();
        for (BlockFace face : FACES) {
            Block other = b.getRelative(face);
            Material material = other.getType();
            if (material.isAir()
                    || material.isOccluding()
                    || other.isLiquid()
                    || Tag.LEAVES.isTagged(material)) continue;
            BlockData data = other.getBlockData();
            if (!data.isSupported(other)) continue;
            Owned owned = store.at(Pos.of(other));
            if (owned == null
                    || !farm.equals(owned.farm())
                    || !owned.data().equals(GrowthTracker.signature(data))) neighbors.add(other);
        }
        return neighbors;
    }

    private static boolean supportsBuild(Block support) {
        for (BlockFace face : FACES) {
            Block neighbor = support.getRelative(face);
            BlockData data = neighbor.getBlockData();
            // Hanging wall signs can hang from a support on either lateral side.
            if (data instanceof WallHangingSign s
                    && face.getModY() == 0
                    && s.getFacing() != face
                    && s.getFacing() != face.getOppositeFace()) return true;
            if (data instanceof WallSign s && s.getFacing() == face) return true;
            if (data instanceof org.bukkit.block.data.type.HangingSign && face == BlockFace.DOWN)
                return true;
            if (data instanceof org.bukkit.block.data.type.Sign && face == BlockFace.UP)
                return true;
            if (data instanceof FaceAttachable a) {
                switch (a.getAttachedFace()) {
                    case FLOOR:
                        if (face == BlockFace.UP) return true;
                        break;
                    case CEILING:
                        if (face == BlockFace.DOWN) return true;
                        break;
                    case WALL:
                        if (data instanceof Directional d && d.getFacing() == face) return true;
                        break;
                }
            }
            if (data instanceof Lantern l
                    && face == (l.isHanging() ? BlockFace.DOWN : BlockFace.UP)) return true;
            if (neighbor.getType().hasGravity() && face == BlockFace.UP) return true;
            if (data instanceof Directional d
                    && d.getFacing() == face
                    && (neighbor.getType().name().contains("WALL_")
                            || neighbor.getType() == Material.LADDER
                            || neighbor.getType() == Material.TRIPWIRE_HOOK)) return true;
            if (face == BlockFace.UP
                    && (neighbor.getType().name().endsWith("TORCH")
                            || Tag.RAILS.isTagged(neighbor.getType())
                            || Tag.PRESSURE_PLATES.isTagged(neighbor.getType())
                            || neighbor.getType() == Material.REDSTONE_WIRE)) return true;
        }
        // Entity bounding boxes also cover multi-block paintings.
        return !support.getWorld()
                .getNearbyEntities(support.getBoundingBox().expand(0.11), e -> e instanceof Hanging)
                .isEmpty();
    }
}
