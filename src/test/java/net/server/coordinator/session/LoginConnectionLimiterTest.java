package net.server.coordinator.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoginConnectionLimiterTest {

    private static final String HOME = "203.0.113.7";
    private static final long NOW = 1_800_000_000_000L;
    private static final long WINDOW = 60_000L;

    private final LoginConnectionLimiter limiter = new LoginConnectionLimiter();

    @Test
    void severalClientsFromOneAddressCanSitOnTheLoginScreensTogether() {
        // Cosmic refused the second one outright; the soak's scripted logins (all 127.0.0.1) hit that ~13 % of the time.
        for (long session = 1; session <= 8; session++) {
            assertNull(limiter.tryAcquire(HOME, session, 8, 30, WINDOW, NOW + session));
        }
        assertEquals(8, limiter.openSessions(HOME));
        assertEquals(LoginConnectionLimiter.Refusal.TOO_MANY_SESSIONS, limiter.tryAcquire(HOME, 9, 8, 30, WINDOW, NOW + 9));
        assertNull(limiter.tryAcquire("198.51.100.1", 10, 8, 30, WINDOW, NOW + 10), "other addresses are not affected");
    }

    @Test
    void closingASessionFreesItsSlotAndDoubleReleaseIsHarmless() {
        assertNull(limiter.tryAcquire(HOME, 1, 1, 0, WINDOW, NOW));
        assertEquals(LoginConnectionLimiter.Refusal.TOO_MANY_SESSIONS, limiter.tryAcquire(HOME, 2, 1, 0, WINDOW, NOW));
        limiter.release(HOME, 1);
        limiter.release(HOME, 1);
        assertNull(limiter.tryAcquire(HOME, 2, 1, 0, WINDOW, NOW));
        assertEquals(1, limiter.openSessions(HOME));
    }

    @Test
    void aBurstOfConnectionsIsCappedUntilTheWindowPasses() {
        for (long session = 1; session <= 30; session++) {
            assertNull(limiter.tryAcquire(HOME, session, 0, 30, WINDOW, NOW));
            limiter.release(HOME, session);
        }
        assertEquals(LoginConnectionLimiter.Refusal.TOO_MANY_CONNECTS, limiter.tryAcquire(HOME, 31, 0, 30, WINDOW, NOW + 1_000));
        assertNull(limiter.tryAcquire(HOME, 32, 0, 30, WINDOW, NOW + WINDOW), "the window slid past the burst");
    }

    @Test
    void zeroLimitsTurnTheChecksOff() {
        for (long session = 1; session <= 100; session++) {
            assertNull(limiter.tryAcquire(HOME, session, 0, 0, WINDOW, NOW));
        }
    }

    @Test
    void purgeDropsOldHistoryAndSnapshotListsOpenSessions() {
        limiter.tryAcquire(HOME, 1, 8, 30, WINDOW, NOW);
        limiter.tryAcquire(HOME, 2, 8, 30, WINDOW, NOW);
        limiter.tryAcquire("198.51.100.1", 3, 8, 30, WINDOW, NOW);
        assertEquals(java.util.Map.of(HOME, 2, "198.51.100.1", 1), limiter.snapshot());
        limiter.release("198.51.100.1", 3);
        limiter.purge(WINDOW, NOW + WINDOW);
        assertEquals(java.util.Map.of(HOME, 2), limiter.snapshot());
    }
}
