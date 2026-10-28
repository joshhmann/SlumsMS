package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.1 ticket 114, "Friend crews": friends who hang out move towns together, a few at a time, capped. */
class BotCrewsTest {

    static final int A = BotStore.BOT_ID_BASE + 81_001, B = A + 1, C = A + 2, D = A + 3, E = A + 4;
    static final int HENESYS = 100000000, ELLINIA = 101000000, FIELD = 104040000;

    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final Map<Integer, int[]> where = new HashMap<>();
    final List<long[]> nudges = new ArrayList<>();

    @BeforeEach
    void setUp() {
        System.setProperty("slums.crews", "true");
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get, id -> new double[]{.25, .25},
                (id, p) -> { }, id -> null);
        BotCrews.resetForTest();
        BotCrews.where = id -> where.get(id);
        BotCrews.nudge = (id, ms) -> nudges.add(new long[]{id, ms});
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("slums.crews");
        System.clearProperty("slums.crews.max");
        BotCrews.resetForTest();
        BotBonds.useForTest(null, null, null, null, null, null);
    }

    static Character at(int id, int channel, int mapId) {
        Character c = mock(Character.class);
        MapleMap m = mock(MapleMap.class);
        when(m.getChannelId()).thenReturn(channel);
        when(m.getId()).thenReturn(mapId);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(m);
        when(c.getMapId()).thenReturn(mapId);
        return c;
    }

    void crew(int... ids) {
        for (int i = 0; i < ids.length; i++) {
            for (int j = i + 1; j < ids.length; j++) {
                BotBonds.stage(ids[i], ids[j], AffinityState.FRIEND);
            }
        }
    }

    @Test
    void theLeaderPicksAndItsFriendsFollowWithSmallDelays() {
        crew(A, B, C);
        where.put(A, new int[]{1, FIELD});
        where.put(B, new int[]{1, FIELD});
        where.put(C, new int[]{1, FIELD});
        BotCrews.led(at(A, 1, FIELD), ELLINIA);
        assertEquals(2, nudges.size(), "both friends are nudged");
        assertTrue(nudges.get(0)[1] >= 2_000 && nudges.get(0)[1] < 6_000, "first after 2-6 s");
        assertTrue(nudges.get(1)[1] >= nudges.get(0)[1] + 3_000, "the next a few seconds later, not all at once");
        long now = clock.get();
        assertEquals(ELLINIA, BotCrews.follow(at(B, 1, FIELD), now));
        assertEquals(ELLINIA, BotCrews.follow(at(C, 1, FIELD), now));
        assertEquals(-1, BotCrews.follow(at(A, 1, FIELD), now), "the leader follows nobody");
        assertEquals(-1, BotCrews.follow(at(B, 1, ELLINIA), now), "already there");
        assertEquals(-1, BotCrews.follow(at(B, 2, FIELD), now), "another channel's crew has its own leader");
    }

    @Test
    void onlyTheLowestIdOnTheChannelLeadsAndThePickExpires() {
        crew(A, B);
        where.put(A, new int[]{1, FIELD});
        where.put(B, new int[]{1, FIELD});
        BotCrews.led(at(B, 1, FIELD), ELLINIA);
        assertTrue(nudges.isEmpty(), "B is not the leader while A is online on its channel");
        BotCrews.led(at(A, 1, FIELD), HENESYS);
        assertEquals(HENESYS, BotCrews.follow(at(B, 1, FIELD), clock.get()));
        clock.addAndGet(BotCrews.PICK_TTL_MS + 1);
        where.put(A, new int[]{1, FIELD});
        assertEquals(-1, BotCrews.follow(at(B, 1, FIELD), clock.get()), "a stale pick leads nowhere");
    }

    @Test
    void aCrewMovesAtMostFourAtATime() {
        crew(A, B, C, D, E);
        for (int id : new int[]{A, B, C, D, E}) {
            where.put(id, new int[]{1, FIELD});
        }
        BotCrews.led(at(A, 1, FIELD), ELLINIA);
        assertEquals(BotCrews.maxMovers() - 1, nudges.size(), "the leader and three friends move; the rest stay");
        int followers = 0;
        for (int id : new int[]{B, C, D, E}) {
            followers += BotCrews.follow(at(id, 1, FIELD), clock.get()) == ELLINIA ? 1 : 0;
        }
        assertEquals(3, followers);
    }

    @Test
    void offWithoutTheSwitch() {
        System.clearProperty("slums.crews");
        crew(A, B);
        where.put(A, new int[]{1, FIELD});
        where.put(B, new int[]{1, FIELD});
        BotCrews.led(at(A, 1, FIELD), ELLINIA);
        assertTrue(nudges.isEmpty());
        assertEquals(-1, BotCrews.follow(at(B, 1, FIELD), clock.get()));
    }
}
