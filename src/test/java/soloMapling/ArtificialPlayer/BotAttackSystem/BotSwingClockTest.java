package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* 1.5.0 attack pacing: a bot's due tick fires at the moment its swing ends, once, and never alongside a sweep visit. */
class BotSwingClockTest {

    /* A scheduler that only records what was asked of it; run() fires the tasks in order. */
    static final class Manual implements BotSwingClock.Scheduler {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMs) {
            delays.add(delayMs);
            tasks.add(task);
        }

        void runAll() {
            List<Runnable> now = new ArrayList<>(tasks);
            tasks.clear();
            now.forEach(Runnable::run);
        }
    }

    @BeforeEach
    void on() {
        BotSwingClock.setEnabledForTest(true);
    }

    @AfterEach
    void back() {
        BotSwingClock.setEnabledForTest(null);
    }

    @Test
    void aDueTickIsArmedForTheMomentTheSwingEnds() {
        Manual m = new Manual();
        BotSwingClock clock = new BotSwingClock(m);
        AtomicInteger ticks = new AtomicInteger();
        clock.armAt(10_660, 10_000, ticks::incrementAndGet);
        clock.armAt(10_660, 10_100, ticks::incrementAndGet); // the same moment again: not armed twice
        assertEquals(List.of(660L), m.delays);
        assertEquals(10_660, clock.armedAt());
        m.runAll();
        assertEquals(1, ticks.get());
        assertEquals(0, clock.armedAt());
    }

    @Test
    void nothingIsArmedForThePastTheFarFutureOrWithTheFlagOff() {
        Manual m = new Manual();
        BotSwingClock clock = new BotSwingClock(m);
        clock.armAt(9_000, 10_000, () -> {});
        clock.armAt(10_000, 10_000, () -> {});
        clock.armAt(10_000 + BotSwingClock.MAX_ARM_MS + 1, 10_000, () -> {});
        BotSwingClock.setEnabledForTest(false);
        clock.armAt(10_500, 10_000, () -> {});
        assertTrue(m.delays.isEmpty());
    }

    @Test
    void aReplacedOrDisarmedDueTickDoesNothing() {
        Manual m = new Manual();
        BotSwingClock clock = new BotSwingClock(m);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        clock.armAt(10_660, 10_000, first::incrementAndGet);
        clock.armAt(10_300, 10_000, second::incrementAndGet); // a turn re-armed it sooner
        m.runAll();
        assertEquals(0, first.get());
        assertEquals(1, second.get());

        clock.armAt(11_000, 10_400, first::incrementAndGet);
        clock.disarm(); // the bot left its grind
        m.runAll();
        assertEquals(0, first.get());
    }

    @Test
    void theSweepSkipsABotOnlyWhileItsArmedSwingIsPlaying() {
        Manual m = new Manual();
        BotSwingClock clock = new BotSwingClock(m);
        assertTrue(clock.sweepVisits(10_000, 10_660), "nothing armed: the sweep visits as before");
        clock.armAt(10_660, 10_000, () -> {});
        assertFalse(clock.sweepVisits(10_250, 10_660));
        assertTrue(clock.sweepVisits(10_250, 10_200), "a keydown released early: visited again");
        BotSwingClock.setEnabledForTest(false);
        assertTrue(clock.sweepVisits(10_250, 10_660));
    }

    @Test
    void aSweepVisitNeverRunsAlongsideADueTick() throws Exception {
        BotSwingClock.Scheduler pool = (task, delayMs) -> new Thread(task).start();
        BotSwingClock clock = new BotSwingClock(pool);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        clock.armAt(System.currentTimeMillis() + 50, System.currentTimeMillis(), () -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        AtomicInteger swept = new AtomicInteger();
        assertFalse(clock.trySweep(swept::incrementAndGet), "the due tick holds the bot");
        release.countDown();
        long until = System.currentTimeMillis() + 5_000;
        while (!clock.trySweep(swept::incrementAndGet) && System.currentTimeMillis() < until) {
            Thread.onSpinWait();
        }
        assertEquals(1, swept.get());
    }
}
