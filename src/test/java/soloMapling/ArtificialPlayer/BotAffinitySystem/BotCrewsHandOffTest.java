package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 say-do ("friends follow a few seconds later"): when a crew's leader heads to a town each follower is handed
 * the trip after its delay, not only nudged to decide (auto-QA t-bt-2 on TEST: 1 of 2 followers arrived; the other's
 * brain kept grinding). A follower held a moment is tried again; one already there, or after the pick is gone, is left
 * alone.
 */
class BotCrewsHandOffTest {

    static final int A = BotStore.BOT_ID_BASE + 82_001, B = A + 1, C = A + 2;
    static final int ELLINIA = 101000000, FIELD = 104040000;

    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final Map<Integer, int[]> where = new HashMap<>();
    final List<Runnable> scheduled = new ArrayList<>();
    final List<Long> delays = new ArrayList<>();
    final List<int[]> sent = new ArrayList<>();
    final Map<Integer, Integer> heldTimes = new HashMap<>();

    @BeforeEach
    void setUp() {
        System.setProperty("slums.crews", "true");
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get, id -> new double[]{.25, .25},
                (id, p) -> { }, id -> null);
        BotCrews.resetForTest();
        BotCrews.where = id -> where.get(id);
        BotCrews.nudge = (id, ms) -> { };
        BotCrews.later = (ms, r) -> {
            delays.add(ms);
            scheduled.add(r);
        };
        BotCrews.mover = (id, town, until, now) -> {
            int held = heldTimes.getOrDefault(id, 0);
            if (held > 0) {
                heldTimes.put(id, held - 1);
                return false;
            }
            sent.add(new int[]{id, town});
            return true;
        };
        for (int i : new int[]{A, B, C}) {
            for (int j : new int[]{A, B, C}) {
                if (i < j) {
                    BotBonds.stage(i, j, AffinityState.FRIEND);
                }
            }
            where.put(i, new int[]{1, FIELD});
        }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("slums.crews");
        BotCrews.resetForTest();
        BotBonds.useForTest(null, null, null, null, null, null);
    }

    private void runScheduled() {
        List<Runnable> now = new ArrayList<>(scheduled);
        scheduled.clear();
        now.forEach(Runnable::run);
    }

    @Test
    void eachFollowerIsHandedTheTripAfterItsDelay() {
        BotCrews.led(BotCrewsTest.at(A, 1, FIELD), ELLINIA);
        assertEquals(2, scheduled.size());
        assertTrue(delays.get(0) >= 2_000 && delays.get(1) >= delays.get(0) + 3_000, "a few seconds apart: " + delays);
        runScheduled();
        assertEquals(2, sent.size(), "both followers are sent to the town");
        assertEquals(B, sent.get(0)[0]);
        assertEquals(ELLINIA, sent.get(0)[1]);
        assertEquals(C, sent.get(1)[0]);
        assertEquals(2L, BotCrews.stats().get("handedOff"));
    }

    @Test
    void aFollowerHeldAMomentIsTriedAgain() {
        heldTimes.put(C, 2);
        BotCrews.led(BotCrewsTest.at(A, 1, FIELD), ELLINIA);
        runScheduled();
        assertEquals(1, sent.size());
        runScheduled();
        runScheduled();
        assertEquals(2, sent.size(), "the held follower goes on its third try");
        assertEquals(C, sent.get(1)[0]);
    }

    @Test
    void aFollowerAlreadyThereOrAfterThePickExpiredIsLeftAlone() {
        BotCrews.led(BotCrewsTest.at(A, 1, FIELD), ELLINIA);
        where.put(B, new int[]{1, ELLINIA}); // walked there by itself
        clock.addAndGet(BotCrews.PICK_TTL_MS + 1); // and C's turn comes after the pick is stale
        runScheduled();
        assertTrue(sent.isEmpty(), "nothing to hand over");
    }
}
