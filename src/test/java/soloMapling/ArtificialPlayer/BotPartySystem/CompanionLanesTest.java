package soloMapling.ArtificialPlayer.BotPartySystem;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.3 s-lanes: each companion of a leader takes a spot of its own, spread over the map's spawn ground - distinct,
 * apart, left to right in party order, and the same spots every time the order is given.
 */
class CompanionLanesTest {

    private static final CompanionLanes.Reach SCREEN = CompanionLanes.Reach.SCREEN;
    private static final CompanionLanes.Reach MAP = CompanionLanes.Reach.MAP;
    private static final BiPredicate<Point, Point> ONE_PLATFORM = (a, b) -> false;

    private static Point p(int x, int y) {
        return new Point(x, y);
    }

    private static void assertApart(List<Point> spots) {
        for (int i = 0; i < spots.size(); i++) {
            for (int j = i + 1; j < spots.size(); j++) {
                Point a = spots.get(i);
                Point b = spots.get(j);
                assertTrue(Math.abs(a.x - b.x) >= CompanionLanes.MIN_SPACING_PX
                                || Math.abs(a.y - b.y) >= CompanionLanes.MIN_SPACING_PX,
                        "spots " + i + " and " + j + " are on top of each other: " + a + " " + b);
            }
        }
    }

    @Test
    void everyCompanionGetsItsOwnSpotLeftToRight() {
        // a flat row of spawn ground: 5 companions cannot stand within 400 px of each other on 1600 px
        List<Point> ground = List.of(p(-700, 100), p(-350, 100), p(0, 100), p(350, 100), p(700, 100));
        List<Point> spots = CompanionLanes.assign(ground, p(0, 100), SCREEN, 3, ONE_PLATFORM);
        assertEquals(3, spots.size());
        assertEquals(List.of(p(-700, 100), p(0, 100), p(700, 100)), spots, "party order: leftmost lane first");
        assertApart(spots);
        assertTrue(Math.abs(spots.get(0).x - spots.get(2).x) > 0);
    }

    @Test
    void denseSpawnGroundStillKeepsTheSpacing() {
        List<Point> ground = new ArrayList<>();
        for (int x = -800; x <= 800; x += 50) {
            ground.add(p(x, 100));
        }
        List<Point> spots = CompanionLanes.assign(ground, p(0, 100), SCREEN, 4, ONE_PLATFORM);
        assertEquals(4, spots.size(), "room for four lanes at 400 px apart in a 1600 px window");
        assertApart(spots);
        for (int i = 1; i < spots.size(); i++) {
            assertTrue(spots.get(i).x > spots.get(i - 1).x, "left to right: " + spots);
        }
    }

    @Test
    void stackedPlatformsMayStandAboveOneAnother() {
        // two platforms at the same x: the second lane prefers the fresh platform over the crowded one
        List<Point> ground = List.of(p(0, 100), p(40, 100), p(0, 300), p(40, 300));
        BiPredicate<Point, Point> stacked = (a, b) -> a.y != b.y;
        List<Point> spots = CompanionLanes.assign(ground, p(0, 100), SCREEN, 2, stacked);
        assertEquals(2, spots.size());
        assertTrue(spots.get(0).y == 100 && spots.get(1).y == 300,
                "one lane per platform when the map has two: " + spots);
    }

    @Test
    void aScreenReachSpreadsOnlyAroundTheOwnerAndAMapReachEverywhere() {
        List<Point> ground = List.of(p(-1500, 100), p(-700, 100), p(0, 100), p(700, 100), p(1500, 100));
        List<Point> screen = CompanionLanes.assign(ground, p(0, 100), SCREEN, 5, ONE_PLATFORM);
        assertEquals(3, screen.size(), "only the ground within a screen of the owner: " + screen);
        for (Point s : screen) {
            assertTrue(Math.abs(s.x) <= CompanionLanes.HALF_WINDOW_PX, s + " is off the owner's screen");
        }
        List<Point> map = CompanionLanes.assign(ground, p(0, 100), MAP, 5, ONE_PLATFORM);
        assertEquals(5, map.size(), "the whole map: " + map);
        assertEquals(-1500, map.get(0).x);
        assertEquals(1500, map.get(4).x);
        assertEquals(800, CompanionLanes.HALF_WINDOW_PX, "a screen: 800 px either side");
        assertTrue(CompanionLanes.MIN_SPACING_PX >= 2 * CompanionStance.SENTRY_REACH_PX,
                "two lanes are never within two sentry reaches of each other when there is room");
    }

    @Test
    void theSameMapGivesTheSameLanesWhateverOrderTheSpawnsComeIn() {
        List<Point> ground = new ArrayList<>();
        for (int x = -900; x <= 900; x += 60) {
            ground.add(p(x, 100));
            ground.add(p(x, 100));
        }
        List<Point> first = CompanionLanes.assign(ground, p(30, 100), SCREEN, 4, ONE_PLATFORM);
        assertEquals(4, first.size());
        Random rnd = new Random(163);
        for (int i = 0; i < 6; i++) {
            List<Point> shuffled = new ArrayList<>(ground);
            Collections.shuffle(shuffled, rnd);
            assertEquals(first, CompanionLanes.assign(shuffled, p(30, 100), SCREEN, 4, ONE_PLATFORM),
                    "re-issuing the order must not reshuffle the lanes");
        }
    }

