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
}

