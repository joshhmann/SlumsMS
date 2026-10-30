package soloMapling.ArtificialPlayer.GCMoveSystem;

import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The daily stuck-hotspot report (ticket 04c): reads the {@link NavStuckLog} day files and the failure counts of
 * {@link NavEdgeLearning}, and writes where bots got stuck - map, spot, what happened, the edge they were on and how
 * often - as {@code reports/nav/stuck-<day>.md} (and {@code stuck-latest.md}), with a one-line summary on stdout.
 * It feeds the nav lab; the cases physics cannot solve (scripted portals, one-way platforms) are fixed by hand.
 * Runs beside the server, reads files only.
 *
 * <pre>
 *   java -Dbot.nav.cacheDir=cache/bot-nav -cp target/Cosmic.jar soloMapling.ArtificialPlayer.GCMoveSystem.NavStuckReport
 *       [--day=2026-10-19 (default: yesterday, UTC)] [--days=1] [--top=40] [--out-dir=reports/nav]
 * </pre>
 */
public final class NavStuckReport {

    private NavStuckReport() {
    }

    public static void main(String[] args) throws IOException {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        int days = 1;
        int top = 40;
        Path outDir = Path.of("reports", "nav");
        Path stuckDir = NavStuckLog.defaultDir();
        Path learnedDir = Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"), "learned");
        for (String arg : args) {
            String value = arg.substring(arg.indexOf('=') + 1);
            if (arg.startsWith("--day=")) {
                day = value.equals("today") ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(value);
            } else if (arg.startsWith("--days=")) {
                days = Math.max(1, Integer.parseInt(value));
            } else if (arg.startsWith("--top=")) {
                top = Math.max(1, Integer.parseInt(value));
            } else if (arg.startsWith("--out-dir=")) {
                outDir = Path.of(value);
            } else if (arg.startsWith("--stuck-dir=")) {
                stuckDir = Path.of(value);
            } else if (arg.startsWith("--learned-dir=")) {
                learnedDir = Path.of(value);
            } else {
                throw new IllegalArgumentException("unknown option " + arg);
            }
        }
        List<NavStuckLog.Spot> spots = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            spots.addAll(NavStuckLog.read(NavStuckLog.fileFor(stuckDir, day.minusDays(i))));
        }
        List<FailingEdge> failing = failingEdges(learnedDir);
        String label = days == 1 ? day.toString() : day.minusDays(days - 1) + " to " + day;
        String report = render(label, merge(spots), failing, top);
        Files.createDirectories(outDir);
        NavDeepBake.writeAtomically(outDir.resolve("stuck-" + day + ".md"), report);
        NavDeepBake.writeAtomically(outDir.resolve("stuck-latest.md"), report);
        System.out.println(summary(label, merge(spots)) + " -> " + outDir.resolve("stuck-" + day + ".md"));
    }

    /** The same spot counted on several days (or after a restart) is one row. */
    static List<NavStuckLog.Spot> merge(List<NavStuckLog.Spot> spots) {
        Map<String, NavStuckLog.Spot> merged = new HashMap<>();
        for (NavStuckLog.Spot s : spots) {
            String key = s.mapId + "|" + s.kind + "|" + s.x + "|" + s.y + "|" + s.edge;
            NavStuckLog.Spot into = merged.get(key);
            if (into == null) {
                merged.put(key, s);
            } else {
                into.count += s.count;
                into.first = Math.min(into.first, s.first);
                into.last = Math.max(into.last, s.last);
                if (s.trace != null && (into.trace == null || s.traceAt > into.traceAt)) {
                    into.trace = s.trace; // ticket 157: the newest trace of the spot
                    into.traceAt = s.traceAt;
                }
                into.traces += s.traces;
            }
        }
        List<NavStuckLog.Spot> list = new ArrayList<>(merged.values());
        list.sort(Comparator.comparingInt((NavStuckLog.Spot s) -> -s.count).thenComparingInt(s -> s.mapId));
        return list;
    }

    static String summary(String label, List<NavStuckLog.Spot> spots) {
        int stuck = 0;
        int noPath = 0;
        java.util.Set<Integer> maps = new java.util.HashSet<>();
        NavStuckLog.Spot worst = null;
        for (NavStuckLog.Spot s : spots) {
            if (s.kind == NavStuckLog.Kind.NO_PATH) {
                noPath += s.count;
                continue;
            }
            stuck += s.count;
            maps.add(s.mapId);
            if (worst == null || s.count > worst.count) {
                worst = s;
            }
        }
        return "nav stuck " + label + ": " + stuck + " stuck events on " + maps.size() + " maps, " + noPath
                + " route failures" + (worst == null ? "" : "; worst " + worst.count + "x " + worst.kind + " map "
                + worst.mapId + " (" + worst.x + "," + worst.y + ")" + (worst.edge.isEmpty() ? "" : " " + worst.edge));
    }

    static String render(String label, List<NavStuckLog.Spot> spots, List<FailingEdge> failing, int top) {
        StringBuilder out = new StringBuilder();
        out.append("# Nav stuck hotspots ").append(label).append(" (UTC)\n\n");
        out.append(summary(label, spots)).append(".\n\n");

        Map<NavStuckLog.Kind, Integer> byKind = new EnumMap<>(NavStuckLog.Kind.class);
        Map<Integer, int[]> byMap = new HashMap<>();
        for (NavStuckLog.Spot s : spots) {
            byKind.merge(s.kind, s.count, Integer::sum);
            if (s.kind != NavStuckLog.Kind.NO_PATH) {
                int[] m = byMap.computeIfAbsent(s.mapId, k -> new int[2]);
                m[0] += s.count;
                m[1]++;
            }
        }
        out.append("By kind: ").append(byKind.isEmpty() ? "none" : byKind.toString()).append("\n\n");
        out.append("Kinds: UNSTUCK = stood still with a move to make until the unstuck hop; GAVE_UP = never got into an")
                .append(" edge's launch window; ROPE_HUNG = hung on a rope; AIR_FROZEN / FELL_OFF_MAP = put back by a")
                .append(" watchdog; NO_REGION = standing off the graph; NO_PATH = no route to the target's region.\n\n");

        out.append("## Top stuck spots\n\n");
        List<NavStuckLog.Spot> stuck = spots.stream().filter(s -> s.kind != NavStuckLog.Kind.NO_PATH).toList();
        if (stuck.isEmpty()) {
            out.append("None.\n\n");
        } else {
            out.append("| count | map | spot (x,y) | kind | edge | graph | last (UTC) |\n")
                    .append("| ---: | --- | --- | --- | --- | --- | --- |\n");
            for (NavStuckLog.Spot s : stuck.subList(0, Math.min(top, stuck.size()))) {
                out.append("| ").append(s.count).append(" | ").append(s.mapId).append(" | ").append(s.x).append(',')
                        .append(s.y).append(" | ").append(s.kind).append(" | ").append(s.edge.isEmpty() ? "-" : s.edge)
                        .append(" | ").append(s.graph.isEmpty() ? "-" : s.graph).append(" | ")
                        .append(s.last > 0 ? java.time.Instant.ofEpochMilli(s.last).toString().substring(11, 19) : "-")
                        .append(" |\n");
            }
            out.append('\n');
        }

        out.append("## Maps with the most stuck events\n\n");
        List<Map.Entry<Integer, int[]>> maps = new ArrayList<>(byMap.entrySet());
        maps.sort(Comparator.comparingInt((Map.Entry<Integer, int[]> e) -> -e.getValue()[0]));
        if (maps.isEmpty()) {
            out.append("None.\n\n");
        } else {
            out.append("| map | events | spots |\n| --- | ---: | ---: |\n");
            for (Map.Entry<Integer, int[]> e : maps.subList(0, Math.min(20, maps.size()))) {
                out.append("| ").append(e.getKey()).append(" | ").append(e.getValue()[0]).append(" | ")
                        .append(e.getValue()[1]).append(" |\n");
            }
            out.append('\n');
        }

        out.append("## Route failures (no path between regions)\n\n");
        List<NavStuckLog.Spot> noPath = spots.stream().filter(s -> s.kind == NavStuckLog.Kind.NO_PATH).toList();
        if (noPath.isEmpty()) {
            out.append("None.\n\n");
        } else {
            out.append("Often a target on a platform walkers cannot reach; repeated pairs are candidates for the nav lab.\n\n");
            out.append("| count | map | spot (x,y) | regions | graph |\n| ---: | --- | --- | --- | --- |\n");
            for (NavStuckLog.Spot s : noPath.subList(0, Math.min(20, noPath.size()))) {
                out.append("| ").append(s.count).append(" | ").append(s.mapId).append(" | ").append(s.x).append(',')
                        .append(s.y).append(" | ").append(s.edge).append(" | ").append(s.graph).append(" |\n");
            }
            out.append('\n');
        }

        renderTraces(out, stuck);

        out.append("## Edges the planner penalises (nav learning)\n\n");
        if (failing.isEmpty()) {
            out.append("None.\n");
        } else {
            out.append("Failures and successes fade with a 12-hour half-life; the factor is what the planner multiplies")
                    .append(" the edge's cost by.\n\n");
            out.append("| graph | edge | failures | successes | factor |\n| --- | --- | ---: | ---: | ---: |\n");
            for (FailingEdge f : failing.subList(0, Math.min(top, failing.size()))) {
                out.append("| ").append(f.graph()).append(" | ").append(f.edge()).append(" | ")
                        .append(String.format("%.1f", f.failures())).append(" | ")
                        .append(String.format("%.1f", f.successes())).append(" | ")
                        .append(String.format("%.1f", f.factor())).append(" |\n");
            }
        }
        return out.toString();
    }

    /** Traces shown per report, and the newest lines of each (the full trace stays in the day file). */
    static final int TRACED_SPOTS = 5;
    static final int TRACE_LINES = 40;

    /** Ticket 157: the nav ticks that led into the worst traced spots (-Dslums.navTrace=true on the server). */
    static void renderTraces(StringBuilder out, List<NavStuckLog.Spot> stuck) {
        List<NavStuckLog.Spot> traced = stuck.stream().filter(s -> s.trace != null && !s.trace.isEmpty())
                .limit(TRACED_SPOTS).toList();
        if (traced.isEmpty()) {
            return;
        }
        out.append("## Path traces (what led into the spot)\n\n");
        out.append("The newest traced event of each spot, oldest tick first; time is relative to the event.\n\n");
        for (NavStuckLog.Spot s : traced) {
            out.append("### ").append(s.kind).append(" map ").append(s.mapId).append(" (").append(s.x).append(',')
                    .append(s.y).append(")").append(s.edge.isEmpty() ? "" : " " + s.edge).append(", ")
                    .append(s.traces).append(s.traces == 1 ? " trace" : " traces").append("\n\n```\n");
            List<String> lines = s.trace.subList(Math.max(0, s.trace.size() - TRACE_LINES), s.trace.size());
            for (String line : lines) {
                out.append(line).append('\n');
            }
            out.append("```\n\n");
        }
    }

    /** One edge of a learning book that the planner currently penalises. */
    record FailingEdge(String graph, String edge, double failures, double successes, double factor) {
    }

    static List<FailingEdge> failingEdges(Path learnedDir) {
        List<FailingEdge> out = new ArrayList<>();
        if (!Files.isDirectory(learnedDir)) {
            return out;
        }
        long now = System.currentTimeMillis();
        try (Stream<Path> files = Files.list(learnedDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                try {
                    if (!(Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root)
                            || !(root.get("version") instanceof Number v)
                            || v.intValue() != BotNavigationGraphProvider.GRAPH_VERSION
                            || !(root.get("edges") instanceof Map<?, ?> edges)) {
                        continue;
                    }
                    for (Map.Entry<?, ?> e : edges.entrySet()) {
                        if (e.getValue() instanceof List<?> l && l.size() == 3) {
                            NavEdgeLearning.Stats stats = new NavEdgeLearning.Stats();
                            stats.failures = ((Number) l.get(0)).doubleValue();
                            stats.successes = ((Number) l.get(1)).doubleValue();
                            stats.at = ((Number) l.get(2)).longValue();
                            stats.fade(now);
                            double factor = NavEdgeLearning.factorFor(stats.failures, stats.successes);
                            if (factor > 1.0) {
                                out.add(new FailingEdge(name.substring(0, name.length() - 5), String.valueOf(e.getKey()),
                                        stats.failures, stats.successes, factor));
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // unreadable book: skip it
                }
            }
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparingDouble((FailingEdge f) -> -f.failures()));
        return out;
    }
}
