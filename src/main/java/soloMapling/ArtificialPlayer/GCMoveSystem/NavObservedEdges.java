package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import constants.game.CharacterStance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Rope;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.Json;

import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

/**
 * Movement that learns from real players (bot-persistence ticket 17, SlumsMS ticket 04). The graph builder only
 * tries moves from sampled points on each platform and rope, so a move launched from a spot it skipped never becomes
 * an edge - but a player finds it. Every move packet of a real player carries a foothold id (0 while airborne, the
 * client's rope id negated while climbing) and a stance (rope and ladder stances while climbing); the fragments
 * between them say how the player moved (a teleport, a flash jump or a down-jump). So:
 * <ul>
 *   <li>ground on foothold A, a stretch in the air, ground on foothold B is a <b>jump</b> (or a walk off A's end,
 *       which is proven as a drop);</li>
 *   <li>the same with a flash-jump fragment in the air is a <b>flash jump</b>, with a teleport fragment a
 *       <b>teleport</b> (counted only from a player whose job has the skill), with a fall-down fragment a
 *       <b>down-jump</b>;</li>
 *   <li>a rope at one end or both (climbing stance) is a <b>rope move</b>: a jump-grab, a jump off, or a
 *       rope-to-rope transfer.</li>
 * </ul>
 * When the graph has no such move between the two regions and players made it at least {@link #ADOPT_AFTER} times
 * from about the same spot, it is re-simulated with the bots' own physics and skills from the player's takeoff
 * point, at the base movement profile and at each of the bots' faster level tiers ({@link #PROOF_PROFILES}); if it
 * lands in the same region, it is proven possible for bots of that tier and joins the map as an ordinary edge of its
 * type (JUMP, DROP, FLASH_JUMP, TELEPORT or CLIMB), planned only on graphs of a tier that proved it and that do not
 * make the move themselves - kept in an overlay
 * next to the cached graph, never written into it, so planners reading the graph concurrently are untouched. Skill
 * edges are planned only for bots whose job carries the skill, like the graph's own. A flash jump a plain jump could
 * also make is adopted as a plain jump, for every job. Lag, knockback and anything the physics cannot reproduce fail
 * the re-simulation and are dropped.
 *
 * <p>Both ends are found by foothold id, checked against the reported position (a client whose map data numbers
 * footholds differently is caught and resolved by position instead). Every move's outcome is counted and logged by
 * {@link NavPlayerLearning}; sightings survive restarts.
 *
 * <p>The overlay is stamped with the graph version. A newer graph keeps each learned move whose two ends still lie
 * on a platform (or rope) of the new graph and that the new graph does not already make itself.
 */
public final class NavObservedEdges {

    private static final Logger log = LoggerFactory.getLogger(NavObservedEdges.class);

    static final int ADOPT_AFTER = 2;
    /** Two rope positions this many pixels apart are two ropes (a rope-to-rope transfer). */
    static final int SPOT_PX = 20;
    static final Path DIR = Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"), "observed");
    /** How far off a platform's surface a stored point may sit and still be on it in a rebuilt graph. */
    static final int REMAP_Y_PX = 4;
    /** How far off its foothold's surface a reported player position may sit (client rounding, slopes). */
    static final int FH_MATCH_PX = 6;
    /** A rope end reported this far past the rope's top or bottom still belongs to the rope (climbing off an end). */
    static final int ROPE_END_SLACK_PX = 12;
    /** A takeoff this close to a platform's end, landing lower, may be a walk off the end: proven as a drop too. */
    static final int WALK_OFF_EDGE_PX = 10;
    /** Moves waiting for their map's graph to load from the disk cache, per map. */
    static final int WAITING_CAP = 64;

    /**
     * The movement profiles a move is re-simulated with, slowest first. Since 1.5.0 bots move on earned speed (100% plus
     * real gear, buffs and Haste, capped like a player at 140 / 123), so the tiers are the base profile, light speed
     * gear, a hasted thief, and the cap. A move is planned only for bots of a tier that proved it.
     */
    static final BotMovementProfile[] PROOF_PROFILES = {
            BotMovementProfile.base(),
            new BotMovementProfile(110, 100),
            new BotMovementProfile(120, 110),
            new BotMovementProfile(140, 120),
            new BotMovementProfile(BotMovementProfile.PLAYER_MAX_SPEED, BotMovementProfile.PLAYER_MAX_JUMP)
    };

    /** The tiers before 1.5.0 (level baselines), which stored masks without a {@code tiers} key refer to. */
    static final BotMovementProfile[] LEGACY_PROOF_PROFILES = {
            BotMovementProfile.base(),
            new BotMovementProfile(115, 100),
            new BotMovementProfile(125, 105),
            new BotMovementProfile(130, 110),
            new BotMovementProfile(135, 115),
            new BotMovementProfile(145, 120),
            new BotMovementProfile(155, 123)
    };

    /** The stored-mask format: 2 = {@link #PROOF_PROFILES} of 1.5.0. */
    static final int TIERS_FORMAT = 2;

    /**
     * A pre-1.5.0 mask in today's tiers: a move proven at an old tier applies to every new tier at least as fast and
     * as high-jumping (never to a slower one). A move proven only at the old 155 % is dropped (no bot is that fast now).
     */
    static int fromLegacy(int legacyMask) {
        int mask = 0;
        for (int k = 0; k < LEGACY_PROOF_PROFILES.length; k++) {
            if ((legacyMask >> k & 1) == 0) {
                continue;
            }
            for (int n = 0; n < PROOF_PROFILES.length; n++) {
                if (PROOF_PROFILES[n].totalSpeedStat() >= LEGACY_PROOF_PROFILES[k].totalSpeedStat()
                        && PROOF_PROFILES[n].totalJumpStat() >= LEGACY_PROOF_PROFILES[k].totalJumpStat()) {
                    mask |= 1 << n;
                }
            }
        }
        return mask;
    }

    /** How the player got from one foothold or rope to the other. */
    public enum Kind {
        JUMP,
        FLASH_JUMP,
        TELEPORT,
        DOWN_JUMP
    }

