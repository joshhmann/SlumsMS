package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

class LifeActivityQueueTest {
    private static final Result RESTOCK = new Result(List.of(), Optional.of(Activity.RESTOCK), Outcome.CHOOSE);

    @Test
    void duplicateRequestsNeverStartTwiceOrReturnAnotherActionFact() {
        var queue = new LifeActivityQueue();
        var starts = new AtomicInteger();
        var request = queue.prepare(RESTOCK, 100, 1000);
        assertEquals(LifeActivityQueue.Offer.QUEUED, queue.offer(request, 100));
        assertEquals(LifeActivityQueue.Offer.DUPLICATE, queue.offer(request, 101));
        var first = queue.drain(102, true, () -> true, executor(queue, starts));
        assertEquals(LifeAcceptance.Status.ACCEPTED, first.attempt().orElseThrow().status());
        assertEquals(LifeActivityQueue.Offer.DUPLICATE, queue.offer(request, 103));
        assertTrue(queue.drain(104, true, () -> true, executor(queue, starts)).attempt().isEmpty());
        assertTrue(queue.finish(request.number(), LifeActivityQueue.End.COMPLETED));
        assertEquals(LifeActivityQueue.Offer.DUPLICATE, queue.offer(request, 105));
        assertEquals(1, starts.get());
    }

    @Test
    void reopenedQueueTakesNewPlansButNeverOneFromBeforeTheStop() {
        var queue = new LifeActivityQueue();
        var old = queue.prepare(RESTOCK, 100, 1000);
        queue.close();
        assertThrows(IllegalStateException.class, () -> queue.prepare(RESTOCK, 101, 1000));
        queue.reopen();
        assertFalse(queue.state().closed());
        assertEquals(LifeActivityQueue.Offer.STALE, queue.offer(old, 102));
        var fresh = queue.prepare(RESTOCK, 103, 1000);
        assertEquals(LifeActivityQueue.Offer.QUEUED, queue.offer(fresh, 103));
        queue.reopen(); // an open queue is left alone
        assertTrue(queue.hasWaiting());
    }

    @Test
    void onlyNewestWaitingDecisionRunsAndActiveWorkCannotBeReplaced() {
        var queue = new LifeActivityQueue();
        var starts = new AtomicInteger();
        var one = queue.prepare(RESTOCK, 100, 1000);
        queue.offer(one, 100);
        var two = queue.prepare(RESTOCK, 101, 1000);
        assertEquals(LifeActivityQueue.Offer.REPLACED, queue.offer(two, 101));
        assertEquals(two.number(), queue.drain(102, true, () -> true, executor(queue, starts)).number());
        var three = queue.prepare(RESTOCK, 103, 1000);
        queue.offer(three, 103);
        assertEquals(LifeActivityQueue.RunStatus.BUSY,
                queue.drain(104, true, () -> true, executor(queue, starts)).status());
        assertEquals(two.number(), queue.state().active());
        assertFalse(queue.finish(one.number(), LifeActivityQueue.End.COMPLETED));
        assertTrue(queue.finish(two.number(), LifeActivityQueue.End.COMPLETED));
        queue.drain(105, true, () -> true, executor(queue, starts));
        assertEquals(2, starts.get());
        assertEquals(three.number(), queue.state().active());
        assertFalse(queue.finish(two.number(), LifeActivityQueue.End.COMPLETED), "late callback must not release three");
    }

    @Test
    void expiredOrBackwardsClockRequestsNeverRunAndCannotBeResurrected() {
        var queue = new LifeActivityQueue();
        var starts = new AtomicInteger();
        var request = queue.prepare(RESTOCK, 100, 1000);
        assertEquals(LifeActivityQueue.Offer.STALE, queue.offer(request, 1100));
        assertEquals(LifeActivityQueue.Offer.DUPLICATE, queue.offer(request, 200));
        var backwards = queue.prepare(RESTOCK, 1000, 1000);
        queue.offer(backwards, 1000);
        assertEquals(LifeActivityQueue.RunStatus.STALE,
                queue.drain(999, true, () -> true, executor(queue, starts)).status());
        assertEquals(0, starts.get());
    }

    @Test
    void phaseChangesInvalidateWaitingAndNotYetSubmittedPlans() {
        var queue = new LifeActivityQueue();
        var request = queue.prepare(RESTOCK, 100, 1000);
        queue.offer(request, 100);
        var prepared = queue.prepare(RESTOCK, 101, 1000);
        queue.invalidateWaiting();
        assertFalse(queue.hasWaiting());
        assertEquals(LifeActivityQueue.Offer.STALE, queue.offer(request, 102));
        assertEquals(LifeActivityQueue.Offer.STALE, queue.offer(prepared, 102));
    }

    @Test
    void anIssuedNewerPlanMakesAnOlderWaitingPlanStaleEvenBeforeSubmission() {
        var queue = new LifeActivityQueue();
        var starts = new AtomicInteger();
        var request = queue.prepare(RESTOCK, 100, 1000);
        queue.offer(request, 100);
        queue.prepare(RESTOCK, 101, 1000);
        assertEquals(LifeActivityQueue.RunStatus.STALE,
                queue.drain(102, true, () -> true, executor(queue, starts)).status());
        assertEquals(0, starts.get());
    }

