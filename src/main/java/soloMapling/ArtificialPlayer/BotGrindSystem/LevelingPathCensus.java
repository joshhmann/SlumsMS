package soloMapling.ArtificialPlayer.BotGrindSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Ticket 31: where training bots chose to train, by level band, job group and map, and whether they got there.
// TrainingMapChooser records each decision (and whether the leveling path or the old nearby search made it);
// TrainingBot records the map it starts grinding on. Read out by `!env path dist`, and logged every N seconds when
// -Dsolomapling.levelPath.reportSeconds=N is set (headless checks). Memory: one small record per training bot.
// Ours (SoloMapling).
public final class LevelingPathCensus {

    private LevelingPathCensus() {
    }

    public enum Source { PATH, LOCAL }

    // One bot's latest decision, and the map it is grinding on (-1 = not grinding yet / travelling).
    record Pick(int level, int jobId, int mapId, Source source, boolean relocates, long atMs, int grindingOn) {
    }

    private static final Map<Integer, Pick> PICKS = new ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService reporter;

    public static void decided(int botId, int level, int jobId, TrainingMap pick, Source source) {
        PICKS.put(botId, new Pick(level, jobId, pick.mapId(), source, pick.relocates(), System.currentTimeMillis(), -1));
    }

    public static void grinding(int botId, int mapId) {
        PICKS.computeIfPresent(botId, (id, p) -> new Pick(p.level(), p.jobId(), p.mapId(), p.source(), p.relocates(),
                p.atMs(), mapId));
    }

    public static void forget(int botId) {
        PICKS.remove(botId);
    }

    static void clear() {
        PICKS.clear();
    }

    // Report lines: totals, then per band x job group the path/local split and the top maps chosen.
    public static List<String> report(int topMaps) {
        LevelingPath.Path path = LevelingPath.path();
        int total = 0;
        int fromPath = 0;
        int arrived = 0;
        int onTarget = 0;
        int abroad = 0;
        Map<String, int[]> split = new TreeMap<>();                 // "band|group" -> {path, local}
        Map<String, Map<Integer, Integer>> maps = new TreeMap<>(); // "band|group" -> map -> bots
        for (Pick p : PICKS.values()) {
            total++;
            if (p.source() == Source.PATH) {
                fromPath++;
            }
            if (p.relocates()) {
                abroad++;
            }
            if (p.grindingOn() > 0) {
                arrived++;
                if (p.grindingOn() == p.mapId()) {
                    onTarget++;
                }
            }
            int band = path.bandIndex(p.level());
            String bandLabel = band < 0 ? "lv" + p.level() : String.format("%03d-%03d", path.bands().get(band).lo(),
                    path.bands().get(band).hi());
            String key = bandLabel + "|" + path.groupOf(p.jobId());
            split.computeIfAbsent(key, k -> new int[2])[p.source() == Source.PATH ? 0 : 1]++;
            maps.computeIfAbsent(key, k -> new TreeMap<>()).merge(p.mapId(), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        out.add(String.format("[LevelPathCensus] bots=%d path=%d local=%d abroad=%d grinding=%d onTarget=%d",
                total, fromPath, total - fromPath, abroad, arrived, onTarget));
        for (Map.Entry<String, int[]> e : split.entrySet()) {
            List<Map.Entry<Integer, Integer>> top = new ArrayList<>(maps.get(e.getKey()).entrySet());
            top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(topMaps, top.size()); i++) {
                sb.append(i == 0 ? "" : " ").append(top.get(i).getKey()).append('x').append(top.get(i).getValue());
            }
            out.add(String.format("[LevelPathCensus] %s path=%d local=%d maps=%d top: %s", e.getKey(),
                    e.getValue()[0], e.getValue()[1], top.size(), sb));
        }
        return out;
    }

    // Periodic log of the report when -Dsolomapling.levelPath.reportSeconds > 0. Idempotent.
    public static synchronized void startReporterIfConfigured() {
        if (reporter != null) {
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(System.getProperty("solomapling.levelPath.reportSeconds", "0").trim());
        } catch (NumberFormatException e) {
            seconds = 0;
        }
        if (seconds <= 0) {
            return;
        }
        reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LevelPathCensus");
            t.setDaemon(true);
            return t;
        });
        boolean sweep = "true".equalsIgnoreCase(System.getProperty("solomapling.levelPath.sweep", "false").trim());
        final boolean[] swept = {!sweep};
        reporter.scheduleAtFixedRate(() -> {
            try {
                if (!swept[0]) {
                    swept[0] = true;
                    for (String line : LevelingPathChooser.sweep(constants.id.MapId.HENESYS)) {
                        System.out.println(line);
                    }
                }
                for (String line : report(6)) {
                    System.out.println(line);
                }
            } catch (RuntimeException ignored) {
                // a report must never take anything down
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }
}
