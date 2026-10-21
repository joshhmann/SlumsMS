package soloMapling.qa;

import org.junit.jupiter.api.Test;
import server.movement.AbsoluteLifeMovement;
import server.movement.LifeMovementFragment;
import server.movement.RelativeLifeMovement;
import server.movement.TeleportMovement;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.qa.SlideMeter.Frag.abs;
import static soloMapling.qa.SlideMeter.Frag.rel;

/** mv-1's analysis: attack windows over a timeline of movement fragments. */
class SlideMeterTest {
    static final int STAND_RIGHT = 4, STAND_LEFT = 5, WALK_RIGHT = 2, JUMP_RIGHT = 6;
    static final int FH = 12;

    @Test
    void standingAttackOnTheGroundDoesNotSlide() {
        SlideMeter m = new SlideMeter(600, 5);
        m.move(1000, List.of(abs(0, 100, 50, 200, 0, 0, FH, STAND_RIGHT)));
        assertTrue(m.attack(1000, 1001004, "close", 50, 200, STAND_RIGHT, 100));
        m.move(1300, List.of(abs(0, 150, 50, 200, 0, 0, FH, STAND_RIGHT), abs(0, 150, 50, 200, 0, 0, FH, STAND_RIGHT)));
        SlideMeter.Result r = m.flush().get(0);
        assertEquals(0, r.maxSlide());
        assertEquals(0, r.netDx());
        assertEquals(2, r.samples());
        assertTrue(r.grounded());
        assertFalse(r.directionHeld());
        assertEquals(FH, r.attack().fh0());
    }

    @Test
    void holdingADirectionWhileAttackingShowsTheSlide() {
        SlideMeter m = new SlideMeter(600, 5);
        m.move(1000, List.of(abs(0, 200, 100, 200, 125, 0, FH, WALK_RIGHT)));
        m.attack(1000, 0, "close", 100, 200, WALK_RIGHT, 130);
        // the client keeps moving right for the whole attack
        m.move(1500, List.of(abs(0, 200, 125, 200, 125, 0, FH, WALK_RIGHT),
                abs(0, 200, 160, 200, 90, 0, FH, STAND_RIGHT), abs(0, 100, 170, 200, 0, 0, FH, STAND_RIGHT)));
        SlideMeter.Result r = m.flush().get(0);
        assertEquals(70, r.maxSlide());
        assertEquals(70, r.netDx());
        assertEquals(0, r.dy());
        assertEquals(125, r.maxAbsXv());
        assertTrue(r.directionHeld());
        assertTrue(r.grounded());
        assertFalse(r.airborne());
        assertEquals(130, r.attack().speed());
    }

    @Test
    void fragmentTimesCountBackFromTheArrivalAndOnlyTheWindowCounts() {
        SlideMeter m = new SlideMeter(300, 5);
        m.move(900, List.of(abs(0, 100, 0, 0, 0, 0, FH, STAND_LEFT)));
        m.attack(1000, 0, "magic", 0, 0, STAND_LEFT, 100);
        // ends at 1000 (before/at the attack: out), 1200 (in), 1400 (after the 300 ms window: out)
        m.move(1400, List.of(abs(0, 0, -40, 0, 0, 0, FH, STAND_LEFT), abs(0, 200, -10, 0, 0, 0, FH, STAND_LEFT),
                abs(0, 200, -300, 0, 0, 0, FH, STAND_LEFT)));
        SlideMeter.Result r = m.flush().get(0);
        assertEquals(1, r.samples());
        assertEquals(10, r.maxSlide());
        assertEquals(-10, r.netDx());
    }

    @Test
    void maxSlideIsTheFurthestPointNotTheEnd() {
        SlideMeter m = new SlideMeter(600, 5);
        m.move(0, List.of(abs(0, 10, 0, 0, 0, 0, FH, STAND_RIGHT)));
        m.attack(1000, 0, "ranged", 0, 0, STAND_RIGHT, 100);
        m.move(1400, List.of(abs(0, 200, 30, -5, 50, 0, FH, STAND_RIGHT), abs(0, 200, 5, 0, -50, 0, FH, STAND_RIGHT)));
        SlideMeter.Result r = m.flush().get(0);
        assertEquals(30, r.maxSlide());
        assertEquals(5, r.netDx());
        assertEquals(0, r.dy());
        assertEquals(50, r.maxAbsXv());
    }

