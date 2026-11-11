package net.thex.farm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * TreeFarm (/tf). Preview a tree farm with holograms, build it on confirm, remove one with /tf fell.
 *
 * /tf &lt;type&gt; &lt;W&gt; &lt;L&gt; [spacing] [lit]   preview
 * /tf confirm | cancel | relocate | fell | help
 */
public class SaplingFarm extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final String[] TYPE_TOKENS = {
        "oak", "spruce", "birch", "jungle", "acacia", "cherry", "mangrove", "pale_oak",
        "spruce_large", "jungle_large", "dark_oak", "random"
    };

    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, PendingFell> pendingFell = new HashMap<>();
    private final Random rng = new Random();
    private FarmStore store;
    private ClaimGuard guard;

    @Override
    public void onEnable() {
        try { saveDefaultConfig(); } catch (Exception ex) {
            getLogger().warning("No embedded config.yml (" + ex.getMessage() + "); using built-in defaults.");
        }
        store = new FarmStore(this);
        guard = makeGuard();
        PluginCommand cmd = getCommand("treefarm");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 10L);
        getLogger().info("TreeFarm enabled (claims=" + guard.getClass().getSimpleName()
                + ", farms tracked=" + store.all().size() + ")");
    }

    @Override
    public void onDisable() {
        for (Pending p : pending.values()) removeDisplays(p);
        pending.clear();
        pendingFell.clear();
        if (store != null) store.save();
    }

    /** Load the GP bridge only when GriefPrevention is actually installed. */
    private ClaimGuard makeGuard() {
        if (getServer().getPluginManager().getPlugin("GriefPrevention") != null) {
            try { return new GpGuard(); }
            catch (Throwable t) { getLogger().warning("GriefPrevention present but bridge failed: " + t); }
        }
        return (player, loc, mat) -> true;   // no claims plugin: everything allowed
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Pending p = pending.remove(e.getPlayer().getUniqueId());
        if (p != null) removeDisplays(p);
        pendingFell.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player;
        String[] rest;
        if (sender instanceof Player) {
            player = (Player) sender;
            if (!player.hasPermission("treefarm.use") && !player.hasPermission("saplingfarm.use")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to use that.");
                return true;
            }
            rest = args;
        } else {
            if (args.length < 1) { sender.sendMessage("Usage from console: /treefarm <player> <type|confirm|cancel|relocate|fell|help> ..."); return true; }
            player = getServer().getPlayerExact(args[0]);
            if (player == null) { sender.sendMessage("No online player named " + args[0] + "."); return true; }
            rest = Arrays.copyOfRange(args, 1, args.length);
        }

        if (rest.length == 0) { sendHelp(player); return true; }
        String sub = rest[0].toLowerCase();
        switch (sub) {
            case "help": case "?": sendHelp(player); return true;
            case "confirm": return doConfirm(player, sender);
            case "cancel":  return doCancel(player, sender);
            case "relocate": return doRelocate(player, sender);
            case "fell":    return doFell(player, sender, rest);
            default: break;
        }

        TypeSpec type = resolveType(rest[0]);
        if (type == null) {
            player.sendMessage(ChatColor.RED + "Unknown tree type '" + rest[0] + "'.");
            player.sendMessage(ChatColor.GRAY + "Types: " + typeList());
            return true;
        }

        List<Integer> nums = new ArrayList<>();
        boolean lit = false;
        for (int i = 1; i < rest.length; i++) {
            String t = rest[i], tl = t.toLowerCase();
            if (tl.equals("lit") || tl.equals("light") || tl.equals("torch") || tl.equals("torches")) lit = true;
            else if (tl.equals("unlit") || tl.equals("nolight") || tl.equals("dark")) lit = false;
            else {
                try { nums.add(Integer.parseInt(t)); }
                catch (NumberFormatException nfe) { player.sendMessage(ChatColor.RED + "Unknown argument '" + t + "'."); return true; }
            }
        }
        if (nums.size() < 2) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /tf " + type.token() + " <W> <L> [spacing] [lit]   (W,L " + minSize() + ".." + maxSize() + ")");
            return true;
        }

        int minS = minSize(), maxS = maxSize(), maxSp = maxSpacing();
        int W = nums.get(0), L = nums.get(1);
        if (W < minS || L < minS || W > maxS || L > maxS) {
            player.sendMessage(ChatColor.RED + "W and L must be between " + minS + " and " + maxS + ".");
            return true;
        }
        int minSp = minSpacing(type), defSp = defaultSpacing(type);
        int spacing = nums.size() >= 3 ? nums.get(2) : defSp;
        if (spacing < minSp) { player.sendMessage(ChatColor.RED + "Minimum spacing for " + type.label + " is " + minSp + " (you gave " + spacing + ")."); return true; }
        if (spacing > maxSp) { player.sendMessage(ChatColor.RED + "Maximum spacing is " + maxSp + " (you gave " + spacing + ")."); return true; }

        if (!isAdmin(player)) {
            int lim = farmLimit(player);
            int have = store.countOwned(player.getUniqueId());
            if (have >= lim) {
                player.sendMessage(ChatColor.RED + "You already have " + have + "/" + lim + " tree farms. Remove one (/tf fell) or ask for a higher limit.");
                return true;
            }
        }

        Plan plan = buildPlan(player, type, W, L, spacing, lit);
        if (plan == null || (plan.trees.isEmpty() && plan.torches.isEmpty())) {
            player.sendMessage(ChatColor.RED + "No plantable ground found in front of you.");
            return true;
        }

        if (!claimOk(player, plan)) {
            player.sendMessage(ChatColor.RED + "You can't build a tree farm there - part of it is in a claim you can't build in.");
            return true;
        }

        FarmStore.Farm clash = overlappingFarm(plan);
        if (clash != null) {
            player.sendMessage(ChatColor.RED + "That would overlap an existing tree farm. Pick a clear spot.");
            return true;
        }

        Pending old = pending.remove(player.getUniqueId());
        if (old != null) removeDisplays(old);
        Pending p = new Pending();
        p.plan = plan;
        p.createdAt = System.currentTimeMillis();
        if (getConfig().getBoolean("preview.use-block-display", true)) p.displays = spawnPreview(plan);
        pending.put(player.getUniqueId(), p);
        showPreview(player, plan, sender);
        return true;
    }

    private boolean doConfirm(Player player, CommandSender sender) {
        PendingFell pf = pendingFell.get(player.getUniqueId());
        if (pf != null) {
            pendingFell.remove(player.getUniqueId());
            if (System.currentTimeMillis() > pf.expiresAt) {
                player.sendMessage(ChatColor.YELLOW + "That removal expired. Stand inside the farm and run /tf fell again.");
                return true;
            }
            FarmStore.Farm farm = store.get(pf.farmId);
            if (farm == null) { player.sendMessage(ChatColor.YELLOW + "That tree farm no longer exists."); return true; }
            if (!canManage(player, farm)) { player.sendMessage(ChatColor.RED + "That tree farm isn't yours."); return true; }
            int removed = removeFarm(farm, player);
            store.remove(farm.id);
            String msg = ChatColor.GREEN + "Tree farm removed (" + removed + " block(s) cleared, torches returned).";
            player.sendMessage(msg);
            if (sender != player) sender.sendMessage(msg);
            return true;
        }

        Pending p = pending.get(player.getUniqueId());
        if (p == null) { player.sendMessage(ChatColor.YELLOW + "Nothing to confirm. Run /tf <type> <W> <L> [spacing] [lit] first."); return true; }
        Plan plan = p.plan;

        Map<Material, Integer> need = new EnumMap<>(plan.saplingNeed);
        if (!plan.torches.isEmpty()) need.merge(Material.TORCH, plan.torches.size(), Integer::sum);
        Map<Material, Integer> miss = missing(player, need);
        if (!miss.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You don't have the items yet:");
            for (Map.Entry<Material, Integer> e : miss.entrySet()) player.sendMessage(ChatColor.RED + "  " + e.getValue() + " x " + pretty(e.getKey()));
            player.sendMessage(ChatColor.YELLOW + "Get them, then run /tf confirm again.");
            return true;
        }
        if (!isAdmin(player) && store.countOwned(player.getUniqueId()) >= farmLimit(player)) {
            player.sendMessage(ChatColor.RED + "Farm limit reached - remove one with /tf fell first.");
            return true;
        }
        if (!claimOk(player, plan)) {
            player.sendMessage(ChatColor.RED + "Part of the farm is now in a claim you can't build in. Preview cancelled.");
            removeDisplays(p); pending.remove(player.getUniqueId());
            return true;
        }
        if (overlappingFarm(plan) != null) {
            player.sendMessage(ChatColor.RED + "That spot now overlaps another tree farm. Preview cancelled.");
            removeDisplays(p); pending.remove(player.getUniqueId());
            return true;
        }

        FarmStore.Farm farm = applyPlan(player, plan);
        removeDisplays(p);
        pending.remove(player.getUniqueId());
        if (farm.trees.isEmpty() && farm.torches.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Nothing was placed - no valid ground, and no dirt for the spots that needed it.");
            return true;
        }
        store.add(farm);

        String msg = ChatColor.GREEN + "Built " + farm.trees.size() / plan.fs + " " + plan.type.label + " tree(s) ("
                + plan.W + "x" + plan.L + ", " + plan.spacing + " apart)" + (plan.lit ? ", lit" : "")
                + (farm.dirt.size() > 0 ? ", dirt used: " + farm.dirt.size() : "")
                + ChatColor.GRAY + ". Remove it with /tf fell while standing inside it.";
        player.sendMessage(msg);
        if (sender != player) sender.sendMessage(msg);
        return true;
    }

    private boolean doCancel(Player player, CommandSender sender) {
        Pending p = pending.remove(player.getUniqueId());
        if (p != null) removeDisplays(p);
        pendingFell.remove(player.getUniqueId());
        player.sendMessage(ChatColor.YELLOW + "Cancelled.");
        return true;
    }

    private boolean doRelocate(Player player, CommandSender sender) {
        Pending p = pending.get(player.getUniqueId());
        if (p == null) { player.sendMessage(ChatColor.YELLOW + "No preview to move. Run /tf <type> <W> <L> first."); return true; }
        Plan plan = p.plan;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE,
            minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (TreeSpot t : plan.trees) { minY = Math.min(minY, t.y); maxY = Math.max(maxY, t.y);
            for (int[] c : t.cols) { minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]); minZ = Math.min(minZ, c[1]); maxZ = Math.max(maxZ, c[1]); } }
        for (int[] tp : plan.torches) { minY = Math.min(minY, tp[1]); maxY = Math.max(maxY, tp[1]);
            minX = Math.min(minX, tp[0]); maxX = Math.max(maxX, tp[0]); minZ = Math.min(minZ, tp[2]); maxZ = Math.max(maxZ, tp[2]); }
        if (minX == Integer.MAX_VALUE) { player.sendMessage(ChatColor.YELLOW + "Nothing to move."); return true; }

        Location feet = player.getLocation();
        int bx = feet.getBlockX(), by = feet.getBlockY(), bz = feet.getBlockZ();
        int[][] corners = { {minX,minY,minZ},{minX,minY,maxZ},{minX,maxY,minZ},{minX,maxY,maxZ},
                            {maxX,minY,minZ},{maxX,minY,maxZ},{maxX,maxY,minZ},{maxX,maxY,maxZ} };
        int[] near = corners[0]; double best = Double.MAX_VALUE;
        for (int[] c : corners) { double d = sq(c[0]-bx, c[1]-by, c[2]-bz); if (d < best) { best = d; near = c; } }
        int dx = bx - near[0], dy = by - near[1], dz = bz - near[2];
        if (dx == 0 && dy == 0 && dz == 0) { player.sendMessage(ChatColor.YELLOW + "Already at your feet."); return true; }

        for (TreeSpot t : plan.trees) { t.y += dy; for (int[] c : t.cols) { c[0] += dx; c[1] += dz; } }
        for (int[] tp : plan.torches) { tp[0] += dx; tp[1] += dy; tp[2] += dz; }
        plan.cx += dx; plan.cy += dy; plan.cz += dz;

        if (!claimOk(player, plan)) {
            player.sendMessage(ChatColor.RED + "Can't move it there - you can't build in that claim. (No change made.)");
            for (TreeSpot t : plan.trees) { t.y -= dy; for (int[] c : t.cols) { c[0] -= dx; c[1] -= dz; } }
            for (int[] tp : plan.torches) { tp[0] -= dx; tp[1] -= dy; tp[2] -= dz; }
            plan.cx -= dx; plan.cy -= dy; plan.cz -= dz;
            return true;
        }
        removeDisplays(p);
        if (getConfig().getBoolean("preview.use-block-display", true)) p.displays = spawnPreview(plan);
        player.sendMessage(ChatColor.GREEN + "Preview moved to your feet.");
        return true;
    }

    private boolean doFell(Player player, CommandSender sender, String[] rest) {
        FarmStore.Farm farm = farmContaining(player);
        if (farm == null) {
            player.sendMessage(ChatColor.YELLOW + "Stand inside the tree farm you want to remove first.");
            return true;
        }
        if (!canManage(player, farm)) {
            player.sendMessage(ChatColor.RED + "That tree farm isn't yours (owner " + farm.ownerName + ").");
            return true;
        }
        PendingFell pf = new PendingFell();
        pf.farmId = farm.id;
        pf.expiresAt = System.currentTimeMillis() + 120_000L;
        pendingFell.put(player.getUniqueId(), pf);
        player.sendMessage(ChatColor.GOLD + "Remove the " + farm.typeLabel + " tree farm here?");
        player.sendMessage(ChatColor.GREEN + "Run " + ChatColor.YELLOW + "/tf confirm" + ChatColor.GREEN
                + " within 120s to remove it (torches are returned to you), or " + ChatColor.YELLOW + "/tf cancel" + ChatColor.GREEN + ".");
        return true;
    }

    /** Owner, or someone who can build there (their claim), or an admin. */
    private boolean canManage(Player player, FarmStore.Farm farm) {
        if (isAdmin(player)) return true;
        if (farm.owner != null && farm.owner.equals(player.getUniqueId())) return true;
        World w = getServer().getWorld(farm.world);
        if (w != null && !farm.trees.isEmpty()) {
            return guard.canBuild(player, new Location(w, farm.trees.get(0)[0], farm.trees.get(0)[1], farm.trees.get(0)[2]), Material.DIRT);
        }
        return false;
    }

    /** The tracked farm whose bounds the player is standing inside, or null. */
    private FarmStore.Farm farmContaining(Player player) {
        World w = player.getWorld();
        Location loc = player.getLocation();
        int px = loc.getBlockX(), py = loc.getBlockY(), pz = loc.getBlockZ();
        FarmStore.Farm best = null;
        long bestArea = Long.MAX_VALUE;
        for (FarmStore.Farm f : store.all()) {
            if (f.world == null || !f.world.equalsIgnoreCase(w.getName())) continue;
            int[] b = farmBounds(f);
            if (b == null) continue;
            if (px < b[0] - 1 || px > b[1] + 1 || pz < b[2] - 1 || pz > b[3] + 1) continue;
            if (py < b[4] - 3 || py > b[5] + 10) continue;
            long area = (long) (b[1] - b[0] + 1) * (b[3] - b[2] + 1);
            if (area < bestArea) { bestArea = area; best = f; }
        }
        return best;
    }

    private int[] farmBounds(FarmStore.Farm f) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE,
            minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] p : f.trees) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]); }
        for (int[] p : f.torches) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]); }
        if (minX == Integer.MAX_VALUE) return null;
        return new int[]{ minX, maxX, minZ, maxZ, minY, maxY };
    }

    private int[] planBounds(Plan plan) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, minY = Integer.MAX_VALUE;
        for (TreeSpot t : plan.trees) { minY = Math.min(minY, t.y); for (int[] c : t.cols) { minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]); minZ = Math.min(minZ, c[1]); maxZ = Math.max(maxZ, c[1]); } }
        for (int[] tp : plan.torches) { minX = Math.min(minX, tp[0]); maxX = Math.max(maxX, tp[0]); minZ = Math.min(minZ, tp[2]); maxZ = Math.max(maxZ, tp[2]); minY = Math.min(minY, tp[1]); }
        if (minX == Integer.MAX_VALUE) return null;
        return new int[]{ minX, maxX, minZ, maxZ, minY, minY };
    }

    /** Any existing farm in the same world whose bounding box intersects the planned one. */
    private FarmStore.Farm overlappingFarm(Plan plan) {
        int[] pb = planBounds(plan);
        if (pb == null) return null;
        for (FarmStore.Farm f : store.all()) {
            if (f.world == null || !f.world.equalsIgnoreCase(plan.world.getName())) continue;
            int[] b = farmBounds(f);
            if (b == null) continue;
            if (pb[0] <= b[1] && b[0] <= pb[1] && pb[2] <= b[3] && b[2] <= pb[3]) return f;
        }
        return null;
    }

    private int removeFarm(FarmStore.Farm farm, Player player) {
        World world = getServer().getWorld(farm.world);
        if (world == null) return 0;
        int removed = removeTrees(world, farm);
        int torchesBack = 0;
        for (int[] tp : farm.torches) {
            Block b = world.getBlockAt(tp[0], tp[1], tp[2]);
            if (b.getType() == Material.TORCH) { b.setType(Material.AIR, false); removed++; torchesBack++; }
        }
        // refund every torch that was part of the farm, plus the ones we just pulled out
        int total = torchesBack;
        if (total > 0) {
            ItemStack stack = new ItemStack(Material.TORCH, total);
            Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
            for (ItemStack l : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), l);
            player.sendMessage(ChatColor.GREEN + "Returned " + total + " torch" + (total == 1 ? "" : "es") + " to your inventory.");
        }
        return removed;
    }

    /**
     * Remove the farm's trees. Two passes over the farm's recorded PLOT box:
     *  1. clear saplings, and seed a flood fill at every log that touches a leaf (tree wood);
     *  2. flood-fill the connected logs from those seeds, so trunks (which sit below the canopy and have
     *     no leaf adjacent) are removed as one piece with their branches.
     * A log that is not connected to any leaf is left alone, so a player build placed inside the plot is
     * preserved. Only log and sapling blocks are ever touched; leaves are left to decay naturally.
     */
    private int removeTrees(World world, FarmStore.Farm farm) {
        int removed = 0, cap = 40000;
        int x0 = farm.bx0, y0 = farm.by0, z0 = farm.bz0, x1 = farm.bx1, y1 = farm.by1, z1 = farm.bz1;
        if (x0 == -1) {
            // legacy farm recorded before plot boxes existed: derive a box from the recorded positions
            int mnX = Integer.MAX_VALUE, mxX = Integer.MIN_VALUE, mnY = Integer.MAX_VALUE, mnZ = Integer.MAX_VALUE, mxZ = Integer.MIN_VALUE;
            for (int[] p : farm.trees) { mnX = Math.min(mnX, p[0]); mxX = Math.max(mxX, p[0]); mnY = Math.min(mnY, p[1]); mnZ = Math.min(mnZ, p[2]); mxZ = Math.max(mxZ, p[2]); }
            for (int[] p : farm.torches) { mnX = Math.min(mnX, p[0]); mxX = Math.max(mxX, p[0]); mnY = Math.min(mnY, p[1]); mnZ = Math.min(mnZ, p[2]); mxZ = Math.max(mxZ, p[2]); }
            if (mnX == Integer.MAX_VALUE) return 0;
            x0 = mnX - 5; x1 = mxX + 5; z0 = mnZ - 5; z1 = mxZ + 5; y0 = mnY - 3; y1 = mnY + 80;
        }
        Set<Long> seen = new HashSet<>();
        Deque<int[]> q = new ArrayDeque<>();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (isSapling(m)) { world.getBlockAt(x, y, z).setType(Material.AIR, false); removed++; }
                    else if (isLog(m) && hasLeafNeighbor(world, x, y, z)) q.add(new int[]{ x, y, z });
                }
        while (!q.isEmpty() && removed < cap) {
            int[] p = q.poll();
            if (!seen.add(enc(p[0], p[1], p[2]))) continue;
            if (!isLog(world.getBlockAt(p[0], p[1], p[2]).getType())) continue;
            world.getBlockAt(p[0], p[1], p[2]).setType(Material.AIR, false); removed++;
            q.add(new int[]{ p[0] + 1, p[1], p[2] }); q.add(new int[]{ p[0] - 1, p[1], p[2] });
            q.add(new int[]{ p[0], p[1], p[2] + 1 }); q.add(new int[]{ p[0], p[1], p[2] - 1 });
            q.add(new int[]{ p[0], p[1] + 1, p[2] }); q.add(new int[]{ p[0], p[1] - 1, p[2] });
        }
        return removed;
    }

    private boolean hasLeafNeighbor(World world, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            if (isLeaf(world.getBlockAt(x + dx, y + dy, z + dz).getType())) return true;
        }
        return false;
    }

    private long enc(int x, int y, int z) {
        return (((long) x & 0x3FFFFFF) << 38) | (((long) z & 0x3FFFFFF) << 12) | ((long) y & 0xFFF);
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "TreeFarm");
        player.sendMessage(ChatColor.YELLOW + "/tf <type> <W> <L> [spacing] [lit]" + ChatColor.GRAY + " - preview (W,L " + minSize() + ".." + maxSize() + ", spacing up to " + maxSpacing() + ")");
        player.sendMessage(ChatColor.YELLOW + "/tf confirm" + ChatColor.GRAY + " - build it (takes saplings + torches from you)");
        player.sendMessage(ChatColor.YELLOW + "/tf cancel" + ChatColor.GRAY + " - discard the preview");
        player.sendMessage(ChatColor.YELLOW + "/tf relocate" + ChatColor.GRAY + " - move the preview's nearest corner to your feet");
        player.sendMessage(ChatColor.YELLOW + "/tf fell" + ChatColor.GRAY + " - stand inside a farm, then /tf confirm to remove it");
        player.sendMessage(ChatColor.YELLOW + "/tf help");
        player.sendMessage(ChatColor.GRAY + "Types: " + typeList());
    }

    // ------------------------------------------------------------------ claims / limits

    private boolean isAdmin(Player p) { return p.hasPermission("treefarm.admin"); }

    private int farmLimit(Player p) {
        int lim = getConfig().getInt("farm-limit", 3);
        if (p.hasPermission("treefarm.farmlimit.unlimited")) return Integer.MAX_VALUE;
        for (int n = 1000; n > lim; n--) if (p.hasPermission("treefarm.farmlimit." + n)) return n;
        return lim;
    }

    private boolean claimOk(Player p, Plan plan) {
        World w = plan.world;
        if (w == null || !guard.claimsActive(w)) return true;
        for (TreeSpot t : plan.trees) {
            for (int[] c : t.cols) {
                if (!guard.canBuild(p, new Location(w, c[0], t.y, c[1]), t.mat)) return false;
                // the dirt replacement (if any) sits directly below, in the same column/claim
            }
        }
        for (int[] tp : plan.torches) if (!guard.canBuild(p, new Location(w, tp[0], tp[1], tp[2]), Material.TORCH)) return false;
        return true;
    }

    // ------------------------------------------------------------------ planning

    private Plan buildPlan(Player player, TypeSpec type, int W, int L, int spacing, boolean lit) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return null;
        double yaw = Math.toRadians(loc.getYaw());
        int sx = (-Math.sin(yaw)) >= 0 ? 1 : -1;
        int sz = (Math.cos(yaw)) >= 0 ? 1 : -1;
        int cx = loc.getBlockX(), cy = loc.getBlockY(), cz = loc.getBlockZ();
        int fs = type.twoByTwo ? 2 : 1;

        List<TreeSpot> trees = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < W; i++) for (int j = 0; j < L; j++) {
            int u = spacing * i, v = spacing * j;
            List<int[]> cols = new ArrayList<>();
            for (int d = 0; d < fs; d++) for (int e = 0; e < fs; e++) cols.add(new int[]{ cx + sx*(u+d), cz + sz*(v+e) });
            boolean[] dirt = new boolean[cols.size()];
            Integer y = findGround(world, cols, cy, dirt);
            if (y == null) { skipped++; continue; }
            TreeSpot t = new TreeSpot();
            t.y = y; t.cols = cols; t.needsDirt = dirt;
            trees.add(t);
        }

        Map<Material, Integer> saplingNeed = new EnumMap<>(Material.class);
        int dirtMax = 0;
        if (type.random) {
            Map<Material, Integer> pool = availableSaplings(player, true);
            List<TreeSpot> keep = new ArrayList<>();
            for (TreeSpot t : trees) {
                Material m = pickRandom(pool);
                if (m == null) continue;
                t.mat = m; keep.add(t); saplingNeed.merge(m, 1, Integer::sum);
                for (boolean b : t.needsDirt) if (b) dirtMax++;
            }
            trees = keep;
        } else {
            for (TreeSpot t : trees) { t.mat = type.sapling; for (boolean b : t.needsDirt) if (b) dirtMax++; }
            if (type.sapling != null && !trees.isEmpty()) saplingNeed.merge(type.sapling, trees.size() * fs * fs, Integer::sum);
        }

        List<int[]> torches = new ArrayList<>();
        if (lit) {
            int ts = Math.max(1, getConfig().getInt("lit.spacing", 4));
            int half = ts / 2;
            int maxU = spacing * (W - 1) + fs - 1;
            int maxV = spacing * (L - 1) + fs - 1;
            for (int tu = half; tu <= maxU; tu += ts) for (int tv = half; tv <= maxV; tv += ts) {
                boolean onTree = false;
                for (int i = 0; i < W && !onTree; i++) for (int j = 0; j < L; j++)
                    if (tu >= spacing*i && tu <= spacing*i + fs - 1 && tv >= spacing*j && tv <= spacing*j + fs - 1) { onTree = true; break; }
                if (onTree) continue;
                int x = cx + sx*tu, z = cz + sz*tv;
                Integer y = findTorchGround(world, x, z, cy);
                if (y == null) continue;
                torches.add(new int[]{ x, y, z });
            }
        }

        Plan plan = new Plan();
        plan.world = world; plan.type = type;
        plan.W = W; plan.L = L; plan.spacing = spacing; plan.lit = lit; plan.fs = fs;
        plan.cx = cx; plan.cy = cy; plan.cz = cz; plan.sx = sx; plan.sz = sz;
        plan.trees = trees; plan.torches = torches; plan.saplingNeed = saplingNeed; plan.skipped = skipped;
        plan.dirtMax = dirtMax;
        plan.owner = player.getUniqueId(); plan.ownerName = player.getName();
        return plan;
    }

    private Integer findGround(World world, List<int[]> cols, int cy, boolean[] needsDirt) {
        for (int y = cy + 8; y >= cy - 16; y--) {
            boolean ok = true;
            for (int i = 0; i < cols.size(); i++) {
                int[] c = cols.get(i);
                if (!isFree(world.getBlockAt(c[0], y, c[1]).getType())) { ok = false; break; }
                Material below = world.getBlockAt(c[0], y - 1, c[1]).getType();
                if (isSoil(below)) needsDirt[i] = false;
                else if (isSolidSurface(below)) needsDirt[i] = true;
                else { ok = false; break; }
            }
            if (ok) return y;
        }
        return null;
    }

    private Integer findTorchGround(World world, int x, int z, int cy) {
        for (int y = cy + 8; y >= cy - 16; y--) {
            if (!isFree(world.getBlockAt(x, y, z).getType())) continue;
            Material below = world.getBlockAt(x, y - 1, z).getType();
            if (isSoil(below) || isSolidSurface(below)) return y;
        }
        return null;
    }

    private FarmStore.Farm applyPlan(Player player, Plan plan) {
        FarmStore.Farm farm = new FarmStore.Farm();
        farm.id = UUID.randomUUID().toString();
        farm.owner = player.getUniqueId();
        farm.ownerName = player.getName();
        farm.world = plan.world.getName();
        farm.created = System.currentTimeMillis();
        farm.typeLabel = plan.type.label;
        farm.W = plan.W; farm.L = plan.L;
        // record the allocated plot box (whole tree grid plus a canopy margin) so /tf fell can clear it later
        int fm = 5;
        int uMax = plan.spacing * (plan.W - 1) + (plan.fs - 1);
        int vMax = plan.spacing * (plan.L - 1) + (plan.fs - 1);
        int ex = plan.cx + plan.sx * uMax, ez = plan.cz + plan.sz * vMax;
        int yb = plan.cy;
        for (TreeSpot t : plan.trees) yb = Math.min(yb, t.y);
        farm.bx0 = Math.min(plan.cx, ex) - fm; farm.bx1 = Math.max(plan.cx, ex) + fm;
        farm.bz0 = Math.min(plan.cz, ez) - fm; farm.bz1 = Math.max(plan.cz, ez) + fm;
        farm.by0 = yb - 3; farm.by1 = yb + 80;

        int dirtAvail = countItem(player, Material.DIRT);
        Map<Material, Integer> used = new EnumMap<>(Material.class);
        int treeCount = 0;
        for (TreeSpot t : plan.trees) {
            int need = 0;
            for (boolean b : t.needsDirt) if (b) need++;
            if (need > 0 && dirtAvail < need) continue;
            for (int i = 0; i < t.cols.size(); i++) if (t.needsDirt[i]) {
                plan.world.getBlockAt(t.cols.get(i)[0], t.y - 1, t.cols.get(i)[1]).setType(Material.DIRT, false);
                farm.dirt.add(new int[]{ t.cols.get(i)[0], t.y - 1, t.cols.get(i)[1] });
                dirtAvail--;
            }
            for (int i = 0; i < t.cols.size(); i++) {
                int[] c = t.cols.get(i);
                plan.world.getBlockAt(c[0], t.y, c[1]).setType(t.mat, false);
                farm.trees.add(new int[]{ c[0], t.y, c[1] });
            }
            used.merge(t.mat, t.cols.size(), Integer::sum);
            treeCount++;
        }
        for (int[] tp : plan.torches) {
            plan.world.getBlockAt(tp[0], tp[1], tp[2]).setType(Material.TORCH, false);
            farm.torches.add(tp);
            used.merge(Material.TORCH, 1, Integer::sum);
        }
        if (!farm.dirt.isEmpty()) used.merge(Material.DIRT, farm.dirt.size(), Integer::sum);
        removeItems(player, used);
        return farm;
    }

    // ------------------------------------------------------------------ preview

    private List<Entity> spawnPreview(Plan plan) {
        List<Entity> list = new ArrayList<>();
        World w = plan.world;
        for (TreeSpot t : plan.trees) {
            int[] c = t.cols.get(0);
            Material mat = t.mat != null ? t.mat : Material.OAK_SAPLING;
            list.add(w.spawn(new Location(w, c[0], t.y, c[1]), BlockDisplay.class, d -> {
                d.setBlock(mat.createBlockData());
                d.setBrightness(new Display.Brightness(15, 15));
                d.setPersistent(false);
                d.setTransformation(mini());
            }));
        }
        for (int[] tp : plan.torches) {
            list.add(w.spawn(new Location(w, tp[0], tp[1], tp[2]), BlockDisplay.class, d -> {
                d.setBlock(Material.TORCH.createBlockData());
                d.setBrightness(new Display.Brightness(15, 15));
                d.setPersistent(false);
                d.setTransformation(mini());
            }));
        }
        int maxY = plan.cy;
        for (TreeSpot t : plan.trees) maxY = Math.max(maxY, t.y);
        double midU = (plan.spacing * (plan.W - 1) + (plan.fs - 1)) / 2.0;
        double midV = (plan.spacing * (plan.L - 1) + (plan.fs - 1)) / 2.0;
        Location tl = new Location(w, plan.cx + plan.sx * midU + 0.5, maxY + 3.0, plan.cz + plan.sz * midV + 0.5);
        list.add(w.spawn(tl, TextDisplay.class, d -> {
            d.text(Component.text(plan.label() + "  -  /tf confirm", NamedTextColor.GOLD));
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(true);
            d.setPersistent(false);
        }));
        return list;
    }

    private Transformation mini() {
        return new Transformation(new Vector3f(0.25f, 0f, 0.25f), new AxisAngle4f(0f, 0f, 0f, 1f),
                new Vector3f(0.5f, 0.5f, 0.5f), new AxisAngle4f(0f, 0f, 0f, 1f));
    }

    private void showPreview(Player player, Plan plan, CommandSender sender) {
        player.sendMessage(ChatColor.GOLD + "Tree farm preview: " + ChatColor.WHITE + plan.label()
                + ChatColor.GRAY + "  (" + plan.trees.size() + " tree(s)" + (plan.skipped > 0 ? ", " + plan.skipped + " skipped" : "") + ")");
        player.sendMessage(ChatColor.YELLOW + "Requires from your inventory:");
        Map<Material, Integer> have = count(player);
        for (Map.Entry<Material, Integer> e : plan.saplingNeed.entrySet()) {
            int h = have.getOrDefault(e.getKey(), 0);
            player.sendMessage((h >= e.getValue() ? ChatColor.GREEN : ChatColor.RED) + "  " + e.getValue() + " x " + pretty(e.getKey()) + ChatColor.GRAY + " (you have " + h + ")");
        }
        if (!plan.torches.isEmpty()) {
            int h = have.getOrDefault(Material.TORCH, 0);
            player.sendMessage((h >= plan.torches.size() ? ChatColor.GREEN : ChatColor.RED) + "  " + plan.torches.size() + " x Torch" + ChatColor.GRAY + " (you have " + h + ")");
        }
        if (plan.dirtMax > 0) {
            int h = have.getOrDefault(Material.DIRT, 0);
            player.sendMessage(ChatColor.YELLOW + "  " + plan.dirtMax + " x Dirt " + ChatColor.GRAY + "(optional; " + Math.min(h, plan.dirtMax)
                    + " available - spots without soil need it, otherwise they are skipped)");
        }
        player.sendMessage(ChatColor.GREEN + "Run /tf confirm to build, /tf cancel to abort, /tf relocate to move it.");
        if (sender != player) sender.sendMessage("Preview sent to " + player.getName() + ".");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long timeoutMs = getConfig().getLong("confirm-timeout-seconds", 120) * 1000L;
        boolean particles = getConfig().getBoolean("preview.use-particles", true);
        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> e = it.next();
            Pending p = e.getValue();
            if (now - p.createdAt > timeoutMs) {
                Player pl = getServer().getPlayer(e.getKey());
                if (pl != null) pl.sendMessage(ChatColor.YELLOW + "Tree farm preview expired (run /tf again).");
                removeDisplays(p);
                it.remove();
                continue;
            }
            if (particles) drawBorder(p.plan);
        }
    }

    private void drawBorder(Plan plan) {
        int maxU = plan.spacing * (plan.W - 1) + plan.fs - 1;
        int maxV = plan.spacing * (plan.L - 1) + plan.fs - 1;
        double baseY = plan.trees.isEmpty() ? plan.cy + 1 : plan.trees.get(0).y + 0.1;
        for (int u = 0; u <= maxU; u += 2) { dot(plan, u, 0, baseY); dot(plan, u, maxV, baseY); }
        for (int v = 0; v <= maxV; v += 2) { dot(plan, 0, v, baseY); dot(plan, maxU, v, baseY); }
    }

    private void dot(Plan plan, int u, int v, double y) {
        plan.world.spawnParticle(Particle.END_ROD, plan.cx + plan.sx * u + 0.5, y, plan.cz + plan.sz * v + 0.5, 1, 0, 0, 0, 0);
    }

    private void removeDisplays(Pending p) {
        if (p.displays == null) return;
        for (Entity en : p.displays) if (en != null && !en.isDead()) en.remove();
        p.displays.clear();
    }

    // ------------------------------------------------------------------ inventory

    private Map<Material, Integer> count(Player player) {
        Map<Material, Integer> have = new EnumMap<>(Material.class);
        PlayerInventory inv = player.getInventory();
        for (ItemStack it : inv.getStorageContents()) if (it != null) have.merge(it.getType(), it.getAmount(), Integer::sum);
        for (ItemStack it : inv.getExtraContents()) if (it != null) have.merge(it.getType(), it.getAmount(), Integer::sum);
        return have;
    }

    private int countItem(Player p, Material m) { return count(p).getOrDefault(m, 0); }

    private Map<Material, Integer> missing(Player player, Map<Material, Integer> need) {
        Map<Material, Integer> have = count(player);
        Map<Material, Integer> miss = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Integer> e : need.entrySet())
            if (have.getOrDefault(e.getKey(), 0) < e.getValue()) miss.put(e.getKey(), e.getValue() - have.getOrDefault(e.getKey(), 0));
        return miss;
    }

    private int removeItems(Player player, Map<Material, Integer> need) {
        Inventory inv = player.getInventory();
        ItemStack[] items = inv.getStorageContents();
        int total = 0;
        for (Map.Entry<Material, Integer> e : need.entrySet()) {
            int remaining = e.getValue();
            for (int i = 0; i < items.length && remaining > 0; i++) {
                ItemStack it = items[i];
                if (it == null || it.getType() != e.getKey()) continue;
                int take = Math.min(it.getAmount(), remaining);
                remaining -= take; total += take;
                int left = it.getAmount() - take;
                if (left <= 0) items[i] = null; else it.setAmount(left);
            }
        }
        inv.setStorageContents(items);
        return total;
    }

    private Map<Material, Integer> availableSaplings(Player player, boolean excludeDarkOak) {
        Map<Material, Integer> pool = new EnumMap<>(Material.class);
        for (ItemStack it : player.getInventory().getStorageContents()) {
            if (it == null || !isSapling(it.getType())) continue;
            if (excludeDarkOak && it.getType() == Material.DARK_OAK_SAPLING) continue;
            pool.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        return pool;
    }

    private Material pickRandom(Map<Material, Integer> pool) {
        List<Material> keys = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : pool.entrySet()) if (e.getValue() > 0) keys.add(e.getKey());
        if (keys.isEmpty()) return null;
        Material m = keys.get(rng.nextInt(keys.size()));
        pool.put(m, pool.get(m) - 1);
        return m;
    }

    // ------------------------------------------------------------------ types

    private TypeSpec resolveType(String tok) {
        String k = tok.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (k.endsWith("sapling")) k = k.substring(0, k.length() - 7);
        switch (k) {
            case "oak": return single("Oak", Material.OAK_SAPLING);
            case "birch": return single("Birch", Material.BIRCH_SAPLING);
            case "spruce": return single("Spruce", Material.SPRUCE_SAPLING);
            case "jungle": return single("Jungle", Material.JUNGLE_SAPLING);
            case "acacia": return single("Acacia", Material.ACACIA_SAPLING);
            case "cherry": return single("Cherry", Material.CHERRY_SAPLING);
            case "mangrove": return single("Mangrove", Material.MANGROVE_PROPAGULE);
            case "paleoak": { Material m = Material.getMaterial("PALE_OAK_SAPLING"); return m == null ? null : single("Pale Oak", m); }
            case "sprucelarge": case "largespruce": return large("Large Spruce", Material.SPRUCE_SAPLING);
            case "junglelarge": case "largejungle": return large("Large Jungle", Material.JUNGLE_SAPLING);
            case "darkoak": case "darkoaklarge": case "dark": return large("Dark Oak", Material.DARK_OAK_SAPLING);
            case "random": { TypeSpec t = new TypeSpec(); t.label = "Random"; t.random = true; return t; }
            default: return null;
        }
    }

    private TypeSpec single(String label, Material s) { TypeSpec t = new TypeSpec(); t.label = label; t.sapling = s; return t; }
    private TypeSpec large(String label, Material s) { TypeSpec t = new TypeSpec(); t.label = label; t.sapling = s; t.twoByTwo = true; return t; }

    private String typeList() { return String.join(", ", TYPE_TOKENS); }
    private int minSpacing(TypeSpec t) { return t.twoByTwo ? getConfig().getInt("spacing.large-min", 6) : getConfig().getInt("spacing.single-min", 3); }
    private int defaultSpacing(TypeSpec t) { return t.twoByTwo ? getConfig().getInt("spacing.large-default", 7) : getConfig().getInt("spacing.single-default", 4); }
    private int maxSpacing() { return getConfig().getInt("spacing.max", 15); }
    private int minSize() { return getConfig().getInt("limits.min-size", 2); }
    private int maxSize() { return getConfig().getInt("limits.max-size", 20); }

    // ------------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase();
            for (String s : new String[]{ "help", "confirm", "cancel", "relocate", "fell" }) if (s.startsWith(pre)) out.add(s);
            for (String s : TYPE_TOKENS) if (s.startsWith(pre)) out.add(s);
        } else if (resolveType(args[0]) != null) {
            boolean hasLit = false;
            for (int i = 1; i < args.length; i++) if (args[i].equalsIgnoreCase("lit")) hasLit = true;
            String pre = args[args.length - 1].toLowerCase();
            if (!hasLit && "lit".startsWith(pre)) out.add("lit");
        }
        Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------------ block helpers

    private boolean isSapling(Material m) { String n = m.name(); return n.endsWith("_SAPLING") || n.equals("MANGROVE_PROPAGULE"); }
    private boolean isLog(Material m) { String n = m.name(); return n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE"); }
    private boolean isLeaf(Material m) { String n = m.name(); return n.endsWith("_LEAVES") || n.equals("NETHER_WART_BLOCK") || n.equals("WARPED_WART_BLOCK"); }

    private boolean isSoil(Material m) {
        switch (m.name()) {
            case "DIRT": case "GRASS_BLOCK": case "PODZOL": case "COARSE_DIRT": case "ROOTED_DIRT":
            case "MUD": case "MUDDY_MANGROVE_ROOTS": case "MOSS_BLOCK": case "MYCELIUM": case "FARMLAND":
                return true;
            default: return false;
        }
    }

    private boolean isFree(Material m) {
        if (m.isAir()) return true;
        if (m == Material.WATER || m == Material.LAVA) return false;
        if (m.name().endsWith("_LEAVES")) return false;
        return !m.isSolid();
    }

    private boolean isSolidSurface(Material m) {
        if (m.isAir()) return false;
        if (m == Material.WATER || m == Material.LAVA) return false;
        if (m.name().endsWith("_LEAVES")) return false;
        return m.isSolid();
    }

    private double sq(double a, double b, double c) { return a*a + b*b + c*c; }

    private String pretty(Material m) { String s = m.name().toLowerCase().replace('_', ' '); return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    // ------------------------------------------------------------------ data

    private static final class TypeSpec {
        String label; Material sapling; boolean twoByTwo; boolean random;
        String token() { return label.toLowerCase().replace(' ', '_'); }
    }

    private static final class TreeSpot {
        int y; List<int[]> cols; boolean[] needsDirt; Material mat;
    }

    private static final class Plan {
        World world; TypeSpec type;
        int W, L, spacing, fs, skipped, dirtMax;
        boolean lit;
        int cx, cy, cz, sx, sz;
        UUID owner; String ownerName;
        List<TreeSpot> trees; List<int[]> torches; Map<Material, Integer> saplingNeed;
        String label() { return type.label + "  " + W + "x" + L + ", " + spacing + " apart" + (lit ? ", lit" : ""); }
    }

    private static final class Pending {
        Plan plan; long createdAt; List<Entity> displays = new ArrayList<>();
    }

    private static final class PendingFell {
        String farmId; long expiresAt;
    }
}
