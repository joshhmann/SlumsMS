package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