    @Test
    void noRoomNoLanesTheCompanionsKeepFollowing() {
        assertEquals(List.of(), CompanionLanes.assign(List.of(), p(0, 0), SCREEN, 3, ONE_PLATFORM));
        assertEquals(List.of(), CompanionLanes.assign(List.of(p(5000, 100)), p(0, 100), SCREEN, 2, ONE_PLATFORM),
                "spawn ground off the owner's screen: nothing to hold there");
        List<Point> one = CompanionLanes.assign(List.of(p(0, 100)), p(0, 100), MAP, 3, ONE_PLATFORM);
        assertEquals(1, one.size(), "one spot: one lane, the other companions follow");
    }

    @Test
    void spotsComeFromTheMapsSpawnGroundOnItsFootholds() {
        MapleMap map = mock(MapleMap.class);
        when(map.getMonsterSpawnPositions()).thenReturn(List.of(p(-200, 100), p(200, 100), p(600, 100)));
        List<CompanionLanes.Lane> lanes = CompanionLanes.lanes(map, p(0, 100), SCREEN, 2);
        assertEquals(2, lanes.size(), "two lanes from the spawn ground: " + lanes);
        assertEquals(-200, lanes.get(0).spot().x);
        assertEquals(SCREEN, lanes.get(0).reach(), "an explicit screen reach is a screen lane");
        assertTrue(Math.abs(lanes.get(1).spot().x - lanes.get(0).spot().x) >= CompanionLanes.MIN_SPACING_PX,
                "the second lane keeps its distance rather than stack on the first: " + lanes);
    }

    @Test
    void aMapWithoutSpawnGroundGivesNoLanes() {
        MapleMap map = mock(MapleMap.class);
        assertEquals(List.of(), CompanionLanes.lanes(map, p(0, 100), SCREEN, 3));
        assertEquals(List.of(), CompanionLanes.lanes(null, p(0, 100), SCREEN, 3));
        assertEquals(List.of(), CompanionLanes.lanes(map, p(0, 100), SCREEN, 0));
    }

    @Test
    void reachWords() {
        assertEquals(MAP, CompanionLanes.Reach.parse("map"));
        assertEquals(MAP, CompanionLanes.Reach.parse(" Whole "));
        assertEquals(SCREEN, CompanionLanes.Reach.parse("screen"));
        assertEquals(SCREEN, CompanionLanes.Reach.parse("nearby"));
        assertEquals(CompanionLanes.Reach.AUTO, CompanionLanes.Reach.parse("auto"));
        assertNull(CompanionLanes.Reach.parse("pot"));
        assertNull(CompanionLanes.Reach.parse(null));
    }

    // ---- the automatic rule (owner, 2026-11-01): a screen each, and only the extras of a party of two or more wide

    private static List<CompanionLanes.Lane> auto(List<Point> ground, Point owner, int count) {
        return CompanionLanes.spreads(ground, owner, CompanionLanes.Reach.AUTO, count, ONE_PLATFORM);
    }

    @Test
    void oneCompanionAloneNeverGoesWiderThanTheScreen() {
        // the screen is cramped and there is ground across the map: still no lane out there for a lone companion
        List<Point> ground = List.of(p(-40, 100), p(20, 100), p(3000, 100), p(4000, 100));
        List<CompanionLanes.Lane> lanes = auto(ground, p(0, 100), 1);
        assertEquals(1, lanes.size(), "one lane: " + lanes);
        assertEquals(SCREEN, lanes.get(0).reach(), "one companion alone never goes across the map by default");
        assertTrue(Math.abs(lanes.get(0).spot().x) <= CompanionLanes.HALF_WINDOW_PX,
                lanes.get(0).spot() + " is off the owner's screen");
        assertEquals(List.of(), auto(List.of(p(3000, 100), p(4000, 100)), p(0, 100), 1),
                "nothing on screen and a lone companion: no lane rather than one across the map");
    }

    @Test
    void aPartyTooBigForOneScreenSendsOnlyTheExtrasAcrossTheMap() {
        // two lanes fit this screen (-500 and 500 are 1000 px apart); the third companion has no screen spot left
        List<Point> ground = List.of(p(-2500, 100), p(-500, 100), p(500, 100), p(2500, 100));
        List<CompanionLanes.Lane> lanes = auto(ground, p(0, 100), 3);
        assertEquals(3, lanes.size(), "everybody has a lane, the third one wide: " + lanes);
        assertEquals(List.of(p(-500, 100), p(500, 100)), List.of(lanes.get(0).spot(), lanes.get(1).spot()),
                "party order: the first two hold the screen lanes, left to right");
        assertEquals(SCREEN, lanes.get(0).reach());
        assertEquals(SCREEN, lanes.get(1).reach());
        assertEquals(CompanionLanes.Reach.MAP, lanes.get(2).reach(),
                "only the companion the screen could not seat goes wide");
        assertTrue(Math.abs(lanes.get(2).spot().x) > CompanionLanes.HALF_WINDOW_PX,
                "the extra lane is off screen: " + lanes.get(2).spot());
        for (int i = 0; i < 2; i++) {
            assertClearOfTheScreenLanes(lanes.get(2).spot(), lanes.get(i).spot());
        }
    }

