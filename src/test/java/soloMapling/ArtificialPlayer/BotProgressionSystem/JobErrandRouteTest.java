package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.BotClient;
import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotScriptedWarp;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.WzTestData;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b266: JobErrandTest replaces the mover, so it proves the errand's logic but never that a real route to an
 * instructor exists — the gap that let the 1.6.3 regression (bot cannot route to 100000201, advances on the spot)
 * ship. These tests run the real planner and the real {@code Mover.LIVE} gate, and pin the fare arithmetic that
 * decides it where the world graph is not available.
 */
class JobErrandRouteTest {

    private static final int HENESYS = 100000000;
    private static final int HENESYS_PARK = 100000200;
    private static final int BOWMAN_SCHOOL = 100000201;
    private static final int ELLINIA = 101000000;
    private static final int MAGIC_LIBRARY = 101000003;
    private static final int PERION = 102000000;
    private static final int WARRIORS_SANCTUARY = 102000003;
    private static final int KERNING = 103000000;
    private static final int THIEVES_HIDEOUT = 103000003;
    private static final int NAUTILUS_NAVIGATION = 120000101;
    private static final int LEVEL = 8;

    private static final int BEGINNER = 0;
    private static final int SWORDMAN = 100;
    private static final int MAGICIAN_JOB = 200;
    private static final int ARCHER = 300;
    private static final int ROGUE = 400;

    private static final int BASE_ID = 1_000_000_000 + 5266;

    private final List<Runnable> queued = new ArrayList<>();

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @AfterEach
    void tearDown() {
        JobErrand.useForTest(null, null, null, null, null);
        for (int i = 0; i < 10; i++) {
            JobErrand.forget(BASE_ID + i);
        }
    }

    /** A bot just spawned at level 8 in Henesys: beginner job, no mesos (BotGeneration seeds none). */
    private static Character freshBotOnHenesys(int id) {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(id);
        bot.setName("ErrandRouter" + id);
        bot.setJob(Job.getById(BEGINNER));
        bot.setLevel(LEVEL);
        bot.setMapId(HENESYS);
        bot.setMap(new MapleMap(HENESYS, 0, 0, HENESYS, 1.0f));
        return bot;
    }

    /** The real {@code Mover.LIVE} gate: false means an errand started, true means the instant advance. */
    private boolean advancesOnTheSpot(Character bot, int nextJob) {
        JobErrand.useForTest(null, (task, delayMs) -> queued.add(task), b -> true, () -> true, b -> { });
        queued.clear();
        return JobErrand.gateFor(bot).mayAdvanceInto(nextJob);
    }

    @Test
    void everyVictoriaInstructorIsReachableByARealRoute() {
        WzTestData.assumePresent("Map.wz");
        assertRoute(HENESYS, BOWMAN_SCHOOL, "bowman", true);
        assertRoute(ELLINIA, MAGIC_LIBRARY, "magician", true);
        assertRoute(HENESYS, WARRIORS_SANCTUARY, "warrior", false);
        assertRoute(HENESYS, THIEVES_HIDEOUT, "thief", false);
    }

    private static void assertRoute(int from, int to, String what, boolean scriptedDoor) {
        List<Integer> route = GCMovement.route(from, to, 0, LEVEL);
        assertNotNull(route, what + ": no route " + from + " -> " + to);
        assertFalse(route.isEmpty(), what + ": route lists no hop");
        assertEquals(to, route.get(route.size() - 1), what + ": route ends on the instructor's map");
        int lastHopFrom = route.size() < 2 ? from : route.get(route.size() - 2);
        assertEquals(scriptedDoor, BotScriptedWarp.edge(lastHopFrom, to) != null,
                what + ": the last hop into " + to + " from " + lastHopFrom + " is "
                        + (scriptedDoor ? "the curated scripted door" : "a plain portal"));
        int fare = GCMovement.routeFare(from, route, BEGINNER);
        assertEquals(scriptedDoor ? 0 : 100, fare, what + ": a Beginner's fare on " + route);
    }

    /** The card's case: with the switch on, a watched level-8 beginner on Henesys walks to the instructor. */
    @Test
    void aLevelEightBeginnerWalksToEveryReachableInstructor() {
        WzTestData.assumePresent("Map.wz");
        int[][] errands = {{SWORDMAN, PERION}, {MAGICIAN_JOB, ELLINIA}, {ARCHER, BOWMAN_SCHOOL},
                {ROGUE, KERNING}};
        for (int i = 0; i < errands.length; i++) {
            Character bot = freshBotOnHenesys(BASE_ID + i);
            assertEquals(0, bot.getMeso(), "a fresh bot holds no mesos");
            long blocked = blockedCount();
            boolean instant = advancesOnTheSpot(bot, errands[i][0]);
            assertFalse(instant, "job " + errands[i][0] + " advanced on the spot instead of walking to "
                    + errands[i][1] + " (" + JobErrand.stats() + ")");
            assertEquals(blocked, blockedCount(), "the blocked counter must not move for a reachable instructor");
            assertTrue(JobErrand.holds(bot.getId()), "an errand is running for job " + errands[i][0]);
        }
    }

    /** The {@code blocked=} figure from {@link JobErrand#stats()}. */
    private static long blockedCount() {
        String stats = JobErrand.stats();
        int at = stats.indexOf("blocked=") + "blocked=".length();
        return Long.parseLong(stats.substring(at, stats.indexOf(' ', at)));
    }

    /** Nautilus is version-gated out of the bots' graph (portal_versions.yaml: 120000000/120000100/120000200 at
     *  62), so the pirate errand keeps taking the ticket's instant-advance fallback. */
    @Test
    void thePirateInstructorIsVersionGatedOutOfTheGraph() {
        WzTestData.assumePresent("Map.wz");
        assertTrue(GCMovement.route(HENESYS, NAUTILUS_NAVIGATION, 0, LEVEL) == null,
                "Nautilus is still closed to the bots' graph");
        Character bot = freshBotOnHenesys(BASE_ID + 9);
        assertTrue(advancesOnTheSpot(bot, 500), "a pirate bot still advances instantly, blocked="
                + JobErrand.stats());
        assertFalse(JobErrand.holds(bot.getId()));
    }

    // ---- the arithmetic canReach decides on, in tests that need no WZ data ----

    @Test
    void theScriptedInstructorsDoorCostsNothing() {
        assertEquals(0, GCMovement.routeFare(HENESYS, List.of(HENESYS_PARK, BOWMAN_SCHOOL), BEGINNER),
                "walking into the Bowman school through its scripted portal is free");
        assertEquals(0, GCMovement.routeFare(ELLINIA, List.of(MAGIC_LIBRARY), BEGINNER),
                "the Magic Library door is free");
    }

    @Test
    void theCabFareRulesOutAFreshBotsTimedRoute() {
        assertEquals(100, GCMovement.routeFare(HENESYS, List.of(PERION, WARRIORS_SANCTUARY), BEGINNER),
                "the Victoria cab charges a Beginner a tenth");
        assertEquals(1000, GCMovement.routeFare(HENESYS, List.of(PERION, WARRIORS_SANCTUARY), SWORDMAN));
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setLevel(LEVEL);
        assertEquals(0, bot.getMeso(), "a bot spawned at level 8 has no mesos, so the fare test alone rejects "
                + "a walkable errand");
    }
}
