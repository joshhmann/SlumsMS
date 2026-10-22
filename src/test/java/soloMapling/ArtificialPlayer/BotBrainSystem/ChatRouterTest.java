package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.ConvoContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 7: local first, cloud for players' overflow, budget cap, priorities. */
class ChatRouterTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private final AtomicLong now = new AtomicLong(LocalDateTime.of(2026, 10, 22, 12, 0).atZone(ZONE).toInstant().toEpochMilli());

    static final class FakeLocal implements ChatRouter.Slotted {
        volatile int free = 2;
        volatile long takesMs;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong clock;

        FakeLocal(AtomicLong clock) {
            this.clock = clock;
        }

        public int freeSlots() {
            return free;
        }

        public int maxSlots() {
            return 2;
        }

        public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> c) {
            calls.incrementAndGet();
            clock.addAndGet(takesMs);
            return CompletableFuture.completedFuture(Optional.of("local"));
        }

        public CompletableFuture<Optional<String>> notes(List<Turn> c) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        public boolean isLive() {
            return true;
        }

        public String describe() {
            return "local";
        }
    }

    static class FakeCloud implements BotBrain {
        final AtomicInteger calls = new AtomicInteger();

        public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> c) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of("cloud reply here"));
        }

        public CompletableFuture<Optional<String>> notes(List<Turn> c) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        public boolean isLive() {
            return true;
        }

        public String describe() {
            return "cloud";
        }
    }

    private final FakeLocal local = new FakeLocal(now);
    private final FakeCloud cloud = new FakeCloud();

    @AfterEach
    void reset() {
        System.clearProperty("solomapling.convo.forceBank");
        System.clearProperty("solomapling.convo.router");
    }

    private ChatRouter router(double budgetUsd) {
        return new ChatRouter(local, cloud, new CloudBudget(budgetUsd, 1_000_000, 1_000_000, now::get, ZONE, null),
                2500, now::get);
    }

    private static List<BotBrain.Turn> conv() {
        return List.of(BotBrain.Turn.system("x".repeat(400)), BotBrain.Turn.user("wanna party?"));
    }

    private String ask(ChatRouter r, Priority p) {
        return r.reply(p, 7, conv()).join().map(ConvoContract.Reply::text).orElse("BANK");
    }

    @Test
    void playersGoLocalWhileThereIsRoom() {
        ChatRouter r = router(1);
        assertEquals("local", ask(r, Priority.PLAYER));
        assertEquals(0, cloud.calls.get());
    }

    @Test
    void playersOverflowToTheCloudWhenSlotsAreFullOrTheLocalModelIsSlow() {
        ChatRouter r = router(1000);
        local.free = 0;
        assertEquals("cloud reply here", ask(r, Priority.PLAYER));
        local.free = 2;
        local.takesMs = 4000;
        ask(r, Priority.PLAYER); // measured slow
        assertTrue(r.localEwmaMs() > 2500);
        local.takesMs = 0;
        assertEquals("cloud reply here", ask(r, Priority.PLAYER));
        assertEquals(2, cloud.calls.get());
    }

    @Test
    void onlyPlayersEverReachTheCloud() {
        ChatRouter r = router(1000);
        local.free = 0;
        for (Priority p : new Priority[]{Priority.PARTNER, Priority.THREAD, Priority.AMBIENT}) {
            assertEquals("BANK", ask(r, p), p.name());
        }
        assertEquals(0, cloud.calls.get());
    }

    @Test
    void lowerPrioritiesNeedSpareSlots() {
        ChatRouter r = router(1);
        local.free = 1;
        assertEquals("local", ask(r, Priority.PARTNER));
        assertEquals("BANK", ask(r, Priority.THREAD), "threads only when every slot is free");
        local.free = 2;
        assertEquals("local", ask(r, Priority.THREAD));
        ask(r, Priority.PLAYER);
        assertEquals("BANK", ask(r, Priority.AMBIENT), "ambient waits 10 s after a player reply");
        now.addAndGet(11_000);
        assertEquals("local", ask(r, Priority.AMBIENT));
    }

    @Test
    void theDailyCapStopsTheCloudUntilMidnight() {
        // price 1 USD per token in this test: a call reserves ~286 tokens and uses ~110 -> one call fits a $300 cap
        ChatRouter r = router(300);
        local.free = 0;
        assertEquals("cloud reply here", ask(r, Priority.PLAYER));
        assertTrue(r.budget().spentToday() >= 100, r.budget().describe());
        assertEquals("local", ask(r, Priority.PLAYER), "over the cap: back to the local queue");
        assertEquals(1, cloud.calls.get());
        now.addAndGet(13 * 3_600_000L); // past midnight
        assertEquals("cloud reply here", ask(r, Priority.PLAYER));
    }

    @Test
    void theCapIsHardCallsInFlightReserveTheirWorstCase() {
        // review 2026-10-22: charging after the call let several calls pass just under the cap
        CompletableFuture<Optional<String>> pending = new CompletableFuture<>();
        AtomicInteger cloudCalls = new AtomicInteger();
        BotBrain slowCloud = new FakeCloud() {
            @Override
            public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> c) {
                cloudCalls.incrementAndGet();
                return pending;
            }
        };
        // $1 per million tokens; a call reserves about (412/2 + 16) in + 64 out = ~286 tokens -> $0.000286
        CloudBudget budget = new CloudBudget(0.0004, 1, 1, now::get, ZONE, null);
        ChatRouter r = new ChatRouter(local, slowCloud, budget, 2500, now::get, 64);
        local.free = 0;
        CompletableFuture<Optional<ConvoContract.Reply>> first = r.reply(Priority.PLAYER, 7, conv());
        assertEquals("local", ask(r, Priority.PLAYER), "the second does not fit while the first is in flight");
        assertEquals(1, cloudCalls.get());
        pending.complete(Optional.of("ok"));
        first.join();
        assertTrue(budget.spentToday() <= 0.0004, "never past the cap: " + budget.describe());
        assertEquals(0, budget.reservedNow(), 1e-12);
    }

    @Test
    void aReservationThatDoesNotFitRefusesTheCall() {
        CloudBudget budget = new CloudBudget(0.0001, 1, 1, now::get, ZONE, null);
        ChatRouter r = new ChatRouter(local, cloud, budget, 2500, now::get, 64);
        local.free = 0;
        assertEquals("local", ask(r, Priority.PLAYER), "worst case > cap: no cloud call at all");
        assertEquals(0, cloud.calls.get());
        assertEquals(0, budget.spentToday(), 1e-12);
    }

    @Test
    void legacyCompleteCallsAreAmbientAndNeverReachTheCloud() {
        // review 2026-10-22: BotRivalry.roast (an on-sight taunt) went through complete() as a player reply
        ChatRouter r = router(1000);
        local.free = 0;
        assertEquals(Optional.empty(), r.complete(7, conv()).join());
        assertEquals(0, cloud.calls.get());
    }

    @Test
    void noCloudConfiguredMeansLocalOnly() {
        ChatRouter r = new ChatRouter(local, null, null, 2500, now::get);
        local.free = 0;
        assertEquals("local", ask(r, Priority.PLAYER));
        assertTrue(r.describe().contains("no cloud"));
    }

    @Test
    void forceBankAnswersNothingAndTheSwitchRestoresTheOldPath() {
        ChatRouter r = router(1);
        System.setProperty("solomapling.convo.forceBank", "true");
        assertEquals("BANK", ask(r, Priority.PLAYER));
        System.clearProperty("solomapling.convo.forceBank");
        System.setProperty("solomapling.convo.router", "false");
        local.free = 0;
        assertEquals("local", ask(r, Priority.THREAD));
    }

    @Test
    void theBudgetSurvivesARestartTheSameDay() throws Exception {
        Path dir = Files.createTempDirectory("budget");
        Path file = dir.resolve("cloud-budget.json");
        CloudBudget a = new CloudBudget(1, 0.28, 0.42, now::get, ZONE, file);
        a.charge(1_000_000, 1_000_000);
        CloudBudget b = new CloudBudget(1, 0.28, 0.42, now::get, ZONE, file);
        assertEquals(0.70, b.spentToday(), 1e-9);
        assertTrue(b.canSpend());
        b.charge(1_000_000, 1_000_000);
        assertFalse(b.canSpend(), "$1.40 is over the $1 cap");
    }

    @Test
    void costIsTokensTimesPrice() {
        CloudBudget b = new CloudBudget(1, 0.28, 0.42, now::get, ZONE, null);
        assertEquals(0.28 * 400 / 1e6 + 0.42 * 30 / 1e6, b.cost(400, 30), 1e-12);
        assertEquals(3, CloudBudget.estimateTokens("hello world!"));
    }
}
