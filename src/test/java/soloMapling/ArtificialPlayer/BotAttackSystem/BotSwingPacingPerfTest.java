package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import constants.inventory.EquipType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 1.5.0 attack pacing, point 1: a busy observed map - 40 grinders, each swinging at the cadence its job's attack really
 * has with its weapon (BotSwingPlan.busyMs) - driven the old way (the shared 250 ms combat sweep alone) and the new way
 * (the sweep plus each bot's BotSwingClock).
 *
 * - A deterministic run in simulated time (5 minutes) counts the combat ticks and swings per second and how late each
 *   swing went out after its pose ended. A combat tick is the unit of combat CPU (grind brain + target scan + the
 *   attack call), so the tick count is the cost comparison.
 * - A short real-time run on a real scheduled pool measures the clock's own cost (thread CPU of the pool) with an empty
 *   tick body, and how precisely the due ticks land.
 * The numbers print with -Dbot.pacing.print=true (also in the 1.5.0 notes).
 */
class BotSwingPacingPerfTest {

    static final int GRINDERS = 40;
    static final long SWEEP_MS = 250;

    @BeforeEach
    void data() {
        SkillPoseFixture.install();
        BotAttackTiming.setEnabledForTest(true);
        BotSwingClock.setEnabledForTest(true);
    }

    @AfterEach
    void back() {
        BotAttackTiming.setEnabledForTest(null);
        BotSwingClock.setEnabledForTest(null);
    }

    /* The cadences of 40 grinders: the single-target and AoE attacks of 2nd-4th job classes, with their own weapons. */
    static List<Long> cadences() {
        List<Long> all = new ArrayList<>();
        for (Job job : Job.values()) {
            if (job.getId() % 100 == 0 || job.getId() == 2001 || job.getId() >= 2200) {
                continue; // grinders on a busy map are past 1st job; leave out Evan
            }
            List<EquipType> kinds = BotWeapons.weaponsFor(job);
            if (kinds.isEmpty()) {
                continue;
            }
            int weaponId = kinds.get(0).getValue() * 1000;
            WeaponType wt = BotAttackClientValidityTest.weaponType(weaponId);
            BotAttackConfig.JobAttacks a = BotAttackConfig.resolve(job, wt);
            for (BotAttackProfile p : new BotAttackProfile[]{a.single(), a.aoe()}) {
                if (p != null) {
                    BotSwingPlan plan = BotSwingPlan.of(p, Math.max(1, p.skillLevel), weaponId, false, p.numDamage);
                    if (plan.shots() == 1) { // a held keydown stream is timed by its own chain, not the ticker
                        all.add(plan.busyMs(p.cooldownMs));
                    }
                }
            }
        }
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < GRINDERS; i++) {
            out.add(all.get((i * 7) % all.size()));
        }
        return out;
    }

    record Sim(double ticksPerSec, double swingsPerSec, double meanLateMs, long maxLateMs, double cadenceRatio) {}

    /* Simulated time: one event queue holds the sweeps and the due ticks. */
    static Sim simulate(List<Long> cadences, boolean clockOn, long durationMs) {
        BotSwingClock.setEnabledForTest(clockOn);
        record Ev(long at, long seq, Runnable run) {}
        PriorityQueue<Ev> queue = new PriorityQueue<>((x, y) -> x.at != y.at ? Long.compare(x.at, y.at)
                : Long.compare(x.seq, y.seq));
        long[] now = {0};
        long[] seq = {0};
        BotSwingClock.Scheduler scheduler = (task, delayMs) -> queue.add(new Ev(now[0] + delayMs, seq[0]++, task));
        int n = cadences.size();
        long[] swingEnds = new long[n];
        long[] ticks = {0};
        long[] swings = {0};
        long[] late = {0};
        long[] maxLate = {0};
        long[] lateCount = {0};
        double[] ratio = {0};
        BotSwingClock[] clocks = new BotSwingClock[n];
        Runnable[] tick = new Runnable[n];
        for (int i = 0; i < n; i++) {
            int b = i;
            clocks[b] = new BotSwingClock(scheduler);
            long[] lastSwing = {-1};
            tick[b] = () -> {
                ticks[0]++;
                if (now[0] >= swingEnds[b]) { // the bot's attack call: off cooldown, so it swings
                    if (lastSwing[0] >= 0) {
                        long l = now[0] - swingEnds[b];
                        late[0] += l;
                        maxLate[0] = Math.max(maxLate[0], l);
                        lateCount[0]++;
                        ratio[0] += (double) (now[0] - lastSwing[0]) / cadences.get(b);
                    }
                    lastSwing[0] = now[0];
                    swings[0]++;
                    swingEnds[b] = now[0] + cadences.get(b);
                }
                clocks[b].armAt(swingEnds[b], now[0], tick[b]); // TrainingBot.pacedCombatTick
            };
        }
        for (long t = SWEEP_MS; t <= durationMs; t += SWEEP_MS) {
            queue.add(new Ev(t, seq[0]++, () -> {
                for (int b = 0; b < n; b++) { // TrainingBot.sweepVisit
                    if (clocks[b].sweepVisits(now[0], swingEnds[b])) {
                        clocks[b].trySweep(tick[b]);
                    }
                }
            }));
        }
        while (!queue.isEmpty() && queue.peek().at <= durationMs) {
            Ev e = queue.poll();
            now[0] = e.at;
            e.run.run();
        }
        double secs = durationMs / 1000.0;
        return new Sim(ticks[0] / secs, swings[0] / secs, (double) late[0] / Math.max(1, lateCount[0]), maxLate[0],
                ratio[0] / Math.max(1, lateCount[0]));
    }

    @Test
    void fortyGrindersSwingAtAPlayersPaceForNoMoreCombatTicks() {
        List<Long> cadences = cadences();
        assertEquals(GRINDERS, cadences.size());
        Sim before = simulate(cadences, false, 300_000);
        Sim after = simulate(cadences, true, 300_000);
        print(String.format("cadences (ms): %s", cadences));
        print(String.format("coarse 250 ms sweep: %.1f combat ticks/s, %.1f swings/s, swing late by %.0f ms on average"
                + " (max %d), swing-to-swing %.2fx a player's", before.ticksPerSec(), before.swingsPerSec(),
                before.meanLateMs(), before.maxLateMs(), before.cadenceRatio()));
        print(String.format("sweep + swing clock: %.1f combat ticks/s, %.1f swings/s, swing late by %.0f ms on average"
                + " (max %d), swing-to-swing %.2fx a player's", after.ticksPerSec(), after.swingsPerSec(),
                after.meanLateMs(), after.maxLateMs(), after.cadenceRatio()));

        assertTrue(before.meanLateMs() > 50, "the coarse sweep rounds swings up: " + before);
        assertEquals(0, after.maxLateMs(), "every swing goes out the moment its pose ends");
        assertEquals(1.0, after.cadenceRatio(), 1e-9);
        assertTrue(after.swingsPerSec() > before.swingsPerSec());
        // bounded CPU: fewer combat ticks than the coarse sweep, though every bot now swings more often
        assertTrue(after.ticksPerSec() <= before.ticksPerSec(), before + " vs " + after);
    }

    @Test
    void theClocksOwnCostOnARealPool() throws Exception {
        List<Long> cadences = cadences();
        long windowMs = 3_000;
        Real before = real(cadences, false, windowMs);
        Real after = real(cadences, true, windowMs);
        print(String.format("real pool, %d grinders, %d ms, empty tick body: coarse %.2f ms CPU/s (%d sweeps), with"
                        + " clock %.2f ms CPU/s (%d due ticks); due ticks land %.1f ms after the pose ends on average",
                GRINDERS, windowMs, before.cpuMsPerSec, before.sweeps, after.cpuMsPerSec, after.dueTicks,
                after.meanDueLagMs));
        assertTrue(after.dueTicks > 0);
        assertTrue(after.meanDueLagMs < 20, "due ticks land on time: " + after.meanDueLagMs);
        // 40 bots' scheduling is a sliver of one core: well under 5% (50 ms of CPU per second)
        assertTrue(after.cpuMsPerSec < 50, after.cpuMsPerSec + " ms/s");
    }

    record Real(double cpuMsPerSec, long sweeps, long dueTicks, double meanDueLagMs) {}

    static Real real(List<Long> cadences, boolean clockOn, long windowMs) throws Exception {
        BotSwingClock.setEnabledForTest(clockOn);
        ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<>();
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(10, r -> {
            Thread t = new Thread(r, "pacing-perf");
            threads.add(t);
            return t;
        });
        BotSwingClock.Scheduler scheduler = (task, delayMs) -> pool.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        int n = cadences.size();
        long[] swingEnds = new long[n];
        BotSwingClock[] clocks = new BotSwingClock[n];
        Runnable[] tick = new Runnable[n];
        AtomicLong sweeps = new AtomicLong();
        AtomicLong due = new AtomicLong();
        AtomicLong lag = new AtomicLong();
        for (int i = 0; i < n; i++) {
            int b = i;
            clocks[b] = new BotSwingClock(scheduler);
            tick[b] = () -> {
                long now = System.currentTimeMillis();
                if (now >= swingEnds[b]) {
                    if (swingEnds[b] > 0 && clocks[b].armedAt() == 0 && clockOn) {
                        due.incrementAndGet();
                        lag.addAndGet(now - swingEnds[b]);
                    }
                    swingEnds[b] = now + cadences.get(b);
                }
                clocks[b].armAt(swingEnds[b], now, tick[b]);
            };
        }
        pool.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            sweeps.incrementAndGet();
            for (int b = 0; b < n; b++) {
                if (clocks[b].sweepVisits(now, swingEnds[b])) {
                    clocks[b].trySweep(tick[b]);
                }
            }
        }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS);
        Thread.sleep(windowMs);
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long cpu = 0;
        for (Thread t : threads) {
            long c = mx.getThreadCpuTime(t.getId());
            if (c > 0) {
                cpu += c;
            }
        }
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        long d = due.get();
        return new Real(cpu / 1e6 / (windowMs / 1000.0), sweeps.get(), d, d == 0 ? 0 : (double) lag.get() / d);
    }

    private static void print(String line) {
        if (Boolean.getBoolean("bot.pacing.print")) {
            System.out.println("[pacing] " + line);
        }
    }
}
