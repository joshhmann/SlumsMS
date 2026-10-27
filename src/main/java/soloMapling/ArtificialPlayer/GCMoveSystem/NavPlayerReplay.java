package soloMapling.ArtificialPlayer.GCMoveSystem;

import soloMapling.server.History;
import server.maps.MapleMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * Replays stored real-player moves ({@link NavPlayerLearning}'s {@code moves-<day>.jsonl}) - or recorded movement
 * packets (the GM recorder's {@code map<id>/*.csv}) - against the current base graph, offline, and prints what would
 * be learned and why the rest would not. Reads files only: nothing is adopted or written. Sightings are counted
 * from the replayed moves alone (the live server also carries sightings across restarts).
 *
 * <pre>
 *   java -Dwz-path=wz -Dbot.nav.cacheDir=cache/bot-nav -cp target/Cosmic.jar \
 *       soloMapling.ArtificialPlayer.GCMoveSystem.NavPlayerReplay
 *       [--days=7 | --all] [--map=104040000] [--dir=cache/bot-nav/players] [--csv=DIR] [--build] [--examples=10]
 * </pre>
 * {@code --csv=DIR} reads recorded packets from {@code DIR/map<id>/*.csv} through the live capture path instead of
 * stored moves. {@code --build} builds a map's graph in memory when the cache has none (slow; never saved).
 */
public final class NavPlayerReplay {

    private NavPlayerReplay() {
    }

    /** What one replay found: outcomes per map, the moves that would be learned, and examples of the rest. */
    record Report(Map<Integer, Map<NavObservedEdges.Outcome, Integer>> outcomes, List<String> adopted,
                  Map<NavObservedEdges.Outcome, List<String>> examples, int fhMismatch, Set<Integer> missingMaps) {

        int count(NavObservedEdges.Outcome o) {
            return outcomes.values().stream().mapToInt(m -> m.getOrDefault(o, 0)).sum();
        }
    }

    /** A map, its base graph and a lookup of its other movement tiers' graphs (null when there is none). */
    record Loaded(MapleMap map, BotNavigationGraph graph, Function<BotMovementProfile, BotNavigationGraph> tiers) {
        Loaded(MapleMap map, BotNavigationGraph graph) {
            this(map, graph, profile -> null);
        }
    }

    public static void main(String[] args) throws IOException {
        int days = 7;
        boolean all = false;
        Integer onlyMap = null;
        Path dir = NavPlayerLearning.DIR;
        Path csv = null;
        boolean build = false;
        int examples = 10;
        for (String arg : args) {
            String value = arg.substring(arg.indexOf('=') + 1);
            if (arg.startsWith("--days=")) {
                days = Math.max(1, Integer.parseInt(value));
            } else if (arg.equals("--all")) {
                all = true;
            } else if (arg.startsWith("--map=")) {
                onlyMap = Integer.parseInt(value);
            } else if (arg.startsWith("--dir=")) {
                dir = Path.of(value);
            } else if (arg.startsWith("--csv=")) {
                csv = Path.of(value);
            } else if (arg.equals("--build")) {
                build = true;
            } else if (arg.startsWith("--examples=")) {
                examples = Math.max(0, Integer.parseInt(value));
            } else {
                throw new IllegalArgumentException("unknown option " + arg);
            }
        }
        List<NavObservedEdges.Transition> moves = csv != null ? fromRecordedPackets(csv, onlyMap)
                : fromStoredMoves(dir, all ? Integer.MAX_VALUE : days);
        if (onlyMap != null) {
            int m = onlyMap;
            moves.removeIf(t -> t.mapId() != m);
        }
        boolean buildMissing = build;
        Report report = replay(moves, mapId -> load(mapId, buildMissing), examples);
        System.out.print(format(report, moves.size()));
    }

    /** A moves file's plain name: {@code moves-<day>.jsonl.gz} and {@code moves-<day>.jsonl} are the same day. */
    private static Path plainOf(Path f) {
        String n = f.getFileName().toString();
        return n.endsWith(".gz") ? f.resolveSibling(n.substring(0, n.length() - 3)) : f;
    }

    /** The stored moves of the last {@code days} UTC days (oldest first). */
    static List<NavObservedEdges.Transition> fromStoredMoves(Path dir, int days) throws IOException {
        List<NavObservedEdges.Transition> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        String oldest = days == Integer.MAX_VALUE ? "" : LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L).toString();
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(f -> f.getFileName().toString().matches("moves-\\d{4}-\\d{2}-\\d{2}\\.jsonl(\\.gz)?"))
                    .filter(f -> f.getFileName().toString().substring(6, 16).compareTo(oldest) >= 0)
                    .map(NavPlayerReplay::plainOf).distinct().sorted().toList();
        }
        for (Path f : files) {
            for (String line : History.readLines(f)) {
                NavObservedEdges.Transition t = NavPlayerLearning.parseLine(line);
                if (t != null) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * Recorded movement packets ({@code root/map<id>/*.csv}: a timestamp line, a count line, then that many
     * {@code command,x,y,vx,vy,fh,stance,duration} lines) run through the live capture path, one track per file.
     */
    static List<NavObservedEdges.Transition> fromRecordedPackets(Path root, Integer onlyMap) throws IOException {
        List<NavObservedEdges.Transition> out = new ArrayList<>();
        List<Path> mapDirs;
        try (Stream<Path> list = Files.list(root)) {
            mapDirs = list.filter(p -> p.getFileName().toString().matches("map\\d+") && Files.isDirectory(p)).sorted().toList();
        }
        for (Path mapDir : mapDirs) {
            int mapId = Integer.parseInt(mapDir.getFileName().toString().substring(3));
            if (onlyMap != null && onlyMap != mapId) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> list = Files.list(mapDir)) {
                files = list.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
            }
            for (Path f : files) {
                out.addAll(captureCsv(mapId, Files.readAllLines(f, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    /** One recorded packet file through {@link NavObservedEdges.Capture}: the moves it shows. */
    static List<NavObservedEdges.Transition> captureCsv(int mapId, List<String> lines) {
        NavObservedEdges.Capture capture = new NavObservedEdges.Capture();
        List<NavObservedEdges.Transition> out = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split(",");
            if (p.length != 8) {
                continue;
            }
            int command = Integer.parseInt(p[0].trim());
            if (command == 0 || command == 5 || command == 17) {
                NavObservedEdges.Transition t = capture.element(mapId, Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()),
                        Integer.parseInt(p[5].trim()), Integer.parseInt(p[6].trim()));
                if (t != null) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static Loaded load(int mapId, boolean build) {
        MapleMap map;
        try {
            map = BotNavigationMapLoader.loadMapGeometry(mapId);
        } catch (RuntimeException e) {
            return null;
        }
        Map<BotMovementProfile, java.util.Optional<BotNavigationGraph>> graphs = new HashMap<>();
        Function<BotMovementProfile, BotNavigationGraph> lookup = profile -> graphs.computeIfAbsent(profile, p -> {
            BotNavigationGraph g = BotNavigationGraphProvider.loadGraph(BotNavigationGraphProvider.GraphCacheKey.from(mapId, p));
            if (g == null && build) {
                g = BotNavigationGraphProvider.buildUncachedGraph(map, p);
            }
            return java.util.Optional.ofNullable(g);
        }).orElse(null);
        BotNavigationGraph graph = lookup.apply(BotMovementProfile.base());
        return graph == null ? null : new Loaded(map, graph, lookup);
    }

    /** Replays the moves in order: what the live server would make of them, without adopting or writing anything. */
    static Report replay(List<NavObservedEdges.Transition> moves, IntFunction<Loaded> loader, int examplesPerOutcome) {
        boolean wasReadOnly = NavObservedEdges.readOnly;
        NavObservedEdges.readOnly = true;
        try {
            Map<Integer, Loaded> loaded = new HashMap<>();
            Set<Integer> missing = new HashSet<>();
            Map<Integer, Map<NavObservedEdges.Outcome, Integer>> outcomes = new TreeMap<>();
            Map<NavObservedEdges.Outcome, List<String>> examples = new EnumMap<>(NavObservedEdges.Outcome.class);
            Map<String, Integer> sightings = new HashMap<>();
            Set<String> learned = new HashSet<>();
            List<String> adopted = new ArrayList<>();
            int mismatch = 0;
            for (NavObservedEdges.Transition t : moves) {
                Loaded l = loaded.computeIfAbsent(t.mapId(), loader::apply);
                NavObservedEdges.Outcome o;
                String detail = describe(t);
                if (l == null) {
                    missing.add(t.mapId());
                    o = NavObservedEdges.Outcome.NO_GRAPH;
                } else {
                    NavObservedEdges.Ends e = NavObservedEdges.ends(t, l.graph());
                    mismatch += e.fhMismatch() ? 1 : 0;
                    o = NavObservedEdges.classify(t, l.graph(), l.tiers());
                    String pair = t.mapId() + ":" + e.type() + ":" + e.from() + ">" + e.to();
                    detail += " regions " + e.from() + "->" + e.to() + " " + e.type() + (e.fhMismatch() ? " (fh id mismatch)" : "");
                    if (o == NavObservedEdges.Outcome.SIGHTED && learned.contains(pair)) {
                        o = NavObservedEdges.Outcome.ALREADY_JOINED; // learned earlier in this replay
                    } else if (o == NavObservedEdges.Outcome.SIGHTED
                            && sightings.merge(NavObservedEdges.sightingKey(t, e, l.graph().version), 1, Integer::sum)
                            >= NavObservedEdges.ADOPT_AFTER) {
                        NavObservedEdges.Proof proof = NavObservedEdges.prove(l.map(), l.graph(), t, e);
                        NavObservedEdges.Proof trimmed = proof == null ? null : NavObservedEdges.withoutKnown(proof, e, l.tiers());
                        if (proof == null) {
                            o = NavObservedEdges.Outcome.PROVE_FAILED;
                        } else if (trimmed == null) {
                            o = NavObservedEdges.Outcome.ALREADY_JOINED;
                            detail += " (only faster bots make it, and their graphs already do)";
                        } else {
                            proof = trimmed;
                            o = NavObservedEdges.Outcome.ADOPTED;
                            learned.add(pair);
                            adopted.add(detail + " => " + proof.edge().type + " from (" + proof.edge().startPoint.x + ","
                                    + proof.edge().startPoint.y + ") step " + proof.edge().launchStepX + ", movement tiers (speed/jump) "
                                    + NavObservedEdges.tiers(proof.mask()));
                        }
                    }
                }
                outcomes.computeIfAbsent(t.mapId(), k -> new EnumMap<>(NavObservedEdges.Outcome.class)).merge(o, 1, Integer::sum);
                List<String> ex = examples.computeIfAbsent(o, k -> new ArrayList<>());
                if (ex.size() < examplesPerOutcome && o != NavObservedEdges.Outcome.ADOPTED) {
                    ex.add(detail);
                }
            }
            return new Report(outcomes, adopted, examples, mismatch, missing);
        } finally {
            NavObservedEdges.readOnly = wasReadOnly;
        }
    }

    private static String describe(NavObservedEdges.Transition t) {
        return "map " + t.mapId() + " " + t.kind() + " fh " + t.fromFh() + " (" + t.takeoff().x + "," + t.takeoff().y
                + ") -> fh " + t.toFh() + " (" + t.landing().x + "," + t.landing().y + ")";
    }

    static String format(Report r, int moves) {
        StringBuilder sb = new StringBuilder();
        sb.append("Replayed ").append(moves).append(" players' moves on ").append(r.outcomes().size()).append(" maps against the current base graphs.\n");
        Map<NavObservedEdges.Outcome, Integer> sum = new EnumMap<>(NavObservedEdges.Outcome.class);
        r.outcomes().values().forEach(m -> m.forEach((o, n) -> sum.merge(o, n, Integer::sum)));
        sb.append("Outcomes: ").append(sum).append('\n');
        if (r.fhMismatch() > 0) {
            sb.append("Foothold id not under the reported position (resolved by position): ").append(r.fhMismatch()).append('\n');
        }
        if (!r.missingMaps().isEmpty()) {
            sb.append("No cached base graph (use --build): ").append(r.missingMaps()).append('\n');
        }
        sb.append("\nPer map:\n");
        r.outcomes().forEach((map, m) -> sb.append("  ").append(map).append(' ').append(m).append('\n'));
        sb.append("\nWould be learned (").append(r.adopted().size()).append("):\n");
        r.adopted().forEach(a -> sb.append("  ").append(a).append('\n'));
        sb.append("\nWhy the rest were not:\n");
        r.examples().forEach((o, ex) -> {
            if (ex.isEmpty()) {
                return;
            }
            sb.append("  ").append(o).append(" - ").append(reason(o)).append('\n');
            ex.forEach(e -> sb.append("    ").append(e).append('\n'));
        });
        return sb.toString();
    }

    private static String reason(NavObservedEdges.Outcome o) {
        return switch (o) {
            case NO_MAP -> "map not loaded";
            case NO_GRAPH -> "no cached base graph for the map";
            case ROPE_SKILL -> "a teleport, flash jump or down-jump to or from a rope is not learned";
            case UNKNOWN_REGION -> "an end on no platform or rope of the graph";
            case SAME_REGION -> "a hop on one platform";
            case ALREADY_JOINED -> "the graph already makes this move";
            case SIGHTED -> "new, but seen fewer than " + NavObservedEdges.ADOPT_AFTER + " times from that spot";
            case PROVE_FAILED -> "the bots' physics could not repeat it at any profile";
            case ADOPTED -> "learned";
        };
    }
}
