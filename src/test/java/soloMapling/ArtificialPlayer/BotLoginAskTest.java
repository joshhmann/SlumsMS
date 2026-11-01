package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPopulationDirector.LoginAsk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b269 fix 1: the director's login ask says which of three things happened, so a caller can tell "on its way in"
 * from "not coming", and a virtual thread's ask never waits for a slot or for the bots' share of the pool.
 */
class BotLoginAskTest {

    private static final int SLOT = 900_101;

    @AfterEach
    void restore() {
        BotPopulationDirector.useLoginAskForTest(null);
        BotRosterTestAccess.forget();
    }

    @Test
    void aVirtualThreadsLoginAskIsQueuedAndComesBackAtOnce() throws Exception {
        emptyRoster();
        long started = System.nanoTime();
        LoginAsk ask;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ask = executor.submit(() -> BotPopulationDirector.askLogin(SLOT)).get(10, TimeUnit.SECONDS);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertEquals(LoginAsk.QUEUED, ask, "a virtual thread must never be told 'refused' for a login it handed on");
        assertTrue(ms < 1_000, "the ask answered in " + ms + " ms: a virtual thread never waits for the bots' share");
        awaitQueuedLoginsDrained();
    }

    @Test
    void logInBoundedOnAVirtualThreadStillAnswersOnlyWhatIsInTheWorldNow() throws Exception {
        emptyRoster();
        boolean inWorld;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            inWorld = executor.submit(() -> BotPopulationDirector.logInBounded(SLOT)).get(10, TimeUnit.SECONDS);
        }
        assertFalse(inWorld, "the historical boolean answers 'in the world now', which a queued login is not");
        awaitQueuedLoginsDrained();
    }

    @Test
    void aPlatformThreadsAskSaysRefusedWhenNoLoginHappened() throws Exception {
        emptyRoster();
        assertEquals(LoginAsk.REFUSED, BotPopulationDirector.askLogin(SLOT),
                "a slot the roster does not hold cannot be logged in, and that is no, not 'on its way'");
        assertFalse(BotPopulationDirector.loginQueued(SLOT));
    }

    private static void emptyRoster() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(select.executeQuery()).thenReturn(rs);
        when(con.prepareStatement(anyString())).thenReturn(select);
        BotRosterTestAccess.use(() -> con);
        BotRoster.member(SLOT); // loads the empty roster now, so the queued login finds no member and no database
    }

    private static void awaitQueuedLoginsDrained() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (BotPopulationDirector.queuedLoginsForTest() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, BotPopulationDirector.queuedLoginsForTest(), "the queued login finished on a bot-db thread");
    }
}
