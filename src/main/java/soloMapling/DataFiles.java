package soloMapling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Finds SoloMapling's shipped data files (dialogue packs, item pools, movement recordings, name lists...).
 *
 * <p>Those files live next to the code under {@code src/main/java/soloMapling/...} and every loader
 * names them by that source-tree path. This resolver keeps those names but looks in three places, in order:
 * <ol>
 *   <li>{@code -Dsolomapling.dataDir=<dir>}: {@code <dir>/soloMapling/...} (an operator's override copy);</li>
 *   <li>the source tree, {@code src/main/java/soloMapling/...} relative to the working dir (unchanged behaviour);</li>
 *   <li>the classpath: maven packs the same files into the jar as {@code soloMapling/...} resources. A resource
 *       inside a jar is extracted once to a temp dir so loaders that want a {@link File} still get one.</li>
 * </ol>
 * A path not under {@code src/main/java/} passes through untouched (tests hand loaders temp files).
 * When nothing is found the source-tree path comes back, so the caller fails exactly as it did before,
 * and the miss is logged once.
 *
 * <p>Only reads go through here. Files the server writes (movement recordings, town pins, caches) keep
 * their working-dir paths.
 */
public final class DataFiles {
    private static final Logger log = LoggerFactory.getLogger(DataFiles.class);

    public static final String OVERRIDE_PROPERTY = "solomapling.dataDir";
    public static final String SOURCE_PREFIX = "src/main/java/";

    /** Every shipped data file or folder a loader names; checked once at startup. */
    static final List<String> KNOWN = List.of(
            "soloMapling/ArtificialPlayer/BotDialoguePack",
            "soloMapling/ArtificialPlayer/BotDialoguePack/ConversationDialogue.yaml",
            "soloMapling/ArtificialPlayer/BotDialoguePack/DropGameLootPool.yaml",
            "soloMapling/ArtificialPlayer/BotDialoguePack/LogoutDialogue.yaml",
            "soloMapling/ArtificialPlayer/BotDialoguePack/TownChatterDialogue.yaml",
            "soloMapling/ArtificialPlayer/BotNames",
            "soloMapling/FreeMarket/FMNameDesc",
            "soloMapling/FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt",
            "soloMapling/ArtificialPlayer/BotDecoratorSystem/GenericEquipPool.yaml",
            "soloMapling/ArtificialPlayer/BotDecoratorSystem/NXItemPool.yaml",
            "soloMapling/ArtificialPlayer/BotGrindSystem/LevelingPath.yaml",
            "soloMapling/ArtificialPlayer/BotGrindSystem/TrainingRegions.yaml",
            "soloMapling/ArtificialPlayer/BotTownSystem/TownPresence.yaml",
            "soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.yaml",
            "soloMapling/ArtificialPlayer/BotMovementSystem/movementDataPackets",
            "soloMapling/DailyCheckin/daily-checkin-rewards.yaml",
            "soloMapling/itemPool/EquipOmitList.yaml",
            "soloMapling/itemPool/itemConfig",
            "soloMapling/itemPool/itemConfig/desirableEquips.yaml",
            "soloMapling/itemPool/itemConfig/gachaFiller.yaml",
            "soloMapling/itemPool/itemConfig/itemQuantities.yaml",
            "soloMapling/server/npc_versions.yaml",
            "soloMapling/server/portal_versions.yaml");

    public enum Origin { OVERRIDE, SOURCE, CLASSPATH, MISSING, PASSTHROUGH }

    public record Resolved(Path path, Origin origin) {
        public boolean found() {
            return origin != Origin.MISSING;
        }
    }

    private static volatile DataFiles shared;

    private final Path overrideDir;
    private final Path sourceRoot;
    private final ClassLoader loader;
    private volatile Path extractRoot;
    private final Set<String> extracted = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    /**
     * @param overrideDir folder holding {@code soloMapling/...}, or null
     * @param sourceRoot  folder holding {@code src/main/java/...}; the empty path means the working dir
     * @param extractRoot where jar resources are unpacked, or null for a temp dir made on first use
     */
    DataFiles(Path overrideDir, Path sourceRoot, ClassLoader loader, Path extractRoot) {
        this.overrideDir = overrideDir;
        this.sourceRoot = sourceRoot;
        this.loader = loader;
        this.extractRoot = extractRoot == null ? null : extractRoot.toAbsolutePath().normalize();
    }

    static DataFiles shared() {
        DataFiles s = shared;
        if (s == null) {
            synchronized (DataFiles.class) {
                s = shared;
                if (s == null) {
                    String dir = System.getProperty(OVERRIDE_PROPERTY, "").trim();
                    s = new DataFiles(dir.isEmpty() ? null : Path.of(dir), Path.of(""),
                            DataFiles.class.getClassLoader(), null);
                    shared = s;
                }
            }
        }
        return s;
    }

    /** The file or folder to read for a {@code src/main/java/...} data path; logs a miss once. */
    public static File file(String path) {
        return shared().resolve(path, true).path().toFile();
    }

    /** Same as {@link #file} for loaders that want a path string. */
    public static String name(String path) {
        return file(path).getPath();
    }

    /** Same as {@link #file} for NIO loaders. */
    public static Path path(String path) {
        return shared().resolve(path, true).path();
    }

    /** For data that is allowed to be absent (e.g. a map without movement recordings): no miss is logged. */
    public static File optional(String path) {
        return shared().resolve(path, false).path().toFile();
    }

    /** Resolves every known data path, logs each miss and one summary line. Returns the miss count. */
    public static int checkAtStartup() {
        return shared().check();
    }

    int check() {
        Map<Origin, Integer> counts = new EnumMap<>(Origin.class);
        for (String rel : KNOWN) {
            counts.merge(resolve(SOURCE_PREFIX + rel, true).origin(), 1, Integer::sum);
        }
        int missing = counts.getOrDefault(Origin.MISSING, 0);
        String summary = String.format("SoloMapling data: %d/%d resolved (override %d, source tree %d, classpath %d), %d missing%s",
                KNOWN.size() - missing, KNOWN.size(),
                counts.getOrDefault(Origin.OVERRIDE, 0), counts.getOrDefault(Origin.SOURCE, 0),
                counts.getOrDefault(Origin.CLASSPATH, 0), missing,
                overrideDir == null ? "" : "; -D" + OVERRIDE_PROPERTY + "=" + overrideDir);
        if (missing > 0) {
            log.warn(summary);
        } else {
            log.info(summary);
        }
        return missing;
    }

    Resolved resolve(String path, boolean reportMiss) {
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith(SOURCE_PREFIX)) {
            return new Resolved(Path.of(path), Origin.PASSTHROUGH);
        }
        String rel = stripSlashes(normalized.substring(SOURCE_PREFIX.length()));
        Path sourcePath = sourceRoot.resolve(SOURCE_PREFIX + rel);

        if (overrideDir != null) {
            Path p = overrideDir.resolve(rel);
            if (Files.exists(p)) {
                return new Resolved(p, Origin.OVERRIDE);
            }
        }
        if (Files.exists(sourcePath)) {
            return new Resolved(sourcePath, Origin.SOURCE);
        }
        Path fromClasspath = fromClasspath(rel);
        if (fromClasspath != null) {
            return new Resolved(fromClasspath, Origin.CLASSPATH);
        }
        if (reportMiss && reportedMissing.add(rel)) {
            log.warn("SoloMapling data file not found: {} (looked in {}the source tree and the classpath)",
                    rel, overrideDir == null ? "" : overrideDir + ", ");
        }
        return new Resolved(sourcePath, Origin.MISSING);
    }

    private Path fromClasspath(String rel) {
        URL url = loader.getResource(rel);
        if (url == null) {
            url = loader.getResource(rel + "/");
        }
        if (url == null) {
            return null;
        }
        try {
            if ("file".equals(url.getProtocol())) {
                Path p = Path.of(url.toURI());
                return Files.exists(p) ? p : null;
            }
            if ("jar".equals(url.getProtocol())) {
                return extractFromJar(rel, url);
            }
            return extractStream(rel, url);
        } catch (IOException | URISyntaxException | RuntimeException e) {
            log.warn("Could not read SoloMapling data {} from the classpath ({})", rel, url, e);
            return null;
        }
    }

    private synchronized Path extractFromJar(String rel, URL url) throws IOException, URISyntaxException {
        Path target = extractRoot().resolve(rel);
        if (extracted.contains(rel)) {
            return target;
        }
        JarURLConnection conn = (JarURLConnection) url.openConnection();
        try (JarFile jar = new JarFile(Path.of(conn.getJarFileURL().toURI()).toFile())) {
            JarEntry entry = jar.getJarEntry(rel);
            if (entry != null && !entry.isDirectory()) {
                copy(jar, entry, target);
            } else {
                Files.createDirectories(target);
                String prefix = rel + "/";
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.getName().startsWith(prefix) && !e.isDirectory()) {
                        copy(jar, e, extractRoot().resolve(e.getName()));
                    } else if (e.getName().startsWith(prefix)) {
                        Files.createDirectories(safe(extractRoot().resolve(e.getName())));
                    }
                }
            }
        }
        extracted.add(rel);
        return target;
    }

    private synchronized Path extractStream(String rel, URL url) throws IOException {
        Path target = extractRoot().resolve(rel);
        if (!extracted.contains(rel)) {
            try (InputStream in = url.openStream()) {
                Files.createDirectories(safe(target).getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            extracted.add(rel);
        }
        return target;
    }

    private void copy(JarFile jar, JarEntry entry, Path target) throws IOException {
        Files.createDirectories(safe(target).getParent());
        try (InputStream in = jar.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path safe(Path target) throws IOException {
        Path root = extractRoot();
        if (!target.normalize().startsWith(root)) {
            throw new IOException("jar entry escapes the extract dir: " + target);
        }
        return target;
    }

    private Path extractRoot() throws IOException {
        if (extractRoot == null) {
            Path dir = Files.createTempDirectory("solomapling-data-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir), "solomapling-data-cleanup"));
            extractRoot = dir;
        }
        return extractRoot;
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | UncheckedIOException ignored) {
            // best effort: it is a temp dir
        }
    }

    private static String stripSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    /** {@code java -cp Cosmic.jar soloMapling.DataFiles}: checks a deploy's data; exits 1 on any miss. */
    public static void main(String[] args) {
        System.exit(checkAtStartup() == 0 ? 0 : 1);
    }
}
