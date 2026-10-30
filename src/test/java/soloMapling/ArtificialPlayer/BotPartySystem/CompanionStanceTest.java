package soloMapling.ArtificialPlayer.BotPartySystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionStance.Mode;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.2 ticket 99: formations (nutnnut/Cosmic 8fd725404) and the hold stances, shared by alts and bot friends. */
class CompanionStanceTest {

    @AfterEach
    void clear() {
        CompanionStance.forget(7);
        CompanionStance.forgetLeader(1);
    }

    @Test
    void formationOffsetsAsUpstream() {
        Formation split = new Formation(Formation.Type.SPLIT, 60);
        assertEquals(List.of(60, -60, 120, -120, 180), offsets(split));
        Formation spread = new Formation(Formation.Type.SPREAD, 50);
        assertEquals(List.of(0, 50, -50, 100, -100), offsets(spread));
        assertEquals(List.of(0, 0, 0, 0, 0), offsets(new Formation(Formation.Type.STACK, 0)));
        assertEquals(List.of(-40, -80, -120, -160, -200), offsets(new Formation(Formation.Type.LEFT, 40)));
        assertEquals(List.of(150, 300, 360, 360, 360), offsets(new Formation(Formation.Type.RIGHT, 150)),
                "no companion stands more than a short walk away");
        Formation random = new Formation(Formation.Type.RANDOM, 60);
        assertEquals(offsets(random), offsets(random), "a random place is fixed per slot (no jitter per tick)");
        for (int dx : offsets(random)) {
            assertTrue(Math.abs(dx) <= 60);
        }
    }

    private static List<Integer> offsets(Formation f) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            out.add(f.offsetFor(i));
        }
        return out;
    }

    @Test
    void formationArgsRoundTripAndTheLeaderKeepsOne() {
        Formation f = Formation.parse("left", "loose");
        assertEquals(new Formation(Formation.Type.LEFT, 120), f);
        assertEquals(f, Formation.fromArg(f.toArg()));
        assertNull(Formation.fromArg(Formation.SNAP));
        assertNull(Formation.parse("diamond", null));
        assertEquals(Formation.DEFAULT, CompanionStance.formation(1));
        CompanionStance.setFormation(1, f);
        List<Integer> placed = new ArrayList<>();
        List<IntConsumer> places = List.of(placed::add, placed::add);
        CompanionStance.place(1, places);
        assertEquals(List.of(-120, -240), placed);
    }

    @Test
    void stancesHoldTheirSpotOnTheirMapOnly() {
        Point own = new Point(100, 50);
        Point leader = new Point(400, 50);
        CompanionStance.set(7, Mode.SENTRY, own, leader, 1000);
        assertEquals(Mode.SENTRY, CompanionStance.of(7, 1000).mode);
        assertEquals(own, CompanionStance.holdPoint(CompanionStance.of(7, 1000), own));
        assertEquals(Mode.FOLLOW, CompanionStance.of(7, 2000).mode, "another map: it follows again");
        assertEquals(Mode.FOLLOW, CompanionStance.of(7, 1000).mode, "and the old stance is gone");

        CompanionStance.set(7, Mode.MOVE_HERE, own, leader, 1000);
        assertEquals(leader, CompanionStance.of(7, 1000).spot, "move here: where the leader stood");
        CompanionStance.set(7, Mode.FOLLOW, own, leader, 1000);
        assertNull(CompanionStance.holdPoint(CompanionStance.of(7, 1000), own));
    }

    @Test
    void aPatrolTurnsAtEitherEnd() {
        CompanionStance.Stance s = CompanionStance.set(7, Mode.PATROL, new Point(0, 0), new Point(500, 0), 1);
        int half = CompanionStance.PATROL_HALF_PX;
        assertEquals(500 + half, CompanionStance.holdPoint(s, new Point(500, 0)).x);
        assertEquals(500 - half, CompanionStance.holdPoint(s, new Point(500 + half, 0)).x);
        assertEquals(500 - half, CompanionStance.holdPoint(s, new Point(400, 0)).x);
        assertEquals(500 + half, CompanionStance.holdPoint(s, new Point(500 - half, 0)).x);
    }

    @Test
    void whatEachStanceGoesAfter() {
        CompanionStance.Stance sentry = CompanionStance.set(7, Mode.SENTRY, new Point(0, 0), null, 1);
        assertTrue(CompanionStance.mayChase(sentry, new Point(CompanionStance.SENTRY_REACH_PX, 0)));
        assertFalse(CompanionStance.mayChase(sentry, new Point(CompanionStance.SENTRY_REACH_PX + 1, 0)));
        CompanionStance.Stance stay = CompanionStance.set(7, Mode.STAY, new Point(0, 0), null, 1);
        assertFalse(CompanionStance.mayChase(stay, new Point(10, 0)));
        CompanionStance.Stance patrol = CompanionStance.set(7, Mode.PATROL, new Point(0, 0), new Point(0, 0), 1);
        assertTrue(CompanionStance.mayChase(patrol, new Point(800, 0)));
        assertTrue(CompanionStance.mayChase(CompanionStance.of(99, 1), new Point(800, 0)), "a follower: as before");
    }

    @Test
    void verbsToModes() {
        assertEquals(Mode.SENTRY, CompanionStance.modeOf(BotCommandParser.Verb.SENTRY));
        assertEquals(Mode.FOLLOW, CompanionStance.modeOf(BotCommandParser.Verb.FOLLOW));
        assertNull(CompanionStance.modeOf(BotCommandParser.Verb.TRADE));
    }
}
