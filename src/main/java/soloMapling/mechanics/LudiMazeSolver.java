package soloMapling.mechanics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ludimaze} (ticket 164, LudiMaze PQ): find the exit of a sixteen-map maze, then hand the leader's passes over.
 *
 * <p><b>The maze is not shuffled, and this solver does not pretend it is.</b> {@code LudiMazePQ.js:120-127} calls
 * {@code shuffleReactors()} on every maze map, which moves the <em>reactors</em>; the passages are {@code pt 2} portals
 * named {@code t000}..{@code t003} and their targets are fixed in the client data
 * ({@code Map.wz/Map/Map8/8090500*.img.xml}): map {@code 8090500NN} leads to {@code NN+4}, {@code NN+7} and
 * {@code NN+12} modulo fifteen, and only {@code 809050008}'s {@code t001} reaches the goal map {@code 809050015}, where
 * NPC {@code 9103000} stands. What <em>is</em> random is where each member starts: {@code playerEntry} warps it to
 * {@code 809050000 + rand(0..14)} ({@code LudiMazePQ.js:144-150}), so no fixed route from one map can serve a party.
 *
 * <p>So the solver reads the portals it can see ({@link StageView#portalsOn(int)}) into a graph kept in
 * {@link StageView#stageMemory()} — per instance, thrown away when the stage changes — and walks the shortest route to
 * the goal over everything the party has learned so far. A member on a map the graph does not know yet explores a
 * portal the party has not taken from it, so the union of sixteen members' sightings covers the maze within a few ticks.
 * Nothing is read from any upstream file: their sixteen-room arm layout is their instance's.
 *
 * <p><b>The passes.</b> The maze's own monsters drop {@code 4001106} and the reactor {@code 8098000}
 * ({@code scripts/reactor/8098000.js}) is what summons them on every map but three. The goal NPC wants the leader to
 * hold {@code 30} ({@code scripts/npc/9103000.js:62}) and refuses until {@code isEventTeamTogether()}
 * ({@code :57-61}), which is {@link StageView#teamTogether(int)} — not a count of members the instance has let go.
 *
 * <p><b>Bounded.</b> There is no property on the clear: {@code clearPQ()} only warps the team to {@code 809050016}, so
 * the row says {@code clear: { instance: true }}. If the graph never reaches the goal, the solver says what it has
 * mapped and, past {@link #GIVE_UP_MS}, abandons the run by name rather than wandering into the script's fifteen-minute
 * timeout.
 */
public final class LudiMazeSolver implements StageSolver {

    public static final String ID = "ludimaze";

    /** The exit of the maze: NPC {@code 9103000} stands here ({@code Map.wz/Map/Map8/809050015.img.xml:life}). */
    static final int GOAL_MAP = 809050015;

    /** The summoner reactor every maze map but three carries ({@code scripts/reactor/8098000.js:3}). */
    static final int SUM_REACTOR = 8098000;

    /** The maze's own item; the script makes it instance-exclusive ({@code LudiMazePQ.js:73-76}). */
    static final int PASS = 4001106;

    /** What {@code 9103000.js:62} asks of the leader. */
    static final int NEED = 30;

    /** Past this the maze is abandoned with a reason: the script's own limit is fifteen minutes. */
    static final long GIVE_UP_MS = 12 * 60_000L;

    static final String GRAPH = "maze.graph";
    static final String STARTED = "maze.started";
    static final String TRIED = "maze.tried.";

    @Override
    public String id() {
        return ID;
    }

    /** The map set the row declares, so a member that starts on any of the fifteen is where it belongs. */
    static Set<Integer> maps(StageView view) {
        return view.stage().mapSet();
    }

    /** What the party knows about the maze: portal name to target map, per map. */
    @SuppressWarnings("unchecked")
    static Map<Integer, Map<String, Integer>> graph(StageView view) {
        Object known = view.stageMemory().get(GRAPH);
        if (known instanceof Map<?, ?> m) {
            return (Map<Integer, Map<String, Integer>>) m;
        }
        Map<Integer, Map<String, Integer>> fresh = new java.util.concurrent.ConcurrentHashMap<>();
        Object previous = view.stageMemory().putIfAbsent(GRAPH, fresh);
        return previous instanceof Map<?, ?> p ? (Map<Integer, Map<String, Integer>>) p : fresh;
    }

    /** Records every portal this member can see on its map, so one sighting is enough for the whole party. */
    static void learn(StageView view, int mapId) {
        Map<String, Integer> seen = view.portalsOn(mapId);
        if (!seen.isEmpty()) {
            graph(view).put(mapId, new LinkedHashMap<>(seen));
        }
    }

    /** Every map the party has a sighting of, the goal included once it is reached. */
    static Set<Integer> known(StageView view) {
        return new LinkedHashSet<>(graph(view).keySet());
    }

    /**
     * The portal to walk into next: the first hop of a shortest route to the goal over what the party knows. When the
     * goal is not reachable yet, the first portal that leads somewhere unmapped (that is how the graph grows); when
     * every neighbour is already mapped, a portal this map has not been tried from; null when there is nothing left.
     */
    static String firstHop(StageView view, int from) {
        Map<Integer, Map<String, Integer>> g = graph(view);
        String route = firstHopToward(g, from, GOAL_MAP);
        if (route != null) {
            return route;
        }
        Map<String, Integer> exits = g.get(from);
        if (exits == null) {
            return null;
        }
        for (Map.Entry<String, Integer> e : exits.entrySet()) {
            if (!g.containsKey(e.getValue())) {
                return e.getKey(); // a map nobody has seen: walk there and map it
            }
        }
        Set<String> tried = tried(view, from);
        for (String portal : exits.keySet()) {
            if (!tried.contains(portal)) {
                return portal;
            }
        }
        return null;
    }

    /** Breadth-first over the known edges; the portal name of the first edge on a shortest path to {@code goal}. */
    static String firstHopToward(Map<Integer, Map<String, Integer>> g, int from, int goal) {
        if (from == goal) {
            return null;
        }
        Map<Integer, String> viaPortal = new LinkedHashMap<>();
        Map<Integer, Integer> cameFrom = new LinkedHashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        cameFrom.put(from, -1);
        while (!queue.isEmpty()) {
            int at = queue.removeFirst();
            Map<String, Integer> exits = g.get(at);
            if (exits == null) {
                continue;
            }
            for (Map.Entry<String, Integer> e : exits.entrySet()) {
                int next = e.getValue();
                if (cameFrom.containsKey(next)) {
                    continue;
                }
                cameFrom.put(next, at);
                viaPortal.put(next, e.getKey());
                if (next == goal) {
                    // walk back to the map we are on: the first hop is the last edge we unwind to it
                    int step = goal;
                    while (cameFrom.get(step) != from) {
                        step = cameFrom.get(step);
                        if (step == from) {
                            break;
                        }
                    }
                    return viaPortal.get(step);
                }
                queue.addLast(next);
            }
        }
        return null;
    }

    /** The portals this member has already walked out of this map, so a dead end is escaped rather than looped. */
    @SuppressWarnings("unchecked")
    static Set<String> tried(StageView view, int mapId) {
        Object known = view.stageMemory().get(TRIED + mapId);
        if (known instanceof Set<?> s) {
            return (Set<String>) s;
        }
        Set<String> fresh = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Object previous = view.stageMemory().putIfAbsent(TRIED + mapId, fresh);
        return previous instanceof Set<?> p ? (Set<String>) p : fresh;
    }

    /** How long this stage has been running; the first call starts the clock in the run's own stage memory. */
    static long elapsed(StageView view) {
        Object started = view.stageMemory().putIfAbsent(STARTED, view.nowMs());
        long at = started instanceof Long l ? l : view.nowMs();
        return view.nowMs() - at;
    }

    /** Who is not at the exit yet, by name when the view knows it: what the wait line says. */
    static String missing(StageView view) {
        List<Integer> ids = view.missingFrom(GOAL_MAP);
        List<String> names = new ArrayList<>();
        for (int id : ids) {
            names.add(view.nameOf(id).orElse("#" + id));
        }
        return String.join(", ", names);
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        int here = view.mapOf(id);
        Set<Integer> maps = maps(view);
        if (!maps.contains(here)) {
            return new SolverStep.WarpTo(view.stage().mapId(), "into the maze");
        }
        learn(view, here);
        long elapsed = elapsed(view);
        int have = view.itemCount(id, PASS);

        if (here == GOAL_MAP) {
            if (member.leader()) {
                if (!view.teamTogether(GOAL_MAP)) {
                    // 9103000.js:57-61 - the script refuses while one member is missing, so say who before the leader
                    // wastes the talk: this is the Wait that makes a split party visible instead of silent
                    return new SolverStep.Wait(1_500, "waiting at the exit for " + missing(view));
                }
                if (have >= NEED) {
                    return new SolverStep.TalkNpc(view.stage().npcId(), PASS, NEED);
                }
                // short at the exit: the exit has one passage back into the maze (its t000), so the leader farms
                // rather than idling at the goal while the party feeds it
                String back = view.portalsOn(GOAL_MAP).keySet().stream().findFirst().orElse(null);
                if (back != null) {
                    return new SolverStep.EnterPortal(back);
                }
                return new SolverStep.Wait(1_500, "at the exit with " + have + " of " + NEED + " passes");
            }
            RunMember leader = view.leader().orElse(null);
            if (leader != null && leader.characterId() != id && have > 0 && view.mapOf(leader.characterId()) == GOAL_MAP) {
                return PassHandover.give(view, id, PASS, have, leader);
            }
            if (leader != null && leader.characterId() != id && view.itemCount(leader.characterId(), PASS) < NEED) {
                // nothing left to hand over and the leader is still short: go back in and farm, rather than stand here
                String back = view.portalsOn(GOAL_MAP).keySet().stream().findFirst().orElse(null);
                if (back != null) {
                    return new SolverStep.EnterPortal(back);
                }
            }
            return new SolverStep.Wait(1_500, "at the exit, waiting for the leader's talk");
        }

        // A member holding enough on its own carries them to the exit; the rest of the party fights for the difference.
        if (have >= NEED) {
            return travel(view, member, here);
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, PASS, NEED);
        }
        if (view.reactorState(SUM_REACTOR) >= 0) {
            // scripts/reactor/8098000.js: hit it and monsters arrive; they drop the passes
            return new SolverStep.HitReactor(SUM_REACTOR);
        }
        return travel(view, member, here);
    }

    /** Walks the first hop towards the exit, or says what is missing when the maze has not been mapped that far. */
    private SolverStep travel(StageView view, RunMember member, int here) {
        String hop = firstHop(view, here);
        if (hop != null) {
            tried(view, here).add(hop);
            return new SolverStep.EnterPortal(hop);
        }
        if (elapsed(view) > GIVE_UP_MS) {
            return new SolverStep.Abandon("the maze has no route to " + GOAL_MAP + " from " + here + " after "
                    + elapsed(view) / 1000 + " s and " + known(view).size() + " maps mapped");
        }
        return new SolverStep.Wait(1_000, "no route to the exit from " + here + " yet: " + known(view).size()
                + " maps mapped, " + (view.stage().maps().size() + 1) + " in the maze");
    }
}
