package net.thex.farm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persists every placed tree farm so /tf fell can find and remove it, and so limits can be counted. */
public class FarmStore {

    public static final class Farm {
        public String id;
        public UUID owner;
        public String ownerName;
        public String world;
        public long created;
        public String typeLabel = "Tree";
        public int W, L;
        public final List<int[]> trees = new ArrayList<>();    // sapling base positions (all columns)
        public final List<int[]> torches = new ArrayList<>();
        public final List<int[]> dirt = new ArrayList<>();      // blocks we replaced with dirt

        // Allocated plot box (the whole tree grid plus a canopy margin), recorded at build time so
        // /tf fell can clear the whole area even if a tree's record is missing. -1 means unset.
        public int bx0 = -1, by0 = -1, bz0 = -1, bx1 = 1, by1 = 1, bz1 = 1;

        public int nearestDistanceSq(org.bukkit.Location l) {
            int best = Integer.MAX_VALUE;
            for (int[] t : trees) best = Math.min(best, distSq(l, t));
            for (int[] t : torches) best = Math.min(best, distSq(l, t));
            return best;
        }
        private static int distSq(org.bukkit.Location l, int[] p) {
            double dx = l.getX() - p[0], dy = l.getY() - p[1], dz = l.getZ() - p[2];
            return (int) (dx*dx + dy*dy + dz*dz);
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Farm> farms = new LinkedHashMap<>();

    public FarmStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "farms.yml");
        load();
    }

    public Collection<Farm> all() { return farms.values(); }

    public Farm get(String id) { return farms.get(id); }

    public void add(Farm f) { farms.put(f.id, f); save(); }

    public boolean remove(String id) {
        if (farms.remove(id) != null) { save(); return true; }
        return false;
    }

    public int countOwned(UUID owner) {
        int n = 0;
        for (Farm f : farms.values()) if (f.owner.equals(owner)) n++;
        return n;
    }

    /** Nearest farm in the same world within maxDist blocks, or null. */
    public Farm nearest(String world, org.bukkit.Location loc, int maxDist) {
        Farm best = null; int bestD = maxDist * maxDist;
        for (Farm f : farms.values()) {
            if (f.world == null || !f.world.equalsIgnoreCase(world)) continue;
            int d = f.nearestDistanceSq(loc);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("farms");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Farm f = new Farm();
            f.id = id;
            try { f.owner = UUID.fromString(s.getString("owner")); } catch (Exception e) { continue; }
            f.ownerName = s.getString("owner-name", "?");
            f.world = s.getString("world");
            f.created = s.getLong("created");
            f.typeLabel = s.getString("type", "Tree");
            f.W = s.getInt("w", 0);
            f.L = s.getInt("l", 0);
            readList(s.getStringList("trees"), f.trees);
            readList(s.getStringList("torches"), f.torches);
            readList(s.getStringList("dirt"), f.dirt);
            f.bx0 = s.getInt("bx0", -1); f.by0 = s.getInt("by0", -1); f.bz0 = s.getInt("bz0", -1);
            f.bx1 = s.getInt("bx1", 1); f.by1 = s.getInt("by1", 1); f.bz1 = s.getInt("bz1", 1);
            farms.put(id, f);
        }
    }

    private void readList(List<String> src, List<int[]> dst) {
        for (String s : src) {
            String[] p = s.split(",");
            if (p.length != 3) continue;
            try { dst.add(new int[]{ Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]) }); }
            catch (NumberFormatException ignored) {}
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Farm f : farms.values()) {
            String base = "farms." + f.id + ".";
            y.set(base + "owner", f.owner.toString());
            y.set(base + "owner-name", f.ownerName);
            y.set(base + "world", f.world);
            y.set(base + "created", f.created);
            y.set(base + "type", f.typeLabel);
            y.set(base + "w", f.W);
            y.set(base + "l", f.L);
            y.set(base + "trees", writeList(f.trees));
            y.set(base + "torches", writeList(f.torches));
            y.set(base + "dirt", writeList(f.dirt));
            y.set(base + "bx0", f.bx0); y.set(base + "by0", f.by0); y.set(base + "bz0", f.bz0);
            y.set(base + "bx1", f.bx1); y.set(base + "by1", f.by1); y.set(base + "bz1", f.bz1);
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save farms.yml: " + e.getMessage());
        }
    }

    private List<String> writeList(List<int[]> src) {
        List<String> out = new ArrayList<>();
        for (int[] p : src) out.add(p[0] + "," + p[1] + "," + p[2]);
        return out;
    }
}
