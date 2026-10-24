package soloMapling.fishing;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FishingSessionsTest {
    static final Object MAP = new Object();
    static final FishingSessions.Reward FISH = new FishingSessions.Reward(4031630, "Carp", 100);
    static class Fisher implements FishingSessions.Actor {
        final int id;
        volatile FishingSessions.Position position = new FishingSessions.Position(1, 2, MAP, 10, 120, 3011000, true);
        final AtomicInteger grants = new AtomicInteger(), facts = new AtomicInteger();
        boolean full, broken;
        Fisher(int id) { this.id = id; }
        public int id() { return id; }
        public FishingSessions.Position position() { return position; }
        public boolean give(FishingSessions.Reward reward) { if (full) return false; grants.incrementAndGet(); return true; }
        public void caught(FishingSessions.Reward reward, long sequence) { facts.incrementAndGet(); if (broken) throw new IllegalStateException(); }
    }
    private FishingSessions service() { return new FishingSessions(2, List.of(FISH), bound -> 0); }

    @Test void firstCatchWaitsNinetySecondsAndRepeatStartsCannotResetIt() {
        var service = service(); var fisher = new Fisher(1);
        assertEquals(FishingSessions.Start.STARTED, service.start(fisher, 100));
        assertEquals(FishingSessions.Start.ALREADY_FISHING, service.start(fisher, 90_000));
        assertEquals(FishingSessions.Tick.WAIT, service.tick(1, 90_099));
        assertEquals(FishingSessions.Tick.CAUGHT, service.tick(1, 90_100));
        assertEquals(FishingSessions.Tick.WAIT, service.tick(1, 90_100));
        assertEquals(1, fisher.grants.get()); assertEquals(1, fisher.facts.get());
    }
    @Test void lateTimersNeverPayACatchUpBurstAndFullBagsSpendTheirAttempt() {
        var service = service(); var fisher = new Fisher(1);
        service.start(fisher, 0); fisher.full = true;
        assertEquals(FishingSessions.Tick.FULL_BAG, service.tick(1, 90_000));
        fisher.full = false;
        assertEquals(FishingSessions.Tick.WAIT, service.tick(1, 90_001));
        assertEquals(FishingSessions.Tick.CAUGHT, service.tick(1, 10_000_000));
        assertEquals(FishingSessions.Tick.WAIT, service.tick(1, 10_000_001));
        assertEquals(1, fisher.grants.get());
    }
    @Test void movementStandingDeathDisconnectAndInstanceChangesCancelBeforePayout() {
        for (var changed : List.of(
                new FishingSessions.Position(1, 2, MAP, 11, 120, 3011000, true),
                new FishingSessions.Position(1, 2, MAP, 10, 121, 3011000, true),
                new FishingSessions.Position(1, 2, MAP, 10, 120, -1, true),
                new FishingSessions.Position(1, 2, MAP, 10, 120, 3011000, false),
                new FishingSessions.Position(1, 3, MAP, 10, 120, 3011000, true),
                new FishingSessions.Position(2, 2, MAP, 10, 120, 3011000, true),
                new FishingSessions.Position(1, 2, new Object(), 10, 120, 3011000, true))) {
            var service = service(); var fisher = new Fisher(1); service.start(fisher, 0); fisher.position = changed;
            assertEquals(FishingSessions.Tick.CANCELLED, service.tick(1, 90_000));
            assertEquals(0, fisher.grants.get()); assertEquals(0, service.size());
        }
    }
    @Test void boundedAdmissionsAndWorldCleanupReleaseTheirSlots() {
        var service = service();
        assertEquals(FishingSessions.Start.STARTED, service.start(new Fisher(1), 0));
        assertEquals(FishingSessions.Start.STARTED, service.start(new Fisher(2), 0));
        assertEquals(FishingSessions.Start.FULL, service.start(new Fisher(3), 0));
        service.cancelWorld(2); assertEquals(2, service.size());
        service.cancelWorld(1); assertEquals(0, service.size());
        assertEquals(FishingSessions.Start.STARTED, service.start(new Fisher(3), 0));
    }
    @Test void overlappingTicksCannotGrantTheSameCatchTwice() throws Exception {
        var service = service(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var fisher = new Fisher(1) {
            public boolean give(FishingSessions.Reward r) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return super.give(r);
            }
        };
        service.start(fisher, 0);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.tick(1, 90_000));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> service.tick(1, 90_000)); release.countDown();
            assertEquals(FishingSessions.Tick.CAUGHT, first.get(10, TimeUnit.SECONDS));
            assertEquals(FishingSessions.Tick.WAIT, second.get(10, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertEquals(1, fisher.grants.get()); assertEquals(1, fisher.facts.get());
    }
    @Test void failedNotificationNeverRetriesAnAlreadyGrantedCatch() {
        var service = service(); var fisher = new Fisher(1); fisher.broken = true; service.start(fisher, 0);
        assertEquals(FishingSessions.Tick.FAILED, service.tick(1, 90_000));
        assertEquals(FishingSessions.Tick.CANCELLED, service.tick(1, 90_000));
        assertEquals(1, fisher.grants.get()); assertEquals(0, service.size());
    }
    @Test void invalidAndBackwardsTimeAndStoppedActorsCannotEarn() {
        var service = service(); var fisher = new Fisher(1);
        assertEquals(FishingSessions.Start.REFUSED, service.start(fisher, -1));
        assertEquals(FishingSessions.Start.REFUSED, service.start(fisher, Long.MAX_VALUE));
        service.start(fisher, 100);
        assertEquals(FishingSessions.Tick.CANCELLED, service.tick(1, 99));
        service.start(fisher, 100); service.cancel(1);
        assertEquals(FishingSessions.Tick.CANCELLED, service.tick(1, 100_000)); assertEquals(0, fisher.grants.get());
    }
    @Test void aReconnectReplacesTheOldActorAndItsLateCleanupCannotCancelTheNewOne() {
        var service = service(); var old = new Fisher(1); var fresh = new Fisher(1);
        service.start(old, 0);
        assertEquals(FishingSessions.Start.STARTED, service.start(fresh, 100));
        service.cancel(1, old);
        assertTrue(service.active(1, fresh));
        assertEquals(FishingSessions.Tick.CAUGHT, service.tick(1, 90_100));
        assertEquals(0, old.grants.get()); assertEquals(1, fresh.grants.get());
    }

}
