package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 1.6.0 S9: bot-made fads spread along bonds, players can join, and they die out in a few days. */
class FadsTest {
    static final long MIN = 60_000L, DAY = 86_400_000L, T0 = 1_800_000_000_000L;

    /** Bots 1-4 a chain of friends, 5 and 6 friendless, all on channel 1 in Henesys. */
    static final class Fake implements Fads.World {
        final Map<Integer, List<Integer>> friends = new HashMap<>(Map.of(1, List.of(2), 2, List.of(1, 3),
                3, List.of(2, 4), 4, List.of(3), 5, List.of(), 6, List.of()));
        int playersAtSpot;
        /** Channels where new fads may start on their own (none: only the test starts them). */
        List<Integer> starting = List.of(1);

        public List<Integer> onlineBots(int channel) { return channel == 1 ? List.of(1, 2, 3, 4, 5, 6) : List.of(); }
        public List<Integer> channels() { return starting; }
        public int channelOf(int botId) { return friends.containsKey(botId) ? 1 : -1; }
        public int mapOf(int botId) { return 100000000; }
        public List<Integer> friendsOf(int botId) { return friends.getOrDefault(botId, List.of()); }
        public double socialPull(int botId) { return .5; }
        public int playersAt(int channel, int mapId) { return playersAtSpot; }
        public int playersOnChair(int channel, int chairId) { return 0; }
    }

    final Fake world = new Fake();

    @BeforeEach
    void setUp() {
        Fads.useForTest(world, new Random(7));
    }

    @AfterEach
    void tearDown() {
        Fads.useForTest(null, null);
        System.clearProperty("solomapling.fads.noFile");
    }

    @Test
    void aFadSpreadsAlongBondsOnlyAndDiesOut() {
        world.starting = List.of();
        assertTrue(Fads.startForTest(Fads.Kind.SPOT, 1, 101000000, 1, T0));
        List<Fads.Event> events = new ArrayList<>();
        Fads.listen(events::add);
        long t = T0;
        for (int step = 0; step < 12 * 24 && Fads.follows(4).isEmpty(); step++) Fads.step(t += 5 * MIN);
        assertFalse(Fads.follows(2).isEmpty(), "the starter's friend joined");
        assertFalse(Fads.follows(4).isEmpty(), "and along the chain, friend of a friend of a friend");
        assertTrue(Fads.follows(5).isEmpty() && Fads.follows(6).isEmpty(), "friendless bots never hear of it");
        assertEquals(101000000, Fads.spotFor(3, 1), "a follower hangs out at the spot");
        assertEquals(-1, Fads.spotFor(3, 2), "on its own channel only");
        assertTrue(Fads.isSpot(101000000));
        assertTrue(events.stream().anyMatch(e -> e.change() == Fads.Change.JOINED && e.botId() == 2));
        // then nobody new joins; within a few days it is over
        for (int step = 0; step < 12 * 24 * 6 && !Fads.active().isEmpty(); step++) Fads.step(t += 5 * MIN);
        assertTrue(Fads.active().isEmpty(), "dead after a few days");
        assertTrue(t - T0 <= (Fads.MAX_AGE_DAYS + .01) * DAY);
        assertEquals(-1, Fads.spotFor(3, 1));
        assertTrue(events.stream().anyMatch(e -> e.change() == Fads.Change.ENDED));
        Fads.remove(events::add);
    }

    @Test
    void realPlayersAtTheSpotKeepItWarmer() {
        world.starting = List.of();
        Fads.startForTest(Fads.Kind.SPOT, 1, 101000000, 5, T0); // friendless starter: only players can heat it
        world.playersAtSpot = 0;
        Fads.step(T0 + DAY);
        double cold = Fads.active().getFirst().heat();
        Fads.useForTest(world, new Random(7));
        Fads.startForTest(Fads.Kind.SPOT, 1, 101000000, 5, T0);
        world.playersAtSpot = 3;
        Fads.step(T0 + DAY);
        assertTrue(Fads.active().getFirst().heat() > cold);
        assertEquals(3, Fads.active().getFirst().playersSeen());
    }

    @Test
    void boundedPerChannelAndOnePerKind() {
        assertTrue(Fads.startForTest(Fads.Kind.SPOT, 1, 101000000, 1, T0));
        assertFalse(Fads.startForTest(Fads.Kind.SPOT, 1, 102000000, 2, T0), "one SPOT per channel");
        assertTrue(Fads.startForTest(Fads.Kind.CHAIR, 1, 3010005, 2, T0));
        assertTrue(Fads.startForTest(Fads.Kind.PHRASE, 1, 0, 3, T0));
        assertEquals(Fads.MAX_PER_CHANNEL, Fads.active(1).size());
        assertFalse(Fads.startForTest(Fads.Kind.CHAIR, 1, 9999999, 2, T0), "only chairs from the v83 list");
        assertEquals(3010005, Fads.chairFor(2, 3010001));
        assertEquals(3011000, Fads.chairFor(2, 3011000), "the fishing chair is never replaced");
        assertEquals(Fads.PHRASES.get(0), Fads.phraseFor(3));
        assertNull(Fads.phraseFor(4));
        // many steps never pass the follower cap or the per-channel cap
        for (int i = 0; i < 2000; i++) Fads.step(T0 + i * 5 * MIN);
        for (Fads.View v : Fads.active()) assertTrue(v.followers() <= Fads.MAX_FOLLOWERS);
        assertTrue(Fads.active(1).size() <= Fads.MAX_PER_CHANNEL);
    }

    @Test
    void phrasesComeFromThe2009BankAndPassTheContentGuard() {
        for (String phrase : Fads.PHRASES) {
            assertTrue(soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard.ok(phrase), phrase);
            assertFalse(soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard.aiTalk(phrase), phrase);
        }
    }
}
