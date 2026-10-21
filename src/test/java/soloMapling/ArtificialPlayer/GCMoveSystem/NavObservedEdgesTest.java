package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import soloMapling.WzTestData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** bot-persistence ticket 17. The physics test needs the real wz/ and is skipped without it. */
class NavObservedEdgesTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @BeforeEach
    void scratch() {
        NavObservedEdges.useForTest(dir, null);
    }

    @AfterEach
    void real() {
        NavObservedEdges.useForTest(null, null);
    }

    @Test
    void aJumpIsGroundThenAirThenOtherGroundAndWalkingIsNot() {
        List<NavObservedEdges.Transition> seen = new ArrayList<>();
        NavObservedEdges.sink = seen::add;
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(1001);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000);
        player.setMap(map);

        NavObservedEdges.onPlayerMove(player, 100, 200, 5);   // standing on foothold 5
        NavObservedEdges.onPlayerMove(player, 120, 200, 6);   // walked onto foothold 6: not a jump
        NavObservedEdges.onPlayerMove(player, 130, 170, 0);   // took off
        NavObservedEdges.onPlayerMove(player, 160, 120, 0);   // still in the air
        NavObservedEdges.onPlayerMove(player, 190, 90, 9);    // landed on foothold 9: a jump
        NavObservedEdges.onPlayerMove(player, 200, 90, 0);    // hopped ...
        NavObservedEdges.onPlayerMove(player, 205, 90, 9);    // ... and came down on the same foothold: not a jump

        assertEquals(List.of(new NavObservedEdges.Transition(100000000, 6, new Point(120, 200), 9, new Point(190, 90))), seen);
    }

    /** Henesys' baked graph minus one of its own jumps: the jump a player then makes is what the bots learn. */
    @Test
    void aPlayersJumpTheGraphLacksIsProvenByTheBotsPhysicsAndUsedByThePlanner() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000000);
        BotNavigationGraph full = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        assertNotNull(full);

        BotNavigationGraph.Edge removed = null;
        for (BotNavigationGraph.Region region : full.regions) {
            for (BotNavigationGraph.Edge edge : full.getOutgoing(region.id)) {
                long sameTarget = full.getOutgoing(region.id).stream().filter(e -> e.toRegionId == edge.toRegionId).count();
                if (edge.type == BotNavigationGraph.EdgeType.JUMP && sameTarget == 1 && edge.launchStepX != 0) {
                    removed = edge;
                    break;
                }
            }
            if (removed != null) {
                break;
            }
        }
        assertNotNull(removed, "Henesys has a directional jump that is the only link between two regions");
        BotNavigationGraph.Edge gone = removed;
        Map<Integer, List<BotNavigationGraph.Edge>> outgoing = new HashMap<>();
        for (BotNavigationGraph.Region region : full.regions) {
            List<BotNavigationGraph.Edge> kept = new ArrayList<>(full.getOutgoing(region.id));
            kept.remove(gone);
            outgoing.put(region.id, kept);
        }
        BotNavigationGraph lacking = new BotNavigationGraph(full.mapId, full.version, full.movementProfile, full.regions,
                full.regionsById, full.regionIdByFootholdId, outgoing, full.collidableWallIds, full.collidableFromBelowIds);

        int fromFh = footholdOf(full, gone.fromRegionId);
        int toFh = footholdOf(full, gone.toRegionId);
        Point takeoff = new Point(gone.launchMinX, gone.startPoint.y);
        NavObservedEdges.Transition jump = new NavObservedEdges.Transition(map.getId(), fromFh, takeoff, toFh,
                new Point(gone.endPoint));

        assertFalse(NavObservedEdges.consider(jump, map, lacking), "one sighting is not enough");
        assertTrue(NavObservedEdges.consider(jump, map, lacking), "the second is, once the physics repeats it");
        assertTrue(NavObservedEdges.outgoing(lacking, gone.fromRegionId).stream()
                .anyMatch(e -> e.toRegionId == gone.toRegionId), "the planner now sees the learned jump");

        // A 'jump' the physics cannot make - landing in a region the takeoff cannot reach - is never adopted.
        int farRegion = -1;
        for (BotNavigationGraph.Region region : full.regions) {
            if (region.id != gone.fromRegionId && region.id != gone.toRegionId && !region.segments.isEmpty()
                    && Math.abs(region.segments.get(0).minX - takeoff.x) > 1500) {
                farRegion = region.id;
                break;
            }
        }
        Assumptions.assumeTrue(farRegion >= 0);
        NavObservedEdges.Transition impossible = new NavObservedEdges.Transition(map.getId(), fromFh, takeoff,
                footholdOf(full, farRegion), new Point(takeoff.x + 1600, takeoff.y));
        NavObservedEdges.consider(impossible, map, lacking);
        assertFalse(NavObservedEdges.consider(impossible, map, lacking), "lag or a knockback is not a jump");
    }

    private static int footholdOf(BotNavigationGraph graph, int regionId) {
        for (Map.Entry<Integer, Integer> e : graph.regionIdByFootholdId.entrySet()) {
            if (e.getValue() == regionId) {
                return e.getKey();
            }
        }
        throw new AssertionError("region " + regionId + " has no foothold");
    }

    // ── ticket 04: flash jumps, teleports and rope moves ──────────────────────────────────────

    private static Character player(int id, client.Job job) {
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(id);
        player.setJob(job);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000);
        player.setMap(map);
        return player;
    }

    @Test
    void flashJumpsTeleportsAndRopeMovesAreToldApartByFragmentAndStance() {
        List<NavObservedEdges.Transition> seen = new ArrayList<>();
        NavObservedEdges.sink = seen::add;
        int rope = constants.game.CharacterStance.ROPE_LEFT_STANCE;
        int stand = constants.game.CharacterStance.STAND_RIGHT_STANCE;
        Character hermit = player(1002, client.Job.HERMIT);
        NavObservedEdges.onPlayerMove(hermit, 100, 200, 5, stand);
        NavObservedEdges.onPlayerMove(hermit, 110, 170, 0, 6);
        NavObservedEdges.onPlayerSkillMove(hermit, NavObservedEdges.Kind.FLASH_JUMP);
        NavObservedEdges.onPlayerMove(hermit, 300, 150, 8, stand);   // flash jump 5 -> 8
        NavObservedEdges.onPlayerMove(hermit, 300, 130, 0, 6);
        NavObservedEdges.onPlayerMove(hermit, 320, 110, 0, rope);    // grabbed a rope mid-air: jump-grab 8 -> rope
        NavObservedEdges.onPlayerMove(hermit, 320, 90, 0, rope);     // climbing
        NavObservedEdges.onPlayerMove(hermit, 330, 80, 0, 6);        // jumped off ...
        NavObservedEdges.onPlayerMove(hermit, 380, 60, 12, stand);   // ... onto foothold 12: rope exit
        NavObservedEdges.onPlayerMove(hermit, 390, 60, 0, 6);
        NavObservedEdges.onPlayerMove(hermit, 395, 40, 0, rope);     // another jump-grab ...
        NavObservedEdges.onPlayerMove(hermit, 400, 30, 0, 6);
        NavObservedEdges.onPlayerMove(hermit, 520, 30, 0, rope);     // ... and a rope-to-rope transfer

        Character wizard = player(1003, client.Job.FP_WIZARD);
        NavObservedEdges.onPlayerMove(wizard, 100, 200, 5, stand);
        NavObservedEdges.onPlayerSkillMove(wizard, NavObservedEdges.Kind.TELEPORT);
        NavObservedEdges.onPlayerMove(wizard, 250, 200, 7, stand);   // teleport 5 -> 7, never airborne
        NavObservedEdges.onPlayerSkillMove(wizard, NavObservedEdges.Kind.TELEPORT);
        NavObservedEdges.onPlayerMove(wizard, 260, 200, 7, stand);   // a blink along the same foothold: nothing

        Character warrior = player(1004, client.Job.WARRIOR);
        NavObservedEdges.onPlayerMove(warrior, 100, 200, 5, stand);
        NavObservedEdges.onPlayerSkillMove(warrior, NavObservedEdges.Kind.TELEPORT); // not a teleport job: ignored
        NavObservedEdges.onPlayerMove(warrior, 120, 170, 0, 6);
        NavObservedEdges.onPlayerMove(warrior, 150, 150, 9, stand);  // a plain jump

        int m = 100000000;
        assertEquals(List.of(
                new NavObservedEdges.Transition(m, NavObservedEdges.Kind.FLASH_JUMP, 5, new Point(100, 200), 8, new Point(300, 150)),
                new NavObservedEdges.Transition(m, 8, new Point(300, 150), 0, new Point(320, 110)),
                new NavObservedEdges.Transition(m, 0, new Point(320, 90), 12, new Point(380, 60)),
                new NavObservedEdges.Transition(m, 12, new Point(380, 60), 0, new Point(395, 40)),
                new NavObservedEdges.Transition(m, 0, new Point(395, 40), 0, new Point(520, 30)),
                new NavObservedEdges.Transition(m, NavObservedEdges.Kind.TELEPORT, 5, new Point(100, 200), 7, new Point(250, 200)),
                new NavObservedEdges.Transition(m, 5, new Point(100, 200), 9, new Point(150, 150))), seen);
    }

    private static final int[] TOWNS = {100000000, 101000000, 102000000, 103000000, 104000000, 105040300};

    /** A graph edge of the wanted kind that is the only link of its kind between its two regions. */
    private record Found(MapleMap map, BotNavigationGraph full, BotNavigationGraph lacking, BotNavigationGraph.Edge edge) {
    }

    private static Found findLearnable(java.util.function.Predicate<BotNavigationGraph.Edge> wanted) {
        for (int mapId : TOWNS) {
            MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
            if (map == null) {
                continue;
            }
            BotNavigationGraph full = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
            if (full == null) {
                continue;
            }
            for (BotNavigationGraph.Region region : full.regions) {
                for (BotNavigationGraph.Edge edge : full.getOutgoing(region.id)) {
                    if (!wanted.test(edge)) {
                        continue;
                    }
                    boolean otherwiseJoined = full.getOutgoing(region.id).stream().anyMatch(e -> e.toRegionId == edge.toRegionId
                            && e.type != edge.type && !e.isMovementSkill());
                    if (otherwiseJoined) {
                        continue;
                    }
                    Map<Integer, List<BotNavigationGraph.Edge>> outgoing = new HashMap<>();
                    for (BotNavigationGraph.Region r : full.regions) {
                        List<BotNavigationGraph.Edge> kept = new ArrayList<>(full.getOutgoing(r.id));
                        kept.removeIf(e -> e.fromRegionId == edge.fromRegionId && e.toRegionId == edge.toRegionId);
                        outgoing.put(r.id, kept);
                    }
                    BotNavigationGraph lacking = new BotNavigationGraph(full.mapId, full.version, full.movementProfile,
                            full.regions, full.regionsById, full.regionIdByFootholdId, outgoing, full.collidableWallIds,
                            full.collidableFromBelowIds);
                    return new Found(map, full, lacking, edge);
                }
            }
        }
        return null;
    }

    private static int endFh(BotNavigationGraph graph, int regionId) {
        return graph.getRegion(regionId).isRopeRegion ? 0 : footholdOf(graph, regionId);
    }

    /** Players make the graph's move twice; the bots must re-prove it and plan with it, as the same edge type. */
    private void learnsBack(java.util.function.Predicate<BotNavigationGraph.Edge> wanted, NavObservedEdges.Kind kind,
                            BotNavigationGraph.EdgeType expected) {
        WzTestData.assumePresent("Map.wz");
        Found found = findLearnable(wanted);
        Assumptions.assumeTrue(found != null, "no town has a lone " + expected + " link");
        BotNavigationGraph.Edge gone = found.edge();
        NavObservedEdges.Transition move = new NavObservedEdges.Transition(found.map().getId(), kind,
                endFh(found.full(), gone.fromRegionId), new Point(gone.startPoint),
                endFh(found.full(), gone.toRegionId), new Point(gone.endPoint));

        assertFalse(NavObservedEdges.consider(move, found.map(), found.lacking()), "one sighting is not enough");
        assertTrue(NavObservedEdges.consider(move, found.map(), found.lacking()), "the second is, once the physics repeats it");
        List<BotNavigationGraph.Edge> learned = NavObservedEdges.outgoing(found.lacking(), gone.fromRegionId).stream()
                .filter(e -> e.toRegionId == gone.toRegionId).toList();
        assertEquals(1, learned.size());
        assertEquals(expected, learned.get(0).type);
        if (expected == BotNavigationGraph.EdgeType.TELEPORT || expected == BotNavigationGraph.EdgeType.FLASH_JUMP) {
            assertFalse(NavMovementSkills.allows(learned.get(0), NavMovementSkills.maskForJob(100)), "a warrior never plans it");
            assertTrue(NavMovementSkills.allows(learned.get(0), NavMovementSkills.maskForJob(
                    expected == BotNavigationGraph.EdgeType.TELEPORT ? 210 : 411)));
        }

        // A graph version bump keeps it: the ends are found again on the rebuilt graph's platforms and ropes ...
        NavObservedEdges.useForTest(dir, null);
        BotNavigationGraph bumped = new BotNavigationGraph(found.lacking().mapId, found.lacking().version + 1,
                found.lacking().movementProfile, found.lacking().regions, found.lacking().regionsById,
                found.lacking().regionIdByFootholdId, found.lacking().outgoingByRegionId, found.lacking().collidableWallIds,
                found.lacking().collidableFromBelowIds);
        assertTrue(NavObservedEdges.outgoing(bumped, gone.fromRegionId).stream()
                .anyMatch(e -> e.toRegionId == gone.toRegionId && e.type == expected), "carried to the new version");
        // ... and a later version that makes the move itself drops the learned copy.
        NavObservedEdges.useForTest(dir, null);
        BotNavigationGraph builtIn = new BotNavigationGraph(found.full().mapId, found.full().version + 2,
                found.full().movementProfile, found.full().regions, found.full().regionsById,
                found.full().regionIdByFootholdId, found.full().outgoingByRegionId, found.full().collidableWallIds,
                found.full().collidableFromBelowIds);
        assertEquals(found.full().getOutgoing(gone.fromRegionId).size(),
                NavObservedEdges.outgoing(builtIn, gone.fromRegionId).size());
    }

    @Test
    void aPlayersTeleportIsProvenWithTheBotsTeleportAndPlannedOnlyForMages() {
        learnsBack(e -> e.type == BotNavigationGraph.EdgeType.TELEPORT, NavObservedEdges.Kind.TELEPORT,
                BotNavigationGraph.EdgeType.TELEPORT);
    }

    @Test
    void aPlayersFlashJumpIsProvenWithTheBotsFlashJumpAndPlannedOnlyForThieves() {
        learnsBack(e -> e.type == BotNavigationGraph.EdgeType.FLASH_JUMP, NavObservedEdges.Kind.FLASH_JUMP,
                BotNavigationGraph.EdgeType.FLASH_JUMP);
    }

    @Test
    void aPlayersJumpOffARopeIsLearned() {
        learnsBack(e -> e.type == BotNavigationGraph.EdgeType.CLIMB && e.launchStepX != 0 && e.hasLaunchYWindow(),
                NavObservedEdges.Kind.JUMP, BotNavigationGraph.EdgeType.CLIMB);
    }

    @Test
    void aPlayersJumpGrabOntoARopeIsLearned() {
        learnsBack(e -> e.type == BotNavigationGraph.EdgeType.CLIMB && e.launchStepX != 0 && !e.hasLaunchYWindow()
                        && e.launchMaxX > e.launchMinX,
                NavObservedEdges.Kind.JUMP, BotNavigationGraph.EdgeType.CLIMB);
    }

    // ── players' moves as the packets show them (recorded packets of real play) ───────────────

    static final Path RECORDED = Path.of("src/main/java/soloMapling/ArtificialPlayer/BotMovementSystem/movementDataPackets");
    private static final int HUNTING_GROUND = 100040000;

    private static List<NavObservedEdges.Transition> recorded(int mapId) throws java.io.IOException {
        return NavPlayerReplay.fromRecordedPackets(RECORDED, mapId);
    }

    /** All movement tiers' graphs of a map, built in memory (never cached, so other tests' graphs are untouched). */
    private static NavPlayerReplay.Loaded built(int mapId) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        Map<BotMovementProfile, BotNavigationGraph> graphs = new HashMap<>();
        java.util.function.Function<BotMovementProfile, BotNavigationGraph> tiers =
                p -> graphs.computeIfAbsent(p, q -> BotNavigationGraphProvider.buildUncachedGraph(map, q));
        return new NavPlayerReplay.Loaded(map, tiers.apply(BotMovementProfile.base()), tiers);
    }

    /** A graph minus every plain edge from one region to another (and skill edges of the same type). */
    private static BotNavigationGraph without(BotNavigationGraph full, int from, int to) {
        Map<Integer, List<BotNavigationGraph.Edge>> outgoing = new HashMap<>();
        for (BotNavigationGraph.Region r : full.regions) {
            List<BotNavigationGraph.Edge> kept = new ArrayList<>(full.getOutgoing(r.id));
            kept.removeIf(e -> e.fromRegionId == from && e.toRegionId == to);
            outgoing.put(r.id, kept);
        }
        return new BotNavigationGraph(full.mapId, full.version, full.movementProfile, full.regions, full.regionsById,
                full.regionIdByFootholdId, outgoing, full.collidableWallIds, full.collidableFromBelowIds);
    }

    @Test
    void recordedPacketsGiveTheTakeoffAtTheLastGroundedElementAndTheLandingAtTheFirst() throws Exception {
        // m1.csv: walking right over footholds 309..74, a jump at (-1309,2070) off foothold 74, 3 airborne elements,
        // down on foothold 80 at (-1227,2026).
        List<NavObservedEdges.Transition> moves = NavPlayerReplay.captureCsv(HUNTING_GROUND,
                Files.readAllLines(RECORDED.resolve("map100040000/m1.csv")));
        assertTrue(moves.contains(new NavObservedEdges.Transition(HUNTING_GROUND, 74, new Point(-1309, 2070), 80,
                new Point(-1227, 2026))), moves.toString());
        // Walking from foothold to foothold is never a move, and climbing (rope ids are negative) is not ground.
        assertTrue(moves.stream().noneMatch(t -> t.fromFh() < 0 || t.toFh() < 0));
    }

    /**
     * Why the live server learned nothing (2026-10-21): on a baked hunting ground every move real players make is one
     * the graph already has. Every recorded move resolves to platforms (rope ends included), none is new, and every
     * recorded jump the graph has is one the bots' physics re-proves from the player's takeoff.
     */
    @Test
    void onABakedHuntingGroundPlayersMovesAreAllKnownAndAllProvable() throws Exception {
        WzTestData.assumePresent("Map.wz");
        NavPlayerReplay.Loaded l = built(HUNTING_GROUND);
        List<NavObservedEdges.Transition> moves = recorded(HUNTING_GROUND);
        assertTrue(moves.size() > 100, "the recorded hunting-ground play: " + moves.size());
        NavPlayerReplay.Report report = NavPlayerReplay.replay(moves, id -> l, 5);
        assertEquals(0, report.count(NavObservedEdges.Outcome.UNKNOWN_REGION), report.examples().toString());
        assertEquals(0, report.count(NavObservedEdges.Outcome.SIGHTED) + report.count(NavObservedEdges.Outcome.ADOPTED));
        assertEquals(moves.size(), report.count(NavObservedEdges.Outcome.ALREADY_JOINED)
                + report.count(NavObservedEdges.Outcome.SAME_REGION));

        int provable = 0;
        int ground = 0;
        List<String> failed = new ArrayList<>();
        for (NavObservedEdges.Transition t : moves) {
            NavObservedEdges.Ends e = NavObservedEdges.ends(t, l.graph());
            if (e.type() != BotNavigationGraph.EdgeType.JUMP || e.from() == e.to()) {
                continue;
            }
            ground++;
            if (NavObservedEdges.prove(l.map(), without(l.graph(), e.from(), e.to()), t, e) != null) {
                provable++;
            } else {
                failed.add(t + " " + e);
            }
        }
        assertTrue(ground > 50, "ground-to-ground moves: " + ground);
        assertTrue(provable >= ground * 0.95, provable + " of " + ground + " re-proven; failed: " + failed);
    }

    /** A real jump between two platforms, once the graph lacks it, is learned from two players and planned. */
    @Test
    void aRecordedJumpBetweenTwoPlatformsIsLearnedFromTwoSightings() throws Exception {
        WzTestData.assumePresent("Map.wz");
        NavPlayerReplay.Loaded l = built(HUNTING_GROUND);
        for (NavObservedEdges.Transition t : recorded(HUNTING_GROUND)) {
            NavObservedEdges.Ends e = NavObservedEdges.ends(t, l.graph());
            if (e.type() != BotNavigationGraph.EdgeType.JUMP || e.from() == e.to()
                    || Math.abs(t.landing().y - t.takeoff().y) < 20) {
                continue;
            }
            BotNavigationGraph lacking = without(l.graph(), e.from(), e.to());
            NavObservedEdges.useForTest(dir, null);
            assertEquals(NavObservedEdges.Outcome.SIGHTED, NavObservedEdges.classify(t, lacking));
            assertEquals(NavObservedEdges.Outcome.SIGHTED, NavObservedEdges.evaluate(t, l.map(), lacking));
            NavObservedEdges.Outcome second = NavObservedEdges.evaluate(t, l.map(), lacking);
            if (second != NavObservedEdges.Outcome.ADOPTED) {
                continue; // that one needs a tier the base graph's planner is not at; try the next
            }
            BotNavigationGraph.Edge learned = NavObservedEdges.outgoing(lacking, e.from()).stream()
                    .filter(x -> x.toRegionId == e.to()).findFirst().orElseThrow();
            assertTrue(learned.type == BotNavigationGraph.EdgeType.JUMP || learned.type == BotNavigationGraph.EdgeType.DROP);
            assertEquals(NavObservedEdges.Outcome.ALREADY_JOINED, NavObservedEdges.classify(t, lacking), "a third is known");
            assertTrue(Files.exists(dir.resolve(HUNTING_GROUND + ".json")));
            return;
        }
        throw new AssertionError("no recorded jump was re-learned");
    }

    /** The client reports a walk off a platform's end like a jump; it is proven as the graph's own walk-off drop. */
    @Test
    void aWalkOffThePlatformsEndIsLearnedAsADrop() {
        WzTestData.assumePresent("Map.wz");
        for (int mapId : new int[]{HUNTING_GROUND, 100000000, 101000000, 102000000, 103000000}) {
            NavPlayerReplay.Loaded l = built(mapId);
            BotNavigationGraph g = l.graph();
            for (BotNavigationGraph.Region region : g.regions) {
                for (BotNavigationGraph.Edge drop : g.getOutgoing(region.id)) {
                    if (drop.type != BotNavigationGraph.EdgeType.DROP || drop.launchStepX == 0) {
                        continue;
                    }
                    int dir = Integer.signum(drop.launchStepX);
                    Point end = dir < 0 ? region.leftPoint() : region.rightPoint();
                    BotNavigationGraph lacking = without(g, drop.fromRegionId, drop.toRegionId);
                    NavObservedEdges.useForTest(dir(), null);
                    // What the packets show: standing at the end, then air, then the landing platform's foothold.
                    List<NavObservedEdges.Transition> seen = new ArrayList<>();
                    NavObservedEdges.Capture capture = new NavObservedEdges.Capture();
                    int fromFh = footholdOf(g, drop.fromRegionId);
                    int toFh = footholdUnder(g, l.map(), drop.endPoint);
                    capture.element(mapId, end.x - dir * 30, end.y, fromFh, constants.game.CharacterStance.WALK_RIGHT_STANCE);
                    capture.element(mapId, end.x, end.y, fromFh, constants.game.CharacterStance.WALK_RIGHT_STANCE);
                    capture.element(mapId, end.x + dir * 20, end.y + 30, 0, constants.game.CharacterStance.JUMP_RIGHT_STANCE);
                    NavObservedEdges.Transition t = capture.element(mapId, drop.endPoint.x, drop.endPoint.y, toFh,
                            constants.game.CharacterStance.STAND_RIGHT_STANCE);
                    assertNotNull(t);
                    if (NavObservedEdges.evaluate(t, l.map(), lacking) != NavObservedEdges.Outcome.SIGHTED
                            || NavObservedEdges.evaluate(t, l.map(), lacking) != NavObservedEdges.Outcome.ADOPTED) {
                        continue; // a jump from the end lands there too, or the drop is speed-dependent: try another
                    }
                    BotNavigationGraph.Edge learned = NavObservedEdges.outgoing(lacking, drop.fromRegionId).stream()
                            .filter(x -> x.toRegionId == drop.toRegionId).findFirst().orElseThrow();
                    if (learned.type == BotNavigationGraph.EdgeType.JUMP) {
                        continue; // a hop off the edge made it first: also right, but not what this test is about
                    }
                    assertEquals(BotNavigationGraph.EdgeType.DROP, learned.type);
                    assertEquals(dir, Integer.signum(learned.launchStepX), "a directional walk-off, not a down-jump");
                    return;
                }
            }
        }
        throw new AssertionError("no walk-off drop was re-learned");
    }

    private Path dir() {
        return dir;
    }

    private static int footholdUnder(BotNavigationGraph g, MapleMap map, Point p) {
        server.maps.Foothold fh = BotPhysicsEngine.findBelowIndexed(map, new Point(p.x, p.y - 2));
        assertNotNull(fh);
        return fh.getId();
    }

    /** A client that numbers a map's footholds differently: the ends are found by position, and the mismatch counted. */
    @Test
    void aFootholdIdThatIsNotUnderThePlayerIsResolvedByPosition() {
        WzTestData.assumePresent("Map.wz");
        NavPlayerReplay.Loaded l = built(HUNTING_GROUND);
        BotNavigationGraph g = l.graph();
        BotNavigationGraph.Region a = g.regions.stream().filter(r -> !r.isRopeRegion).findFirst().orElseThrow();
        BotNavigationGraph.Region b = g.regions.stream().filter(r -> !r.isRopeRegion && r.id != a.id
                && Math.abs(r.centerPoint().y - a.centerPoint().y) > 200).findFirst().orElseThrow();
        int wrongForA = footholdOf(g, b.id);
        int wrongForB = footholdOf(g, a.id);
        NavObservedEdges.Transition t = new NavObservedEdges.Transition(HUNTING_GROUND, wrongForA, a.centerPoint(),
                wrongForB, b.centerPoint());
        NavObservedEdges.Ends e = NavObservedEdges.ends(t, g);
        assertEquals(a.id, e.from());
        assertEquals(b.id, e.to());
        assertTrue(e.fhMismatch());
        assertFalse(NavObservedEdges.ends(new NavObservedEdges.Transition(HUNTING_GROUND, wrongForB, a.centerPoint(),
                wrongForA, b.centerPoint()), g).fhMismatch(), "the right ids are no mismatch");
    }

    /** Climbing off a rope's top or bottom reports a point a few pixels past the rope: still that rope. */
    @Test
    void aRopeEndAFewPixelsPastTheRopeIsStillTheRope() {
        WzTestData.assumePresent("Map.wz");
        BotNavigationGraph g = built(HUNTING_GROUND).graph();
        BotNavigationGraph.Region rope = g.regions.stream().filter(r -> r.isRopeRegion).findFirst().orElseThrow();
        assertEquals(-1, g.findRopeRegionId(new Point(rope.minX, rope.minY - 5)));
        assertEquals(rope.id, NavObservedEdges.ropeRegionNear(g, new Point(rope.minX, rope.minY - 5)));
        assertEquals(rope.id, NavObservedEdges.ropeRegionNear(g, new Point(rope.minX, rope.maxY + 3)));
        assertEquals(-1, NavObservedEdges.ropeRegionNear(g, new Point(rope.minX, rope.maxY + 40)));
    }

    /** A move only faster bots make is planned only on their graphs, and not on a tier whose graph already has it. */
    @Test
    void aMoveProvenOnlyAtFasterTiersIsPlannedOnlyForThoseTiers() {
        WzTestData.assumePresent("Map.wz");
        NavObservedEdges.Learned learned = new NavObservedEdges.Learned(new BotNavigationGraph.Edge(1, 2,
                BotNavigationGraph.EdgeType.JUMP, new Point(0, 0), new Point(50, -60), 0, 0, 0, 0, 7, 0, 0, 0, 0, 500, 500, 500),
                0b0000110);
        BotNavigationGraph g = built(HUNTING_GROUND).graph();
        java.util.function.Function<BotMovementProfile, BotNavigationGraph> at = p -> new BotNavigationGraph(g.mapId,
                g.version, p, g.regions, g.regionsById, g.regionIdByFootholdId, g.outgoingByRegionId, g.collidableWallIds,
                g.collidableFromBelowIds);
        assertFalse(learned.appliesTo(g), "base");
        assertTrue(learned.appliesTo(at.apply(new BotMovementProfile(115, 100))));
        assertTrue(learned.appliesTo(at.apply(new BotMovementProfile(125, 105))));
        assertTrue(learned.appliesTo(at.apply(new BotMovementProfile(125, 110))), "125/110 plans as the 125/105 tier");
        assertFalse(learned.appliesTo(at.apply(new BotMovementProfile(130, 110))));
        assertFalse(learned.appliesTo(at.apply(new BotMovementProfile(155, 123))));
        assertEquals(-1, NavObservedEdges.tierOf(new BotMovementProfile(95, 100)));
    }
}