    @Test
    void disabledHeldRejectedAndFailedRequestsAreConsumedWithoutAnActionFact() {
        for (int mode = 0; mode < 4; mode++) {
            var queue = new LifeActivityQueue();
            var request = queue.prepare(RESTOCK, 100, 1000);
            queue.offer(request, 100);
            int testMode = mode;
            var executor = new LifeAcceptance.Executor() {
                public boolean supports(Activity activity) { return true; }
                public Optional<LifeAcceptance.Accepted> start(Activity activity) {
                    if (testMode == 3) throw new IllegalStateException("fixture");
                    return Optional.empty();
                }
            };
            var run = queue.drain(101, mode != 0, () -> testMode != 1, executor);
            assertTrue(run.attempt().orElseThrow().accepted().isEmpty());
            assertEquals(0, queue.state().active());
            assertEquals(LifeActivityQueue.Offer.DUPLICATE, queue.offer(request, 102));
            assertFalse(queue.isStartingHere());
        }
    }

    @Test
    void closedAndOtherInstanceRequestsCannotStartAnything() {
        var old = new LifeActivityQueue();
        var request = old.prepare(RESTOCK, 100, 1000);
        old.close();
        assertEquals(LifeActivityQueue.Offer.CLOSED, old.offer(request, 100));
        assertThrows(IllegalStateException.class, () -> old.prepare(RESTOCK, 101, 1000));
        var replacement = new LifeActivityQueue();
        assertEquals(LifeActivityQueue.Offer.FOREIGN, replacement.offer(request, 101));
        assertFalse(replacement.hasWaiting());
    }

    @Test
    void overlappingDrainsStartOnlyOnce() throws Exception {
        var queue = new LifeActivityQueue();
        var starts = new AtomicInteger();
        var request = queue.prepare(RESTOCK, 100, 1000);
        queue.offer(request, 100);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        var go = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<LifeActivityQueue.Run>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                assertTrue(go.await(30, TimeUnit.SECONDS));
                return queue.drain(101, true, () -> true, executor(queue, starts));
            }));
            go.countDown();
            int receipts = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS).attempt().isPresent()) receipts++;
            assertEquals(1, starts.get());
            assertEquals(1, receipts);
        } finally {
            go.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void reentrantDrainAndStopDuringCallbackDoNotStartAgainOrLeakAnActiveActivity() {
        var queue = new LifeActivityQueue();
        var starts = new AtomicInteger();
        queue.offer(queue.prepare(RESTOCK, 100, 1000), 100);
        var executor = new LifeAcceptance.Executor() {
            public boolean supports(Activity activity) { return true; }
            public Optional<LifeAcceptance.Accepted> start(Activity activity) {
                assertEquals(LifeActivityQueue.RunStatus.BUSY,
                        queue.drain(101, true, () -> true, executor(queue, starts)).status());
                queue.close();
                return Optional.of(new LifeAcceptance.Accepted(activity, "fixture start"));
            }
        };
        assertEquals(LifeActivityQueue.RunStatus.CLOSED, queue.drain(101, true, () -> true, executor).status());
        assertEquals(0, starts.get());
        assertEquals(0, queue.state().active());
        assertFalse(queue.isStartingHere());
    }

    @Test
    void cancellationOfAnOldRequestCannotRemoveNewWaitingWork() {
        var queue = new LifeActivityQueue();
        var old = queue.prepare(RESTOCK, 100, 1000);
        queue.offer(old, 100);
        var next = queue.prepare(RESTOCK, 101, 1000);
        queue.offer(next, 101);
        assertFalse(queue.cancelWaiting(old));
        assertEquals(next.number(), queue.state().waiting());
        assertTrue(queue.cancelWaiting(next));
        assertFalse(queue.hasWaiting());
    }

    @Test
    void validatesBoundedLifetimes() {
        var queue = new LifeActivityQueue();
        assertThrows(IllegalArgumentException.class, () -> queue.prepare(RESTOCK, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> queue.prepare(RESTOCK, 100, 30_001));
        assertThrows(IllegalArgumentException.class, () -> queue.prepare(RESTOCK, Long.MAX_VALUE, 1));
    }

    @Test
    void stoppingClearsRunningAndWaitingWork() {
        var queue = new LifeActivityQueue();
        var running = queue.prepare(RESTOCK, 100, 1000);
        queue.offer(running, 100);
        queue.drain(101, true, () -> true, executor(queue, new AtomicInteger()));
        var next = queue.prepare(RESTOCK, 102, 1000);
        queue.offer(next, 102);
        queue.close();
        assertEquals(0, queue.state().active());
        assertEquals(0, queue.state().waiting());
        assertEquals(running.number(), queue.state().lastEnded());
        assertEquals(LifeActivityQueue.End.CANCELLED, queue.state().end());
        assertEquals(LifeActivityQueue.Offer.CLOSED, queue.offer(next, 103));
    }

    @Test
    void evenAnUnexpectedErrorCannotLeaveStartPermissionOrAnUnconsumedRequestBehind() {
        var queue = new LifeActivityQueue();
        var request = queue.prepare(RESTOCK, 100, 1000);
        queue.offer(request, 100);
        var executor = new LifeAcceptance.Executor() {
            public boolean supports(Activity activity) { return true; }
            public Optional<LifeAcceptance.Accepted> start(Activity activity) { throw new AssertionError("fixture"); }
        };
        assertThrows(AssertionError.class, () -> queue.drain(101, true, () -> true, executor));
        assertFalse(queue.isStartingHere());
        assertFalse(queue.hasWaiting());
        assertEquals(LifeActivityQueue.Offer.DUPLICATE, queue.offer(request, 102));
    }

    private static LifeAcceptance.Executor executor(LifeActivityQueue queue, AtomicInteger starts) {
        return new LifeAcceptance.Executor() {
            public boolean supports(Activity activity) { return true; }
            public Optional<LifeAcceptance.Accepted> start(Activity activity) {
                assertTrue(queue.isStartingHere());
                starts.incrementAndGet();
                return Optional.of(new LifeAcceptance.Accepted(activity, "fixture start"));
            }
        };
    }
}
