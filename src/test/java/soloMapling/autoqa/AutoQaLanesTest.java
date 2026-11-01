package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 167: several checks in flight at once, on separate lanes of synthetic players. */
class AutoQaLanesTest {

    private final String saved = System.getProperty(AutoQaScheduler.PROPERTY);

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty(AutoQaScheduler.PROPERTY);
        } else {
            System.setProperty(AutoQaScheduler.PROPERTY, saved);
        }
    }

    /** Counts how many steps run at the same time, keeps the peak, and records which lane ran what. */
    static final class Meter {
        final AtomicInteger live = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final Map<String, Integer> laneOf = new ConcurrentHashMap<>();
        final Map<String, Integer> slotOfP1 = new ConcurrentHashMap<>();
        final List<String> order = Collections.synchronizedList(new ArrayList<>());

        Check.Step step(String id) {
            return new Check.Step() {
                public Check.StepResult run(CheckRun r) throws InterruptedException {
                    laneOf.put(id, r.lane());
                    slotOfP1.put(id, r.actor(1).slot());
                    order.add(id);
                    peak.accumulateAndGet(live.incrementAndGet(), Math::max);
                    Thread.sleep(60);
                    live.decrementAndGet();
                    return Check.StepResult.OK;
                }

                public String describe() {
                    return id;
                }
            };
        }
    }

    @Test
    void everyLaneGetsItsOwnGmAndPairOfPlayers() {
        Set<String> names = new HashSet<>();
        for (int lane = 1; lane <= SyntheticPlayers.MAX_LANES; lane++) {
            int gm = SyntheticPlayers.laneSlot(lane, 1);
            int other = SyntheticPlayers.laneSlot(lane, 2);
            assertEquals(lane, SyntheticPlayers.laneOfSlot(gm), "slot " + gm + " belongs to lane " + lane);
            assertEquals(lane, SyntheticPlayers.laneOfSlot(other), "slot " + other + " belongs to lane " + lane);
            assertTrue(SyntheticPlayers.isLaneSlot(gm) && SyntheticPlayers.isGmSlot(gm),
                    "lane " + lane + " starts with a GM in slot " + gm);
            assertFalse(SyntheticPlayers.isGmSlot(other), "lane " + lane + "'s second player is no GM");
            names.add(SyntheticPlayers.nameOf(gm));
            names.add(SyntheticPlayers.nameOf(other));
        }
        assertEquals(2 * SyntheticPlayers.MAX_LANES, names.size(), "no two players share a name: " + names);
        for (int observer = SyntheticPlayers.FIRST_OBSERVER_SLOT; observer < SyntheticPlayers
                .laneSlot(2, 1); observer++) {
            assertFalse(SyntheticPlayers.isLaneSlot(observer), "observer slot " + observer + " must stay out of lanes");
        }
    }

    @Test
    void twoLanesRunTwoChecksAtOnceAndTheResultsKeepCatalogueOrder() {
        System.setProperty(AutoQaScheduler.PROPERTY, "2");
        Meter m = new Meter();
        List<Check> checks = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String id = "t-ln-" + i;
            ids.add(id);
            checks.add(Check.auto(id, "lane check " + i).laneOnly().minutes(1).step(m.step(id))
                    .expect(Expects.probe("ok")).build());
        }
        AutoQaRunnerTest.FakeWorld w = AutoQaRunnerTest.world();
        AutoQaRunner r = new AutoQaRunner(w);

        List<AutoQaRunner.CheckResult> out = r.run(checks, false, null);

        assertEquals(ids, out.stream().map(AutoQaRunner.CheckResult::id).toList(), "results stay in catalogue order");
        assertEquals(2, m.peak.get(), "two 60 ms steps ran at the same time, on two lanes");
        assertEquals(1, w.logouts, "the lanes log out together at the end");
        assertEquals(AutoQaRunner.State.IDLE, r.state());
        assertTrue(m.laneOf.values().stream().allMatch(l -> l >= 1 && l <= 2), m.laneOf.toString());
        for (String id : ids) {
            int lane = m.laneOf.get(id);
            assertEquals(SyntheticPlayers.laneSlot(lane, 1), m.slotOfP1.get(id),
                    id + " ran on lane " + lane + ", so player 1 is that lane's first player");
        }
        assertTrue(m.laneOf.values().stream().distinct().count() > 1, "both lanes were used: " + m.laneOf);
    }

    @Test
    void aMapHoldKeepsTwoChecksApart() {
        System.setProperty(AutoQaScheduler.PROPERTY, "2");
        Meter m = new Meter();
        List<Check> checks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            checks.add(Check.auto("t-lm-" + i, "map check " + i).holds("map:104040000").minutes(1)
                    .step(m.step("t-lm-" + i)).build());
        }
        new AutoQaRunner(AutoQaRunnerTest.world()).run(checks, false, null);
        assertEquals(1, m.peak.get(), "checks that hold one map never overlap");
        assertEquals(4, m.laneOf.size(), "all four still ran");
    }

    @Test
    void aWorldCheckRunsAlone() {
        System.setProperty(AutoQaScheduler.PROPERTY, "2");
        Meter m = new Meter();
        List<Check> checks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            checks.add(Check.auto("t-lw-" + i, "world check " + i).holds(Check.WORLD).minutes(1)
                    .step(m.step("t-lw-" + i)).build());
        }
        new AutoQaRunner(AutoQaRunnerTest.world()).run(checks, false, null);
        assertEquals(1, m.peak.get(), "a check that holds the world runs alone");
    }

    @Test
    void oneLaneStillRunsTheChecksOneAfterAnother() {
        System.clearProperty(AutoQaScheduler.PROPERTY);
        Meter m = new Meter();
        List<Check> checks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            checks.add(Check.auto("t-ls-" + i, "serial check " + i).laneOnly().minutes(1)
                    .step(m.step("t-ls-" + i)).build());
        }
        AutoQaRunnerTest.FakeWorld w = AutoQaRunnerTest.world();
        List<AutoQaRunner.CheckResult> out = new AutoQaRunner(w).run(checks, false, null);
        assertEquals(3, out.size());
        assertEquals(1, m.peak.get(), "the default is today's serial run");
        assertTrue(m.laneOf.values().stream().allMatch(l -> l == 1), m.laneOf.toString());
    }

    /** A step that finishes after {@code ms}, so a test can make lane 2's check finish first. */
    static Check.Step slow(String id, long ms) {
        return new Check.Step() {
            public Check.StepResult run(CheckRun r) throws InterruptedException {
                Thread.sleep(ms);
                return Check.StepResult.OK;
            }

            public String describe() {
                return id;
            }
        };
    }

    /**
     * Ticket-167 follow-up: the lanes call back in the order they finish, and the report, the run line and the
     * release verdict used to read that order (and add to a plain ArrayList from several threads). The run's results
     * are rebuilt from the catalogue: every result is kept, once, in the order the catalogue names.
     */
    @Test
    void laneResultsAreRebuiltInCatalogueOrder() {
        System.setProperty(AutoQaScheduler.PROPERTY, "2");
        List<Check> checks = List.of(
                Check.auto("t-lo-1", "slow check").laneOnly().minutes(1).step(slow("a", 400)).build(),
                Check.auto("t-lo-2", "quick check").laneOnly().minutes(1).step(slow("b", 0)).build());
        List<AutoQaRunner.CheckResult> finished = Collections.synchronizedList(new ArrayList<>());
        AutoQaRunnerTest.FakeWorld w = AutoQaRunnerTest.world();

        new AutoQaRunner(w).run(checks, false, finished::add);
        List<AutoQaRunner.CheckResult> ordered = AutoQaService.orderedByCatalogue(checks, finished);

        assertEquals(List.of("t-lo-2", "t-lo-1"), finished.stream().map(AutoQaRunner.CheckResult::id).toList(),
                "the 0 ms check on lane 2 finished before the 400 ms one on lane 1: the callbacks arrive out of order");
        assertEquals(List.of("t-lo-1", "t-lo-2"), ordered.stream().map(AutoQaRunner.CheckResult::id).toList(),
                "the run's results are in catalogue order, whatever order the lanes finished in");
    }

    /** Out of order, twice over, and a result the selector did not name: still one row per id, catalogue order first. */
    @Test
    void theCatalogueOrderKeepsEveryResultOnce() {
        List<Check> checks = List.of(Check.auto("t-lc-1", "one").laneOnly().build(),
                Check.auto("t-lc-2", "two").laneOnly().build());
        AutoQaRunner.CheckResult a = new AutoQaRunner.CheckResult("t-lc-2", "two", Check.Kind.AUTO,
                Check.Status.PASS, null, List.of(), 1, 2, List.of());
        AutoQaRunner.CheckResult b = new AutoQaRunner.CheckResult("t-lc-1", "one", Check.Kind.AUTO,
                Check.Status.PASS, null, List.of(), 1, 2, List.of());
        AutoQaRunner.CheckResult c = new AutoQaRunner.CheckResult("t-lc-9", "nine", Check.Kind.AUTO,
                Check.Status.PASS, null, List.of(), 1, 2, List.of());

        List<AutoQaRunner.CheckResult> out = AutoQaService.orderedByCatalogue(checks, List.of(a, b, a, c));

        assertEquals(List.of("t-lc-1", "t-lc-2", "t-lc-9"), out.stream().map(AutoQaRunner.CheckResult::id).toList(),
                "catalogue order, then anything the catalogue did not name: nothing dropped, no id twice");
    }

    /**
     * A probe that acts on "both players" must act on the caller's lane: lane 2's check reading slot 1 would read
     * lane 1's player and prove nothing (ticket-167 follow-up).
     */
    @Test
    void aLanePairIsTheCallersOwnTwoPlayers() {
        for (int lane = 1; lane <= SyntheticPlayers.MAX_LANES; lane++) {
            int gm = SyntheticPlayers.laneSlot(lane, 1);
            int other = SyntheticPlayers.laneSlot(lane, 2);
            assertEquals(List.of(gm, other), List.of(SyntheticPlayers.lanePair(gm)[0], SyntheticPlayers.lanePair(gm)[1]),
                    "lane " + lane + "'s GM pairs with its own second player");
            assertEquals(List.of(gm, other), List.of(SyntheticPlayers.lanePair(other)[0], SyntheticPlayers.lanePair(other)[1]),
                    "and the second player of lane " + lane + " pairs with its own GM");
            assertEquals(other, SyntheticPlayers.partnerSlot(gm), "lane " + lane + "'s GM's partner is its second player");
            assertEquals(gm, SyntheticPlayers.partnerSlot(other), "and the other way round");
            if (lane > 1) {
                assertFalse(List.of(SyntheticPlayers.lanePair(gm)[0], SyntheticPlayers.lanePair(gm)[1]).contains(1),
                        "lane " + lane + " never acts on lane 1's players");
            }
        }
        assertEquals(List.of(1, 2), List.of(SyntheticPlayers.lanePair(0)[0], SyntheticPlayers.lanePair(0)[1]),
                "no caller: the original pair");
        assertEquals(2, SyntheticPlayers.partnerSlot(0), "no caller: the second of the original pair");
        assertEquals(2, SyntheticPlayers.partnerSlot(SyntheticPlayers.FIRST_OBSERVER_SLOT),
                "an observer has no lane: the second of the original pair");
    }
}
