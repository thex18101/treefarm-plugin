package net.thex.farm;

import static net.thex.farm.FarmStore.*;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Sapling;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

final class GrowthTracker implements Listener {
    private final JavaPlugin plugin;
    private final FarmStore store;
    private final List<Growth> pending = new ArrayList<>();

    GrowthTracker(JavaPlugin plugin, FarmStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // Natural leaf-distance updates and sapling growth stages do not change ownership.
    static String signature(BlockData input) {
        BlockData data = input.clone();
        if (data instanceof Leaves l) l.setDistance(l.getMinimumDistance());
        if (data instanceof Sapling s) s.setStage(0);
        return data.getAsString();
    }

    static boolean seed(Material m) {
        return Tag.SAPLINGS.isTagged(m) || m == Material.MANGROVE_PROPAGULE;
    }

    private static boolean treePart(Material m) {
        return Tag.LOGS.isTagged(m)
                || Tag.LEAVES.isTagged(m)
                || m == Material.MANGROVE_ROOTS
                || m == Material.MUDDY_MANGROVE_ROOTS
                || m == Material.VINE
                || m == Material.MANGROVE_PROPAGULE;
    }

    private record Before(String state, Material material, Owned owner) {}

    private static final class Growth {
        StructureGrowEvent event;
        BlockFertilizeEvent fertilizer;
        String farm;
        Map<Pos, Before> before = new HashMap<>();
        Set<Pos> edited = new HashSet<>();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void grow(StructureGrowEvent e) {
        if (store.all().isEmpty()) return;
        Block origin = e.getLocation().getBlock();
        Growth g = new Growth();
        g.event = e;
        Owned root = store.at(Pos.of(origin));
        if (root != null
                && seed(origin.getType())
                && root.data().equals(signature(origin.getBlockData()))) g.farm = root.farm();
        for (BlockState s : e.getBlocks()) {
            Block b = s.getBlock();
            Pos p = Pos.of(b);
            Owned old = store.at(p);
            if (old == null && g.farm == null) continue;
            g.before.put(p, new Before(signature(b.getBlockData()), b.getType(), old));
        }
        // If a giant grew from a mixed set of player and farm saplings, relinquish the consumed
        // seeds.
        if (g.farm != null)
            for (Before b : g.before.values()) {
                if (seed(b.material) && (b.owner == null || !g.farm.equals(b.owner.farm()))) {
                    g.farm = null;
                    break;
                }
            }
        if (g.before.isEmpty()) return;
        pending.add(g);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void fertilize(BlockFertilizeEvent e) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            Growth g = pending.get(i);
            if (g.fertilizer == null && g.event.getBlocks() == e.getBlocks()) {
                g.fertilizer = e;
                break;
            }
        }
    }

    void flush() {
        for (Growth g : List.copyOf(pending)) finish(g);
    }

    private void finish(Growth g) {
        if (!pending.remove(g)
                || g.event.isCancelled()
                || (g.fertilizer != null && g.fertilizer.isCancelled())) return;
        List<Pos> forget = new ArrayList<>();
        List<Owned> add = new ArrayList<>();
        for (BlockState s : g.fertilizer == null ? g.event.getBlocks() : g.fertilizer.getBlocks()) {
            Block b = s.getBlock();
            Pos p = Pos.of(b);
            Before old = g.before.get(p);
            if (old == null || g.edited.contains(p)) continue;
            String expected = signature(s.getBlockData());
            if (old.state.equals(expected) || !expected.equals(signature(b.getBlockData())))
                continue;
            Owned live = store.at(p);
            if (!Objects.equals(old.owner, live)) continue;
            if (live != null) forget.add(p);
            if (g.farm == null || store.get(g.farm) == null || !treePart(s.getType())) continue;
            String restore;
            if (live != null && live.farm().equals(g.farm) && live.data().equals(old.state))
                restore = live.restore();
            else if (old.material.isAir()
                    || old.material == Material.WATER
                    || (old.material == Material.MUD
                            && s.getType() == Material.MUDDY_MANGROVE_ROOTS)) restore = old.state;
            else continue; // Existing foliage/builds do not become property of a farm.
            add.add(new Owned(p, g.farm, expected, restore));
        }
        store.change(forget, add);
    }

    // Also used by bulk editors via SaplingFarm.forgetEditedBlocks before replacing blocks.
    void forget(Collection<Block> blocks) {
        if (store.all().isEmpty()) return;
        Set<Pos> remove = new HashSet<>();
        for (Block b : blocks) {
            Pos p = Pos.of(b);
            for (Growth g : pending) if (g.before.containsKey(p)) g.edited.add(p);
            if (store.at(p) != null) remove.add(p);
        }
        store.change(remove, List.of());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (!e.canBuild()) return;
        if (e instanceof BlockMultiPlaceEvent m)
            forget(m.getReplacedBlockStates().stream().map(BlockState::getBlock).toList());
        else forget(List.of(e.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent e) {
        forget(List.of(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void decay(LeavesDecayEvent e) {
        forget(List.of(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) {
        forget(List.of(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) {
        forget(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(BlockExplodeEvent e) {
        forget(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent e) {
        forget(List.of(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void bucket(PlayerBucketFillEvent e) {
        forget(List.of(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void interact(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK
                || e.useItemInHand() == Event.Result.DENY
                || e.getItem() == null) return;
        Block b = e.getClickedBlock();
        if (b == null
                || !Tag.LOGS.isTagged(b.getType())
                || !e.getItem().getType().name().endsWith("_AXE")) return;
        Pos p = Pos.of(b);
        String before = signature(b.getBlockData());
        Owned previous = store.at(p);
        plugin.getServer()
                .getScheduler()
                .runTask(
                        plugin,
                        () -> {
                            // A denied interaction or a click with no effect keeps the existing
                            // ownership.
                            if (!before.equals(signature(b.getBlockData()))
                                    && Objects.equals(previous, store.at(p))) forget(List.of(b));
                        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void brokenByBlock(io.papermc.paper.event.block.BlockBreakBlockEvent e) {
        forget(List.of(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent e) {
        forget(List.of(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent e) {
        piston(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent e) {
        piston(e.getBlocks(), e.getDirection());
    }

    private void piston(List<Block> moved, BlockFace direction) {
        List<Block> blocks = new ArrayList<>(moved);
        for (Block b : moved) blocks.add(b.getRelative(direction));
        forget(blocks);
    }
}
