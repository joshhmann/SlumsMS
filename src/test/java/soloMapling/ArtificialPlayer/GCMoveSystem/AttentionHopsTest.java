package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owner, FM on TEST: "playerbots jump but not while they have chat bubble on their heads ... I'd say 3-4 jumps right
 * after a message is sent". Pins the burst (3-4 hops, 350-600 ms apart, first within 0.3 s), the states that skip
 * it, the 20 s cooldown, and that the idle fidget's lone hops are now rare.
 */
class AttentionHopsTest {

    private static final int FM_ROOM = 910000001;
    private static int nextId = 77_001;

    private record Pending(Runnable task, long delayMs) {
    }

    private final List<Pending> queue = new ArrayList<>();
    private final List<Long> hopTimes = new ArrayList<>();
    private long clock;

    @BeforeEach
    void seams() {
        AttentionHops.resetForTest();
        AttentionHops.scheduler = (task, delayMs) -> queue.add(new Pending(task, delayMs));
        AttentionHops.observed = id -> true;
        AttentionHops.chance = b -> 1.0;
        AttentionHops.walking = b -> false;
        AttentionHops.airborne = b -> false;
        AttentionHops.hopper = b -> {
            hopTimes.add(clock);
            return true;
        };
    }

    @AfterEach
    void restore() {
        AttentionHops.resetForTest();
    }

    /** Runs the scheduled tasks in order on a fake clock. */
    private void drain() {
        while (!queue.isEmpty()) {
            Pending p = queue.remove(0);
            clock += p.delayMs();
            p.task().run();
        }
    }

