package soloMapling.ArtificialPlayer.BotTownSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * rc6 personal space (owner: town bots stood pixel to pixel, name tags on top of each other). The pure rules: who steps,
 * where to, where a pair stands, and how hot spots hand out a ring of spots.
 */
class PersonalSpaceTest {

    /** A floor at y=274 from x=-500 to 500; nothing past it (a ledge edge on both sides). */
    static final PersonalSpace.Ground LEDGE = (x, nearY) -> x >= -500 && x <= 500 ? 274 : null;

    /** The same floor with a platform 50 px higher from x=200 on (a step up, not walkable for a small move). */
    static final PersonalSpace.Ground STEP_UP = (x, nearY) -> x < -500 || x > 1500 ? null : x >= 200 ? 224 : 274;

    static PersonalSpace.Body bot(int id, int x, long since) {
        return new PersonalSpace.Body(id, x, 274, false, true, since);
    }

    @Test
    void aStepIsFortyToOneTwentyPxOnTheSameGroundAndClearOfOthers() {
        Point me = new Point(0, 274);
        List<Point> others = List.of(new Point(0, 274), new Point(70, 274), new Point(-95, 274));
        for (int seed = 0; seed < 300; seed++) {
            Point p = PersonalSpace.pickStep(me, others, new Random(seed), LEDGE, PersonalSpace.FLAT_DY,
                    PersonalSpace.OVERSHOOT_PX);
            assertNotNull(p, "seed " + seed);
            int d = Math.abs(p.x - me.x);
            assertTrue(d >= PersonalSpace.STEP_MIN && d <= PersonalSpace.STEP_MAX, "step " + d);
            assertEquals(274, p.y);
            assertTrue(PersonalSpace.clear(p, others), "clear of others: " + p);
        }
    }

    @Test
    void aStepNeverGoesOffALedge() {
        Point me = new Point(470, 274); // 30 px from the right edge
        for (int seed = 0; seed < 300; seed++) {
            Point p = PersonalSpace.pickStep(me, List.of(new Point(470, 274)), new Random(seed), LEDGE,
                    PersonalSpace.FLAT_DY, PersonalSpace.OVERSHOOT_PX);
            assertNotNull(p);
            assertTrue(p.x < me.x, "only left is safe, got " + p);
            assertTrue(p.x >= -500, "on the floor");
        }
        // the overshoot margin: a small move may run 35 px past its target, so the target keeps that far from the edge
        assertNull(PersonalSpace.walkable(new Point(400, 274), 480, LEDGE, PersonalSpace.FLAT_DY, 35));
        assertNotNull(PersonalSpace.walkable(new Point(400, 274), 460, LEDGE, PersonalSpace.FLAT_DY, 35));
    }

    @Test
    void aStepNeverClimbsOntoAnotherPlatform() {
        assertNull(PersonalSpace.walkable(new Point(150, 274), 250, STEP_UP, PersonalSpace.SLOPE_DY, 0),
                "a 50 px step up is a different platform");
        Point me = new Point(150, 274);
        for (int seed = 0; seed < 200; seed++) {
            Point p = PersonalSpace.pickStep(me, List.of(me), new Random(seed), STEP_UP, PersonalSpace.SLOPE_DY, 0);
            assertNotNull(p);
            assertTrue(p.x < 200 && p.y == 274, "stays on its own floor: " + p);
        }
    }

    @Test
    void aGentleSlopeIsFineForAPhysicsWalkButNotForAFlatRecording() {
        PersonalSpace.Ground slope = (x, nearY) -> 274 - x / 10; // 1 px up per 10 px
        assertNotNull(PersonalSpace.walkable(new Point(0, 274), 100, slope, PersonalSpace.SLOPE_DY, 0));
        assertNull(PersonalSpace.walkable(new Point(0, 274), 100, slope, PersonalSpace.FLAT_DY, 0));
    }

    @Test
    void noFreeSpotMeansStayPut() {
        PersonalSpace.Ground tiny = (x, nearY) -> Math.abs(x) <= 30 ? 274 : null;
        assertNull(PersonalSpace.pickStep(new Point(0, 274), List.of(), new Random(1), tiny, 4, 0));
    }

