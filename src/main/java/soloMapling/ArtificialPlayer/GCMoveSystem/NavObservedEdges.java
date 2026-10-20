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
 * an edge - but a player finds it. Every move packet of a real player carries a foothold id (0 while airborne or
 * climbing) and a stance (rope and ladder stances while climbing); the fragments between them say how the player
 * moved (a teleport or a flash jump). So:
 * <ul>
 *   <li>ground on foothold A, a stretch in the air, ground on foothold B is a <b>jump</b>;</li>
 *   <li>the same with a flash-jump fragment in the air is a <b>flash jump</b>, with a teleport fragment a
 *       <b>teleport</b> (counted only from a player whose job has the skill);</li>
 *   <li>a rope at one end or both (climbing stance) is a <b>rope move</b>: a jump-grab, a jump off, or a
 *       rope-to-rope transfer.</li>
 * </ul>
 * When the graph has no such move between the two regions and players made it at least {@link #ADOPT_AFTER} times
 * from about the same spot, it is re-simulated with the bots' own physics and skills from the player's takeoff
 * point; if it lands in the same region, it is proven possible for a bot and joins the map as an ordinary edge of
 * its type (JUMP, FLASH_JUMP, TELEPORT or CLIMB) - kept in an overlay next to the cached graph, never written into
 * it, so planners reading the graph concurrently are untouched. Skill edges are planned only for bots whose job
 * carries the skill, like the graph's own. A flash jump a plain jump could also make is adopted as a plain jump, for
 * everyone. Lag, knockback and anything the physics cannot reproduce fail the re-simulation and are dropped.
 *
 * <p>The overlay is stamped with the graph version. A newer graph keeps each learned move whose two ends still lie
 * on a platform (or rope) of the new graph and that the new graph does not already make itself.
 */
public final class NavObservedEdges {

    private static final Logger log = LoggerFactory.getLogger(NavObservedEdges.class);

    static final int ADOPT_AFTER = 2;
    /** Takeoff points within this many pixels count as the same spot. */
    static final int SPOT_PX = 20;
    static final Path DIR = Path.of(System.getProperty("bot.nav.cacheDir", "cache/bot-nav"), "observed");
    /** How far off a platform's surface a stored point may sit and still be on it in a rebuilt graph. */
    static final int REMAP_Y_PX = 4;

    /** How the player got from one foothold or rope to the other. */
    public enum Kind {
        JUMP,
        FLASH_JUMP,
        TELEPORT
    }

    /** One player's move, as the packets showed it. A foothold id of 0 is a rope or ladder. */
    record Transition(int mapId, Kind kind, int fromFh, Point takeoff, int toFh, Point landing) {
        Transition(int mapId, int fromFh, Point takeoff, int toFh, Point landing) {
            this(mapId, Kind.JUMP, fromFh, takeoff, toFh, landing);
        }
    }

    private static final class Track {
        int mapId = -1;
        boolean anchored;
        /** Where the player last stood: a foothold id, or 0 on a rope. */
        int anchorFh;
        Point anchorPoint;
        boolean airborne;
        Kind skill;
    }

    /** Adopted edges of one map, for the graph version they were proven on. */
    private record Overlay(int version, List<BotNavigationGraph.Edge> edges) {
    }

    private static final Map<Integer, Track> tracks = new ConcurrentHashMap<>();
    private static final Map<String, Integer> sightings = new ConcurrentHashMap<>();
    private static final Map<Integer, Overlay> overlays = new ConcurrentHashMap<>();
    private static volatile Path dir = DIR;
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

    /** One absolute movement element of a real player. {@code fh} is 0 while airborne or climbing. */
    public static void onPlayerMove(Character player, int x, int y, int fh, int stance) {
        if (player == null || player.getMap() == null) {
            return;
        }
        if (player.getMap().isSwim()) {
            SwimNavObserved.onPlayerMove(player, x, y, fh, stance); // ticket 34: players' swims teach the swim graph
        }
        boolean climbing = isClimbing(stance);
        Track track = tracks.computeIfAbsent(player.getId(), k -> new Track());
        int mapId = player.getMapId();
        Transition moved = null;
        synchronized (track) {
            if (track.mapId != mapId) {
                track.mapId = mapId;
                track.anchored = false;
                track.airborne = false;
                track.skill = null;
            }
            if (fh == 0 && !climbing) {
                track.airborne = track.anchored;
                return;
            }
            int here = climbing ? 0 : fh;
            if (track.airborne && track.anchored && (here != track.anchorFh
                    || here == 0 && Math.abs(x - track.anchorPoint.x) > SPOT_PX)) { // rope to another rope
                Kind kind = track.skill != null ? track.skill : Kind.JUMP;
                moved = new Transition(mapId, kind, track.anchorFh, track.anchorPoint, here, new Point(x, y));
            }
            track.anchored = true;
            track.anchorFh = here;
            track.anchorPoint = new Point(x, y);
            track.airborne = false;
            track.skill = null;
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
        if (player == null || player.getJob() == null || kind == Kind.JUMP) {
            return;
        }
        int job = player.getJob().getId();
        if (kind == Kind.TELEPORT ? !NavMovementSkills.isTeleportJob(job) : !NavMovementSkills.isFlashJumpJob(job)) {
            return;
        }
        Track track = tracks.get(player.getId());
        if (track == null) {
            return;
        }
        synchronized (track) {
            if (track.mapId == player.getMapId() && track.anchored && track.anchorFh != 0) {
                track.skill = kind;
                track.airborne = true; // a teleport leaves the foothold without a fall in between
            }
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

    /** Counts a move, and adopts it once it was seen often enough and the physics can repeat it. */
    static boolean consider(Transition t) {
        MapleMap map = maps.apply(t.mapId());
        if (map == null) {
            return false;
        }
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base());
        return graph != null && consider(t, map, graph);
    }

    static boolean consider(Transition t, MapleMap map, BotNavigationGraph graph) {
        boolean fromRope = t.fromFh() == 0;
        boolean toRope = t.toFh() == 0;
        if (t.kind() != Kind.JUMP && (fromRope || toRope)) {
            return false; // teleports and flash jumps are learned between platforms
        }
        int from = fromRope ? graph.findRopeRegionId(t.takeoff()) : graph.regionIdByFootholdId.getOrDefault(t.fromFh(), -1);
        int to = toRope ? graph.findRopeRegionId(t.landing()) : graph.regionIdByFootholdId.getOrDefault(t.toFh(), -1);
        BotNavigationGraph.EdgeType type = switch (t.kind()) {
            case TELEPORT -> BotNavigationGraph.EdgeType.TELEPORT;
            case FLASH_JUMP -> BotNavigationGraph.EdgeType.FLASH_JUMP;
            case JUMP -> fromRope || toRope ? BotNavigationGraph.EdgeType.CLIMB : BotNavigationGraph.EdgeType.JUMP;
        };
        if (from < 0 || to < 0 || from == to || joined(graph, from, to, type)) {
            return false;
        }
        int dir = Integer.signum(t.landing().x - t.takeoff().x);
        int spot = Math.floorDiv(fromRope ? t.takeoff().y : t.takeoff().x, SPOT_PX);
        String key = t.mapId() + ":" + type + ":" + from + ">" + to + ":" + dir + ":" + spot;
        if (sightings.merge(key, 1, Integer::sum) < ADOPT_AFTER) {
            return false;
        }
        BotNavigationGraph.Edge proven = prove(map, graph, type, from, to, t.takeoff(), t.landing(), dir);
        if (proven == null) {
            return false;
        }
        adopt(graph, proven);
        sightings.remove(key);
        log.info("Bots learned a {} on map {} from players: region {} -> {} at ({},{})", proven.type, t.mapId(),
                from, to, t.takeoff().x, t.takeoff().y);
        return true;
    }

    /** Re-simulates the move with the bots' own physics; the edge when it ends in the region the player reached. */
    static BotNavigationGraph.Edge prove(MapleMap map, BotNavigationGraph graph, BotNavigationGraph.EdgeType type,
                                         int from, int to, Point takeoff, Point landing, int dir) {
        BotMovementProfile profile = graph.movementProfile;
        int step = BotPhysicsEngine.walkStep(map, profile);
        return switch (type) {
            case JUMP -> proveJump(map, graph, from, to, takeoff, dir, step);
            case FLASH_JUMP -> {
                if (dir == 0) {
                    yield null;
                }
                // A plain jump that makes it is the better edge: every bot can use it.
                BotNavigationGraph.Edge plain = proveJump(map, graph, from, to, takeoff, dir, step);
                if (plain != null && plain.launchStepX != 0) {
                    yield plain;
                }
                BotPhysicsEngine.JumpLanding fj = BotPhysicsEngine.simulateFlashJumpLanding(map, takeoff, dir * step, profile);
                yield landsIn(graph, from, to, fj) ? groundEdge(from, to, BotNavigationGraph.EdgeType.FLASH_JUMP,
                        takeoff, fj.point(), dir * step, Math.max(1, fj.timeMs())) : null;
            }
            case TELEPORT -> proveTeleport(map, graph, from, to, takeoff, landing);
            case CLIMB -> proveRopeMove(map, graph, from, to, takeoff, dir, step);
            default -> null;
        };
    }

    /** The first launch of {@code dir} then straight up that lands in {@code to}. */
    private static BotNavigationGraph.Edge proveJump(MapleMap map, BotNavigationGraph graph, int from, int to,
                                                     Point takeoff, int dir, int step) {
        for (int stepX : dir == 0 ? new int[]{0} : new int[]{dir * step, 0}) {
            BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(map, takeoff, stepX,
                    graph.movementProfile);
            if (landsIn(graph, from, to, landing)) {
                return groundEdge(from, to, BotNavigationGraph.EdgeType.JUMP, takeoff, landing.point(), stepX,
                        Math.max(1, landing.timeMs()));
            }
        }
        return null;
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
    private static BotNavigationGraph.Edge proveRopeMove(MapleMap map, BotNavigationGraph graph, int from, int to,
                                                         Point takeoff, int dir, int step) {
        BotNavigationGraph.Region source = graph.getRegion(from);
        BotNavigationGraph.Region target = graph.getRegion(to);
        if (source == null || target == null) {
            return null;
        }
        BotMovementProfile profile = graph.movementProfile;
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
     * kind (a teleport is no reason not to learn a flash jump, but a walker's route makes both redundant).
     */
    private static boolean joined(BotNavigationGraph graph, int from, int to, BotNavigationGraph.EdgeType type) {
        for (BotNavigationGraph.Edge edge : outgoing(graph, from)) {
            if (joins(edge, to, type)) {
                return true;
            }
        }
        for (BotNavigationGraph.Edge edge : overlay(graph)) {
            if (edge.fromRegionId == from && joins(edge, to, type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean joins(BotNavigationGraph.Edge edge, int to, BotNavigationGraph.EdgeType type) {
        return edge.toRegionId == to && (!edge.isMovementSkill() || edge.type == type);
    }

    private static void adopt(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        Overlay overlay = overlays.compute(graph.mapId, (id, old) ->
                old != null && old.version() == graph.version ? old : new Overlay(graph.version, new CopyOnWriteArrayList<>()));
        overlay.edges().add(edge);
        save(graph.mapId, graph, overlay);
    }

    // ── the planner's view ────────────────────────────────────────────────────────────────────

    /**
     * A region's outgoing edges: the graph's own, plus the nightly deep bake's ({@link NavDeepOverlay}) and moves
     * learned from players. No allocation when neither added anything on the map (nearly always).
     */
    static List<BotNavigationGraph.Edge> outgoing(BotNavigationGraph graph, int regionId) {
        List<BotNavigationGraph.Edge> own = NavDeepOverlay.outgoing(graph, regionId);
        List<BotNavigationGraph.Edge> learned = overlay(graph);
        if (learned.isEmpty() || !appliesTo(graph)) {
            return own;
        }
        List<BotNavigationGraph.Edge> all = null;
        for (BotNavigationGraph.Edge edge : learned) {
            if (edge.fromRegionId == regionId) {
                if (all == null) {
                    all = new ArrayList<>(own);
                }
                all.add(edge);
            }
        }
        return all == null ? own : all;
    }

    /** Learned moves were proven with the base profile: a bot at least that fast and springy can make them. */
    private static boolean appliesTo(BotNavigationGraph graph) {
        BotMovementProfile base = BotMovementProfile.base();
        return graph.movementProfile.totalSpeedStat() >= base.totalSpeedStat()
                && graph.movementProfile.totalJumpStat() >= base.totalJumpStat();
    }

    private static List<BotNavigationGraph.Edge> overlay(BotNavigationGraph graph) {
        Overlay overlay = overlays.get(graph.mapId);
        if (overlay == null || overlay.version() < graph.version) {
            overlay = overlays.compute(graph.mapId, (id, old) ->
                    old != null && old.version() >= graph.version ? old : load(id, graph));
        }
        return overlay.version() == graph.version ? overlay.edges() : List.of();
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    /** One stored edge: [type, from, to, stepX, startX, startY, endX, endY, cost, fromRope, toRope]. */
    private static void save(int mapId, BotNavigationGraph graph, Overlay overlay) {
        List<Object> edges = new ArrayList<>();
        for (BotNavigationGraph.Edge e : overlay.edges()) {
            edges.add(List.of(e.type.name(), e.fromRegionId, e.toRegionId, e.launchStepX, e.startPoint.x,
                    e.startPoint.y, e.endPoint.x, e.endPoint.y, e.cost, isRope(graph, e.fromRegionId) ? 1 : 0,
                    isRope(graph, e.toRegionId) ? 1 : 0));
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", overlay.version());
        json.put("edges", edges);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(mapId + ".json"), Json.write(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not save learned moves of map {}", mapId, e);
        }
    }

    private static boolean isRope(BotNavigationGraph graph, int regionId) {
        BotNavigationGraph.Region region = graph.getRegion(regionId);
        return region != null && region.isRopeRegion;
    }

    /** A stored edge, with which of its ends are ropes (so a rebuilt graph can find them again). */
    private record Stored(BotNavigationGraph.Edge edge, boolean fromRope, boolean toRope) {
    }

    private static Overlay load(int mapId, BotNavigationGraph graph) {
        Path file = dir.resolve(mapId + ".json");
        List<BotNavigationGraph.Edge> edges = new CopyOnWriteArrayList<>();
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
                for (Object o : list) {
                    Stored stored = o instanceof List<?> f ? parse(f) : null;
                    BotNavigationGraph.Edge edge = stored == null ? null : sameGraph ? stored.edge() : remap(graph, stored);
                    if (edge != null) {
                        edges.add(edge);
                    }
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

    /** A stored edge: 11 fields, or the 8 numbers of a plain jump from before ticket 04. */
    private static Stored parse(List<?> f) {
        if (f.size() == 8) {
            int[] n = f.stream().mapToInt(x -> ((Number) x).intValue()).toArray();
            return new Stored(groundEdge(n[0], n[1], BotNavigationGraph.EdgeType.JUMP, new Point(n[3], n[4]),
                    new Point(n[5], n[6]), n[2], n[7]), false, false);
        }
        if (f.size() != 11) {
            return null;
        }
        BotNavigationGraph.EdgeType type = BotNavigationGraph.EdgeType.valueOf(String.valueOf(f.get(0)));
        int[] n = f.subList(1, 11).stream().mapToInt(x -> ((Number) x).intValue()).toArray();
        Point start = new Point(n[3], n[4]);
        Point end = new Point(n[5], n[6]);
        boolean fromRope = n[8] == 1;
        BotNavigationGraph.Edge edge = fromRope ? ropeEdge(n[0], n[1], start, end, n[2], n[7])
                : groundEdge(n[0], n[1], type, start, end, n[2], n[7]);
        return new Stored(edge, fromRope, n[9] == 1);
    }

    /**
     * A stored edge on a rebuilt graph: its regions found again from where it starts and ends, or null when either
     * end no longer lies on a platform or rope, or the new graph makes the move itself.
     */
    private static BotNavigationGraph.Edge remap(BotNavigationGraph graph, Stored stored) {
        BotNavigationGraph.Edge e = stored.edge();
        int from = stored.fromRope() ? graph.findRopeRegionId(e.startPoint) : groundRegionAt(graph, e.startPoint);
        int to = stored.toRope() ? graph.findRopeRegionId(e.endPoint) : groundRegionAt(graph, e.endPoint);
        if (from < 0 || to < 0 || from == to) {
            return null;
        }
        for (BotNavigationGraph.Edge own : graph.getOutgoing(from)) {
            if (joins(own, to, e.type)) {
                return null;
            }
        }
        return stored.fromRope() ? ropeEdge(from, to, e.startPoint, e.endPoint, e.launchStepX, e.cost)
                : groundEdge(from, to, e.type, e.startPoint, e.endPoint, e.launchStepX, e.cost);
    }

    private static int groundRegionAt(BotNavigationGraph graph, Point p) {
        for (BotNavigationGraph.Region region : graph.regions) {
            if (!region.isRopeRegion && region.surfaceCoversPoint(p.x, p.y, REMAP_Y_PX)) {
                return region.id;
            }
        }
        return -1;
    }

    /** For the GM read-out. */
    public static String describe() {
        Map<BotNavigationGraph.EdgeType, Integer> byType = new EnumMap<>(BotNavigationGraph.EdgeType.class);
        long maps = 0;
        for (Overlay overlay : overlays.values()) {
            for (BotNavigationGraph.Edge edge : overlay.edges()) {
                byType.merge(edge.type, 1, Integer::sum);
            }
            if (!overlay.edges().isEmpty()) {
                maps++;
            }
        }
        return "learned from players: " + byType.getOrDefault(BotNavigationGraph.EdgeType.JUMP, 0) + " jumps, "
                + byType.getOrDefault(BotNavigationGraph.EdgeType.FLASH_JUMP, 0) + " flash jumps, "
                + byType.getOrDefault(BotNavigationGraph.EdgeType.TELEPORT, 0) + " teleports, "
                + byType.getOrDefault(BotNavigationGraph.EdgeType.CLIMB, 0) + " rope moves on " + maps + " maps, "
                + sightings.size() + " moves seen once";
    }

    /** Test seam: a scratch directory and map lookup (nulls restore the real ones), and a clean slate. */
    static void useForTest(Path testDir, IntFunction<MapleMap> testMaps) {
        dir = testDir == null ? DIR : testDir;
        maps = testMaps == null ? soloMapling.server.SoloMaplingUtilities::getMapleMapById : testMaps;
        tracks.clear();
        sightings.clear();
        overlays.clear();
        sink = null;
    }
}
