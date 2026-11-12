package net.thex.farm;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

/** Plot bounds survive harvest. Only currently owned blocks are kept in the position index. */
final class FarmStore implements AutoCloseable {
    static final class Farm {
        String id, ownerName, world, typeLabel;
        UUID owner, worldId;
        int W, L;
        int[] bounds; // minX, maxX, minZ, maxZ, minY, maxY
        boolean legacy;

        boolean in(World w) {
            return worldId == null
                    ? w.getName().equalsIgnoreCase(world)
                    : worldId.equals(w.getUID());
        }
    }

    record Pos(UUID world, int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }

        Block block(World w) {
            return w.getBlockAt(x, y, z);
        }
    }

    record Owned(Pos pos, String farm, String data, String restore) {}

    private final Map<String, Farm> farms = new LinkedHashMap<>();
    private final Connection db;

    private record Chunk(UUID world, int x, int z) {
        static Chunk of(Pos p) {
            return new Chunk(p.world, p.x >> 4, p.z >> 4);
        }
    }

    private final Map<Chunk, Integer> activeChunks = new HashMap<>();
    private final Map<String, Integer> symbols = new HashMap<>();
    private final JavaPlugin plugin;
    private boolean healthy = true;

    FarmStore(JavaPlugin plugin) throws Exception {
        this.plugin = plugin;
        File folder = plugin.getDataFolder();
        java.nio.file.Files.createDirectories(folder.toPath());
        Class.forName("org.sqlite.JDBC");
        db =
                DriverManager.getConnection(
                        "jdbc:sqlite:" + new File(folder, "farms.db").getAbsolutePath());
        try {
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA journal_mode=WAL");
                s.execute(
                        "CREATE TABLE IF NOT EXISTS farms(id TEXT UNIQUE NOT NULL,owner TEXT NOT"
                            + " NULL,name TEXT NOT NULL,world TEXT NOT NULL,world_id TEXT,type TEXT"
                            + " NOT NULL,w INTEGER,l INTEGER,x0 INTEGER,x1 INTEGER,z0 INTEGER,z1"
                            + " INTEGER,y0 INTEGER,y1 INTEGER,legacy INTEGER NOT NULL,fid INTEGER"
                            + " PRIMARY KEY)");
                s.execute(
                        "CREATE TABLE IF NOT EXISTS symbols(id INTEGER PRIMARY KEY,value TEXT"
                            + " UNIQUE NOT NULL)");
                s.execute(
                        "CREATE TABLE IF NOT EXISTS owned(world INTEGER NOT NULL REFERENCES"
                            + " symbols(id),x INTEGER,y INTEGER,z INTEGER,farm INTEGER NOT NULL"
                            + " REFERENCES farms(fid) ON DELETE CASCADE,data INTEGER NOT NULL"
                            + " REFERENCES symbols(id),restore INTEGER NOT NULL REFERENCES"
                            + " symbols(id),PRIMARY KEY(world,x,y,z))");
                s.execute("CREATE INDEX IF NOT EXISTS owned_by_farm ON owned(farm)");
                s.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY)");
            }
            try (Statement s = db.createStatement();
                    ResultSet r = s.executeQuery("SELECT value,id FROM symbols")) {
                while (r.next()) symbols.put(r.getString(1), r.getInt(2));
            }
            try (Statement s = db.createStatement();
                    ResultSet r =
                            s.executeQuery(
                                    "SELECT v.value,o.x>>4,o.z>>4,count(*) FROM owned o JOIN"
                                        + " symbols v ON o.world=v.id GROUP BY"
                                        + " o.world,o.x>>4,o.z>>4")) {
                while (r.next())
                    activeChunks.put(
                            new Chunk(UUID.fromString(r.getString(1)), r.getInt(2), r.getInt(3)),
                            r.getInt(4));
            }
            importLegacy(new File(folder, "farms.yml"));
            try (Statement s = db.createStatement();
                    ResultSet r = s.executeQuery("SELECT * FROM farms ORDER BY rowid")) {
                while (r.next()) {
                    Farm f = new Farm();
                    f.id = r.getString("id");
                    f.owner = UUID.fromString(r.getString("owner"));
                    f.ownerName = r.getString("name");
                    f.world = r.getString("world");
                    String uid = r.getString("world_id");
                    f.worldId = uid == null ? null : UUID.fromString(uid);
                    f.typeLabel = r.getString("type");
                    f.W = r.getInt("w");
                    f.L = r.getInt("l");
                    f.legacy = r.getBoolean("legacy");
                    f.bounds =
                            new int[] {
                                r.getInt("x0"),
                                r.getInt("x1"),
                                r.getInt("z0"),
                                r.getInt("z1"),
                                r.getInt("y0"),
                                r.getInt("y1")
                            };
                    farms.put(f.id, f);
                }
            }
        } catch (Exception e) {
            db.close();
            throw e;
        }
    }

    private void importLegacy(File file) throws Exception {
        try (Statement s = db.createStatement();
                ResultSet r = s.executeQuery("SELECT 1 FROM meta WHERE key='legacy-imported'")) {
            if (r.next()) return;
        }
        List<Farm> importing = new ArrayList<>();
        if (file.isFile()) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file); // Parse failures must never mark an import complete.
            ConfigurationSection root = yaml.getConfigurationSection("farms");
            if (root != null)
                for (String id : root.getKeys(false)) {
                    ConfigurationSection v =
                            Objects.requireNonNull(root.getConfigurationSection(id));
                    Farm f = new Farm();
                    f.id = id;
                    f.legacy = true;
                    f.owner = UUID.fromString(v.getString("owner"));
                    f.ownerName = v.getString("owner-name", "?");
                    f.world = Objects.requireNonNull(v.getString("world"));
                    World w = plugin.getServer().getWorld(f.world);
                    if (w != null) f.worldId = w.getUID();
                    f.typeLabel = v.getString("type", "Tree");
                    f.W = v.getInt("w");
                    f.L = v.getInt("l");
                    for (String key : List.of("trees", "torches"))
                        for (String coord : v.getStringList(key)) {
                            String[] a = coord.split(",");
                            if (a.length != 3)
                                throw new IllegalArgumentException(
                                        "Invalid position in legacy farm " + id);
                            extend(
                                    f,
                                    Integer.parseInt(a[0]),
                                    Integer.parseInt(a[1]),
                                    Integer.parseInt(a[2]));
                        }
                    if (f.bounds == null)
                        throw new IllegalArgumentException(
                                "Legacy farm has no planting positions: " + id);
                    importing.add(f);
                }
        }
        write(
                () -> {
                    for (Farm f : importing) insertFarm(f);
                    try (Statement s = db.createStatement()) {
                        s.executeUpdate("INSERT INTO meta VALUES('legacy-imported')");
                    }
                });
    }

    static void extend(Farm f, int x, int y, int z) {
        if (f.bounds == null) {
            f.bounds = new int[] {x, x, z, z, y, y};
            return;
        }
        int[] b = f.bounds;
        b[0] = Math.min(b[0], x);
        b[1] = Math.max(b[1], x);
        b[2] = Math.min(b[2], z);
        b[3] = Math.max(b[3], z);
        b[4] = Math.min(b[4], y);
        b[5] = Math.max(b[5], y);
    }

    Collection<Farm> all() {
        return farms.values();
    }

    Farm get(String id) {
        return farms.get(id);
    }

    int countOwned(UUID owner) {
        return (int) farms.values().stream().filter(f -> owner.equals(f.owner)).count();
    }

    void add(Farm f, Collection<Owned> blocks) {
        List<Pos> replaced = new ArrayList<>();
        write(
                () -> {
                    insertFarm(f);
                    // A remote edit may already have cleared a canopy at a new planting position.
                    // These new positions were revalidated as air immediately before the build.
                    try (PreparedStatement del =
                            db.prepareStatement(
                                    "DELETE FROM owned WHERE world=? AND x=? AND y=? AND z=?")) {
                        for (Owned b : blocks)
                            if (activeChunks.containsKey(Chunk.of(b.pos))) {
                                position(del, b.pos);
                                if (del.executeUpdate() > 0) replaced.add(b.pos);
                            }
                    }
                    insertBlocks(blocks);
                });
        for (Pos p : replaced) countChunk(p, -1);
        farms.put(f.id, f);
        for (Owned b : blocks) countChunk(b.pos, 1);
    }

    private void insertFarm(Farm f) throws SQLException {
        try (PreparedStatement s =
                db.prepareStatement(
                        "INSERT INTO"
                            + " farms(id,owner,name,world,world_id,type,w,l,x0,x1,z0,z1,y0,y1,legacy)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, f.id);
            s.setString(2, f.owner.toString());
            s.setString(3, f.ownerName);
            s.setString(4, f.world);
            s.setString(5, f.worldId == null ? null : f.worldId.toString());
            s.setString(6, f.typeLabel);
            s.setInt(7, f.W);
            s.setInt(8, f.L);
            for (int i = 0; i < 6; i++) s.setInt(9 + i, f.bounds[i]);
            s.setBoolean(15, f.legacy);
            s.executeUpdate();
        }
    }

    void remove(String id) {
        write(
                () -> {
                    try (PreparedStatement s =
                            db.prepareStatement("DELETE FROM farms WHERE id=?")) {
                        s.setString(1, id);
                        s.executeUpdate();
                    }
                });
        farms.remove(id);
    }

    Owned at(Pos p) {
        check();
        if (!activeChunks.containsKey(Chunk.of(p))) return null;
        try (PreparedStatement s =
                db.prepareStatement(
                        "SELECT f.id,d.value,r.value FROM owned o JOIN farms f ON f.fid=o.farm JOIN"
                            + " symbols d ON d.id=o.data JOIN symbols r ON r.id=o.restore WHERE"
                            + " o.world=? AND o.x=? AND o.y=? AND o.z=?")) {
            position(s, p);
            try (ResultSet r = s.executeQuery()) {
                return r.next()
                        ? new Owned(p, r.getString(1), r.getString(2), r.getString(3))
                        : null;
            }
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    List<Owned> page(String farm, int limit) {
        check();
        try (PreparedStatement s =
                db.prepareStatement(
                        "SELECT w.value,o.x,o.y,o.z,d.value,r.value FROM owned o JOIN symbols w ON"
                            + " w.id=o.world JOIN symbols d ON d.id=o.data JOIN symbols r ON"
                            + " r.id=o.restore WHERE o.farm=(SELECT fid FROM farms WHERE id=?)"
                            + " ORDER BY o.rowid LIMIT ?")) {
            s.setString(1, farm);
            s.setInt(2, limit);
            List<Owned> page = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Pos p =
                            new Pos(
                                    UUID.fromString(r.getString(1)),
                                    r.getInt(2),
                                    r.getInt(3),
                                    r.getInt(4));
                    page.add(new Owned(p, farm, r.getString(5), r.getString(6)));
                }
            }
            return page;
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    void change(Collection<Pos> forget, Collection<Owned> add) {
        if (forget.isEmpty() && add.isEmpty()) return;
        List<Pos> deleted = new ArrayList<>();
        write(
                () -> {
                    if (!forget.isEmpty())
                        try (PreparedStatement s =
                                db.prepareStatement(
                                        "DELETE FROM owned WHERE world=? AND x=? AND y=? AND"
                                            + " z=?")) {
                            for (Pos p : forget) {
                                position(s, p);
                                if (s.executeUpdate() > 0) deleted.add(p);
                            }
                        }
                    if (!add.isEmpty()) insertBlocks(add);
                });
        for (Pos p : deleted) countChunk(p, -1);
        for (Owned b : add) countChunk(b.pos, 1);
    }

    private void insertBlocks(Collection<Owned> blocks) throws SQLException {
        try (PreparedStatement s =
                db.prepareStatement(
                        "INSERT INTO owned VALUES(?,?,?,?,(SELECT fid FROM farms WHERE"
                            + " id=?),?,?)")) {
            for (Owned b : blocks) {
                position(s, b.pos);
                s.setString(5, b.farm);
                s.setInt(6, symbol(b.data));
                s.setInt(7, symbol(b.restore));
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private void countChunk(Pos pos, int delta) {
        activeChunks.compute(
                Chunk.of(pos),
                (k, old) -> {
                    int n = (old == null ? 0 : old) + delta;
                    return n == 0 ? null : n;
                });
    }

    private int symbol(String value) throws SQLException {
        Integer cached = symbols.get(value);
        if (cached != null) return cached;
        try (PreparedStatement s =
                db.prepareStatement("INSERT INTO symbols(value) VALUES(?) RETURNING id")) {
            s.setString(1, value);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SQLException("Missing symbol id");
                int id = r.getInt(1);
                symbols.put(value, id);
                return id;
            }
        }
    }

    private void position(PreparedStatement s, Pos p) throws SQLException {
        s.setInt(1, symbol(p.world.toString()));
        s.setInt(2, p.x);
        s.setInt(3, p.y);
        s.setInt(4, p.z);
    }

    private interface Work {
        void run() throws SQLException;
    }

    private void write(Work work) {
        check();
        try {
            db.setAutoCommit(false);
            try {
                work.run();
                db.commit();
            } catch (SQLException | RuntimeException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    private void check() {
        if (!healthy) throw new IllegalStateException("Farm storage is unavailable");
    }

    private IllegalStateException failed(SQLException e) {
        healthy = false;
        plugin.getLogger()
                .severe(
                        "Farm ownership storage failed. Stop the server and inspect farms.db: "
                                + e);
        return new IllegalStateException("Farm storage is unavailable", e);
    }

    @Override
    public void close() {
        try {
            db.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(e.toString());
        }
    }
}