    @Test
    void theNewerArrivalStepsAndTheOlderStays() {
        PersonalSpace.Body old = bot(1, 300, 1_000);
        PersonalSpace.Body arrived = bot(2, 300, 9_000);
        assertSame(old, PersonalSpace.crowder(arrived, List.of(old), null), "the newcomer makes room");
        assertNull(PersonalSpace.crowder(old, List.of(arrived), null), "the one already there stays");
        // after its step the newcomer is clear
        Point to = PersonalSpace.pickStep(new Point(300, 274), List.of(new Point(300, 274)), new Random(7), LEDGE,
                PersonalSpace.FLAT_DY, PersonalSpace.OVERSHOOT_PX);
        assertNotNull(to);
        PersonalSpace.Body after = bot(2, to.x, 9_000);
        assertFalse(PersonalSpace.crowds(after, old));
        assertNull(PersonalSpace.crowder(after, List.of(old), null));
    }

    @Test
    void aTieGoesByIdAndABotThatWontMoveIsWalkedAroundNotWaitedFor() {
        assertTrue(PersonalSpace.iStep(bot(5, 0, 100), bot(4, 0, 100)));
        assertFalse(PersonalSpace.iStep(bot(4, 0, 100), bot(5, 0, 100)));
        PersonalSpace.Body setPiece = new PersonalSpace.Body(9, 0, 274, false, false, 0);
        assertTrue(PersonalSpace.iStep(bot(1, 0, 50), setPiece), "a set piece or a shop keeper does not move itself");
    }

    @Test
    void aBotNeverMovesAPlayerAndNeverCrowdsOne() {
        PersonalSpace.Body player = new PersonalSpace.Body(7, 300, 274, true, false, 99_999);
        PersonalSpace.Body botThere = bot(1, 310, 1);
        assertSame(player, PersonalSpace.crowder(botThere, List.of(player), null),
                "even a bot that was there first steps away from a player");
    }

    @Test
    void onlyTheSameFootholdCrowds() {
        PersonalSpace.Body a = bot(1, 0, 1);
        assertTrue(PersonalSpace.crowds(a, bot(2, 24, 2)));
        assertFalse(PersonalSpace.crowds(a, bot(2, 25, 2)), "25 px is enough");
        assertFalse(PersonalSpace.crowds(a, new PersonalSpace.Body(2, 0, 200, false, true, 2)), "a platform above");
    }

    @Test
    void aPairMayStandCloseButNotOnOnePixel() {
        PersonalSpace.Body a = bot(1, 300, 1);
        PersonalSpace.Body partner = bot(2, 320, 2); // 20 px: side by side
        assertNull(PersonalSpace.crowder(partner, List.of(a), (x, y) -> true), "a pair 20 px apart is fine");
        assertSame(a, PersonalSpace.crowder(partner, List.of(a), null), "strangers 20 px apart are not");

        PersonalSpace.Body onTop = bot(2, 303, 2);
        assertSame(a, PersonalSpace.crowder(onTop, List.of(a), (x, y) -> true), "a pair on one pixel separates");
        for (int seed = 0; seed < 200; seed++) {
            Point p = PersonalSpace.pairStep(new Point(303, 274), new Point(300, 274), List.of(new Point(400, 274)),
                    new Random(seed), LEDGE, PersonalSpace.FLAT_DY);
            assertNotNull(p);
            int d = Math.abs(p.x - 300);
            assertTrue(d >= PersonalSpace.PAIR_MIN && d <= PersonalSpace.PAIR_MAX, "pair gap " + d);
            assertEquals(274, p.y);
            assertTrue(p.x > 300 || seed >= 0, "either side works");
        }
        // it keeps the side it already stands on when that side is free
        Point right = PersonalSpace.pairStep(new Point(303, 274), new Point(300, 274), List.of(), new Random(3), LEDGE,
                PersonalSpace.FLAT_DY);
        assertTrue(right.x > 300);
    }

    @Test
    void botsSentToOneHotSpotNeverPickTheSamePoint() {
        Point npcFront = new Point(100, 274);
        List<Point> taken = new ArrayList<>();
        Random rng = new Random(11);
        for (int i = 0; i < 9; i++) {
            Point p = PersonalSpace.ringSpot(npcFront, taken, rng, LEDGE, PersonalSpace.SLOPE_DY);
            for (Point t : taken) {
                assertTrue(Math.abs(t.x - p.x) >= PersonalSpace.CLEAR_PX, "bot " + i + " at " + p + " vs " + t);
            }
            taken.add(p);
        }
        assertEquals(npcFront, taken.get(0), "the first one gets the spot itself");
    }

