package soloMapling.dashboard;

import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The day's minute points ({@code series/<day>.json}) and one rollup per finished day ({@code daily/<day>.json}),
 * listed in {@code days.json}. A restart the same day carries on with the day's file. Bounded: at most
 * {@link #MAX_POINTS} points a day and {@link #KEEP_DAYS} days of files.
 */
final class SeriesStore {

    static final int MAX_POINTS = 2_880;
    static final int KEEP_DAYS = 90;

    private final Path dir;
    private final ZoneId zone;
    private LocalDate day;
    private final List<Map<String, Object>> points = new ArrayList<>();

    SeriesStore(Path dir, ZoneId zone) {
        this.dir = dir;
        this.zone = zone;
    }

    /** Adds a point; when the local day turned, the finished day is rolled up first. True on a new day. */
    boolean add(Map<String, Object> point, long now) throws IOException {
        boolean newDay = false;
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone);
        if (day == null) {
            day = today;
            points.addAll(read(seriesFile(today)));
            catchUp(today);
        } else if (!today.equals(day)) {
            finishDay();
            newDay = true;
            day = today;
            points.clear();
            points.addAll(read(seriesFile(today)));
        }
        if (points.size() < MAX_POINTS) {
            points.add(point);
        }
        return newDay;
    }

    /** Writes the day's points. */
    void save() throws IOException {
        if (day != null) {
            AtomicFiles.writeJson(seriesFile(day), Map.of("day", day.toString(), "points", points));
        }
    }

    private void finishDay() throws IOException {
        save();
        AtomicFiles.writeJson(dir.resolve("daily").resolve(day + ".json"), rollup(day.toString(), points));
        prune();
        writeIndex();
    }

    /** Rolls up days the server was down at the turn of (their series exist, their rollup does not). */
    private void catchUp(LocalDate today) throws IOException {
        for (String d : list(dir.resolve("series"))) {
            if (d.compareTo(today.toString()) < 0 && !Files.exists(dir.resolve("daily").resolve(d + ".json"))) {
                AtomicFiles.writeJson(dir.resolve("daily").resolve(d + ".json"),
                        rollup(d, read(dir.resolve("series").resolve(d + ".json"))));
            }
        }
        prune();
        writeIndex();
    }

    /** Writes {@code days.json}: the days with series and with rollups. */
    void writeIndex() throws IOException {
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("series", list(dir.resolve("series")));
        index.put("daily", list(dir.resolve("daily")));
        AtomicFiles.writeJson(dir.resolve("days.json"), index);
    }

    /** A day in numbers: online and target averages and peaks, players, activity mix, resource use. */
    static Map<String, Object> rollup(String day, List<Map<String, Object>> points) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day);
        out.put("points", points.size());
        out.put("onlineAvg", avg(points, "on"));
        out.put("onlineMax", max(points, "on"));
        out.put("targetAvg", avg(points, "tgt"));
        out.put("playersMax", max(points, "pl"));
        out.put("heapMaxMb", max(points, "heap"));
        out.put("cpuAvg", avg(points, "cpu"));
        out.put("tickAvg", avg(points, "tick"));
        out.put("tickMax", max(points, "tick"));
        out.put("poolMax", max(points, "pool"));
        out.put("mesosToday", points.isEmpty() ? 0 : points.get(points.size() - 1).getOrDefault("meso", 0));
        Map<String, Double> act = new TreeMap<>();
        for (Map<String, Object> p : points) {
            if (p.get("act") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    if (v instanceof Number n) {
                        act.merge(String.valueOf(k), n.doubleValue(), Double::sum);
                    }
                });
            }
        }
        act.replaceAll((k, v) -> LiveSnapshot.round1(v / Math.max(1, points.size())));
        out.put("activityAvg", act);
        return out;
    }

    private static double avg(List<Map<String, Object>> points, String key) {
        double sum = 0;
        int n = 0;
        for (Map<String, Object> p : points) {
            if (p.get(key) instanceof Number v) {
                sum += v.doubleValue();
                n++;
            }
        }
        return n == 0 ? 0 : LiveSnapshot.round1(sum / n);
    }

    private static double max(List<Map<String, Object>> points, String key) {
        double best = 0;
        for (Map<String, Object> p : points) {
            if (p.get(key) instanceof Number v) {
                best = Math.max(best, v.doubleValue());
            }
        }
        return LiveSnapshot.round1(best);
    }

    private void prune() throws IOException {
        for (String sub : List.of("series", "daily")) {
            List<String> days = list(dir.resolve(sub));
            for (int i = 0; i < days.size() - KEEP_DAYS; i++) {
                Files.deleteIfExists(dir.resolve(sub).resolve(days.get(i) + ".json"));
            }
        }
    }

    private static List<String> list(Path sub) throws IOException {
        TreeSet<String> days = new TreeSet<>();
        if (Files.isDirectory(sub)) {
            try (Stream<Path> files = Files.list(sub)) {
                files.map(f -> f.getFileName().toString()).filter(n -> n.matches("\\d{4}-\\d{2}-\\d{2}\\.json"))
                        .forEach(n -> days.add(n.substring(0, 10)));
            }
        }
        return new ArrayList<>(days);
    }

    private Path seriesFile(LocalDate d) {
        return dir.resolve("series").resolve(d + ".json");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> read(Path file) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> m
                    && m.get("points") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> p && out.size() < MAX_POINTS) {
                        out.add((Map<String, Object>) p);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // a damaged day file starts the day over
        }
        return out;
    }

    int size() {
        return points.size();
    }
}
