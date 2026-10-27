package soloMapling.site.avatar;

import soloMapling.dashboard.AtomicFiles;
import soloMapling.server.Json;
import soloMapling.server.SoloMaplingConstants;
import tools.DatabaseConnection;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The website's ranking pictures (1.6.0): a standalone job, like the dashboard's map export, that reads the site
 * export's {@code leaderboards.json}, looks up each listed character's skin, hair, face and equipped items, and draws
 * each look once with {@link AvatarRenderer} from the client's {@code Data/} files. It writes, under {@code <out>}:
 * <ul>
 *   <li>{@code avatars/<key>.png}: one 96x96 transparent picture per look, named by {@link AvatarLook#cacheKey()}, so
 *   an unchanged look is never drawn again and two characters dressed alike share one file;</li>
 *   <li>{@code avatars/index.json}: {@code {"schema":1, "generated_at", "size":96, "avatars": {"<name>": "<key>.png"}}}
 *   for every listed character that has a picture. The site shows {@code avatars/<file>} next to the name, and its
 *   placeholder for a name that is not in the map.</li>
 * </ul>
 * Only names the leaderboards already list are drawn, so GMs, QA and banned accounts stay out as they do there. No ids
 * or account data are written. Pictures no character uses are deleted after {@code --keep-days}. Nothing it does
 * touches the game server; run it beside the server at low priority (see deploy/site).
 * <pre>
 *   java -cp target/Cosmic.jar soloMapling.site.avatar.AvatarExport --data=/path/to/client/Data
 *       [--leaderboards=data/site/leaderboards.json] [--out=data/site] [--looks=looks.json] [--keep-days=7] [--force]
 * </pre>
 * Without {@code --looks} the looks come from the database in {@code config.yaml} (SELECTs only).
 */
public final class AvatarExport {

    public static final int SCHEMA = 1;

    public record Options(Path data, Path leaderboards, Path out, Path looks, int keepDays, boolean force) {
        public static Options parse(String[] args) {
            Path data = null;
            Path boards = Path.of("data/site/leaderboards.json");
            Path out = Path.of("data/site");
            Path looks = null;
            int keepDays = 7;
            boolean force = false;
            for (String a : args) {
                if (a.startsWith("--data=")) {
                    data = Path.of(a.substring(7));
                } else if (a.startsWith("--leaderboards=")) {
                    boards = Path.of(a.substring(15));
                } else if (a.startsWith("--out=")) {
                    out = Path.of(a.substring(6));
                } else if (a.startsWith("--looks=")) {
                    looks = Path.of(a.substring(8));
                } else if (a.startsWith("--keep-days=")) {
                    keepDays = Math.max(0, Integer.parseInt(a.substring(12)));
                } else if (a.equals("--force")) {
                    force = true;
                } else {
                    throw new IllegalArgumentException("Unknown argument " + a);
                }
            }
            if (data == null) {
                throw new IllegalArgumentException("--data=<client Data folder> is required");
            }
            return new Options(data, boards, out, looks, keepDays, force);
        }
    }

    /** What one run did. */
    public record Summary(int names, int looks, int drawn, int reused, int failed, int pruned) {
    }

    /** Where the looks come from: by character name; a name with no look is left out. */
    public interface Looks {
        Map<String, AvatarLook> of(Collection<String> names) throws IOException;
    }

    private AvatarExport() {
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        long t0 = System.currentTimeMillis();
        Looks looks = o.looks() != null ? fileLooks(o.looks()) : AvatarExport::dbLooks;
        Summary s = run(o, new AvatarRenderer(new DataDir(o.data())), looks, t0);
        System.out.println("avatar export: " + s.names() + " names, " + s.looks() + " looks, " + s.drawn() + " drawn, "
                + s.reused() + " already drawn, " + s.failed() + " failed, " + s.pruned() + " old pictures removed, "
                + (System.currentTimeMillis() - t0) / 1000 + "s");
        System.exit(0); // the connection pool leaves non-daemon threads behind
    }

    public static Summary run(Options o, AvatarRenderer renderer, Looks looks, long now) throws IOException {
        Path dir = o.out().resolve("avatars");
        Files.createDirectories(dir);
        Set<String> names = names(Json.parse(Files.readString(o.leaderboards(), StandardCharsets.UTF_8)));
        Map<String, AvatarLook> byName = looks.of(names);
        Map<String, String> index = new TreeMap<>();
        Map<String, Boolean> done = new HashMap<>(); // key -> drawn fine
        int drawn = 0;
        int reused = 0;
        int failed = 0;
        for (String name : names) {
            AvatarLook look = byName.get(name);
            if (look == null) {
                continue;
            }
            String key = look.cacheKey();
            Boolean ok = done.get(key);
            if (ok == null) {
                Path png = dir.resolve(key + ".png");
                if (Files.isRegularFile(png) && !o.force()) {
                    ok = true;
                    reused++;
                } else {
                    try {
                        writePng(png, renderer.render(look));
                        ok = true;
                        drawn++;
                    } catch (IOException | RuntimeException e) {
                        ok = false;
                        failed++;
                        System.out.println("avatar for " + name + " failed: " + e);
                    }
                }
                done.put(key, ok);
            }
            if (ok) {
                index.put(name, key + ".png");
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", SCHEMA);
        doc.put("generated_at", Instant.ofEpochMilli(now).toString());
        doc.put("size", AvatarRenderer.SIZE);
        doc.put("avatars", index);
        AtomicFiles.writeJson(dir.resolve("index.json"), doc);
        int pruned = prune(dir, new LinkedHashSet<>(index.values()), now - TimeUnit.DAYS.toMillis(o.keepDays()));
        return new Summary(names.size(), done.size(), drawn, reused, failed, pruned);
    }

    /** Every character name the leaderboards list: level (all and by class), fame, traders, bosses, guild leaders. */
    static Set<String> names(Object boards) {
        Set<String> out = new LinkedHashSet<>();
        if (!(boards instanceof Map<?, ?> m)) {
            return out;
        }
        addNames(out, m.get("level"), "name");
        if (m.get("levelByClass") instanceof Map<?, ?> byClass) {
            byClass.values().forEach(v -> addNames(out, v, "name"));
        }
        addNames(out, m.get("fame"), "name");
        addNames(out, m.get("traders"), "name");
        if (m.get("bosses") instanceof Map<?, ?> bosses) {
            bosses.values().forEach(v -> addNames(out, v, "name"));
        }
        addNames(out, m.get("guilds"), "leader");
        return out;
    }

    private static void addNames(Set<String> out, Object rows, String field) {
        if (rows instanceof List<?> list) {
            for (Object r : list) {
                if (r instanceof Map<?, ?> row && row.get(field) instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
    }

    private static void writePng(Path file, BufferedImage img) throws IOException {
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "." + file.getFileName(), ".tmp");
        try {
            if (!ImageIO.write(img, "png", tmp.toFile())) {
                throw new IOException("no PNG writer");
            }
            try {
                Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException e) {
                // not a POSIX file system
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Deletes pictures no listed character uses that were last written before {@code before}. */
    static int prune(Path dir, Set<String> used, long before) throws IOException {
        int n = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("[0-9a-f]{16}\\.png")).toList()) {
                if (!used.contains(f.getFileName().toString()) && Files.getLastModifiedTime(f).toMillis() < before) {
                    Files.deleteIfExists(f);
                    n++;
                }
            }
        }
        return n;
    }

    // ── looks ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Looks from a JSON file: {@code [{"name", "skin", "hair", "face", "equips": {"-1": 1002140, "-101": 1002186}}]}.
     * For trying the renderer on copied data without a database.
     */
    static Looks fileLooks(Path file) {
        return names -> {
            Map<String, AvatarLook> out = new HashMap<>();
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof List<?> rows) {
                for (Object o : rows) {
                    if (o instanceof Map<?, ?> r && r.get("name") instanceof String name && names.contains(name)) {
                        Map<Integer, Integer> eq = new HashMap<>();
                        if (r.get("equips") instanceof Map<?, ?> e) {
                            e.forEach((k, v) -> eq.put(Integer.parseInt(String.valueOf(k)), ((Number) v).intValue()));
                        }
                        out.put(name, new AvatarLook(num(r.get("skin")), num(r.get("hair")), num(r.get("face")), eq));
                    }
                }
            }
            return out;
        };
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** Player characters by name (a player wins over a bot of the same name, which NameGuard prevents anyway). */
    static final String PLAYERS = "SELECT id, name, skincolor, hair, face FROM characters WHERE name IN (%s)";
    /** Bots by name; their items are stored under {@code BOT_BASE_ID + BotId}, as for players. */
    static final String BOTS = "SELECT BotId, Name, Skin, Hair, Face FROM botstore WHERE Name IN (%s)";
    /** Equipped items (inventory type -1), only the slots the picture draws. Ids listed: uses the CHARID index. */
    static final String EQUIPPED = "SELECT characterid, itemid, position FROM inventoryitems WHERE inventorytype = -1"
            + " AND position BETWEEN -111 AND -1 AND characterid IN (%s)";
    private static final int BATCH = 500;

    private record Who(int id, String name, int skin, int hair, int face) {
    }

    static Map<String, AvatarLook> dbLooks(Collection<String> names) throws IOException {
        if (!DatabaseConnection.initializeConnectionPool()) {
            throw new IOException("no database connection");
        }
        List<String> list = new ArrayList<>(names);
        Map<String, Who> who = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection()) {
            for (int i = 0; i < list.size(); i += BATCH) {
                List<String> part = list.subList(i, Math.min(list.size(), i + BATCH));
                try (PreparedStatement ps = con.prepareStatement(String.format(BOTS, marks(part.size())))) {
                    bind(ps, part);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            who.put(rs.getString("Name"), new Who(SoloMaplingConstants.GameConstants.BOT_BASE_ID
                                    + rs.getInt("BotId"), rs.getString("Name"), rs.getInt("Skin"), rs.getInt("Hair"),
                                    rs.getInt("Face")));
                        }
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(String.format(PLAYERS, marks(part.size())))) {
                    bind(ps, part);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            who.put(rs.getString("name"), new Who(rs.getInt("id"), rs.getString("name"),
                                    rs.getInt("skincolor"), rs.getInt("hair"), rs.getInt("face")));
                        }
                    }
                }
            }
            Map<Integer, Map<Integer, Integer>> equips = new HashMap<>();
            List<Integer> ids = who.values().stream().map(Who::id).toList();
            for (int i = 0; i < ids.size(); i += BATCH) {
                List<Integer> part = ids.subList(i, Math.min(ids.size(), i + BATCH));
                try (PreparedStatement ps = con.prepareStatement(String.format(EQUIPPED, marks(part.size())))) {
                    for (int k = 0; k < part.size(); k++) {
                        ps.setInt(k + 1, part.get(k));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            equips.computeIfAbsent(rs.getInt("characterid"), k -> new HashMap<>())
                                    .put((int) rs.getShort("position"), rs.getInt("itemid"));
                        }
                    }
                }
            }
            Map<String, AvatarLook> out = new HashMap<>();
            for (Who w : who.values()) {
                out.put(w.name(), new AvatarLook(w.skin(), w.hair(), w.face(), equips.getOrDefault(w.id(), Map.of())));
            }
            return out;
        } catch (SQLException e) {
            throw new IOException("looks: " + e.getMessage(), e);
        }
    }

    private static String marks(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static void bind(PreparedStatement ps, List<String> values) throws SQLException {
        for (int k = 0; k < values.size(); k++) {
            ps.setString(k + 1, values.get(k));
        }
    }

    // ── client files ────────────────────────────────────────────────────────────────────────────

    /** The client's {@code Data/} folder: {@code Data/Character/Hair/00030000.img}, parsed once per run. */
    public static final class DataDir implements AvatarRenderer.Source {
        private final Path root;
        private final Map<String, ImgFile.Node> cache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ImgFile.Node> eldest) {
                return size() > 400;
            }
        };

        public DataDir(Path root) {
            this.root = root;
        }

        @Override
        public ImgFile.Node img(String path) {
            if (path == null) {
                return null;
            }
            if (cache.containsKey(path)) {
                return cache.get(path);
            }
            ImgFile.Node n = null;
            Path f = root.resolve(path);
            try {
                if (Files.isRegularFile(f) && Files.size(f) < 64L << 20) {
                    n = ImgFile.parse(Files.readAllBytes(f), f.getFileName().toString());
                }
            } catch (IOException | RuntimeException e) {
                System.out.println("unreadable " + path + ": " + e.getMessage());
            }
            cache.put(path, n);
            return n;
        }
    }
}
