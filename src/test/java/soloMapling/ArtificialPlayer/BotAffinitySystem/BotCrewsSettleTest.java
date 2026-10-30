package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 bot audit B28: with no fresh pick, "a mate in a town pulls you" was mutual, so two mates standing in two towns
 * pulled each other to the other's town and back, over and over. The pull is one way now (only a lower-id mate pulls,
 * so the lowest member online is never pulled and the crew settles on its town), and a member pulled once is left
 * alone for ten minutes so a second standing mate cannot bounce it out the moment it
 * arrives.
 */
class BotCrewsSettleTest {

    static final int A = BotStore.BOT_ID_BASE + 82_001, B = A + 1, C = A + 2;
    /** The crew's settle window: a member pulled once is left alone this long. */
    static final long SETTLE_MS = 10 * 60_000L;
    static final int HENESYS = 100000000, ELLINIA = 101000000, KERNING = 103000000, FIELD = 104040000;

    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final Map<Integer, int[]> where = new HashMap<>();
    final Map<Integer, Character> online = new HashMap<>();

    @BeforeEach
    void setUp() {
        System.setProperty("slums.crews", "true");
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get, id -> new double[]{.25, .25},
                (id, p) -> { }, online::get);
        BotCrews.resetForTest();
        BotCrews.where = id -> where.get(id);
        BotCrews.nudge = (id, ms) -> { };
        BotCrews.later = (ms, r) -> { };
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("slums.crews");
        BotCrews.resetForTest();
        BotBonds.useForTest(null, null, null, null, null, null);
    }

    static Character in(int id, int channel, int mapId, boolean town) {
        Character c = mock(Character.class);
        MapleMap m = mock(MapleMap.class);
        when(m.getChannelId()).thenReturn(channel);
        when(m.getId()).thenReturn(mapId);
        when(m.isTown()).thenReturn(town);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(m);
        when(c.getMapId()).thenReturn(mapId);
        return c;
    }

    /** Both mates are online on channel 1 and in a town; {@code where} and the world lookup agree. */
    private void matesInTowns(int aTown, int bTown) {
        BotBonds.stage(A, B, AffinityState.FRIEND);
        BotBonds.stage(B, A, AffinityState.FRIEND);
        put(A, aTown);
        put(B, bTown);
    }

    private void put(int id, int town) {
        where.put(id, new int[]{1, town});
        online.put(id, in(id, 1, town, true));
    }

    /* The leader (lowest id) is out in a field, so it stands in no town: two mates in two towns used to pull each
     * other across the map, back and forth. */
    @Test
    void twoMatesInTwoTownsDoNotTradeTowns() {
        BotBonds.stage(A, B, AffinityState.FRIEND);
        BotBonds.stage(B, A, AffinityState.FRIEND);
        BotBonds.stage(A, C, AffinityState.FRIEND);
        BotBonds.stage(C, A, AffinityState.FRIEND);
        BotBonds.stage(B, C, AffinityState.FRIEND);
        BotBonds.stage(C, B, AffinityState.FRIEND);
        where.put(A, new int[]{1, FIELD});
        online.put(A, in(A, 1, FIELD, false));
        put(B, HENESYS);
        put(C, ELLINIA);
        long now = clock.get();
        assertEquals(HENESYS, BotCrews.follow(in(C, 1, ELLINIA, true), now),
                "the higher-id mate is pulled to the lower one's town");
        assertEquals(-1, BotCrews.follow(in(B, 1, HENESYS, true), now),
                "the lower-id mate is never pulled back: the crew settles on its town");
    }

    @Test
    void aPulledMateIsLeftAloneUntilTheCrewSettles() {
        matesInTowns(HENESYS, ELLINIA);
        long t0 = clock.get();
        assertEquals(HENESYS, BotCrews.follow(in(B, 1, ELLINIA, true), t0), "first pull");
        put(A, KERNING); // the lower-id mate moved on before B arrived
        assertEquals(-1, BotCrews.follow(in(B, 1, ELLINIA, true), t0 + 60_000),
                "no second pull inside the cooldown");
        assertEquals(KERNING, BotCrews.follow(in(B, 1, ELLINIA, true), t0 + SETTLE_MS + 1),
                "after the cooldown the crew's new town pulls it again");
    }

    @Test
    void aMateWithNothingToFollowIsNotCountedAsPulled() {
        matesInTowns(HENESYS, HENESYS); // both in the same town: no pull, nothing to damp
        long now = clock.get();
        assertEquals(-1, BotCrews.follow(in(B, 1, HENESYS, true), now));
        put(A, ELLINIA);
        assertEquals(ELLINIA, BotCrews.follow(in(B, 1, HENESYS, true), now + 1_000),
                "a pull that never happened does not start a cooldown");
    }
}
