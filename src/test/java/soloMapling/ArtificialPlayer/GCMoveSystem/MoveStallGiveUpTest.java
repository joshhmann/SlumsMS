package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-QA t-mx-2 (Round 2): stage 3 of the KPQ run idled for 1164 s. The route the leader's formation needed was
 * fine (the mover arrives in ~2 s from the same spot with the same stats), but a mechanics member re-issues its
 * target every 4 s (StepExecutor.occupy / moveNear), and every re-issue used to re-arm GCMovementDriver's 8 s
 * no-progress clock - so the give-up could never fire during a run and a bot whose route did not work repeated it
 * silently for as long as the run lasted. The fix keeps the clock on an identical re-issue, and makes the give-up
 * count: it records a GAVE_UP spot and marks the leg the bot was on as failed, so the next plan routes around it.
 */
class MoveStallGiveUpTest {

    private static final int KPQ_STAGE3 = 103000802; // the map the 1164 s run was stuck on
    private static final BotMovementProfile PROFILE = new BotMovementProfile(140, 123, false); // the stuck log's bucket

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
        NavEdgeLearning.useDirForTest(dir.resolve("learned"));
        NavStuckLog.useForTest(dir.resolve("stuck"), Clock.fixed(Instant.parse("2026-10-31T07:40:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void restore() {
        NavEdgeLearning.useDirForTest(null);
        NavStuckLog.useForTest(null, null);
    }

    /*
     * The re-issue that made the give-up unreachable: same point, same map, nothing moved. This is what
     * StepExecutor does every 4 s for as long as the bot is not inside the position's rectangle, and it used to
     * restart the 8 s window each time.
     */
    @Test
    void reIssuingTheSamePointKeepsTheClockThatWouldGiveTheMoveUp() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + 76_401);
        chr.setMapId(910000000); // an id only: no map object, so the driver's tick stops itself
        try {
            GCMovement.move(chr, 950, -215);
            BotMovementState st = GCMovement.stateOf(chr);
            assertNotNull(st, "fixture: the move enabled dynamic movement");
            st.moveProgressAtMs = System.currentTimeMillis() - 30_000; // it has made no progress for 30 s
            long aged = st.moveProgressAtMs;

            GCMovement.move(chr, 950, -215); // the re-issue: same point on the same map
            assertEquals(aged, st.moveProgressAtMs,
                    "the no-progress clock kept running, so giveUpStalledMove can fire during a run");

            GCMovement.move(chr, 960, -215); // a new point is a new journey
            assertTrue(st.moveProgressAtMs > aged, "a new target starts a fresh clock");

            st.moveProgressAtMs = System.currentTimeMillis() - 40_000;
            long aged2 = st.moveProgressAtMs;
            chr.setMapId(910000001); // same coordinates, another map: also a new journey
            GCMovement.move(chr, 960, -215);
            assertTrue(st.moveProgressAtMs > aged2, "a target re-issued on another map starts a fresh clock");
        } finally {
            GCMovement.disable(chr);
        }
    }

    /*
     * The give-up used to be silent and costless: the target was cleared, the caller re-issued it, and the same
     * route came back. Now the spot is recorded and the leg counts as a failure, so the planner's next plan for the
     * same point is more expensive on that leg and takes another way round.
     */
    @Test
    void givingUpAStalledMoveRecordsItAndCountsTheLegAsAFailure() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(KPQ_STAGE3);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, PROFILE);
        BotNavigationGraph.Edge jump = firstJump(graph);
        assertNotNull(jump, "fixture: stage 3's graph has a jump (platform 3 to platform 4 is one)");

        Character chr = parked(map, new Point(1025, -125), BotStore.BOT_ID_BASE + 76_402);
        BotMovementState st = new BotMovementState(chr, null);
        st.flaws.off = true;
        st.movementProfile = PROFILE;
        st.navGraph = graph;
        st.navEdge = jump;
        st.moveTarget = new Point(946, -215); // the stage-3 spot the formation needed
        st.moveTargetMapId = map.getId();
        st.moveBestDist = 0; // nothing has ever been closer than where it stands: no progress to report
        st.moveProgressAtMs = System.currentTimeMillis() - 30_000;
        assertEquals(1.0, NavEdgeLearning.factor(graph, jump), 1e-9, "fixture: the leg has no record yet");

        assertTrue(GCMovementDriver.giveUpStalledMove(st), "a move with no progress for 30 s is given up");
        assertNull(st.moveTarget, "the target is abandoned");
        assertEquals(1, gaveUpSpots(map), "the give-up is recorded like every other stuck kind");
        assertTrue(NavEdgeLearning.factor(graph, jump) > 1.0,
                "the leg that did not work now costs more, so the next plan takes another route");
    }

    /* A bot that IS making progress is never cut off (the clock resets on any real gain). */
    @Test
    void aMoveThatKeepsGettingCloserIsNotGivenUp() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(KPQ_STAGE3);
        Character chr = parked(map, new Point(1025, -125), BotStore.BOT_ID_BASE + 76_403);
        BotMovementState st = new BotMovementState(chr, null);
        st.flaws.off = true;
        st.movementProfile = PROFILE;
        st.moveTarget = new Point(946, -215);
        st.moveTargetMapId = map.getId();
        st.moveBestDist = 100_000; // it got much closer since the clock was last reset... 30 s ago
        st.moveProgressAtMs = System.currentTimeMillis() - 30_000;

        assertTrue(!GCMovementDriver.giveUpStalledMove(st), "no give-up while the bot is getting closer");
        assertNotNull(st.moveTarget);
        assertEquals(0, gaveUpSpots(map));
    }

    private Character parked(MapleMap map, Point start, int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setMap(map);
        Point ground = BotPhysicsEngine.findGroundPoint(map, start);
        chr.setPosition(new Point(ground != null ? ground : start));
        return chr;
    }

    private static BotNavigationGraph.Edge firstJump(BotNavigationGraph graph) {
        for (Map.Entry<Integer, List<BotNavigationGraph.Edge>> e : graph.outgoingByRegionId.entrySet()) {
            for (BotNavigationGraph.Edge edge : e.getValue()) {
                if (edge.type == BotNavigationGraph.EdgeType.JUMP) {
                    return edge;
                }
            }
        }
        return null;
    }

    private int gaveUpSpots(MapleMap map) {
        NavStuckLog.flush();
        int count = 0;
        for (NavStuckLog.Spot spot : NavStuckLog.read(
                NavStuckLog.fileFor(dir.resolve("stuck"), LocalDate.parse("2026-10-31")))) {
            if (spot.kind == NavStuckLog.Kind.GAVE_UP && spot.mapId == map.getId()) {
                count += spot.count;
            }
        }
        return count;
    }
}
