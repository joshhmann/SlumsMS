package soloMapling.ArtificialPlayer.GCMoveSystem;

import server.maps.MapleMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/*
 * Dev tool, not a unit test: bakes nav graphs into the graph cache ahead of a boot, so a graph version bump does not
 * leave the first boot rebuilding every map on the two warm-up threads. Each argument is a cache key
 * (100000000-s150-j123, with -ss for snowshoes) or a directory whose .bin names are keys - typically the previous
 * version's cache, so the new one covers the same maps and movement profiles. Keys already in the cache are skipped.
 * Point -Dwz-path at the WZ directory and -Dbot.nav.cacheDir at the server's cache/bot-nav; -Dbake.threads sets the
 * parallelism (one map per thread at a time, default 2).
 *
 *   java -cp <jar>:<test-classes> -Dwz-path=wz -Dbot.nav.cacheDir=cache/bot-nav -Dbake.threads=4 \
 *       soloMapling.ArtificialPlayer.GCMoveSystem.NavGraphBake cache/bot-nav/v61
 */
public final class NavGraphBake {
    private static final Pattern KEY = Pattern.compile("(\\d+)-s(\\d+)-j(\\d+)(-ss)?(?:\\.bin)?");

    private NavGraphBake() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<Integer, List<BotMovementProfile>> profilesByMap = new TreeMap<>();
        for (String arg : args) {
            Path dir = Path.of(arg);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.forEach(file -> addKey(profilesByMap, file.getFileName().toString()));
                }
            } else {
                addKey(profilesByMap, arg);
            }
        }

        int total = profilesByMap.values().stream().mapToInt(List::size).sum();
        AtomicInteger built = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("bake.threads", 2));
        profilesByMap.forEach((mapId, profiles) -> pool.execute(() -> {
            MapleMap map = null;
            for (BotMovementProfile profile : profiles) {
                BotNavigationGraphProvider.GraphCacheKey key = BotNavigationGraphProvider.GraphCacheKey.from(mapId, profile);
                if (Files.isRegularFile(BotNavigationGraphProvider.graphFile(key))) {
                    skipped.incrementAndGet();
                    continue;
                }
                try {
                    if (map == null) {
                        map = BotNavigationMapLoader.loadMapGeometry(mapId);
                    }
                    BotNavigationGraphProvider.rebuildGraph(map, profile);
                    int done = built.incrementAndGet();
                    if (done % 100 == 0) {
                        System.out.println("built " + done + " (skipped " + skipped.get() + ", failed " + failed.get()
                                + ") of " + total + " in " + (System.nanoTime() - started) / 1_000_000_000L + "s");
                    }
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                    System.out.println("map=" + mapId + " " + profile + " ERROR " + e);
                }
            }
        }));
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.DAYS);
        System.out.println("done: built " + built.get() + ", skipped " + skipped.get() + ", failed " + failed.get()
                + " of " + total + " in " + (System.nanoTime() - started) / 1_000_000_000L + "s");
        // The graph warm-up executors are daemon threads; nothing else keeps the JVM up.
        System.exit(failed.get() == 0 ? 0 : 1);
    }

    private static void addKey(Map<Integer, List<BotMovementProfile>> profilesByMap, String name) {
        Matcher m = KEY.matcher(name);
        if (!m.matches()) {
            return;
        }
        BotMovementProfile profile = new BotMovementProfile(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                m.group(4) != null);
        List<BotMovementProfile> profiles = profilesByMap.computeIfAbsent(Integer.parseInt(m.group(1)), id -> new ArrayList<>());
        if (!profiles.contains(profile)) {
            profiles.add(profile);
        }
    }
}
