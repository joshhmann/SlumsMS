package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 04c. */
class NavStuckLogTest {

    @TempDir
    Path dir;

    /** A clock the test moves by hand. */
    private static final class Manual extends Clock {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Manual clock = new Manual();

    @BeforeEach
    void scratch() {
        NavStuckLog.useForTest(dir, clock);
    }

    @AfterEach
    void real() {
        NavStuckLog.useForTest(null, null);
    }

    private static BotNavigationGraph.Edge jump() {
        return new BotNavigationGraph.Edge(3, 7, BotNavigationGraph.EdgeType.JUMP, new Point(410, 200),
                new Point(520, 120), 400, 430, 8, 0, 0, 0, 0, 600);
    }

    @Test
    void stuckBotsAreCountedPerSpotAndEdge() {
        NavStuckLog.record(NavStuckLog.Kind.UNSTUCK, 100000000, new Point(412, 199), null, jump());
        NavStuckLog.record(NavStuckLog.Kind.UNSTUCK, 100000000, new Point(440, 180), null, jump()); // same 50px spot
        NavStuckLog.record(NavStuckLog.Kind.UNSTUCK, 100000000, new Point(470, 199), null, jump()); // next spot over
        NavStuckLog.record(NavStuckLog.Kind.ROPE_HUNG, 100000000, new Point(412, 199), null, "");

        List<NavStuckLog.Spot> spots = NavStuckLog.todaySpots();
        assertEquals(3, spots.size());
        NavStuckLog.Spot top = spots.get(0);
        assertEquals(2, top.count);
        assertEquals(425, top.x);
        assertEquals(175, top.y);
        assertEquals("JUMP r3>r7 x400..430 right", top.edge);
        assertEquals(List.of("2x UNSTUCK map 100000000 (425,175) JUMP r3>r7 x400..430 right"),
                NavStuckLog.topToday(1));
    }

    @Test
    void countsSurviveARestartAndTheDayTurnsOverIntoItsOwnFile() {
        NavStuckLog.record(NavStuckLog.Kind.GAVE_UP, 101000000, new Point(10, 10), null, jump());
        NavStuckLog.flush();
        assertTrue(Files.isRegularFile(dir.resolve("2026-10-19.json")));

        NavStuckLog.useForTest(dir, clock); // the server restarts the same day
        NavStuckLog.record(NavStuckLog.Kind.GAVE_UP, 101000000, new Point(12, 12), null, jump());
        assertEquals(2, NavStuckLog.todaySpots().get(0).count);

        clock.now = Instant.parse("2026-10-20T00:01:00Z");
        NavStuckLog.record(NavStuckLog.Kind.AIR_FROZEN, 101000000, new Point(12, 12), null, "");
        assertEquals(1, NavStuckLog.todaySpots().size(), "a new day starts empty");
        List<NavStuckLog.Spot> yesterday = NavStuckLog.read(NavStuckLog.fileFor(dir, LocalDate.parse("2026-10-19")));
        assertEquals(1, yesterday.size());
        assertEquals(2, yesterday.get(0).count, "the turn of the day wrote the finished day");
    }

    @Test
    void theReportListsTheWorstSpotsAndKeepsRouteFailuresApart() throws Exception {
        for (int i = 0; i < 5; i++) {
            NavStuckLog.record(NavStuckLog.Kind.UNSTUCK, 100000000, new Point(412, 199), null, jump());
        }
        NavStuckLog.record(NavStuckLog.Kind.NO_PATH, 100000000, new Point(0, 0), null, "no route r1>r9");
        NavStuckLog.record(NavStuckLog.Kind.NO_PATH, 100000000, new Point(0, 0), null, "no route r1>r9");
        NavStuckLog.flush();
        Path learned = dir.resolve("learned");
        Files.createDirectories(learned);
        Files.writeString(learned.resolve("100000000-s100-j100.json"), "{\"version\":"
                + BotNavigationGraphProvider.GRAPH_VERSION + ",\"edges\":{\"3>7:JUMP:400:430:410,200\":[6,1,"
                + System.currentTimeMillis() + "],\"1>2:DROP:0:0:0,0\":[0,9," + System.currentTimeMillis() + "]}}");

        List<NavStuckLog.Spot> spots = NavStuckReport.merge(
                NavStuckLog.read(NavStuckLog.fileFor(dir, LocalDate.parse("2026-10-19"))));
        List<NavStuckReport.FailingEdge> failing = NavStuckReport.failingEdges(learned);
        String report = NavStuckReport.render("2026-10-19", spots, failing, 10);

        assertEquals(1, failing.size(), "only the penalised edge");
        assertTrue(report.contains("| 5 | 100000000 | 425,175 | UNSTUCK | JUMP r3>r7 x400..430 right |"), report);
        assertTrue(report.contains("| 2 | 100000000 | 25,25 | no route r1>r9 |"), report);
        assertTrue(report.contains("| 100000000-s100-j100 | 3>7:JUMP:400:430:410,200 | 6.0 | 1.0 | 8.0 |"), report);
        assertTrue(NavStuckReport.summary("2026-10-19", spots).startsWith(
                "nav stuck 2026-10-19: 5 stuck events on 1 maps, 2 route failures; worst 5x UNSTUCK map 100000000"));
    }
}
