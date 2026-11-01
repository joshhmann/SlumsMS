package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 167: which checks may share a running server, and in what order the lanes take them up. */
class AutoQaSchedulerTest {

    private final String saved = System.getProperty(AutoQaScheduler.PROPERTY);
    private final String savedPacking = System.getProperty(AutoQaScheduler.LONGEST_FIRST_PROPERTY);

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty(AutoQaScheduler.PROPERTY);
        } else {
            System.setProperty(AutoQaScheduler.PROPERTY, saved);
        }
        if (savedPacking == null) {
            System.clearProperty(AutoQaScheduler.LONGEST_FIRST_PROPERTY);
        } else {
            System.setProperty(AutoQaScheduler.LONGEST_FIRST_PROPERTY, savedPacking);
        }
    }

    /** A check with a declared time budget, which is what longest-first packing weighs. */
    private static Check budgeted(String id, int minutes, String... holds) {
        Check.Builder b = Check.auto(id, "test " + id).minutes(minutes);
        if (holds.length == 0) {
            b.laneOnly();
        } else {
            b.holds(holds);
        }
        return b.build();
    }

    /** The wall clock of a whole run, in minutes, if the scheduler started checks on {@code cap} lanes. */
    private static int wallClock(List<Check> checks, int cap) {
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) {
            pending.add(i);
        }
        boolean[] free = new boolean[cap + 1];
        Arrays.fill(free, true);
        int[] ends = new int[cap + 1];
        Integer[] held = new Integer[cap + 1];
        Set<String> busy = new HashSet<>();
        int now = 0;
        while (true) {
            AutoQaScheduler.Start start;
            while ((start = AutoQaScheduler.pick(checks, pending, busy, free)) != null) {
                Check c = checks.get(pending.remove(start.position()));
                free[start.lane()] = false;
                held[start.lane()] = checks.indexOf(c);
                busy.addAll(AutoQaScheduler.keys(c));
                ends[start.lane()] = now + c.minutes();
            }
            int next = Integer.MAX_VALUE;
            for (int lane = 1; lane <= cap; lane++) {
                if (!free[lane]) {
                    next = Math.min(next, ends[lane]);
                }
            }
            if (next == Integer.MAX_VALUE) {
                return now;
            }
            for (int lane = 1; lane <= cap; lane++) {
                if (!free[lane] && ends[lane] == next) {
                    free[lane] = true;
                    busy.removeAll(AutoQaScheduler.keys(checks.get(held[lane])));
                    held[lane] = null;
                }
            }
            now = next;
        }
    }

    private static Check check(String id, String... holds) {
        Check.Builder b = Check.auto(id, "test " + id);
        if (holds.length == 0) {
            b.laneOnly();
        } else {
            b.holds(holds);
        }
        return b.build();
    }

    @Test
    void theLaneCountIsThePropertyClampedToOneToFour() {
        System.clearProperty(AutoQaScheduler.PROPERTY);
        assertEquals(1, AutoQaScheduler.parallel(), "no property means today's serial run");
        for (String unset : List.of("", "nonsense")) {
            System.setProperty(AutoQaScheduler.PROPERTY, unset);
            assertEquals(1, AutoQaScheduler.parallel(), "unreadable setting " + unset);
        }
        System.setProperty(AutoQaScheduler.PROPERTY, "0");
        assertEquals(1, AutoQaScheduler.parallel());
        System.setProperty(AutoQaScheduler.PROPERTY, "3");
        assertEquals(3, AutoQaScheduler.parallel());
        System.setProperty(AutoQaScheduler.PROPERTY, "9");
        assertEquals(SyntheticPlayers.MAX_LANES, AutoQaScheduler.parallel(), "clamped to the players that exist");
    }

    @Test
    void aCheckHoldsExactlyWhatItDeclares() {
        assertEquals(Set.of(), AutoQaScheduler.keys(check("t-zz-1")));
        assertEquals(Set.of(Check.WORLD), AutoQaScheduler.keys(check("t-zz-1", Check.WORLD)));
        assertEquals(Set.of("map:100000000", "pq:kpq"),
                AutoQaScheduler.keys(check("t-zz-1", "map:100000000", "pq:kpq")));
    }

    /**
     * The rule the whole feature rests on: a check that holds the world runs alone, and nothing else starts while it
     * runs; two checks that hold no key in common may overlap.
     */
    @Test
    void aWorldCheckRunsAloneAndUnrelatedChecksOverlap() {
        Set<String> empty = new HashSet<>();
        Check world = check("t-zz-1", Check.WORLD);
        Check map = check("t-zz-2", "map:1");
        Check otherMap = check("t-zz-3", "map:2");

        assertTrue(AutoQaScheduler.canStart(world, empty));
        assertFalse(AutoQaScheduler.canStart(map, AutoQaScheduler.keys(world)),
                "no check starts beside a world check");
        assertFalse(AutoQaScheduler.canStart(world, AutoQaScheduler.keys(map)),
                "a world check waits for the lanes to empty");
        assertFalse(AutoQaScheduler.canStart(map, AutoQaScheduler.keys(check("t-zz-4", "map:1"))),
                "two checks on one map never share it");
        assertTrue(AutoQaScheduler.canStart(otherMap, AutoQaScheduler.keys(map)), "different maps may overlap");
        assertTrue(AutoQaScheduler.canStart(check("t-zz-5"), AutoQaScheduler.keys(map)),
                "a lane-only check overlaps anything but a world check");
    }

    @Test
    void theFirstFreeLaneTakesTheFirstStartableCheckInCatalogueOrder() {
        List<Check> checks = List.of(check("t-zz-1", "map:1"), check("t-zz-2", "map:1"), check("t-zz-3"));
        List<Integer> pending = new ArrayList<>(List.of(0, 1, 2));
        boolean[] free = {false, true, true, true};

        AutoQaScheduler.Start first = AutoQaScheduler.pick(checks, pending, new HashSet<>(), free);
        assertNotNull(first);
        assertEquals(1, first.lane(), "lane 1 is busy");
        assertEquals(0, first.position(), "the first check in the list");

        int idx = pending.get(first.position());
        Set<String> busy = AutoQaScheduler.keys(checks.get(idx));
        pending.remove(first.position());
        AutoQaScheduler.Start second = AutoQaScheduler.pick(checks, pending, busy, free);
        assertNotNull(second);
        assertEquals(1, second.position(), "the map:1 check waits behind its twin, t-zz-3 moves up");
        assertEquals(2, (int) pending.get(second.position()));

        Set<String> inFlight = new HashSet<>(List.of(Check.WORLD));
        assertNull(AutoQaScheduler.pick(checks, new ArrayList<>(List.of(2)), inFlight, free),
                "a world check in flight blocks even a lane-only check");
        boolean[] bothBusy = {false, false, false};
        assertNull(AutoQaScheduler.pick(checks, new ArrayList<>(List.of(2)), new HashSet<>(), bothBusy),
                "no free lane, nothing starts");
    }

    /**
     * The annotations are what keeps the proof run honest: a check that flips a world switch must never share the
     * server, and the two Kerning PQ runs (25% of the suite's check time) must never take the same instance at once.
     */
    @Test
    void theCatalogueDeclaresTheSwitchesAndTheSharedInstances() {
        List<String> switchers = new ArrayList<>();
        for (Check c : AutoQaCatalog.all()) {
            for (Check.Step s : c.steps()) {
                String d = s.describe();
                for (String flip : List.of("@botdrama", "@romance", "@quiet", "@guildwar", "@rates", "@megaphone")) {
                    if (d.contains(flip)) {
                        switchers.add(c.id() + " " + flip);
                        assertTrue(c.holds().contains(Check.WORLD), c.id() + " flips " + flip + " but does not hold "
                                + "the world: " + c.holds());
                    }
                }
                assertFalse(d.contains("!warp 100000000") && c.holds().isEmpty(),
                        c.id() + " stages bots in Henesys but holds no map");
            }
        }
        assertFalse(switchers.isEmpty(), "the catalogue still flips world switches");

        Set<String> kpq1 = AutoQaScheduler.keys(AutoQaCatalog.find("t-mx-1"));
        Set<String> kpq2 = AutoQaScheduler.keys(AutoQaCatalog.find("t-mx-2"));
        assertFalse(AutoQaScheduler.disjoint(kpq1, kpq2), "t-mx-1 and t-mx-2 share the Kerning PQ instance");

        for (String id : List.of("t-tw-1", "t-ch-1", "t-bw-1", "t-st-1", "t-ev-1")) {
            assertTrue(AutoQaCatalog.find(id).holds().contains(Check.WORLD), id + " measures the whole world");
        }
        assertTrue(AutoQaCatalog.find("t-af-1").laneOnly(), "a check about its own staged bot overlaps");
    }

    /**
     * Ticket-167 follow-up: a check whose evidence is what bots do on a whole map, or whose probe sets a system
     * property for the whole server, must never share the server with another lane - the second lane's bots would
     * land in the first one's lines, or read the other one's switch.
     */
    @Test
    void checksThatReadMapWideLinesOrSetAGlobalPropertyDeclareIt() {
        java.util.regex.Pattern mapWide = java.util.regex.Pattern.compile(
                "MANNERS|TALLY|WEALTH |TRADE SHOP|clumpsOnMyMap");
        List<String> mapWideChecks = new ArrayList<>();
        List<String> propertyChecks = new ArrayList<>();
        for (Check c : AutoQaCatalog.all()) {
            List<String> said = new ArrayList<>();
            for (Check.Step s : c.steps()) {
                said.add(s.describe());
            }
            for (Check.Expect e : c.expects()) {
                said.add(e.describe());
            }
            for (String d : said) {
                if (mapWide.matcher(d).find()) {
                    mapWideChecks.add(c.id());
                    assertFalse(c.laneOnly(), c.id() + " reads the map's bots' lines (" + d.trim()
                            + ") but declares nothing: a second lane's bots would show up in them");
                }
                for (String global : List.of("jobErrand", "familyPerks")) {
                    if (d.contains(global)) {
                        propertyChecks.add(c.id() + " " + global);
                        assertTrue(c.holds().contains(Check.WORLD), c.id() + " runs " + global
                                + ", which sets a system property for the whole server, but does not hold the world: "
                                + c.holds());
                    }
                }
            }
        }
        assertFalse(mapWideChecks.isEmpty(), "the catalogue still reads map-wide lines: " + mapWideChecks);
        assertFalse(propertyChecks.isEmpty(), "the catalogue still runs probes that set a global property: "
                + propertyChecks);
    }

    /**
     * Ticket 163: longest-first packing is off until asked, unreadable values read as off, and on one lane it never
     * reorders anything (there is nothing to pack, so the run keeps the catalogue order it always had).
     */
    @Test
    void longestFirstIsOffUntilAskedAndDoesNothingOnOneLane() {
        System.clearProperty(AutoQaScheduler.LONGEST_FIRST_PROPERTY);
        assertFalse(AutoQaScheduler.longestFirst(), "no property means the catalogue order");
        for (String unset : List.of("", "nonsense")) {
            System.setProperty(AutoQaScheduler.LONGEST_FIRST_PROPERTY, unset);
            assertFalse(AutoQaScheduler.longestFirst(), "unreadable setting " + unset);
        }
        List<Check> checks = List.of(budgeted("t-zz-1", 2), budgeted("t-zz-2", 9));
        boolean[] oneLane = {false, true};
        assertEquals(0, AutoQaScheduler.pick(checks, new ArrayList<>(List.of(0, 1)), new HashSet<>(), oneLane).position(),
                "one lane takes the catalogue order whatever the switch says");
        System.setProperty(AutoQaScheduler.LONGEST_FIRST_PROPERTY, "true");
        assertEquals(0, AutoQaScheduler.pick(checks, new ArrayList<>(List.of(0, 1)), new HashSet<>(), oneLane).position(),
                "one lane still takes the catalogue order");
    }

    /**
     * Ticket 163: with the switch on, a free lane takes the check with the longest declared budget (earliest first on
     * a tie), because a long check left for last is what stretches the tail of the run. The keys and the world rule
     * are untouched: reordering never lets two checks that share a key overlap.
     */
    @Test
    void longestFirstTakesTheLongestFreeCheckAndKeepsTheWorldRule() {
        System.setProperty(AutoQaScheduler.LONGEST_FIRST_PROPERTY, "true");
        List<Check> checks = List.of(budgeted("t-zz-1", 3), budgeted("t-zz-2", 9), budgeted("t-zz-3", 9),
                budgeted("t-zz-4", 5, "map:1"), budgeted("t-zz-5", 8, "map:1"));
        List<Integer> pending = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        boolean[] free = {false, true, true, true};

        assertEquals(1, AutoQaScheduler.pick(checks, pending, new HashSet<>(), free).position(),
                "the 9-minute check starts first, not the first check in the list");
        Set<String> busy = AutoQaScheduler.keys(checks.get(1));
        pending.remove(1);
        assertEquals(1, AutoQaScheduler.pick(checks, pending, busy, free).position(),
                "the other 9-minute check; both 9s rank above the 8, the map:1 pair waits");

        List<Check> withWorld = List.of(budgeted("t-zz-6", 20, Check.WORLD), budgeted("t-zz-7", 9));
        AutoQaScheduler.Start world = AutoQaScheduler.pick(withWorld, new ArrayList<>(List.of(0, 1)),
                new HashSet<>(), free);
        assertEquals(0, world.position(), "an idle server starts the world check, longest or not");
        assertNull(AutoQaScheduler.pick(withWorld, new ArrayList<>(List.of(0, 1)), AutoQaScheduler.keys(withWorld.get(0)),
                free), "nothing starts beside a running world check, longest-first or not");
        assertNull(AutoQaScheduler.pick(withWorld, new ArrayList<>(List.of(0, 1)), new HashSet<>(),
                new boolean[] {false, false, false}), "no free lane, nothing starts");
    }

    /**
     * Ticket 163, on the real catalogue: with the same declarations and the checks' own declared budgets, longest-first
     * packing finishes the four-lane run well before the catalogue order does. The win is the tail: the world checks
     * cannot overlap anything, so what is left has to be spread evenly over the lanes.
     */
    @Test
    void longestFirstPacksTheRealCatalogueTighter() {
        List<Check> run = new ArrayList<>();
        for (Check c : AutoQaCatalog.all()) {
            if (c.kind() != Check.Kind.MANUAL) {
                run.add(c);
            }
        }
        System.clearProperty(AutoQaScheduler.LONGEST_FIRST_PROPERTY);
        int catalogueOrder = wallClock(run, 4);
        System.setProperty(AutoQaScheduler.LONGEST_FIRST_PROPERTY, "true");
        int packed = wallClock(run, 4);
        assertTrue(packed < catalogueOrder, "longest-first must finish sooner: catalogue order " + catalogueOrder
                + " min, longest-first " + packed + " min, " + run.size() + " checks");
        assertTrue(catalogueOrder - packed >= 10, "the win is worth at least ten minutes of a four-lane run: "
                + catalogueOrder + " -> " + packed + " min");
    }
}
