package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

class LifeAcceptanceTest {
    private static final Result CHOSEN = new Result(List.of(), Optional.of(Activity.RESTOCK), Outcome.CHOOSE);

    @Test
    void shadowAndHeldAndUnchangedResultsNeverReachTheExecutor() {
        var executor = mock(LifeAcceptance.Executor.class);
        assertEquals(LifeAcceptance.Status.SHADOW, LifeAcceptance.tryStart(CHOSEN, false,
                () -> { throw new AssertionError("shadow must not inspect executable world state"); }, executor).status());
        for (Outcome outcome : List.of(Outcome.HOLD_COMMITMENT, Outcome.KEEP_CURRENT, Outcome.NO_AVAILABLE_OPTION)) {
            var attempt = LifeAcceptance.tryStart(new Result(List.of(), Optional.of(Activity.RESTOCK), outcome),
                    true, () -> true, executor);
            assertTrue(attempt.accepted().isEmpty());
        }
        verifyNoInteractions(executor);
    }

    @Test
    void rechecksCommitmentAfterAvailabilityAndOnlyThenReturnsAnActionFact() {
        var order = new ArrayList<String>();
        var executor = new LifeAcceptance.Executor() {
            public boolean supports(Activity activity) { order.add("supports"); return true; }
            public Optional<LifeAcceptance.Accepted> start(Activity activity) {
                order.add("start");
                return Optional.of(new LifeAcceptance.Accepted(activity, "requested a town trip"));
            }
        };
        var result = LifeAcceptance.tryStart(CHOSEN, true, () -> { order.add("guard"); return true; }, executor);
        assertEquals(List.of("guard", "supports", "guard", "start"), order);
        assertEquals(LifeAcceptance.Status.ACCEPTED, result.status());
        assertEquals("requested a town trip", result.accepted().orElseThrow().fact());
    }

    @Test
    void staleEligibilityRejectionUnsupportedAndFailureReturnNoFact() {
        var executor = mock(LifeAcceptance.Executor.class);
        when(executor.supports(Activity.RESTOCK)).thenReturn(true);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var stale = LifeAcceptance.tryStart(CHOSEN, true, () -> calls.incrementAndGet() == 1, executor);
        assertEquals(LifeAcceptance.Status.HELD, stale.status());
        verify(executor, never()).start(any());
        when(executor.start(Activity.RESTOCK)).thenReturn(Optional.empty());
        assertEquals(LifeAcceptance.Status.REJECTED, LifeAcceptance.tryStart(CHOSEN, true, () -> true, executor).status());
        when(executor.start(Activity.RESTOCK)).thenThrow(new IllegalStateException("fixture"));
        var failed = LifeAcceptance.tryStart(CHOSEN, true, () -> true, executor);
        assertEquals(LifeAcceptance.Status.FAILED, failed.status());
        assertTrue(failed.accepted().isEmpty());
        when(executor.supports(Activity.RESTOCK)).thenReturn(false);
        var unsupported = LifeAcceptance.tryStart(CHOSEN, true, () -> true, executor);
        assertEquals(LifeAcceptance.Status.UNSUPPORTED, unsupported.status());
        assertTrue(unsupported.accepted().isEmpty());
    }

    @Test
    void wrongActionReceiptCannotBeUsedToAnnounceTheSelectedAction() {
        var executor = mock(LifeAcceptance.Executor.class);
        when(executor.supports(Activity.RESTOCK)).thenReturn(true);
        when(executor.start(Activity.RESTOCK)).thenReturn(Optional.of(
                new LifeAcceptance.Accepted(Activity.SELL, "sold items")));
        var result = LifeAcceptance.tryStart(CHOSEN, true, () -> true, executor);
        assertEquals(LifeAcceptance.Status.FAILED, result.status());
        assertTrue(result.accepted().isEmpty());
    }
}
