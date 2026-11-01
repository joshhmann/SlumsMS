package soloMapling.ArtificialPlayer.GCMoveSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * Ticket 157 item 3 (nutnnut's world graph, adoption matrix: "the trap check as an auto-QA check plus a unit test"),
 * auto-QA t-nv-2 (b224): the bots' world graph ({@link GCWorldGraph}: walkable portals, curated scripted warps and
 * BotTransit's rides, no Hyper Teleport Rock) checked for one-way-in traps - maps a bot can reach from Lith Harbor
 * but from which no route leads back. A bot routed into one strands there until a warp fetches it.
 * <p>
 * Many traps are by design (a party quest stage, a jump quest prize room, a boss altar: the way out is an NPC or a
 * timer, not a portal), so the known ones are a baseline ({@link #BASELINE}, generated from the WZ data and committed);
 * the check fails on a trap that is not in it, which is what a data change (a Map.wz edit, a new scripted warp or
 * BotTransit row) that cuts a route home looks like. Read-only: it reads the graph the planner already built.
 */
public final class NavTrapQa {

    /** Where the search starts (upstream's check uses Lith Harbor, the first town). */
    public static final int HUB = constants.id.MapId.LITH_HARBOUR;

    /** The known traps: one map id per line, '#' starts a comment. */
    static final String BASELINE = "/soloMapling/nav-trap-maps.txt";

    private NavTrapQa() {
    }

    /** Maps reachable from the hub, those with a way back, and the traps (reachable, no way back), sorted. */
    public record Traps(int hub, int reachable, int canReturn, SortedSet<Integer> traps) {
    }

    /**
     * The traps of a graph: a forward search from {@code hub} over {@code next}, then a backward search from the hub
     * over the reversed edges among the maps it reached (a way back from a reached map only passes reached maps).
     */
    static Traps find(int hub, IntFunction<int[]> next) {
        Map<Integer, List<Integer>> reverse = new HashMap<>();
        Set<Integer> reached = new HashSet<>();
        ArrayDeque<Integer> open = new ArrayDeque<>();
        reached.add(hub);
        open.add(hub);
        while (!open.isEmpty()) {
            int at = open.poll();
            int[] out = next.apply(at);
            if (out == null) {
                continue;
            }
            for (int to : out) {
                if (to <= 0 || to == at) {
                    continue;
                }
                reverse.computeIfAbsent(to, k -> new ArrayList<>()).add(at);
                if (reached.add(to)) {
                    open.add(to);
                }
            }
        }
        Set<Integer> back = new HashSet<>();
        back.add(hub);
        open.add(hub);
        while (!open.isEmpty()) {
            int at = open.poll();
            for (int from : reverse.getOrDefault(at, List.of())) {
                if (back.add(from)) {
                    open.add(from);
                }
            }
        }
        SortedSet<Integer> traps = new TreeSet<>();
        for (int m : reached) {
            if (!back.contains(m)) {
                traps.add(m);
            }
        }
        return new Traps(hub, reached.size(), back.size(), Collections.unmodifiableSortedSet(traps));
    }

    /**
     * The traps of the server's world graph (builds it on first use, a few seconds; TEST has it already). b230: the
     * search steps the way the planner does for some bot ({@link GCWorldGraph#waysOn}), so the intended one-way rides
     * are not traps: Grandis and Arcane River (the Henesys teleporter and the Gate of Time go in, the Hyper Teleport
     * Rock's out_of ranges in BotTransit.yaml bring a bot home) and Malaysia (the tour home goes back to the saved town).
     */
    public static Traps worldTraps() {
        return find(HUB, GCWorldGraph::waysOn);
    }

    /** Whether the world graph has been built (a probe on a fresh server builds it first). */
    public static boolean graphReady() {
        return GCWorldGraph.isReady();
    }

    /**
     * The committed baseline of known traps (map id -> its comment, '' when none); null when the file is missing from
     * the jar. Empty is a real answer since b230: the intended one-way rides are not traps any more.
     */
    public static Map<Integer, String> baseline() {
        try (InputStream in = NavTrapQa.class.getResourceAsStream(BASELINE)) {
            if (in == null) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) {
                    lines.add(l);
                }
            }
            return parse(lines);
        } catch (IOException e) {
            return null;
        }
    }

    static Map<Integer, String> parse(List<String> lines) {
        Map<Integer, String> out = new TreeMap<>();
        for (String raw : lines) {
            String l = raw.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            int hash = l.indexOf('#');
            String id = (hash < 0 ? l : l.substring(0, hash)).strip();
            out.put(Integer.parseInt(id), hash < 0 ? "" : l.substring(hash + 1).strip());
        }
        return Collections.unmodifiableMap(out);
    }

    /** The traps not in the baseline (new: a FAIL), and the baseline maps that are no longer traps (fixed). */
    public record Diff(SortedSet<Integer> unknown, SortedSet<Integer> fixed) {
    }

    public static Diff diff(Traps now, Set<Integer> known) {
        SortedSet<Integer> unknown = new TreeSet<>(now.traps());
        unknown.removeAll(known);
        SortedSet<Integer> fixed = new TreeSet<>(known);
        fixed.removeAll(now.traps());
        return new Diff(unknown, fixed);
    }
}