    /** What became of one captured move (diagnostics: why players' moves are or are not learned). */
    public enum Outcome {
        /** The map is not loaded on this server. */
        NO_MAP,
        /** The map has no base graph in memory or in the disk cache. */
        NO_GRAPH,
        /** A teleport, flash jump or down-jump to or from a rope: not learned. */
        ROPE_SKILL,
        /** An end on no platform or rope of the graph. */
        UNKNOWN_REGION,
        /** Both ends on one platform (a hop): nothing to learn. */
        SAME_REGION,
        /** The graph (or an earlier adoption) already makes this move. */
        ALREADY_JOINED,
        /** A new move, counted; not yet seen {@link #ADOPT_AFTER} times. */
        SIGHTED,
        /** Seen often enough, but the bots' physics could not repeat it. */
        PROVE_FAILED,
        /** Proven and added to the map. */
        ADOPTED
    }

    /** One player's move, as the packets showed it. A foothold id of 0 is a rope or ladder. */
    record Transition(int mapId, Kind kind, int fromFh, Point takeoff, int toFh, Point landing) {
        Transition(int mapId, int fromFh, Point takeoff, int toFh, Point landing) {
            this(mapId, Kind.JUMP, fromFh, takeoff, toFh, landing);
        }
    }

    /**
     * One player's movement state: where they last stood and whether they have left it. Fed one absolute movement
     * element at a time; returns the move each landing completes. Not thread-safe (the caller synchronizes).
     */
    static final class Capture {
        int mapId = -1;
        boolean anchored;
        /** Where the player last stood: a foothold id, or 0 on a rope. */
        int anchorFh;
        Point anchorPoint;
        boolean airborne;
        Kind skill;

        /** One absolute element: {@code fh} 0 while airborne, negative (a rope) while climbing. */
        Transition element(int map, int x, int y, int fh, int stance) {
            boolean climbing = isClimbing(stance);
            if (fh < 0 && !climbing) {
                return null; // a rope id without a climbing stance: neither ground nor air, say nothing
            }
            if (mapId != map) {
                mapId = map;
                anchored = false;
                airborne = false;
                skill = null;
            }
            if (fh == 0 && !climbing) {
                airborne = anchored;
                return null;
            }
            int here = climbing ? 0 : fh;
            Transition moved = null;
            if (airborne && anchored && (here != anchorFh
                    || here == 0 && Math.abs(x - anchorPoint.x) > SPOT_PX)) { // rope to another rope
                moved = new Transition(map, skill != null ? skill : Kind.JUMP, anchorFh, anchorPoint, here, new Point(x, y));
            }
            anchored = true;
            anchorFh = here;
            anchorPoint = new Point(x, y);
            airborne = false;
            skill = null;
            return moved;
        }

        /** A skill or down-jump fragment: counts only when it leaves the ground. */
        void mark(int map, Kind kind) {
            if (mapId == map && anchored && anchorFh != 0) {
                skill = kind;
                airborne = true; // a teleport leaves the foothold without a fall in between
            }
        }
    }

    /**
     * A learned move and the {@link #PROOF_PROFILES} it was proven at (bit i: profile i makes it and that profile's
     * graph lacked it). A graph uses it when the tier its own profile falls in (the fastest proof profile it is at
     * least as fast as) has the bit: a faster bot can overshoot what a slower one lands, so speed alone is no proof.
     */
    record Learned(BotNavigationGraph.Edge edge, int mask) {
        boolean appliesTo(BotNavigationGraph graph) {
            int tier = tierOf(graph.movementProfile);
            return tier >= 0 && (mask >> tier & 1) == 1;
        }
    }

    static final int ALL_TIERS = (1 << PROOF_PROFILES.length) - 1;

    /** The fastest proof profile this profile is at least as fast as (speed and jump), or -1 when slower than base. */
    static int tierOf(BotMovementProfile profile) {
        for (int i = PROOF_PROFILES.length - 1; i >= 0; i--) {
            if (profile.totalSpeedStat() >= PROOF_PROFILES[i].totalSpeedStat()
                    && profile.totalJumpStat() >= PROOF_PROFILES[i].totalJumpStat()) {
                return i;
            }
        }
        return -1;
    }

    /** Adopted edges of one map, for the graph version they were proven on. */
    private record Overlay(int version, List<Learned> edges) {
    }

    /** How often a new move was seen from one spot, and when last. */
    static final class Sighting {
        int count;
        long last;
    }

    private static final Map<Integer, Capture> tracks = new ConcurrentHashMap<>();
    private static final Map<String, Sighting> sightings = new ConcurrentHashMap<>();
    private static final Map<Integer, Overlay> overlays = new ConcurrentHashMap<>();
    private static final Map<Integer, List<Transition>> waiting = new ConcurrentHashMap<>();
    private static volatile Path dir = DIR;
    /** The offline replay reads the overlays and never writes them. */
    static volatile boolean readOnly;
    /** Test seam: where captured moves go instead of being considered. */
    static volatile java.util.function.Consumer<Transition> sink;
    private static volatile IntFunction<MapleMap> maps = soloMapling.server.SoloMaplingUtilities::getMapleMapById;

    private NavObservedEdges() {
    }

    // ── capture (packet thread: keep it cheap) ─────────────────────────────────────────────────

    /** One movement element of a real player, without its stance (never climbing). */
    public static void onPlayerMove(Character player, int x, int y, int fh) {
        onPlayerMove(player, x, y, fh, -1);
    }

    /** One absolute movement element of a real player. {@code fh} is 0 while airborne, negative while climbing. */
    public static void onPlayerMove(Character player, int x, int y, int fh, int stance) {
        if (player == null || player.getMap() == null) {
            return;
        }
        if (player.getMap().isSwim()) {
            SwimNavObserved.onPlayerMove(player, x, y, fh, stance); // ticket 34: players' swims teach the swim graph
        }
        Capture track = tracks.computeIfAbsent(player.getId(), k -> new Capture());
        Transition moved;
        synchronized (track) {
            moved = track.element(player.getMapId(), x, y, fh, stance);
        }
        if (moved != null) {
            Transition t = moved;
            java.util.function.Consumer<Transition> testSink = sink;
            if (testSink != null) {
                testSink.accept(t);
            } else {
                ExecutorServiceManager.runAsync(() -> consider(t));
            }
        }
    }

