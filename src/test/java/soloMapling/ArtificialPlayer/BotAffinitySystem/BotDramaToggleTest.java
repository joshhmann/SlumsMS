package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.0 rc6 bug hunt (P3): every {@code @botdrama off} wrote the row again and ran the gossip purge (a DELETE over
 * botmemory), so spamming it flooded the DB. A no-change toggle is now a no-op and a player may change it at most once
 * every ten seconds.
 */
class BotDramaToggleTest {

    static final int PLAYER = 42;

    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    final List<Integer> purged = new ArrayList<>();
    final AtomicInteger writes = new AtomicInteger();
    final AffinityStore.Memory store = new AffinityStore.Memory() {
        @Override
        public void setOptedOut(int charId, boolean out, long now) {
            writes.incrementAndGet();
            super.setOptedOut(charId, out, now);
        }
    };

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(store, Runnable::run, clock::get, id -> true);
        BotAffinity.useNotesForTest((slot, player, text) -> { });
        BotAffinity.usePurgeForTest(purged::add);
        assertTrue(BotAffinity.loadOptOuts());
    }

    @AfterEach
    void tearDown() {
        BotAffinity.useForTest(null, null, null, null);
        BotAffinity.useNotesForTest(null);
        BotAffinity.usePurgeForTest(null);
    }

    private static Character player() {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(PLAYER);
        when(c.getName()).thenReturn("P");
        return c;
    }

    @Test
    void repeatingOffTouchesTheDatabaseOnce() {
        Character p = player();
        assertEquals(BotAffinity.Toggle.CHANGED, BotAffinity.toggleByPlayer(p, true));
        for (int i = 0; i < 20; i++) {
            clock.addAndGet(60_000);
            assertEquals(BotAffinity.Toggle.UNCHANGED, BotAffinity.toggleByPlayer(p, true));
        }
        assertEquals(1, writes.get(), "one row write");
        assertEquals(List.of(PLAYER), purged, "one gossip purge");
        assertTrue(BotAffinity.optedOut(PLAYER));
    }

    @Test
    void aRealChangeIsLimitedToOnceEveryTenSeconds() {
        Character p = player();
        assertEquals(BotAffinity.Toggle.CHANGED, BotAffinity.toggleByPlayer(p, true));
        clock.addAndGet(3_000);
        assertEquals(BotAffinity.Toggle.TOO_SOON, BotAffinity.toggleByPlayer(p, false));
        assertTrue(BotAffinity.optedOut(PLAYER), "the refused change did nothing");
        clock.addAndGet(7_000);
        assertEquals(BotAffinity.Toggle.CHANGED, BotAffinity.toggleByPlayer(p, false));
        assertFalse(BotAffinity.optedOut(PLAYER));
        assertEquals(2, writes.get());
    }

    @Test
    void theStaffPathSkipsANoChangeToggleButIsNotRateLimited() {
        Character p = player();
        assertTrue(BotAffinity.setOptedOut(p, true));
        assertFalse(BotAffinity.setOptedOut(p, true), "no change: nothing written");
        assertTrue(BotAffinity.setOptedOut(p, false), "no rate limit for QA staging");
        assertEquals(2, writes.get());
    }
}
