package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b269 fix 6: the boot fill hands logins to the bot-db threads at a pace — at most N at once and at most M a second —
 * instead of all 3,000 at once with a zero spread, and offering one never blocks the caller.
 */
class BootFillPaceTest {

    @Test
    void neverMoreThanTheCapIsInFlight() {
        List<Integer> started = new CopyOnWriteArrayList<>();
        BotPopulationDirector.LoginPace pace = new BotPopulationDirector.LoginPace(2, 0, started::add);

        for (int botId = 1; botId <= 6; botId++) {
            assertTrue(pace.offer(botId));
        }
        assertEquals(List.of(1, 2), started, "only the cap starts; the rest wait for done()");
        assertEquals(6, pace.size());

        pace.done(1);
        assertEquals(List.of(1, 2, 3), started);
        pace.done(2);
        pace.done(3);
        assertEquals(List.of(1, 2, 3, 4, 5), started);
        pace.done(4);
        pace.done(5);
        assertEquals(List.of(1, 2, 3, 4, 5, 6), started);
        assertEquals(1, pace.size(), "the last login is still in flight");
    }

    @Test
    void offeringNeverBlocksEvenWhenEverySlotIsBusy() {
        BotPopulationDirector.LoginPace pace = new BotPopulationDirector.LoginPace(1, 0, botId -> { });
        assertTrue(pace.offer(1));
        long started = System.nanoTime();
        for (int botId = 2; botId <= 500; botId++) {
            pace.offer(botId);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertEquals(500, pace.size());
        assertTrue(ms < 500, "500 offers took " + ms + " ms: the caller must not wait for a login");
    }

    @Test
    void aBotIsQueuedOnceHoweverManyTicksPlanIt() {
        BotPopulationDirector.LoginPace pace = new BotPopulationDirector.LoginPace(1, 0, botId -> { });
        assertTrue(pace.offer(7));
        assertFalse(pace.offer(7), "a login already waiting is not queued again");
        assertEquals(1, pace.size());
    }

    @Test
    void startsAreSpreadAtTheRateTheFillIsGiven() throws Exception {
        List<Long> startsAt = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        BotPopulationDirector.LoginPace pace = new BotPopulationDirector.LoginPace(8, 4, botId -> {
            startsAt.add(System.nanoTime());
            three.countDown();
        });

        for (int botId = 1; botId <= 3; botId++) {
            pace.offer(botId);
        }
        assertTrue(three.await(5, TimeUnit.SECONDS), "three logins at 4 a second finish well inside 5 s");
        long second = (startsAt.get(1) - startsAt.get(0)) / 1_000_000;
        long third = (startsAt.get(2) - startsAt.get(0)) / 1_000_000;
        assertTrue(second >= 150, "the second start waits about 250 ms at 4 a second, waited " + second + " ms");
        assertTrue(third >= 300, "the third start waits about 500 ms, waited " + third + " ms");
    }
}