    private static MapleMap map(int id, boolean town) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        when(map.isTown()).thenReturn(town);
        return map;
    }

    private static Character bot(MapleMap map) {
        Character bot = mock(Character.class);
        int id = BotStore.BOT_ID_BASE + nextId++;
        when(bot.getId()).thenReturn(id);
        when(bot.getMap()).thenReturn(map);
        int mapId = map.getId();
        when(bot.getMapId()).thenReturn(mapId);
        return bot;
    }

    @Test
    void anEligibleLineHopsThreeOrFourTimesWhileTheBubbleIsUp() {
        boolean sawThree = false;
        boolean sawFour = false;
        for (int seed = 0; seed < 60; seed++) {
            queue.clear();
            hopTimes.clear();
            clock = 0;
            Character bot = bot(map(FM_ROOM, false));
            assertTrue(AttentionHops.maybeBurst(bot, 1_000, new Random(seed)));
            assertEquals(1, queue.size(), "one timer task in flight per burst");
            assertTrue(queue.get(0).delayMs() <= 300, "starts within 0.3 s of the line");
            drain();
            int n = hopTimes.size();
            assertTrue(n == 3 || n == 4, "3-4 hops, got " + n);
            sawThree |= n == 3;
            sawFour |= n == 4;
            for (int i = 1; i < n; i++) {
                long gap = hopTimes.get(i) - hopTimes.get(i - 1);
                assertTrue(gap >= 350 && gap <= 600, "mashing the key, gap " + gap);
            }
            assertTrue(hopTimes.get(n - 1) < 3_000, "all while the bubble is still up");
        }
        assertTrue(sawThree && sawFour, "sometimes three, sometimes four");
    }

    @Test
    void theGapsAreNotMetronomic() {
        Random rng = new Random(7);
        java.util.Set<Integer> gaps = new java.util.HashSet<>();
        for (int i = 0; i < 40; i++) {
            gaps.add(AttentionHops.nextGap(rng));
        }
        assertTrue(gaps.size() > 20, "a little randomness in every gap");
    }

    @Test
    void aHopDueMidAirWaitsForTheLanding() {
        AtomicInteger airChecks = new AtomicInteger();
        AttentionHops.airborne = b -> airChecks.getAndIncrement() % 3 == 1; // in the air for a beat after each hop
        Character bot = bot(map(FM_ROOM, false));
        assertTrue(AttentionHops.maybeBurst(bot, 0, new Random(3)));
        drain();
        assertTrue(hopTimes.size() >= 3, "the burst survives the air time");
    }

    @Test
    void townsCountFieldsDoNot() {
        assertTrue(AttentionHops.maybeBurst(bot(map(104000000, true)), 0, new Random(1)), "Lith Harbor");
        assertFalse(AttentionHops.maybeBurst(bot(map(104010001, false)), 0, new Random(1)), "a training field");
        assertTrue(queue.size() == 1);
    }

    @Test
    void nobodyWatchingNoHops() {
        AttentionHops.observed = id -> false;
        assertFalse(AttentionHops.maybeBurst(bot(map(FM_ROOM, false)), 0, new Random(1)));
        assertTrue(queue.isEmpty());
    }

    @Test
    void aReplyToAPlayerDoesNotHop() {
        MapleMap fm = map(FM_ROOM, false);
        Character bot = bot(fm);
        BotClient shared = new BotClient(0, 0);
        when(bot.getClient()).thenReturn(shared);
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(7_777);
        when(player.getMapId()).thenReturn(FM_ROOM);
        when(player.getClient()).thenReturn(shared);
        BotConversation.engaged(bot, player);
        assertTrue(BotConversation.isHoldingChat(bot.getId()));
        assertFalse(AttentionHops.maybeBurst(bot, 0, new Random(1)));
        assertTrue(queue.isEmpty());
    }

    @Test
    void busyBotsDoNotHop() {
        MapleMap fm = map(FM_ROOM, false);

        Character walker = bot(fm);
        AttentionHops.walking = b -> b == walker;
        assertFalse(AttentionHops.maybeBurst(walker, 0, new Random(1)), "walking");

        Character seated = bot(fm);
        when(seated.getChair()).thenReturn(3010000);
        assertFalse(AttentionHops.maybeBurst(seated, 0, new Random(1)), "on a chair");

        Character trader = bot(fm);
        when(trader.getTrade()).thenReturn(mock(server.Trade.class));
        assertFalse(AttentionHops.maybeBurst(trader, 0, new Random(1)), "trading");

        Character shopper = bot(fm);
        when(shopper.getShop()).thenReturn(mock(server.Shop.class));
        assertFalse(AttentionHops.maybeBurst(shopper, 0, new Random(1)), "in an NPC shop");

        Character owner = bot(fm);
        when(owner.getPlayerShop()).thenReturn(mock(server.maps.PlayerShop.class));
        assertFalse(AttentionHops.maybeBurst(owner, 0, new Random(1)), "minding a player shop");

        assertTrue(queue.isEmpty());
    }

    @Test
    void aBurstStopsWhenTheBotStartsWalking() {
        AtomicBoolean walkingNow = new AtomicBoolean(false);
        AttentionHops.walking = b -> walkingNow.get();
        AttentionHops.hopper = b -> {
            hopTimes.add(clock);
            walkingNow.set(true); // a real command took over after the first hop
            return true;
        };
        assertTrue(AttentionHops.maybeBurst(bot(map(FM_ROOM, false)), 0, new Random(1)));
        drain();
        assertEquals(1, hopTimes.size());
    }

    @Test
    void noSecondBurstWithinTwentySeconds() {
        Character bot = bot(map(FM_ROOM, false));
        assertTrue(AttentionHops.maybeBurst(bot, 100_000, new Random(1)));
        assertFalse(AttentionHops.maybeBurst(bot, 105_000, new Random(2)), "5 s later");
        assertFalse(AttentionHops.maybeBurst(bot, 119_000, new Random(3)), "19 s later");
        assertTrue(AttentionHops.maybeBurst(bot, 121_000, new Random(4)), "21 s later");
    }

    @Test
    void notEveryLineAndLoudBotsHopMore() {
        double average = AttentionHops.chanceFor(0.5, 0.25);
        assertTrue(average >= 0.6 && average <= 0.7, "about two in three: " + average);
        assertTrue(AttentionHops.chanceFor(0.9, 0.05) > average, "Socializer/Killer more");
        assertTrue(AttentionHops.chanceFor(0.1, 0.7) < average, "Achiever less");
        assertTrue(AttentionHops.chanceFor(2, 0) <= 0.85 && AttentionHops.chanceFor(0, 1) >= 0.40, "never all or none");

        AttentionHops.chance = b -> 0.65;
        Random rng = new Random(11);
        int bursts = 0;
        for (int i = 0; i < 1_000; i++) {
            if (AttentionHops.maybeBurst(bot(map(FM_ROOM, false)), 0, rng)) {
                bursts++;
            }
        }
        assertTrue(bursts > 580 && bursts < 720, "about 65% of eligible lines: " + bursts);
    }

    @Test
    void loneIdleHopsAreRare() {
        Random rng = new Random(5);
        int hops = 0;
        java.util.EnumSet<GCFidget.Action> seen = java.util.EnumSet.noneOf(GCFidget.Action.class);
        for (int i = 0; i < 20_000; i++) {
            GCFidget.Action a = GCFidget.pickAction(rng);
            seen.add(a);
            if (a == GCFidget.Action.HOP) {
                hops++;
            }
        }
        assertTrue(hops < 20_000 * 0.07, "lone hops were 1 in 4, now rare: " + hops);
        assertEquals(java.util.EnumSet.allOf(GCFidget.Action.class), seen, "turn, duck and wander stay");
    }
}
