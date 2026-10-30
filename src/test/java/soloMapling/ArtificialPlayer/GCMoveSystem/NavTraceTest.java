package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 157: the path trace that rides along with a stuck report. */
class NavTraceTest {

    @TempDir
    Path dir;

    private final Point pos = new Point(100, 200);

    @BeforeEach
    void scratch() {
        NavStuckLog.useForTest(dir, Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC));
        NavTrace.useForTest(true, 20);
    }

    @AfterEach
    void real() {
        NavTrace.useForTest(false, 20);
        NavStuckLog.useForTest(null, null);
    }

    private BotMovementState bot() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(1_000_000_001);
        when(chr.getMapId()).thenReturn(100000000);
        when(chr.getPosition()).thenReturn(pos);
        return new BotMovementState(chr, null);
    }

    private static BotNavigationGraph.Edge jump() {
        return new BotNavigationGraph.Edge(3, 7, BotNavigationGraph.EdgeType.JUMP, new Point(410, 200),
                new Point(520, 120), 400, 430, 8, 0, 0, 0, 0, 600);
    }

    @Test
    void offKeepsNothingAndCapturesNothing() {
        NavTrace.useForTest(false, 20);
        BotMovementState entry = bot();
        NavTrace.tick(entry, true, new Point(0, 0));
        assertNull(entry.navTrace, "no ring is made while the switch is off");
        assertNull(NavTrace.capture(entry));
    }

    @Test
    void theRingKeepsTheLastTicksOldestFirst() {
        BotMovementState entry = bot();
        entry.lastNavDecision = "exec";
        entry.navEdge = jump();
        entry.inAir = true;
        entry.moveDir = 1;
        for (int i = 0; i < NavTrace.TICKS + 30; i++) {
            pos.x = i;
            NavTrace.tick(entry, true, new Point(500, 120));
        }
        NavTrace.Ring ring = entry.navTrace;
        assertNotNull(ring);
        List<String> lines = NavTrace.capture(entry);
        assertEquals(NavTrace.TICKS, lines.size());
        assertTrue(lines.get(0).contains("(30,200)"), lines.get(0));
        assertTrue(lines.get(lines.size() - 1).contains("(" + (NavTrace.TICKS + 29) + ",200)"));
        assertTrue(lines.get(lines.size() - 1).startsWith("0ms m100000000 "), lines.get(lines.size() - 1));
        assertTrue(lines.get(0).contains("exec [JUMP r3>r7 x400..430 right] ->(500,120) air R"), lines.get(0));
        // ticking reuses the same ring: no allocation per tick
        NavTrace.tick(entry, true, null);
        assertSame(ring, entry.navTrace);
    }

    @Test
    void droppingToCoarseLetsTheRingGo() {
        BotMovementState entry = bot();
        NavTrace.tick(entry, true, null);
        assertNotNull(entry.navTrace);
        NavTrace.tick(entry, false, null);
        assertNull(entry.navTrace);
        assertNull(NavTrace.capture(entry), "no ring, no trace");
    }

    @Test
    void copiesAreCappedPerHour() {
        NavTrace.useForTest(true, 2);
        BotMovementState entry = bot();
        NavTrace.tick(entry, true, null);
        assertNotNull(NavTrace.capture(entry));
        assertNotNull(NavTrace.capture(entry));
        assertNull(NavTrace.capture(entry), "the third copy this hour is over the cap");
        assertTrue(NavTrace.describe().contains("2/2 this hour"), NavTrace.describe());
        assertTrue(NavTrace.take(System.currentTimeMillis() + 3_600_001L), "a new hour, a new budget");
    }

    @Test
    void theStuckRecordCarriesTheTraceIntoTheDayFileAndTheReport() {
        BotMovementState entry = bot();
        entry.lastNavDecision = "new";
        for (int i = 0; i < 5; i++) {
            NavTrace.tick(entry, true, new Point(300, 200));
        }
        NavStuckLog.record(NavStuckLog.Kind.UNSTUCK, 100000000, new Point(412, 199), null,
                NavStuckLog.describe(jump()), NavTrace.capture(entry));
        NavStuckLog.record(NavStuckLog.Kind.UNSTUCK, 100000000, new Point(420, 190), null, NavStuckLog.describe(jump()));
        NavStuckLog.flush();

        Path file = NavStuckLog.fileFor(dir, LocalDate.parse("2026-10-30"));
        List<NavStuckLog.Traced> traced = NavStuckLog.traced(file);
        assertEquals(1, traced.size(), "nav learning reads the trace back with its spot");
        assertEquals(2, traced.get(0).count());
        assertEquals(5, traced.get(0).trace().size());
        assertTrue(traced.get(0).trace().get(4).contains("new ->(300,200) ground"), traced.get(0).trace().get(4));

        String report = NavStuckReport.render("2026-10-30", NavStuckReport.merge(NavStuckLog.read(file)), List.of(), 10);
        assertTrue(report.contains("## Path traces"), report);
        assertTrue(report.contains("### UNSTUCK map 100000000 (425,175) JUMP r3>r7 x400..430 right, 1 trace"), report);
    }

    @Test
    void noTraceLeavesTheReportAsBefore() {
        NavStuckLog.record(NavStuckLog.Kind.UNSTUCK, 100000000, new Point(412, 199), null, "");
        NavStuckLog.flush();
        Path file = NavStuckLog.fileFor(dir, LocalDate.parse("2026-10-30"));
        assertTrue(NavStuckLog.traced(file).isEmpty());
        assertFalse(NavStuckReport.render("x", NavStuckReport.merge(NavStuckLog.read(file)), List.of(), 10)
                .contains("Path traces"));
    }
}