    /** The wide lane keeps its distance from the lanes the screen already holds. */
    private static void assertClearOfTheScreenLanes(Point wide, Point here) {
        assertTrue(Math.abs(wide.x - here.x) >= CompanionLanes.MIN_SPACING_PX, wide + " stacks on " + here);
    }

    @Test
    void aPartyThatFitsOnOneScreenIsNeverSplit() {
        List<Point> ground = List.of(p(-700, 100), p(-350, 100), p(0, 100), p(350, 100), p(700, 100),
                p(3000, 100), p(4000, 100));
        List<CompanionLanes.Lane> lanes = auto(ground, p(0, 100), 3);
        assertEquals(3, lanes.size());
        for (CompanionLanes.Lane lane : lanes) {
            assertEquals(SCREEN, lane.reach(), "there is room on screen: " + lane);
        }
    }

    @Test
    void anExplicitWordStillForcesItsOwnReach() {
        List<Point> ground = List.of(p(-2500, 100), p(-500, 100), p(500, 100), p(2500, 100));
        List<CompanionLanes.Lane> byMap = CompanionLanes.spreads(ground, p(0, 100), MAP, 3, ONE_PLATFORM);
        assertEquals(3, byMap.size(), "lanes map: three lanes across the whole map: " + byMap);
        for (CompanionLanes.Lane lane : byMap) {
            assertEquals(CompanionLanes.Reach.MAP, lane.reach(), "an explicit map reach: " + lane);
        }
        List<CompanionLanes.Lane> byScreen = CompanionLanes.spreads(ground, p(0, 100), SCREEN, 3, ONE_PLATFORM);
        assertEquals(2, byScreen.size(), "lanes screen: what the screen seats, no extras: " + byScreen);
        for (CompanionLanes.Lane lane : byScreen) {
            assertEquals(SCREEN, lane.reach(), "an explicit screen reach: " + lane);
        }
    }

    @Test
    void theAutomaticRuleIsTheSwitchDefault() {
        String was = System.getProperty("slums.alts.lanes.reach");
        try {
            System.clearProperty("slums.alts.lanes.reach");
            assertEquals(CompanionLanes.Reach.AUTO, CompanionLanes.Reach.configured(), "no switch: the rule");
            System.setProperty("slums.alts.lanes.reach", "map");
            assertEquals(CompanionLanes.Reach.MAP, CompanionLanes.Reach.configured());
            System.setProperty("slums.alts.lanes.reach", "screen");
            assertEquals(SCREEN, CompanionLanes.Reach.configured());
            System.setProperty("slums.alts.lanes.reach", "auto");
            assertEquals(CompanionLanes.Reach.AUTO, CompanionLanes.Reach.configured());
            System.setProperty("slums.alts.lanes.reach", "pot");
            assertEquals(CompanionLanes.Reach.AUTO, CompanionLanes.Reach.configured(), "not a reach: the rule");
        } finally {
            if (was == null) {
                System.clearProperty("slums.alts.lanes.reach");
            } else {
                System.setProperty("slums.alts.lanes.reach", was);
            }
        }
    }

    @Test
    void theAutomaticRuleIsDeterministicToo() {
        List<Point> ground = new ArrayList<>(List.of(p(-2500, 100), p(-500, 100), p(500, 100), p(2500, 100),
                p(-1500, 100), p(1500, 100)));
        List<CompanionLanes.Lane> first = auto(ground, p(0, 100), 4);
        assertEquals(4, first.size(), first.toString());
        Random rnd = new Random(163);
        for (int i = 0; i < 6; i++) {
            Collections.shuffle(ground, rnd);
            assertEquals(first, auto(ground, p(0, 100), 4), "re-issuing the order must not reshuffle the lanes");
        }
    }

    @Test
    void theLanesAnswerSaysHowManyStayedHereAndHowManyWentWide() {
        assertEquals("k, 2 lanes spread out", PartyBotOrders.lanesSaid(2, 0, false));
        assertEquals("k, 1 lane across the map", PartyBotOrders.lanesSaid(0, 1, false));
        assertEquals("k, 2 lanes here, 1 across the map", PartyBotOrders.lanesSaid(2, 1, false));
        assertEquals("k, 2 lanes here, 1 across the map (no room for more)",
                PartyBotOrders.lanesSaid(2, 1, true));
        assertEquals("k, 1 lane spread out (no room for more)", PartyBotOrders.lanesSaid(1, 0, true));
    }
}