    /**
     * A teleport or flash-jump fragment in a real player's move packet. Counted only when the player's job has
     * that skill (a GM's or anything else's blink is not a move a bot could learn), and only off the ground.
     */
    public static void onPlayerSkillMove(Character player, Kind kind) {
        if (player == null || player.getJob() == null || kind == Kind.JUMP || kind == Kind.DOWN_JUMP) {
            return;
        }
        int job = player.getJob().getId();
        if (kind == Kind.TELEPORT ? !NavMovementSkills.isTeleportJob(job) : !NavMovementSkills.isFlashJumpJob(job)) {
            return;
        }
        markFromGround(player, kind);
    }

    /** A fall-down (down-jump) fragment in a real player's move packet: anyone can down-jump. */
    public static void onPlayerDownJump(Character player) {
        if (player != null) {
            markFromGround(player, Kind.DOWN_JUMP);
        }
    }

    private static void markFromGround(Character player, Kind kind) {
        Capture track = tracks.get(player.getId());
        if (track == null) {
            return;
        }
        synchronized (track) {
            track.mark(player.getMapId(), kind);
        }
    }

    static boolean isClimbing(int stance) {
        return stance == CharacterStance.LADDER_RIGHT_STANCE || stance == CharacterStance.LADDER_LEFT_STANCE
                || stance == CharacterStance.ROPE_RIGHT_STANCE || stance == CharacterStance.ROPE_LEFT_STANCE;
    }

    /** The player left the server: forget their track. */
    public static void forget(int playerId) {
        tracks.remove(playerId);
    }

    // ── learning (off the packet thread) ──────────────────────────────────────────────────────

