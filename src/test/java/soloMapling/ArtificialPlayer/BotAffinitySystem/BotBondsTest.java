package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.SocialGate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.0 S2: bot-to-bot bonds. Bounded per bot, fading in weeks, made only by shared events, stored in batches. */
class BotBondsTest {

    static final int A = BotStore.BOT_ID_BASE + 1, B = BotStore.BOT_ID_BASE + 2, C = BotStore.BOT_ID_BASE + 3;
    static final int D = BotStore.BOT_ID_BASE + 4, E = BotStore.BOT_ID_BASE + 5;
    static final long MIN = TimeUnit.MINUTES.toMillis(1);
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final BondStore.Memory store = new BondStore.Memory();
    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    final Map<Integer, double[]> shares = new HashMap<>();
    final List<String> pushes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotBonds.useForTest(store, Runnable::run, clock::get,
                id -> shares.getOrDefault(id, new double[]{.25, .25}), (id, p) -> pushes.add(id + ":" + p), id -> null);
        SocialGate.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        SocialGate.resetForTest();
        System.clearProperty("solomapling.bonds.k");
    }

    /** One shared event both ways, then past every cooldown. */
    private void both(int a, int b, BondEvent e) {
        BotBonds.together(a, b, e);
        clock.addAndGet(31 * MIN);
    }

    private void friends(int a, int b) {
        both(a, b, BondEvent.PARTY);
        both(a, b, BondEvent.PARTY);
        both(a, b, BondEvent.PARTY);
        both(a, b, BondEvent.PQ);
        assertTrue(BotBonds.mutualFriends(a, b));
    }

    // ── thresholds ──

    @Test
    void aFriendNeedsScoreAndThreeKindThings() {
        long now = clock.get();
        for (int i = 0; i < 3; i++) {
            BotBonds.record(A, B, BondEvent.GIFT, now, true);
        }
        assertEquals(24, BotBonds.score(A, B), 1e-9);
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(A, B), "24 is under friendAt (25)");
        BotBonds.record(A, B, BondEvent.NEAR, now, true);
        assertEquals(AffinityState.FRIEND, BotBonds.state(A, B));
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(B, A), "directional: B felt nothing");

        // a big score is not enough on its own: two big things are only 2 kind things
        BotBonds.record(C, D, BondEvent.PQ, now, true);
        BotBonds.record(C, D, BondEvent.PQ, now, true);
        BotBonds.stage(C, E, AffinityState.FRIEND);
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(C, D), "16 with kind 2");
        BotBonds.record(C, D, BondEvent.PQ, now, true);
        BotBonds.record(C, D, BondEvent.PQ, now, true); // 32, kind 4
        assertEquals(AffinityState.FRIEND, BotBonds.state(C, D));
    }

    @Test
    void aRivalNeedsTwoHostileThingsAndANemesisFive() {
        BotBonds.record(A, B, BondEvent.KS, clock.get(), true);
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(A, B), "one KS is a moment, not a rivalry");
        for (int i = 0; i < 4; i++) {
            BotBonds.record(A, B, BondEvent.KS, clock.get(), true);
        }
        assertEquals(AffinityState.RIVAL, BotBonds.state(A, B), "-30 with 5 hostile");
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(B, A), "the thief does not resent its victim");
        for (int i = 0; i < 6; i++) {
            BotBonds.record(A, B, BondEvent.KS, clock.get(), true);
        }
        assertEquals(AffinityState.NEMESIS, BotBonds.state(A, B));
        assertEquals(List.of(B), BotBonds.rivalsOf(A));

        System.setProperty("solomapling.affinity.hostility", "false");
        try {
            assertEquals(AffinityState.NEUTRAL, BotBonds.state(A, B), "hostility off server-wide hides rivals");
        } finally {
            System.clearProperty("solomapling.affinity.hostility");
        }
    }

    @Test
    void cooldownsKeepASpammedEventToOne() {
        assertTrue(BotBonds.record(A, B, BondEvent.PARTY_TIME, clock.get(), false));
        clock.addAndGet(MIN);
        assertFalse(BotBonds.record(A, B, BondEvent.PARTY_TIME, clock.get(), false), "5 min cooldown");
        clock.addAndGet(5 * MIN);
        assertTrue(BotBonds.record(A, B, BondEvent.PARTY_TIME, clock.get(), false));
        assertFalse(BotBonds.record(A, A, BondEvent.PARTY, clock.get(), false), "no bond with yourself");
        assertFalse(BotBonds.record(A, 42, BondEvent.PARTY, clock.get(), false), "a real player is not a bot bond");
    }

    // ── decay ──

    @Test
    void friendshipsHalveInSixWeeksAndGrudgesInTwo() {
        assertEquals(25, BondRules.faded(50, 42 * DAY), 1e-9);
        assertEquals(-25, BondRules.faded(-50, 14 * DAY), 1e-9);
        assertEquals(12.5, BondRules.faded(50, 84 * DAY), 1e-9);
        assertEquals(50, BondRules.faded(50, -5), 1e-9, "no fade backwards in time");

        BotBonds.stage(A, B, AffinityState.FRIEND); // 60
        clock.addAndGet(42 * DAY);
        assertEquals(30, BotBonds.score(A, B), 1e-6);
        assertEquals(AffinityState.FRIEND, BotBonds.state(A, B), "a six-week-old friendship still holds");
        BotBonds.stage(C, D, AffinityState.RIVAL); // -40
        clock.addAndGet(14 * DAY);
        assertEquals(-20, BotBonds.score(C, D), 1e-6);
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(C, D), "a grudge cools within a few weeks");
    }

    @Test
    void aFadedBondForgetsItsHistory() {
        both(A, B, BondEvent.PARTY);
        both(A, B, BondEvent.PARTY);
        clock.addAndGet(400 * DAY); // 12 -> well under 3
        BotBonds.record(A, B, BondEvent.NEAR, clock.get(), false);
        assertEquals(1, BotBonds.bondsOf(A).get(0).kind(), "starts over");
    }

    // ── Bartle ──

    @Test
    void socializersBondFasterAndKillersSourFaster() {
        shares.put(A, new double[]{.9, .02});  // socializer
        shares.put(C, new double[]{.02, .9});  // killer
        int socializerTicks = ticksToFriend(A, B);
        int evenTicks = ticksToFriend(D, E);
        assertTrue(socializerTicks < evenTicks, socializerTicks + " vs " + evenTicks);
        assertEquals(1.8, BondRules.scale(5, .9, 0), 1e-9);
        assertEquals(0.6, BondRules.scale(5, 0, .9), 1e-9);
        assertEquals(1.0, BondRules.scale(-5, .9, .25), 1e-9);

        BotBonds.record(C, B, BondEvent.KS, clock.get(), true);
        BotBonds.record(C, B, BondEvent.KS, clock.get(), true);
        BotBonds.record(C, B, BondEvent.KS, clock.get(), true);
        BotBonds.record(E, B, BondEvent.KS, clock.get(), true);
        BotBonds.record(E, B, BondEvent.KS, clock.get(), true);
        BotBonds.record(E, B, BondEvent.KS, clock.get(), true);
        assertEquals(AffinityState.RIVAL, BotBonds.state(C, B), "a killer holds a grudge after 3 KS (-32)");
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(E, B), "an even bot is at -18");
    }

    private int ticksToFriend(int a, int b) {
        for (int i = 1; i <= 40; i++) {
            BotBonds.record(a, b, BondEvent.PARTY_TIME, clock.get(), true);
            if (BotBonds.friend(a, b)) {
                return i;
            }
        }
        return 99;
    }

    @Test
    void becomingFriendsPushesTheChartOnce() {
        friends(A, B);
        assertEquals(List.of(A + ":" + SocialProfile.Push.PARTY, B + ":" + SocialProfile.Push.PARTY), pushes);
        both(A, B, BondEvent.PARTY);
        assertEquals(2, pushes.size(), "staying friends is not a new push");
        BotBonds.onThread(C, D, false);
        for (int i = 0; i < 9; i++) {
            BotBonds.record(C, D, BondEvent.THREAD, clock.get(), true);
        }
        assertTrue(pushes.contains(C + ":" + SocialProfile.Push.CHAT), "chat friends push CHAT");
    }

    // ── the K bound ──

    @Test
    void aBotKeepsAtMostKBondsAndTheWeakestGo() {
        int k = BotBonds.maxBonds();
        assertEquals(14, k);
        for (int i = 0; i < 20; i++) {
            int other = BotStore.BOT_ID_BASE + 100 + i;
            for (int n = 0; n <= i; n++) { // the later ones are stronger
                BotBonds.record(A, other, BondEvent.PARTY_TIME, clock.get(), true);
            }
        }
        assertEquals(k, BotBonds.sizeOf(A));
        Set<Integer> held = new HashSet<>();
        BotBonds.bondsOf(A).forEach(v -> held.add(v.otherId()));
        for (int i = 0; i < 20; i++) {
            assertEquals(i >= 20 - k, held.contains(BotStore.BOT_ID_BASE + 100 + i), "bond " + i);
        }
        BotBonds.flush();
        assertEquals(k, store.size(), "only the kept bonds are stored");

        // a weak newcomer to a full list of stronger bonds never makes it, and leaves nothing to delete
        int writes = store.writes;
        assertFalse(BotBonds.record(A, B, BondEvent.NEAR, clock.get(), false));
        assertEquals(k, BotBonds.sizeOf(A));
        assertEquals(0, BotBonds.flush());
        assertEquals(writes, store.writes);

        // a strong newcomer pushes the weakest out, and the store follows
        BotBonds.stage(A, C, AffinityState.FRIEND);
        assertEquals(k, BotBonds.sizeOf(A));
        BotBonds.flush();
        assertEquals(k + 1, store.size(), "A's 14, and C's new bond about A (staging is both ways)");
        assertNotNull(store.row(A, C));
        assertNull(store.row(A, BotStore.BOT_ID_BASE + 100 + 20 - k), "the weakest was deleted");
    }

    @Test
    void theBoundIsTunableButClamped() {
        System.setProperty("solomapling.bonds.k", "1000");
        assertEquals(32, BotBonds.maxBonds());
        System.setProperty("solomapling.bonds.k", "1");
        assertEquals(4, BotBonds.maxBonds());
    }

    // ── no O(N^2) ──

    @Test
    void theSamplerCapsPairsPerMap() {
        List<Integer> crowd = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            crowd.add(i);
        }
        Set<Long> seen = new HashSet<>();
        int given = BotBonds.samplePairs(crowd, 3, new Random(1), (x, y) -> {
            assertTrue(!x.equals(y));
            assertTrue(seen.add(Math.min(x, y) * 1000L + Math.max(x, y)), "distinct pairs");
        });
        assertEquals(3, given);
        assertEquals(1, BotBonds.samplePairs(List.of(1, 2), 3, new Random(1), (x, y) -> { }), "2 people, 1 pair");
        assertEquals(0, BotBonds.samplePairs(List.of(1), 3, new Random(1), (x, y) -> { }));
    }

    @Test
    void theMinuteStepTouchesPartiesAndAFewSampledPairsOnObservedMapsOnly() {
        BotBonds.useObservedForTest(mapId -> mapId == 100);
        MapleMap observed = map(100);
        MapleMap quiet = map(200);
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(9);
        List<Character> bots = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            bots.add(bot(BotStore.BOT_ID_BASE + 1000 + i, i < 150 ? observed : quiet, i < 3 ? party : null));
        }
        BotBonds.step(bots, new Random(7));
        int touched = 0;
        for (Character c : bots) {
            touched += BotBonds.sizeOf(c.getId());
        }
        // party of 3: 3 pairs both ways (6 bonds); observed map: at most 3 pairs both ways (6); the quiet map: none
        assertTrue(touched <= 12, "touched " + touched);
        assertTrue(touched >= 6, "the party counted: " + touched);
        for (int i = 150; i < 300; i++) {
            assertEquals(0, BotBonds.sizeOf(BotStore.BOT_ID_BASE + 1000 + i), "an unobserved map is never sampled");
        }
        assertTrue(BotBonds.stats().contains("party pairs 3"), BotBonds.stats());
    }

    @Test
    void partiedBotsBecomeFriendsAfterHalfAnHourTogether() {
        BotBonds.useObservedForTest(mapId -> false);
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(5);
        MapleMap m = map(300);
        List<Character> two = List.of(bot(A, m, party), bot(B, m, party));
        BotBonds.onPartyJoin(two.get(0), two.get(1));
        int minutes = 0;
        while (!BotBonds.mutualFriends(A, B) && minutes < 120) {
            BotBonds.step(two, new Random(minutes));
            clock.addAndGet(MIN);
            minutes++;
        }
        assertTrue(minutes > 15 && minutes <= 45, "friends after " + minutes + " minutes");
        assertEquals(List.of(A, B), BotBonds.crewOf(A));
    }

    // ── hooks ──

    @Test
    void theAffinityHooksFeedBotPairs() {
        MapleMap m = map(100);
        Character a = bot(A, m, null), b = bot(B, m, null);
        AffinityHooks.onPartyJoin(a, List.of(a, b));
        assertEquals(6, Math.round(BotBonds.score(A, B)));
        assertEquals(6, Math.round(BotBonds.score(B, A)));
        AffinityHooks.onBuff(a, b, false);
        assertEquals(8, Math.round(BotBonds.score(B, A)), "B was buffed by A");
        AffinityHooks.onTrade(a, List.of(), 1000, b, List.of(), 0);
        assertEquals(16, Math.round(BotBonds.score(B, A)), "A gave B something for nothing");
        assertEquals(6, Math.round(BotBonds.score(A, B)));
    }

    @Test
    void aBotTakingAnotherBotsMobOnAnObservedMapIsAKs() {
        BotBonds.useObservedForTest(mapId -> mapId == 100);
        MapleMap m = map(100);
        Character thief = bot(A, m, null), victim = bot(B, m, null);
        when(victim.isAlive()).thenReturn(true);
        when(m.getCharacterById(B)).thenReturn(victim);
        Monster mob = mock(Monster.class);
        when(mob.isAttackedBy(thief)).thenReturn(false);
        when(mob.recentAttackerAtOrAbove(eq(BotStore.BOT_ID_BASE), eq(A), anyLong())).thenReturn(B);
        BotBonds.beforeBotHit(thief, mob);
        assertEquals(-6, Math.round(BotBonds.score(B, A)), "the victim resents the thief");
        assertEquals(0, Math.round(BotBonds.score(A, B)));

        BotBonds.useObservedForTest(mapId -> false);
        clock.addAndGet(10 * MIN);
        BotBonds.beforeBotHit(thief, mob);
        assertEquals(-6, Math.round(BotBonds.score(B, A)), "unobserved maps never count");
    }

    // ── persistence ──

    @Test
    void bondsSurviveAFlushAndAReload() {
        friends(A, B);
        for (int i = 0; i < 5; i++) {
            BotBonds.record(C, D, BondEvent.KS, clock.get(), true);
        }
        double ab = BotBonds.score(A, B);
        assertTrue(BotBonds.flush() >= 3);
        assertEquals(0, BotBonds.flush(), "nothing changed since");
        store.rows.put(BotBonds.key(D, E), new Bond(D, E, 3.5, 1, 0, clock.get() - 400 * DAY)); // long faded

        BotBonds.useForTest(store, Runnable::run, clock::get, id -> new double[]{.25, .25}, (id, p) -> { }, id -> null);
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(A, B), "forgotten until loaded");
        assertTrue(BotBonds.loadNow());
        assertEquals(AffinityState.FRIEND, BotBonds.state(A, B));
        assertEquals(AffinityState.FRIEND, BotBonds.state(B, A));
        assertEquals(AffinityState.RIVAL, BotBonds.state(C, D));
        assertEquals(ab, BotBonds.score(A, B), 1e-4);
        assertEquals(List.of(A, B), BotBonds.crewOf(A));
        assertEquals(0, BotBonds.sizeOf(D), "a faded row is not loaded");
        BotBonds.flush();
        assertNull(store.row(D, E), "and is deleted");
    }

    @Test
    void aTouchedPairWinsOverItsStoredRowAndAFailedReadRetries() {
        store.rows.put(BotBonds.key(A, B), new Bond(A, B, -50, 0, 4, clock.get()));
        BotBonds.stage(A, B, AffinityState.FRIEND);
        assertTrue(BotBonds.loadNow());
        assertEquals(AffinityState.FRIEND, BotBonds.state(A, B), "memory (newer) wins");

        BotBonds.useForTest(store, Runnable::run, clock::get, null, (id, p) -> { }, id -> null);
        store.failing = true;
        assertFalse(BotBonds.loadNow());
    }

    @Test
    void forgettingABotDropsItsBondsBothWays() {
        friends(A, B);
        friends(B, C);
        BotBonds.flush();
        BotBonds.forgetBot(B);
        assertEquals(0, BotBonds.sizeOf(B));
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(A, B));
        BotBonds.flush();
        assertEquals(0, store.size());
    }

    // ── crews ──

    @Test
    void aCrewIsMutualFriendsTwoHopsDeepAtMostSix() {
        friends(A, B);
        friends(B, C);
        friends(C, D); // three hops from A
        assertEquals(List.of(A, B, C), BotBonds.crewOf(A));
        assertTrue(BotBonds.crew(A, C), "a friend of a friend is crew");
        assertFalse(BotBonds.crew(A, D), "three hops is not");

        // one-way feelings are not crew
        for (int i = 0; i < 5; i++) {
            BotBonds.record(E, A, BondEvent.PQ, clock.get(), true);
        }
        assertTrue(BotBonds.friend(E, A));
        assertFalse(BotBonds.crew(E, A));
        assertFalse(BotBonds.crew(A, E));

        int hub = BotStore.BOT_ID_BASE + 500;
        for (int i = 0; i < 9; i++) {
            friends(hub, BotStore.BOT_ID_BASE + 600 + i);
        }
        assertEquals(BotBonds.CREW_MAX, BotBonds.crewOf(hub).size());
        assertEquals(hub, BotBonds.crewOf(hub).get(0));
        assertEquals(List.of(BotStore.BOT_ID_BASE + 7), BotBonds.crewOf(BotStore.BOT_ID_BASE + 7), "alone");
    }

    @Test
    void aCrewChangesWhenABondDoes() {
        friends(A, B);
        assertEquals(List.of(A, B), BotBonds.crewOf(A));
        BotBonds.stage(A, B, AffinityState.NEUTRAL);
        assertEquals(List.of(A), BotBonds.crewOf(A), "the cache is dropped on a state change");
    }

    @Test
    void theSocialGateCountsBondedFriendsAsCrew() {
        assertFalse(BotBonds.CREW.crew(A, B));
        friends(A, B);
        assertTrue(BotBonds.CREW.crew(A, B));
        assertTrue(BotBonds.CREW.crew(B, A));
        assertFalse(BotBonds.CREW.crew(A, A));

        int player = 4242;
        SocialGate.playerStarted(A, player, SocialGate.Provocation.KS);
        assertFalse(SocialGate.rules(B, player, SocialGate.Act.SIDE_EYE).ok(), "not registered yet");
        BotBonds.maybeStep(clock.get()); // registers the source (and loads, and steps)
        assertTrue(SocialGate.rules(B, player, SocialGate.Act.SIDE_EYE).ok(),
                "B side-eyes the player who KS'd its crew mate A");
        assertFalse(SocialGate.rules(B, player, SocialGate.Act.TAUNT).ok(), "but spice stays with A");
        assertFalse(SocialGate.rules(C, player, SocialGate.Act.SIDE_EYE).ok(), "a stranger bot does not");
    }

    @Test
    void whyShowsTopBondsAndTheCrew() {
        friends(A, B);
        BotBonds.record(A, C, BondEvent.KS, clock.get(), true);
        BotBonds.record(A, C, BondEvent.KS, clock.get(), true);
        BotBonds.record(A, C, BondEvent.KS, clock.get(), true);
        BotBonds.record(A, C, BondEvent.KS, clock.get(), true);
        BotBonds.record(A, C, BondEvent.KS, clock.get(), true);
        List<String> lines = BotBonds.describe(A, id -> "n" + (id - BotStore.BOT_ID_BASE), 6);
        assertTrue(lines.get(0).startsWith("Bonds 2/14: n3 rival -30 (k0 h5), n2 friend 26 (k4 h0)"), lines.get(0));
        assertEquals("Crew of 2: n2 (offline)", lines.get(1));
        assertEquals("Crew: none (no mutual friend yet)", BotBonds.describe(D, id -> "x", 6).get(1));
    }

    // ── helpers ──

    static MapleMap map(int id) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(id);
        when(m.getChannelId()).thenReturn(1);
        when(m.getCharacterById(anyInt())).thenReturn(null);
        return m;
    }

    static Character bot(int id, MapleMap map, Party party) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("b" + id);
        when(c.getMap()).thenReturn(map);
        int mapId = map.getId();
        when(c.getMapId()).thenReturn(mapId);
        when(c.getParty()).thenReturn(party);
        when(c.getGuildId()).thenReturn(0);
        return c;
    }
}
