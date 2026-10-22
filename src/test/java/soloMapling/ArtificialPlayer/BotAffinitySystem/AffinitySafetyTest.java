package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotStore;

import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.5.0 pre-release review: KS recency, @botdrama off, the tie cap, defame limits and rescue bookkeeping. */
class AffinitySafetyTest {

    static final int BOT = BotStore.BOT_ID_BASE + 7;
    static final int PLAYER = 42;
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final AffinityStore.Memory store = new AffinityStore.Memory();
    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    final List<Integer> purged = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(store, Runnable::run, clock::get, id -> true);
        BotAffinity.useNotesForTest((slot, player, text) -> { });
        BotAffinity.usePurgeForTest(purged::add);
        AffinityReactions.resetForTest();
        AffinityHooks.resetForTest(bot -> true);
    }

    @AfterEach
    void tearDown() {
        BotAffinity.useForTest(null, null, null, null);
        BotAffinity.useNotesForTest(null);
        BotAffinity.usePurgeForTest(null);
        AffinityHooks.resetForTest(null);
    }

    private static Character chr(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("C" + id);
        when(c.getMapId()).thenReturn(100000000);
        when(c.isAlive()).thenReturn(true);
        return c;
    }

    private Tie tie(int slot) {
        return BotAffinity.load(PLAYER).ties.get(slot);
    }

    // ── P1-2: KS ──

    @Test
    void onlyAFreshUnyieldedHitMakesAKs() {
        long now = 1_000_000L;
        assertTrue(AffinityHooks.countsAsKs(BOT, 5, now - 1_000, now), "hit a second ago");
        assertFalse(AffinityHooks.countsAsKs(BOT, 5, now - AffinityHooks.KS_WINDOW_MS - 1, now), "an old hit");
        assertFalse(AffinityHooks.countsAsKs(BOT, 5, 0, now), "never hit it");
        Character bot = chr(BOT);
        AffinityHooks.onBotYield(bot, null, now - 500); // gave its spot up after the hit
        assertFalse(AffinityHooks.countsAsKs(BOT, 5, now - 1_000, now), "yielded spot");
        assertTrue(AffinityHooks.countsAsKs(BOT, 5, now - 200, now), "hit again after yielding: its fight again");
        Monster mob = mock(Monster.class);
        when(mob.getObjectId()).thenReturn(9);
        AffinityHooks.onBotYield(bot, mob, now - 100);
        assertFalse(AffinityHooks.countsAsKs(BOT, 9, now - 200, now), "that mob was left to the player");
        assertTrue(AffinityHooks.countsAsKs(BOT, 10, now - 200, now), "another mob is not");
    }

    @Test
    void aPlayersHitOnAMobABotFoughtLongAgoOrYieldedIsNoKs() {
        BotAffinity.load(PLAYER);
        Character player = chr(PLAYER);
        Character bot = chr(BOT);
        MapleMap map = mock(MapleMap.class);
        when(player.getMap()).thenReturn(map);
        when(map.getCharacterById(BOT)).thenReturn(bot);
        Monster mob = mock(Monster.class);
        when(mob.getObjectId()).thenReturn(77);

        when(mob.recentAttackerAtOrAbove(eq(BotStore.BOT_ID_BASE), eq(PLAYER), anyLong())).thenReturn(-1);
        AffinityHooks.beforePlayerHit(player, mob); // the bot hit it long ago: no recent attacker
        assertNull(tie(7), "no KS");

        when(mob.recentAttackerAtOrAbove(eq(BotStore.BOT_ID_BASE), eq(PLAYER), anyLong())).thenReturn(BOT);
        when(mob.lastHitBy(BOT)).thenReturn(System.currentTimeMillis() - 1_000);
        AffinityHooks.onBotYield(bot, mob, System.currentTimeMillis());
        AffinityHooks.beforePlayerHit(player, mob);
        assertNull(tie(7), "the bot left this mob to the player");

        Monster fought = mock(Monster.class);
        when(fought.getObjectId()).thenReturn(78);
        when(fought.recentAttackerAtOrAbove(eq(BotStore.BOT_ID_BASE), eq(PLAYER), anyLong())).thenReturn(BOT);
        when(fought.lastHitBy(BOT)).thenReturn(System.currentTimeMillis() - 500);
        AffinityHooks.beforePlayerHit(player, fought);
        assertNotNull(tie(7), "a real KS still counts");
        assertEquals(1, tie(7).hostile());
    }

    // ── P1-3: @botdrama off ──

    @Test
    void theOptOutHoldsForAPlayerWhoseTiesAreNotLoaded() {
        store.out.add(PLAYER);
        assertFalse(BotAffinity.optedOut(PLAYER), "nothing read yet");
        assertTrue(BotAffinity.loadOptOuts());
        assertTrue(BotAffinity.optedOut(PLAYER), "read at boot: holds without the player's ties");
        BotAffinity.setOptedOut(chr(PLAYER), false);
        assertFalse(BotAffinity.optedOut(PLAYER));
        BotAffinity.unload(PLAYER);
        BotAffinity.setOptedOut(chr(PLAYER), true);
        BotAffinity.unload(PLAYER);
        assertTrue(BotAffinity.optedOut(PLAYER), "a toggle holds after their ties are dropped (a logout)");
        assertEquals(List.of(PLAYER), purged, "the gossip about them is purged when they opt out");
    }

    @Test
    void aFailedBootReadIsRetriedAndTogglesMadeMeanwhileHold() {
        store.failing = true;
        assertFalse(BotAffinity.loadOptOuts());
        BotAffinity.setOptedOut(chr(PLAYER), true);
        assertTrue(BotAffinity.optedOut(PLAYER));
    }

    // ── P1-11: the tie cap ──

    @Test
    void theCapEvictsTheWeakestOtherTieNeverTheNewOne() {
        BotAffinity.PlayerTies pt = BotAffinity.load(PLAYER);
        int max = BotAffinity.maxTiesPerPlayer();
        for (int slot = 1; slot <= max; slot++) {
            Tie t = new Tie(slot, PLAYER, slot == 3 ? 10 : 60, 5, 0, clock.get());
            pt.ties.put(slot, t);
            store.save(t);
        }
        int newcomer = BotStore.BOT_ID_BASE + 500;
        assertTrue(BotAffinity.record(newcomer, PLAYER, AffinityEvent.KS, AffinityEvent.KS.delta(), "P", "B", 0));
        assertTrue(pt.ties.containsKey(500), "the new tie stays");
        assertFalse(pt.ties.containsKey(3), "the weakest other one went");
        assertEquals(max, pt.ties.size());
        assertEquals(max, store.size(), "its row went too, and the new one is stored");
        assertTrue(store.load(PLAYER).stream().anyMatch(t -> t.botId == 500));
    }

    @Test
    void aPlayersWritesLandOneAtATimeInOrder() {
        Deque<Runnable> queued = new ArrayDeque<>();
        BotAffinity.useForTest(store, queued::add, clock::get, id -> true);
        BotAffinity.usePurgeForTest(purged::add);
        BotAffinity.load(PLAYER);
        BotAffinity.record(BOT, PLAYER, AffinityEvent.KS, -6, "P", "B", 0);
        BotAffinity.stage(BOT, PLAYER, AffinityState.NEUTRAL); // a save, then a delete of the same tie
        assertEquals(1, queued.size(), "the delete waits for the save");
        queued.poll().run();
        assertEquals(1, store.size());
        assertEquals(1, queued.size());
        queued.poll().run();
        assertEquals(0, store.size(), "the delete landed last");
    }

    // ── P2-10: defame ──

    @Test
    void oneDefamePerPlayerPerLocalDayDurablyDownToTheGamesFloor() {
        ZoneId la = ZoneId.of("America/Los_Angeles");
        long noonLa = java.time.ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, la).toInstant().toEpochMilli();
        long startLa = java.time.ZonedDateTime.of(2026, 10, 22, 0, 0, 0, 0, la).toInstant().toEpochMilli();
        assertEquals(startLa, AffinityReactions.dayStart(noonLa, la));
        assertEquals(startLa, AffinityReactions.dayStart(startLa + DAY - 1, la), "a whole local day, not a UTC one");

        assertTrue(BotAffinity.claimDefame(PLAYER, startLa, noonLa));
        assertFalse(BotAffinity.claimDefame(PLAYER, startLa, noonLa + 1000), "once a day");
        BotAffinity.useForTest(store, Runnable::run, clock::get, id -> true); // a restart: the store remembers
        assertFalse(BotAffinity.claimDefame(PLAYER, startLa, noonLa + 2000), "durable across a restart");
        assertTrue(BotAffinity.claimDefame(PLAYER, startLa + DAY, noonLa + DAY), "the next local day");

        clock.set(noonLa + 3 * DAY);
        Character bot = chr(BOT);
        Character floored = chr(PLAYER + 1);
        when(floored.getFame()).thenReturn(AffinityReactions.FAME_FLOOR);
        assertEquals("fame at its floor: no defame", AffinityReactions.defame(bot, floored));
        verify(floored, never()).gainFame(anyInt(), any(), anyInt());
        Character zero = chr(PLAYER + 2);
        when(zero.getFame()).thenReturn(0);
        when(zero.gainFame(anyInt(), any(), anyInt())).thenReturn(true);
        AffinityReactions.defame(bot, zero);
        verify(zero).gainFame(-1, bot, 0); // negative fame is allowed, as in v83
    }

    // ── P2-13: rescues ──

    @Test
    void mobHitsAreKeptOnlyWithAPlayerThereAndTheOldestGoFirst() {
        Character bot = chr(BOT);
        Monster mob = mock(Monster.class);
        when(mob.getObjectId()).thenReturn(1);
        AffinityHooks.resetForTest(b -> false);
        AffinityHooks.onMobHitBot(bot, mob, 1000);
        assertEquals(0, AffinityHooks.recentHitCount(), "nobody there to rescue it");
        AffinityHooks.resetForTest(b -> true);
        for (int oid = 0; oid < AffinityHooks.MAX_RECENT_HITS + 10; oid++) {
            Monster m = mock(Monster.class);
            when(m.getObjectId()).thenReturn(oid);
            AffinityHooks.onMobHitBot(bot, m, 1000 + oid);
        }
        assertEquals(AffinityHooks.MAX_RECENT_HITS, AffinityHooks.recentHitCount(), "full, never wiped");
    }
}
