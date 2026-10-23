package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.ConvoContract;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 1.5.0 rc6 bug hunt (P2): the cloud budget under-counted. settle charged min(reserved, actual), so an overrun was
 * dropped; and the router read the brain-wide last usage, which a concurrent call overwrote.
 */
class CloudBudgetChargeTest {

    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private final AtomicLong now = new AtomicLong(LocalDateTime.of(2026, 10, 23, 12, 0).atZone(ZONE).toInstant().toEpochMilli());

    @Test
    void anOverrunIsChargedInFull() {
        CloudBudget budget = new CloudBudget(1.0, 1_000_000, 1_000_000, now::get, ZONE, null);   // $1 a token
        org.junit.jupiter.api.Assertions.assertTrue(budget.reserve(0.5));
        budget.settle(0.5, 1, 0);   // reserved 50 cents, really used $1
        assertEquals(1.0, budget.spentToday(), 1e-9, "the whole real cost, not the reservation");
        assertEquals(0, budget.reservedNow(), 1e-12);
        assertFalse(budget.canSpend(), "the overrun reached the cap: no more cloud today");
    }

    /** A cloud brain whose calls finish when the test says, each with its own usage. */
    static final class MeteredCloud implements BotBrain {
        final List<OpenAICompatibleBrain.Metered<Optional<ConvoContract.Reply>>> pending = new java.util.ArrayList<>();

        @Override
        public CompletableFuture<Optional<ConvoContract.Reply>> reply(soloMapling.ArtificialPlayer.BotBrainSystem.Priority p,
                                                                      int playerId, List<Turn> c) {
            OpenAICompatibleBrain.Metered<Optional<ConvoContract.Reply>> f = new OpenAICompatibleBrain.Metered<>();
            pending.add(f);
            return f;
        }

        public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> c) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        public CompletableFuture<Optional<String>> notes(List<Turn> c) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        public boolean isLive() {
            return true;
        }

        public String describe() {
            return "metered";
        }
    }

    @Test
    void eachCallIsChargedItsOwnUsageEvenWhenTheyOverlap() {
        ChatRouterTest.FakeLocal local = new ChatRouterTest.FakeLocal(now);
        local.free = 0;
        MeteredCloud cloud = new MeteredCloud();
        CloudBudget budget = new CloudBudget(1_000, 1_000_000, 1_000_000, now::get, ZONE, null);   // $1 a token
        ChatRouter r = new ChatRouter(local, cloud, budget, 2500, now::get, 64);
        List<BotBrain.Turn> conv = List.of(BotBrain.Turn.user("hi"));

        CompletableFuture<Optional<ConvoContract.Reply>> a = r.reply(soloMapling.ArtificialPlayer.BotBrainSystem.Priority.PLAYER, 1, conv);
        CompletableFuture<Optional<ConvoContract.Reply>> b = r.reply(soloMapling.ArtificialPlayer.BotBrainSystem.Priority.PLAYER, 2, conv);
        assertEquals(2, cloud.pending.size());
        // the second finishes first; under the old brain-wide "last usage" the first would be charged the second's
        cloud.pending.get(1).complete(Optional.of(ConvoContract.Reply.plain("b")), new long[]{100, 10});
        cloud.pending.get(0).complete(Optional.of(ConvoContract.Reply.plain("a")), new long[]{7, 3});
        a.join();
        b.join();
        assertEquals(110 + 10, budget.spentToday(), 1e-6, budget.describe());
        assertEquals(0, budget.reservedNow(), 1e-9);
    }
}