    /**
     * Counts a live move, adopts it once it was seen often enough and the physics can repeat it, and records the
     * outcome. A map whose base graph is not in memory has it loaded from the disk cache first (never built); the
     * move is looked at once it is there.
     */
    static void consider(Transition t) {
        MapleMap map = maps.apply(t.mapId());
        if (map == null) {
            NavPlayerLearning.record(t, Outcome.NO_MAP, null, -1);
            return;
        }
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base());
        if (graph != null) {
            evaluateAndRecord(t, map, graph);
            return;
        }
        boolean first;
        List<Transition> queue;
        synchronized (waiting) {
            queue = waiting.get(t.mapId());
            first = queue == null;
            if (first) {
                queue = new ArrayList<>();
                waiting.put(t.mapId(), queue);
            }
            if (queue.size() < WAITING_CAP) {
                queue.add(t);
            } else {
                NavPlayerLearning.record(t, Outcome.NO_GRAPH, null, -1);
            }
        }
        if (first) {
            BotNavigationGraphProvider.loadCachedGraphAsync(map, BotMovementProfile.base())
                    .whenComplete((loaded, error) -> ExecutorServiceManager.runAsync(() -> drainWaiting(t.mapId(), map, loaded)));
        }
    }

    private static void drainWaiting(int mapId, MapleMap map, BotNavigationGraph graph) {
        List<Transition> queue;
        synchronized (waiting) {
            queue = waiting.remove(mapId);
        }
        if (queue == null) {
            return;
        }
        for (Transition t : queue) {
            if (graph == null) {
                NavPlayerLearning.record(t, Outcome.NO_GRAPH, null, -1);
            } else {
                evaluateAndRecord(t, map, graph);
            }
        }
    }

    private static void evaluateAndRecord(Transition t, MapleMap map, BotNavigationGraph graph) {
        Ends ends = ends(t, graph);
        Outcome outcome = evaluate(t, map, graph);
        NavPlayerLearning.record(t, outcome, ends, graph.version);
    }

    static boolean consider(Transition t, MapleMap map, BotNavigationGraph graph) {
        return evaluate(t, map, graph) == Outcome.ADOPTED;
    }

    /**
     * The two regions a move joins and the edge type it would be. {@code fhMismatch}: a reported foothold id was
     * not under the reported position (the client numbers that map's footholds differently), so the end was found
     * by position.
     */
    record Ends(int from, int to, BotNavigationGraph.EdgeType type, boolean fhMismatch) {
    }

    static Ends ends(Transition t, BotNavigationGraph graph) {
        boolean fromRope = t.fromFh() == 0;
        boolean toRope = t.toFh() == 0;
        int[] mismatch = new int[1];
        int from = fromRope ? ropeRegionNear(graph, t.takeoff()) : groundRegion(graph, t.fromFh(), t.takeoff(), mismatch);
        int to = toRope ? ropeRegionNear(graph, t.landing()) : groundRegion(graph, t.toFh(), t.landing(), mismatch);
        BotNavigationGraph.EdgeType type = switch (t.kind()) {
            case TELEPORT -> BotNavigationGraph.EdgeType.TELEPORT;
            case FLASH_JUMP -> BotNavigationGraph.EdgeType.FLASH_JUMP;
            case DOWN_JUMP -> BotNavigationGraph.EdgeType.DROP;
            case JUMP -> fromRope || toRope ? BotNavigationGraph.EdgeType.CLIMB : BotNavigationGraph.EdgeType.JUMP;
        };
        return new Ends(from, to, type, mismatch[0] > 0);
    }

    /** The ground region under a reported position: by foothold id when that foothold is there, else by position. */
    private static int groundRegion(BotNavigationGraph graph, int fh, Point p, int[] mismatch) {
        int byId = graph.regionIdByFootholdId.getOrDefault(fh, -1);
        BotNavigationGraph.Region region = byId >= 0 ? graph.getRegion(byId) : null;
        if (region != null && region.surfaceCoversPoint(p.x, p.y, FH_MATCH_PX)) {
            return byId;
        }
        int byPosition = groundRegionAt(graph, p, FH_MATCH_PX);
        if (byPosition >= 0) {
            mismatch[0]++;
        }
        return byPosition;
    }

    /** The rope region at a point, counting a few pixels past either end of the rope (climbing off it). */
    static int ropeRegionNear(BotNavigationGraph graph, Point p) {
        int exact = graph.findRopeRegionId(p);
        if (exact >= 0) {
            return exact;
        }
        for (BotNavigationGraph.Region region : graph.regions) {
            if (region.isRopeRegion && Math.abs(p.x - region.minX) <= BotPhysicsEngine.cfg.ROPE_GRAB_X
                    && p.y >= region.minY - ROPE_END_SLACK_PX && p.y <= region.maxY + ROPE_END_SLACK_PX) {
                return region.id;
            }
        }
        return -1;
    }

    /**
     * The sighting key: same graph version, map, type, regions and direction. Not the takeoff spot: two players rarely
     * take off within a few pixels of each other (the old 20 px spot almost never matched twice), and the proof
     * re-simulates the latest takeoff anyway.
     */
    static String sightingKey(Transition t, Ends e, int graphVersion) {
        int dir = Integer.signum(t.landing().x - t.takeoff().x);
        return "v" + graphVersion + ":" + t.mapId() + ":" + e.type() + ":" + e.from() + ">" + e.to() + ":" + dir;
    }

    /** Where a move stands against this graph, without counting it. */
    static Outcome classify(Transition t, BotNavigationGraph graph) {
        return classify(t, graph, profile -> null);
    }

    /**
     * Where a move stands against the base graph and, for a move it lacks, the other movement tiers' graphs at hand
     * ({@code tiers} returns null for one that is not): a move every tier's graph makes is known. Counts nothing
     * (also the offline replay's view).
     */
    static Outcome classify(Transition t, BotNavigationGraph graph,
                            java.util.function.Function<BotMovementProfile, BotNavigationGraph> tiers) {
        Outcome base = classifyBase(t, graph);
        if (base != Outcome.SIGHTED) {
            return base;
        }
        Ends e = ends(t, graph);
        for (int i = 1; i < PROOF_PROFILES.length; i++) {
            BotNavigationGraph tierGraph = tiers.apply(PROOF_PROFILES[i]);
            if (tierGraph == null || !ownJoins(tierGraph, e.from(), e.to(), e.type())) {
                return Outcome.SIGHTED;
            }
        }
        return Outcome.ALREADY_JOINED;
    }

    private static Outcome classifyBase(Transition t, BotNavigationGraph graph) {
        if (t.kind() != Kind.JUMP && (t.fromFh() == 0 || t.toFh() == 0)) {
            return Outcome.ROPE_SKILL; // teleports, flash jumps and down-jumps are learned between platforms
        }
        Ends e = ends(t, graph);
        if (e.from() < 0 || e.to() < 0) {
            return Outcome.UNKNOWN_REGION;
        }
        if (e.from() == e.to()) {
            return Outcome.SAME_REGION;
        }
        return joined(graph, e.from(), e.to(), e.type()) ? Outcome.ALREADY_JOINED : Outcome.SIGHTED;
    }

    static Outcome evaluate(Transition t, MapleMap map, BotNavigationGraph graph) {
        java.util.function.Function<BotMovementProfile, BotNavigationGraph> tiers =
                profile -> BotNavigationGraphProvider.peekGraph(map, profile);
        Outcome first = classify(t, graph, tiers);
        if (first != Outcome.SIGHTED) {
            return first;
        }
        Ends e = ends(t, graph);
        String key = sightingKey(t, e, graph.version);
        if (sight(key, System.currentTimeMillis()) < ADOPT_AFTER) {
            return Outcome.SIGHTED;
        }
        Proof proof = prove(map, graph, t, e);
        if (proof == null) {
            return Outcome.PROVE_FAILED;
        }
        proof = withoutKnown(proof, e, tiers);
        sightings.remove(key);
        if (proof == null) {
            return Outcome.ALREADY_JOINED; // only the bots fast enough for it make it, and their graphs already do
        }
        adopt(graph, proof);
        log.info("Bots learned a {} on map {} from players: region {} -> {} at ({},{}), for movement tiers {}",
                proof.edge().type, t.mapId(), e.from(), e.to(), proof.edge().startPoint.x, proof.edge().startPoint.y,
                tiers(proof.mask()));
        return Outcome.ADOPTED;
    }

    /** The proof minus the tiers whose own graph (when one is at hand) already makes the move; null when none is left. */
    static Proof withoutKnown(Proof proof, Ends e, java.util.function.Function<BotMovementProfile, BotNavigationGraph> graphs) {
        int mask = proof.mask();
        for (int i = 0; i < PROOF_PROFILES.length; i++) {
            if ((mask >> i & 1) == 0 || i == 0) {
                continue; // the base graph was checked by classify()
            }
            BotNavigationGraph tierGraph = graphs.apply(PROOF_PROFILES[i]);
            if (tierGraph != null && ownJoins(tierGraph, e.from(), e.to(), e.type())) {
                mask &= ~(1 << i);
            }
        }
        return mask == 0 ? null : new Proof(proof.edge(), proof.profile(), mask);
    }

    private static boolean ownJoins(BotNavigationGraph graph, int from, int to, BotNavigationGraph.EdgeType type) {
        for (BotNavigationGraph.Edge edge : NavDeepOverlay.outgoing(graph, from)) {
            if (joins(edge, to, type)) {
                return true;
            }
        }
        return false;
    }

    /** The speed/jump tiers of a mask, for logs. */
    static String tiers(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PROOF_PROFILES.length; i++) {
            if ((mask >> i & 1) == 1) {
                sb.append(sb.isEmpty() ? "" : ", ").append(PROOF_PROFILES[i].totalSpeedStat()).append('/')
                        .append(PROOF_PROFILES[i].totalJumpStat());
            }
        }
        return sb.toString();
    }

    private static int sight(String key, long now) {
        NavPlayerLearning.ensureSightingsLoaded();
        Sighting s = sightings.computeIfAbsent(key, k -> new Sighting());
        synchronized (s) {
            s.count++;
            s.last = now;
            return s.count;
        }
    }

    // ── proof ───────────────────────────────────────────────────────────────────────────────────

    /** A proven move: the edge of the slowest profile that makes it, and every proof profile that does (a bit mask). */
    record Proof(BotNavigationGraph.Edge edge, BotMovementProfile profile, int mask) {
    }

    /** Re-simulates the move with the bots' own physics at every proof profile; null when none ends where the player did. */
    static Proof prove(MapleMap map, BotNavigationGraph graph, Transition t, Ends e) {
        Point takeoff = onSurface(graph, e.from(), t.takeoff());
        int dir = Integer.signum(t.landing().x - t.takeoff().x);
        BotNavigationGraph.Edge first = null;
        BotMovementProfile firstProfile = null;
        int mask = 0;
        for (int i = 0; i < PROOF_PROFILES.length; i++) {
            BotNavigationGraph.Edge edge = proveWith(map, graph, PROOF_PROFILES[i], t.kind(), e.type(), e.from(), e.to(),
                    takeoff, t.landing(), dir);
            if (edge == null) {
                continue;
            }
            if (e.type() == BotNavigationGraph.EdgeType.TELEPORT || t.kind() == Kind.DOWN_JUMP) {
                return new Proof(edge, PROOF_PROFILES[i], ALL_TIERS); // neither depends on speed or jump
            }
            mask |= 1 << i;
            if (first == null) {
                first = edge;
                firstProfile = PROOF_PROFILES[i];
            }
        }
        return first == null ? null : new Proof(first, firstProfile, mask);
    }

    /** A ground takeoff put exactly on its platform's surface (the client rounds; the physics start from the surface). */
    private static Point onSurface(BotNavigationGraph graph, int regionId, Point p) {
        BotNavigationGraph.Region region = graph.getRegion(regionId);
        if (region == null || region.isRopeRegion || !region.surfaceCoversPoint(p.x, p.y, FH_MATCH_PX)) {
            return p;
        }
        return region.pointAt(p.x);
    }

    /** Re-simulates the move at one profile; the edge when it ends in the region the player reached. */
    static BotNavigationGraph.Edge proveWith(MapleMap map, BotNavigationGraph graph, BotMovementProfile profile, Kind kind,
                                             BotNavigationGraph.EdgeType type, int from, int to, Point takeoff,
                                             Point landing, int dir) {
        int step = BotPhysicsEngine.walkStep(map, profile);
        return switch (type) {
            case JUMP -> {
                BotNavigationGraph.Edge jump = proveJump(map, graph, profile, from, to, takeoff, dir, step);
                yield jump != null ? jump : proveWalkOff(map, graph, profile, from, to, takeoff, landing, dir, step);
            }
            case DROP -> kind == Kind.DOWN_JUMP ? proveDownJump(map, graph, from, to, takeoff) : null;
            case FLASH_JUMP -> {
                if (dir == 0) {
                    yield null;
                }
                // A plain jump that makes it is the better edge: every bot can use it.
                BotNavigationGraph.Edge plain = proveJump(map, graph, profile, from, to, takeoff, dir, step);
                if (plain != null && plain.launchStepX != 0) {
                    yield plain;
                }
                BotPhysicsEngine.JumpLanding fj = BotPhysicsEngine.simulateFlashJumpLanding(map, takeoff, dir * step, profile);
                yield landsIn(graph, from, to, fj) ? groundEdge(from, to, BotNavigationGraph.EdgeType.FLASH_JUMP,
                        takeoff, fj.point(), dir * step, Math.max(1, fj.timeMs())) : null;
            }
            case TELEPORT -> proveTeleport(map, graph, from, to, takeoff, landing);
            case CLIMB -> proveRopeMove(map, graph, profile, from, to, takeoff, dir, step);
            default -> null;
        };
    }

    /** The first launch of {@code dir} then straight up that lands in {@code to}. */
    private static BotNavigationGraph.Edge proveJump(MapleMap map, BotNavigationGraph graph, BotMovementProfile profile,
                                                     int from, int to, Point takeoff, int dir, int step) {
        for (int stepX : dir == 0 ? new int[]{0} : new int[]{dir * step, 0}) {
            BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(map, takeoff, stepX, profile);
            if (landsIn(graph, from, to, landing)) {
                return groundEdge(from, to, BotNavigationGraph.EdgeType.JUMP, takeoff, landing.point(), stepX,
                        Math.max(1, landing.timeMs()));
            }
        }
        return null;
    }

    /**
     * A walk off the platform's end: the client reports a walk-off exactly like a jump (ground, air, other ground),
     * so a takeoff at the region's end in the direction of travel, landing lower, is also tried as the graph's own
     * directional drop - the same runway, fall and caps as BotNavigationGraphProvider.addDirectionalDropEdge.
     */
    private static BotNavigationGraph.Edge proveWalkOff(MapleMap map, BotNavigationGraph graph, BotMovementProfile profile,
                                                        int from, int to, Point takeoff, Point landing, int dir, int step) {
        BotNavigationGraph.Region source = graph.getRegion(from);
        if (dir == 0 || source == null || source.isRopeRegion || landing.y <= takeoff.y) {
            return null;
        }
        Point end = dir < 0 ? source.leftPoint() : source.rightPoint();
        if (Math.abs(takeoff.x - end.x) > WALK_OFF_EDGE_PX || source.isForbidFallDownAt(end.x)) {
            return null;
        }
        int runway = BotPhysicsEngine.launchRunwayPx(map, profile);
        int launchX = dir < 0 ? Math.min(source.maxX, end.x + runway) : Math.max(source.minX, end.x - runway);
        int actualRunway = Math.abs(launchX - end.x);
        if (actualRunway < Math.min(runway, 20)) {
            return null; // too short to reach walking speed: the graph makes no walk-off here either
        }
        Point start = source.pointAt(launchX);
        if (BotPhysicsEngine.isGroundRunwayBlockedByWall(map, start, end)) {
            return null;
        }
        int stepX = dir * step;
        BotPhysicsEngine.JumpLanding fall = BotPhysicsEngine.simulateFallLanding(map, end, stepX);
        if (!landsIn(graph, from, to, fall) || fall.point().y <= end.y + 4
                || fall.point().y - end.y > BotNavigationGraphProvider.MAX_DROP_PX) {
            return null;
        }
        int cost = Math.max(1, fall.timeMs()) + actualRunway * BotPhysicsEngine.cfg.TICK_MS / Math.max(1, step);
        return groundEdge(from, to, BotNavigationGraph.EdgeType.DROP, start, fall.point(), stepX, cost);
    }

    /** A down-jump through the platform, as the graph's own (straight down, capped and cost-penalised alike). */
    private static BotNavigationGraph.Edge proveDownJump(MapleMap map, BotNavigationGraph graph, int from, int to,
                                                         Point takeoff) {
        BotPhysicsEngine.JumpLanding fall = BotPhysicsEngine.simulateDownJumpLanding(map, takeoff);
        if (fall == null || fall.foothold() == null
                || graph.regionIdByFootholdId.getOrDefault(fall.foothold().getId(), -1) != to
                || fall.point().y - takeoff.y > BotNavigationGraphProvider.DOWN_JUMP_MAX_DROP_PX) {
            return null;
        }
        return groundEdge(from, to, BotNavigationGraph.EdgeType.DROP, takeoff, fall.point(), 0,
                Math.max(1, fall.timeMs()) + BotNavigationGraphProvider.DOWN_JUMP_COST_PENALTY_MS);
    }

    /** A landing in {@code to} that really changes region (graph v67's phantom guard: not on the source's own ground). */
    private static boolean landsIn(BotNavigationGraph graph, int from, int to, BotPhysicsEngine.JumpLanding landing) {
        if (landing == null || landing.foothold() == null
                || graph.regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1) != to) {
            return false;
        }
        BotNavigationGraph.Region source = graph.getRegion(from);
        return source == null || !BotNavigationGraphProvider.isPhantomCrossRegionLanding(source, landing.point());
    }

    /** The bots' teleport (the graph's own landing rule) from the takeoff, in the direction the player blinked. */
    private static BotNavigationGraph.Edge proveTeleport(MapleMap map, BotNavigationGraph graph, int from, int to,
                                                         Point takeoff, Point landing) {
        int dx = landing.x - takeoff.x;
        int dy = landing.y - takeoff.y;
        int dirX = Math.abs(dx) >= Math.abs(dy) ? Integer.signum(dx) : 0;
        int dirY = dirX == 0 ? Integer.signum(dy) : 0;
        if (dirX == 0 && dirY == 0) {
            return null;
        }
        Point dest = BotPhysicsEngine.teleportLanding(map, takeoff, dirX, dirY,
                BotNavigationGraphProvider.TELEPORT_RANGE_PX, BotNavigationGraphProvider.TELEPORT_Y_SNAP_PX);
        if (dest == null || dest.equals(takeoff)) {
            return null;
        }
        Foothold below = BotPhysicsEngine.findBelowIndexed(map, new Point(dest.x, dest.y - 1));
        if (below == null || graph.regionIdByFootholdId.getOrDefault(below.getId(), -1) != to) {
            return null;
        }
        return groundEdge(from, to, BotNavigationGraph.EdgeType.TELEPORT, takeoff, dest, 0,
                BotNavigationGraphProvider.TELEPORT_COST_MS);
    }

    /** A jump-grab onto a rope, a jump off one, or a rope-to-rope transfer, shaped like the graph's own. */
    private static BotNavigationGraph.Edge proveRopeMove(MapleMap map, BotNavigationGraph graph, BotMovementProfile profile,
                                                         int from, int to, Point takeoff, int dir, int step) {
        BotNavigationGraph.Region source = graph.getRegion(from);
        BotNavigationGraph.Region target = graph.getRegion(to);
        if (source == null || target == null) {
            return null;
        }
        int[] steps = dir == 0 ? new int[]{0} : new int[]{dir * step, 0};
        if (!source.isRopeRegion) { // jump-grab from the ground
            Rope rope = BotNavigationGraphProvider.findRopeFromRegion(map, target);
            if (rope == null) {
                return null;
            }
            for (int stepX : steps) {
                Point grab = BotPhysicsEngine.simulateGroundJumpRopeGrab(map, takeoff, stepX, rope, profile);
                if (grab != null) {
                    int cost = BotPhysicsEngine.estimateGroundJumpRopeGrabTimeMs(map, takeoff, stepX, rope, profile);
                    return groundEdge(from, to, BotNavigationGraph.EdgeType.CLIMB, takeoff, grab, stepX, Math.max(1, cost));
                }
            }
            return null;
        }
        Rope rope = BotNavigationGraphProvider.findRopeFromRegion(map, source);
        if (rope == null) {
            return null;
        }
        int y = Math.clamp(takeoff.y, BotPhysicsEngine.firstClimbableY(rope), rope.bottomY());
        Point ropePoint = new Point(rope.x(), y);
        if (target.isRopeRegion) { // rope to rope
            Rope targetRope = BotNavigationGraphProvider.findRopeFromRegion(map, target);
            if (targetRope == null || targetRope.x() == rope.x()) {
                return null;
            }
            int launchDir = Integer.signum(targetRope.x() - rope.x()) * step;
            Point grab = BotPhysicsEngine.simulateRopeJumpGrab(map, ropePoint, launchDir, targetRope, profile);
            if (grab == null) {
                return null;
            }
            int cost = BotPhysicsEngine.estimateRopeJumpGrabTimeMs(map, ropePoint, launchDir, targetRope, profile);
            return ropeEdge(from, to, ropePoint, grab, launchDir, Math.max(1, cost));
        }
        for (int stepX : steps) { // jump off the rope onto a platform
            BotPhysicsEngine.JumpLanding off = BotPhysicsEngine.simulateRopeJumpLanding(map, ropePoint, stepX, profile);
            if (off != null && off.foothold() != null
                    && graph.regionIdByFootholdId.getOrDefault(off.foothold().getId(), -1) == to) {
                return ropeEdge(from, to, ropePoint, off.point(), stepX, Math.max(1, off.timeMs()));
            }
        }
        return null;
    }

    private static BotNavigationGraph.Edge groundEdge(int from, int to, BotNavigationGraph.EdgeType type, Point start,
                                                      Point end, int stepX, int cost) {
        return new BotNavigationGraph.Edge(from, to, type, start, end, start.x, start.x, start.y, start.y, stepX,
                0, 0, 0, 0, cost, cost, cost);
    }

    /** A rope launch from one climb height (a one-height Y window). */
    private static BotNavigationGraph.Edge ropeEdge(int from, int to, Point ropePoint, Point end, int stepX, int cost) {
        return new BotNavigationGraph.Edge(from, to, BotNavigationGraph.EdgeType.CLIMB, ropePoint, end,
                ropePoint.x, ropePoint.x, ropePoint.y, ropePoint.y, stepX, 0, 0, 0, 0, cost, cost, cost);
    }

    /**
     * Whether the map already has this move: any plain edge between the two regions, or a skill edge of the same
     * kind (a teleport is no reason not to learn a flash jump, but a walker's route makes both redundant). A move
     * learned at any speed counts.
     */
    private static boolean joined(BotNavigationGraph graph, int from, int to, BotNavigationGraph.EdgeType type) {
        for (BotNavigationGraph.Edge edge : NavDeepOverlay.outgoing(graph, from)) {
            if (joins(edge, to, type)) {
                return true;
            }
        }
        for (Learned learned : overlay(graph)) {
            if (learned.edge().fromRegionId == from && joins(learned.edge(), to, type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean joins(BotNavigationGraph.Edge edge, int to, BotNavigationGraph.EdgeType type) {
        return edge.toRegionId == to && (!edge.isMovementSkill() || edge.type == type);
    }

    private static void adopt(BotNavigationGraph graph, Proof proof) {
        Overlay overlay = overlays.compute(graph.mapId, (id, old) ->
                old != null && old.version() == graph.version ? old : new Overlay(graph.version, new CopyOnWriteArrayList<>()));
        overlay.edges().add(new Learned(proof.edge(), proof.mask()));
        save(graph.mapId, graph, overlay);
    }

    // ── the planner's view ────────────────────────────────────────────────────────────────────

    /**
     * A region's outgoing edges: the graph's own, plus the nightly deep bake's ({@link NavDeepOverlay}) and moves
     * learned from players that were proven at this graph's movement tier and that it does not make itself. No
     * allocation when neither added anything on the map (nearly always).
     */
    static List<BotNavigationGraph.Edge> outgoing(BotNavigationGraph graph, int regionId) {
        List<BotNavigationGraph.Edge> own = NavDeepOverlay.outgoing(graph, regionId);
        List<Learned> learned = overlay(graph);
        if (learned.isEmpty()) {
            return own;
        }
        List<BotNavigationGraph.Edge> all = null;
        for (Learned l : learned) {
            if (l.edge().fromRegionId == regionId && l.appliesTo(graph) && !joinedBy(own, l.edge())) {
                if (all == null) {
                    all = new ArrayList<>(own);
                }
                all.add(l.edge());
            }
        }
        return all == null ? own : all;
    }

    private static boolean joinedBy(List<BotNavigationGraph.Edge> own, BotNavigationGraph.Edge learned) {
        for (BotNavigationGraph.Edge edge : own) {
            if (joins(edge, learned.toRegionId, learned.type)) {
                return true;
            }
        }
        return false;
    }

    private static List<Learned> overlay(BotNavigationGraph graph) {
        Overlay overlay = overlays.get(graph.mapId);
        if (overlay == null || overlay.version() < graph.version) {
            overlay = overlays.compute(graph.mapId, (id, old) ->
                    old != null && old.version() >= graph.version ? old : load(id, graph));
        }
        return overlay.version() == graph.version ? overlay.edges() : List.of();
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    /**
     * One stored edge: [type, from, to, stepX, startX, startY, endX, endY, cost, fromRope, toRope, tiers] (tiers: the
     * {@link #PROOF_PROFILES} bit mask it applies to).
     */
    private static void save(int mapId, BotNavigationGraph graph, Overlay overlay) {
        if (readOnly) {
            return;
        }
        List<Object> edges = new ArrayList<>();
        for (Learned l : overlay.edges()) {
            BotNavigationGraph.Edge e = l.edge();
            edges.add(List.of(e.type.name(), e.fromRegionId, e.toRegionId, e.launchStepX, e.startPoint.x,
                    e.startPoint.y, e.endPoint.x, e.endPoint.y, e.cost, isRope(graph, e.fromRegionId) ? 1 : 0,
                    isRope(graph, e.toRegionId) ? 1 : 0, l.mask()));
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", overlay.version());
        json.put("tiers", TIERS_FORMAT);
        json.put("edges", edges);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(mapId + ".json");
            Path tmp = dir.resolve(mapId + ".json.tmp");
            Files.writeString(tmp, Json.write(json), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.debug("Could not save learned moves of map {}", mapId, e);
        }
    }

    private static boolean isRope(BotNavigationGraph graph, int regionId) {
        BotNavigationGraph.Region region = graph.getRegion(regionId);
        return region != null && region.isRopeRegion;
    }

    /** A stored edge, with which of its ends are ropes (so a rebuilt graph can find them again). */
    private record Stored(Learned learned, boolean fromRope, boolean toRope) {
    }

    private static Overlay load(int mapId, BotNavigationGraph graph) {
        Path file = dir.resolve(mapId + ".json");
        List<Learned> edges = new CopyOnWriteArrayList<>();
        if (!Files.exists(file)) {
            return new Overlay(graph.version, edges);
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                    && root.get("version") instanceof Number v && root.get("edges") instanceof List<?> list) {
                boolean sameGraph = v.intValue() == graph.version;
                if (!sameGraph && v.intValue() > graph.version) {
                    return new Overlay(graph.version, edges); // written by a newer build: leave the file alone
                }
                boolean legacy = !(root.get("tiers") instanceof Number t && t.intValue() >= TIERS_FORMAT);
                for (Object o : list) {
                    Stored stored = o instanceof List<?> f ? parse(f) : null;
                    if (stored != null && legacy) {
                        int mask = fromLegacy(stored.learned().mask());
                        stored = mask == 0 ? null : new Stored(new Learned(stored.learned().edge(), mask),
                                stored.fromRope(), stored.toRope());
                    }
                    Learned learned = stored == null ? null : sameGraph ? stored.learned() : remap(graph, stored);
                    if (learned != null) {
                        edges.add(learned);
                    }
                }
                if (legacy && sameGraph) {
                    log.info("Moves learned from players on map {} moved to the 1.5.0 speed tiers: {} of {}", mapId,
                            edges.size(), list.size());
                    save(mapId, graph, new Overlay(graph.version, edges));
                }
                if (!sameGraph) {
                    log.info("Moves learned from players on map {} carried from graph v{} to v{}: {} of {}", mapId,
                            v.intValue(), graph.version, edges.size(), list.size());
                    save(mapId, graph, new Overlay(graph.version, edges));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring unreadable learned moves {}", file, e);
        }
        return new Overlay(graph.version, edges);
    }

    /** The learned moves stored for a graph's map that apply to that graph (the dashboard export's view). */
    static List<BotNavigationGraph.Edge> storedEdges(Path file, BotNavigationGraph graph) {
        List<BotNavigationGraph.Edge> out = new ArrayList<>();
        try {
            if (Files.exists(file) && Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                    && root.get("version") instanceof Number v && v.intValue() == graph.version
                    && root.get("edges") instanceof List<?> list) {
                boolean legacy = !(root.get("tiers") instanceof Number t && t.intValue() >= TIERS_FORMAT);
                for (Object o : list) {
                    Stored stored = o instanceof List<?> f ? parse(f) : null;
                    if (stored != null && legacy) {
                        stored = new Stored(new Learned(stored.learned().edge(), fromLegacy(stored.learned().mask())),
                                stored.fromRope(), stored.toRope());
                    }
                    if (stored != null && stored.learned().appliesTo(graph)) {
                        out.add(stored.learned().edge());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: none
        }
        return out;
    }

    /**
     * A stored edge: 12 fields, 11 (proven at the base profile only, applied to every tier as before), or the 8
     * numbers of a plain jump from before ticket 04.
     */
    private static Stored parse(List<?> f) {
        if (f.size() == 8) {
            int[] n = f.stream().mapToInt(x -> ((Number) x).intValue()).toArray();
            return new Stored(new Learned(groundEdge(n[0], n[1], BotNavigationGraph.EdgeType.JUMP, new Point(n[3], n[4]),
                    new Point(n[5], n[6]), n[2], n[7]), ALL_TIERS), false, false);
        }
        if (f.size() != 11 && f.size() != 12) {
            return null;
        }
        BotNavigationGraph.EdgeType type = BotNavigationGraph.EdgeType.valueOf(String.valueOf(f.get(0)));
        int[] n = f.subList(1, f.size()).stream().mapToInt(x -> ((Number) x).intValue()).toArray();
        Point start = new Point(n[3], n[4]);
        Point end = new Point(n[5], n[6]);
        boolean fromRope = n[8] == 1;
        BotNavigationGraph.Edge edge = fromRope ? ropeEdge(n[0], n[1], start, end, n[2], n[7])
                : groundEdge(n[0], n[1], type, start, end, n[2], n[7]);
        int mask = f.size() == 12 ? n[10] & ALL_TIERS : ALL_TIERS;
        return new Stored(new Learned(edge, mask), fromRope, n[9] == 1);
    }

    /**
     * A stored edge on a rebuilt graph: its regions found again from where it starts and ends, or null when either
     * end no longer lies on a platform or rope, or the new graph makes the move itself.
     */
    private static Learned remap(BotNavigationGraph graph, Stored stored) {
        BotNavigationGraph.Edge e = stored.learned().edge();
        int from = stored.fromRope() ? graph.findRopeRegionId(e.startPoint) : groundRegionAt(graph, e.startPoint, REMAP_Y_PX);
        int to = stored.toRope() ? graph.findRopeRegionId(e.endPoint) : groundRegionAt(graph, e.endPoint, REMAP_Y_PX);
        if (from < 0 || to < 0 || from == to) {
            return null;
        }
        for (BotNavigationGraph.Edge own : graph.getOutgoing(from)) {
            if (joins(own, to, e.type)) {
                return null;
            }
        }
        BotNavigationGraph.Edge edge = stored.fromRope() ? ropeEdge(from, to, e.startPoint, e.endPoint, e.launchStepX, e.cost)
                : groundEdge(from, to, e.type, e.startPoint, e.endPoint, e.launchStepX, e.cost);
        return new Learned(edge, stored.learned().mask());
    }

    private static int groundRegionAt(BotNavigationGraph graph, Point p, int yTolerance) {
        for (BotNavigationGraph.Region region : graph.regions) {
            if (!region.isRopeRegion && region.surfaceCoversPoint(p.x, p.y, yTolerance)) {
                return region.id;
            }
        }
        return -1;
    }

    // ── sightings (kept across restarts by NavPlayerLearning) ──────────────────────────────────

    /** A copy of the sightings: key -> [count, last seen ms]. */
    static Map<String, long[]> sightingsSnapshot() {
        Map<String, long[]> out = new LinkedHashMap<>();
        sightings.forEach((k, s) -> {
            synchronized (s) {
                out.put(k, new long[]{s.count, s.last});
            }
        });
        return out;
    }

    /** Restores stored sightings (adding to any counted since start). */
    static void restoreSightings(Map<String, long[]> stored) {
        stored.forEach((k, v) -> {
            Sighting s = sightings.computeIfAbsent(k, x -> new Sighting());
            synchronized (s) {
                s.count += (int) v[0];
                s.last = Math.max(s.last, v[1]);
            }
        });
    }

    static int sightingCount() {
        return sightings.size();
    }

    /** For the GM read-out. */
    public static String describe() {
        Map<BotNavigationGraph.EdgeType, Integer> byType = new EnumMap<>(BotNavigationGraph.EdgeType.class);
        long mapCount = 0;
        for (Overlay overlay : overlays.values()) {
            for (Learned learned : overlay.edges()) {
                byType.merge(learned.edge().type, 1, Integer::sum);
            }
            if (!overlay.edges().isEmpty()) {
                mapCount++;
            }
        }
        return "learned from players: " + byType.getOrDefault(BotNavigationGraph.EdgeType.JUMP, 0) + " jumps, "
                + byType.getOrDefault(BotNavigationGraph.EdgeType.DROP, 0) + " drops, "
                + byType.getOrDefault(BotNavigationGraph.EdgeType.FLASH_JUMP, 0) + " flash jumps, "
                + byType.getOrDefault(BotNavigationGraph.EdgeType.TELEPORT, 0) + " teleports, "
                + byType.getOrDefault(BotNavigationGraph.EdgeType.CLIMB, 0) + " rope moves on " + mapCount + " maps, "
                + sightings.size() + " new moves being counted";
    }

    /** Test seam: a scratch directory and map lookup (nulls restore the real ones), and a clean slate. */
    static void useForTest(Path testDir, IntFunction<MapleMap> testMaps) {
        dir = testDir == null ? DIR : testDir;
        maps = testMaps == null ? soloMapling.server.SoloMaplingUtilities::getMapleMapById : testMaps;
        tracks.clear();
        sightings.clear();
        overlays.clear();
        waiting.clear();
        sink = null;
        readOnly = false;
        NavPlayerLearning.useDirForTest(testDir == null ? null : testDir.resolve("players"));
    }
}
