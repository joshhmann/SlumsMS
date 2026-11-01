package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty(AutoQaScheduler.PROPERTY);
        } else {
            System.setProperty(AutoQaScheduler.PROPERTY, saved);
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
    void checksThatStartTheSameScenarioShareAKeySoTheyNeverCoRun() {
        // 163: a scenario stages bots on the GM's map and its lines are map lines, so two runs of one scenario collide
        // (t-sc-1's bot despawned under it when t-sc-2 stopped; t-bk-3 was refused by the suite's own claim guard and
        // SKIPped). The scheduler can only keep them apart if they share a holds key.
        java.util.regex.Pattern starts = java.util.regex.Pattern.compile("!qa run (\\S+)");
        java.util.Map<String, List<Check>> byScenario = new java.util.LinkedHashMap<>();
        for (Check c : AutoQaCatalog.all()) {
            java.util.Set<String> scenarios = new java.util.LinkedHashSet<>();
            for (Check.Step s : c.steps()) {
                java.util.regex.Matcher m = starts.matcher(s.describe());
                if (m.find()) {
                    scenarios.add(m.group(1));
                }
            }
            for (String scenario : scenarios) {
                byScenario.computeIfAbsent(scenario, k -> new ArrayList<>()).add(c);
            }
        }
        // the scenarios two or more checks start and that were seen to collide at cap 4 (2026-10-31): cv-4/cv-12 and
        // the pq runs are untouched here (they passed) and stay a known pair for their own ticket.
        List<String> shared = new ArrayList<>();
        for (var e : byScenario.entrySet()) {
            if (e.getValue().size() < 2 || !List.of("sc-2", "bk-1").contains(e.getKey())) {
                continue;
            }
            java.util.Set<String> common = new HashSet<>(e.getValue().getFirst().holds());
            for (Check c : e.getValue()) {
                common.retainAll(c.holds());
            }
            List<String> ids = e.getValue().stream().map(Check::id).toList();
            shared.add(e.getKey() + " " + ids);
            assertFalse(common.isEmpty(), "checks " + ids + " all start scenario " + e.getKey()
                    + " but share no holds key, so two lanes can run it at once: " + ids);
        }
        assertFalse(shared.isEmpty(), "the catalogue still has two checks per scenario: " + shared);
    }

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
}