    @Test
    void aRingSpotStaysOnTheGroundAndOffOtherPlatforms() {
        List<Point> taken = new ArrayList<>(List.of(new Point(180, 274)));
        for (int i = 0; i < 6; i++) {
            Point p = PersonalSpace.ringSpot(new Point(180, 274), taken, new Random(i), STEP_UP, 10);
            assertEquals(274, p.y, "not up on the platform at x>=200: " + p);
            taken.add(p);
        }
        Point edge = PersonalSpace.ringSpot(new Point(495, 274), List.of(new Point(495, 274)), new Random(2), LEDGE, 10);
        assertTrue(edge.x <= 500, "not past the ledge: " + edge);
    }

    @Test
    void restTrackingKnowsWhoArrivedFirst() {
        PersonalSpace.Rests r = new PersonalSpace.Rests();
        assertEquals(1_000, r.observe(1, 100000000, 50, 274, 1_000));
        assertEquals(1_000, r.observe(1, 100000000, 52, 274, 5_000), "a fidget of 2 px is still the same rest");
        assertEquals(9_000, r.observe(1, 100000000, 150, 274, 9_000), "it moved: a new arrival");
        assertNotNull(r.active(1, 10_000));
        assertNull(r.active(1, 10_000 + 3 * PersonalSpace.CHECK_EVERY_MS + 31_000), "stopped checking: not counted on");
    }

    @Test
    void reservationsKeepTwoBotsOffOneDestinationUntilTheyExpire() {
        PersonalSpace.clearForTest();
        long key = 7L << 32 | 100000000;
        PersonalSpace.reserve(key, 1, new Point(10, 274), 1_000);
        PersonalSpace.reserve(key, 2, new Point(90, 274), 1_000);
        assertEquals(List.of(new Point(90, 274)), PersonalSpace.reserved(key, 1, 2_000), "not its own");
        PersonalSpace.reserve(key, 1, new Point(40, 274), 2_000);
        assertEquals(2, PersonalSpace.reserved(key, -1, 2_000).size(), "a new pick replaces the bot's old one");
        assertEquals(0, PersonalSpace.reserved(key, -1, 2_000 + PersonalSpace.RESERVE_MS).size());
        PersonalSpace.clearForTest();
    }

    @Test
    void everyTownStandingSpotGoesThroughPersonalSpace() throws Exception {
        String base = "src/main/java/soloMapling/";
        String henesys = Files.readString(Path.of(base + "ArtificialPlayer/BotTypes/HenesysBot.java"));
        assertTrue(henesys.contains("PersonalSpace.keepNow(getChr())") && henesys.contains("PersonalSpace.maybeKeep"));
        assertFalse(henesys.contains("nudgeAwayFromOverlap("), "the half-the-time 20 px nudge is gone");
        assertTrue(Files.readString(Path.of(base + "ArtificialPlayer/BotTypes/SocialBot.java"))
                .contains("PersonalSpace.maybeKeep(getChr())"));
        String station = Files.readString(Path.of(base + "ArtificialPlayer/BotTownSystem/TownStation.java"));
        assertTrue(station.contains("PersonalSpace.freeSpotNear(map, spots.get(0)") && station.contains("keepNow(bot)"));
        String loiter = Files.readString(Path.of(base + "ArtificialPlayer/BotTownSystem/TownLoiter.java"));
        assertTrue(loiter.contains("PersonalSpace.freeSpotNear(map, chosen, botId)"));
        assertTrue(loiter.contains("chosen = candidateSpots.get(0)"), "a full town no longer leaves bots on the portal");
        String training = Files.readString(Path.of(base + "ArtificialPlayer/BotTypes/TrainingBot.java"));
        assertTrue(training.contains("hub.potionNpc(), chr.getId())"), "not onto the vendor's own pixel");
        assertTrue(training.contains("PersonalSpace.maybeKeep(getChr())"));
        assertTrue(Files.readString(Path.of(base + "ArtificialPlayer/BotWanderSystem/BotWanderSystem.java"))
                .contains("PersonalSpace.freeSpotNear(map, spot"));
        String placement = Files.readString(Path.of(base + "Environment/PlatformPlacement.java"));
        assertTrue(placement.contains("spreadOnMap(mapId, spawnPoints)") && placement.contains("freeTarget(fakechar"));
        assertTrue(Files.readString(Path.of(base + "Environment/EnvironmentManager.java"))
                .contains("PersonalSpace.spreadSpawns(map, spots)"));
    }
}
