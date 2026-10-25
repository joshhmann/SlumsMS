package soloMapling.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order and the limits of a shutdown's writes (1.5.0 release rehearsal: the bot sweep ran ahead of the real
 * players' final save, hung on the bots' share of the pool and was killed by systemd).
 */
class ShutdownSavesTest {

    @Test
    void everyRealPlayerIsSavedBeforeAnyBotWorkStarts() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<Integer> players = List.of(1, 2, 3, 4, 5, 6, 7);
        ShutdownSaves.run(new ShutdownSaves.Steps() {
            @Override
            public void saveRealPlayers(long budgetMs) {
                // Slow saves on several threads: the shutdown has to wait for the last of them, not just start them.
                ShutdownSaves.forEachWithin(players, id -> {
                    sleep(50 + 20 * id);
                    events.add("player " + id);
                }, ShutdownSaves.PLAYER_SAVERS, budgetMs);
            }

            @Override
            public void saveBots(long budgetMs) {
                events.add("bots");
            }
        }, 10_000, 10_000, 1_000);

        assertAll(
                () -> assertEquals(players.size() + 1, events.size(), events.toString()),
                () -> assertEquals("bots", events.get(events.size() - 1),
                        "bot work starts only once every real player is saved: " + events));
    }

    @Test
    void aBotPhaseThatOverrunsItsBudgetDoesNotHoldTheShutdown() {
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger playersSaved = new AtomicInteger();
        long started = System.nanoTime();
        ShutdownSaves.run(new ShutdownSaves.Steps() {
            @Override
            public void saveRealPlayers(long budgetMs) {
                playersSaved.incrementAndGet();
            }

            @Override
            public void saveBots(long budgetMs) {
                try {
                    never.await();   // a bot write stuck on a pool that never answers
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 1_000, 300, 200);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        never.countDown();

        assertAll(
                () -> assertEquals(1, playersSaved.get()),
                () -> assertTrue(ms < 2_000, "the shutdown went on after the bot budget, not " + ms + " ms later"));
    }

    @Test
    void aPlayerSaveThatHangsIsReportedAndDoesNotHoldTheShutdownForever() {
        CountDownLatch never = new CountDownLatch(1);
        long started = System.nanoTime();
        ShutdownSaves.BatchResult<String> result = ShutdownSaves.forEachWithin(List.of("ok", "stuck"), name -> {
            if (name.equals("stuck")) {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 2, 300);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        never.countDown();

        assertAll(
                () -> assertEquals(List.of("ok"), result.succeeded()),
                () -> assertEquals(List.of("stuck"), result.late()),
                () -> assertEquals(List.of("stuck"), result.timedOut()),
                () -> assertTrue(result.failed().isEmpty()),
                () -> assertTrue(ms < 2_000, "waited " + ms + " ms"));
    }

    @Test
    void distinctReportingForSuccessFailureAndTimeout() {
        CountDownLatch never = new CountDownLatch(1);
        ShutdownSaves.BatchResult<String> result = ShutdownSaves.forEachWithin(
                List.of("good", "blows_up", "slow"),
                name -> {
                    if ("blows_up".equals(name)) {
                        throw new RuntimeException("simulated db save error");
                    } else if ("slow".equals(name)) {
                        try {
                            never.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                },
                3, 200
        );
        never.countDown();

        assertAll(
                () -> assertEquals(3, result.total(), "all items are accounted for"),
                () -> assertEquals(List.of("good"), result.succeeded(), "only good succeeded"),
                () -> assertEquals(1, result.failed().size(), "blows_up failed"),
                () -> assertEquals("blows_up", result.failed().get(0).item()),
                () -> assertEquals("simulated db save error", result.failed().get(0).error().getMessage()),
                () -> assertEquals(List.of("slow"), result.timedOut(), "slow timed out"),
                () -> assertFalse(result.allSucceeded())
        );
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