    @Test
    void aJumpIsAirNotGround() {
        SlideMeter m = new SlideMeter(600, 5);
        m.move(1000, List.of(abs(0, 100, 0, 0, 0, 0, 0, JUMP_RIGHT)));
        m.attack(1000, 0, "close", 0, 0, JUMP_RIGHT, 100);
        m.move(1400, List.of(rel(SlideMeter.TYPE_JUMP, 200, JUMP_RIGHT), abs(0, 200, 80, 30, 120, 300, 0, JUMP_RIGHT)));
        SlideMeter.Result r = m.flush().get(0);
        assertFalse(r.grounded());
        assertTrue(r.airborne());
        assertEquals(0, r.attack().fh0());
        assertEquals(80, r.maxSlide());
        assertEquals(30, r.dy());
        assertTrue(SlideMeter.format(r).contains("fh 0 (air)"));
    }

    @Test
    void noMovementAtAllIsNoSlideAndUnknownFoothold() {
        SlideMeter m = new SlideMeter(600, 5);
        m.attack(1000, 2001005, "magic", 10, 10, STAND_RIGHT, 100);
        SlideMeter.Result r = m.flush().get(0);
        assertEquals(0, r.samples());
        assertEquals(0, r.maxSlide());
        assertEquals(-1, r.attack().fh0());
        assertFalse(r.grounded());
        String line = SlideMeter.format(r);
        assertTrue(line.contains("fh ?"), line);
        assertTrue(line.contains("no movement sent"), line);
    }

    @Test
    void resultsAreDueAfterTheWindowAndTheGraceAndTheMeterStopsAtItsCount() {
        SlideMeter m = new SlideMeter(600, 2);
        assertTrue(m.attack(1000, 0, "close", 0, 0, STAND_RIGHT, 100));
        assertTrue(m.due(1000 + 600 + SlideMeter.GRACE_MS - 1).isEmpty());
        assertEquals(1, m.due(1000 + 600 + SlideMeter.GRACE_MS).size());
        assertFalse(m.finished());
        assertTrue(m.attack(5000, 0, "close", 0, 0, STAND_RIGHT, 100));
        assertFalse(m.attack(5100, 0, "close", 0, 0, STAND_RIGHT, 100));
        assertFalse(m.finished());
        assertEquals(1, m.flush().size());
        assertTrue(m.finished());
        assertTrue(m.takeSummary());
        assertFalse(m.takeSummary());
        assertEquals(2, m.results().size());
    }

    @Test
    void summaryCountsSlideAndHeldDirection() {
        SlideMeter.Attack ground = new SlideMeter.Attack(1, 0, 0, "close", 0, 0, STAND_RIGHT, 100, FH, 600);
        SlideMeter.Attack air = new SlideMeter.Attack(2, 0, 0, "close", 0, 0, JUMP_RIGHT, 100, 0, 600);
        List<SlideMeter.Result> results = List.of(
                new SlideMeter.Result(ground, 2, 0, 0, 0, 0, false, false, false, true),
                new SlideMeter.Result(ground, 2, 60, 60, 0, 125, true, false, true, true),
                new SlideMeter.Result(air, 2, 90, 90, 20, 125, true, true, true, false));
        SlideMeter.Summary s = SlideMeter.summarize(results);
        assertEquals(3, s.count());
        assertEquals(2, s.grounded());
        assertEquals(50.0, s.avgSlide(), 1e-9);
        assertEquals(90, s.maxSlide());
        assertEquals(30.0, s.avgGroundedSlide(), 1e-9);
        assertEquals(2, s.held());
        assertEquals(1, s.heldGroundedSliding());
        List<String> lines = SlideMeter.format(s);
        assertTrue(lines.get(0).contains("3 attacks (2 grounded)"), lines.get(0));
        assertTrue(lines.get(1).contains("2/3 (67%)"), lines.get(1));
        assertEquals(List.of("mv-1 summary: no attacks measured."), SlideMeter.format(SlideMeter.summarize(List.of())));
    }

    @Test
    void movementFragmentsBecomeMeterFrags() {
        AbsoluteLifeMovement a = new AbsoluteLifeMovement(0, new Point(10, 20), 120, WALK_RIGHT);
        a.setFh(FH);
        a.setPixelsPerSecond(new Point(125, -3));
        TeleportMovement t = new TeleportMovement(4, new Point(99, 99), STAND_LEFT);
        List<LifeMovementFragment> in = List.of(a, new RelativeLifeMovement(1, new Point(5, -10), 90, JUMP_RIGHT), t);
        List<SlideMeter.Frag> out = MoveTrace.frags(in);
        assertEquals(abs(0, 120, 10, 20, 125, -3, FH, WALK_RIGHT), out.get(0));
        assertEquals(rel(1, 90, JUMP_RIGHT), out.get(1));
        assertFalse(out.get(2).absolute());
        assertEquals(STAND_LEFT, out.get(2).stance());
    }
}
