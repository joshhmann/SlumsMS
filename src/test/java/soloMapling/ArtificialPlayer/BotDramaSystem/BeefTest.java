package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotGossip;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.Reputation;
import soloMapling.ArtificialPlayer.SocialGate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 slice S7 "Beef": each behaviour's triggers and caps - friendly teasing keyed to closeness, bot-to-bot
 * rivalries on rival bonds (threads, KS spats, call-outs), rage-quit and return, friends' drama news, the villain arc,
 * guild feuds - and ContentGuard on every new line.
 */
class BeefTest {

    static final int A = BotStore.BOT_ID_BASE + 701, B = BotStore.BOT_ID_BASE + 702, C = BotStore.BOT_ID_BASE + 703;
    static final int D = BotStore.BOT_ID_BASE + 704;
    static final int PLAYER = 5151;
    static final long MIN = TimeUnit.MINUTES.toMillis(1), HOUR = TimeUnit.HOURS.toMillis(1), DAY = TimeUnit.DAYS.toMillis(1);

    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final Map<Integer, Double> killer = new HashMap<>();
    final Map<Integer, Character> online = new HashMap<>();
    final Map<Integer, Collection<Integer>> circles = new HashMap<>();
    final List<String> exits = new ArrayList<>();
    final List<String> megaphones = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get, id -> new double[]{.25, .25},
                (id, p) -> { }, online::get);
        BeefKit.useForTest(clock::get, id -> killer.getOrDefault(id, 0.1), id -> 0.25, online::get);
        DramaKit.resetForTest();
        DramaNews.resetForTest(id -> circles.getOrDefault(id, List.of()));
        VillainArc.resetForTest();
        BotBotRivalry.resetForTest((s, n, t, k) -> megaphones.add(s.getName() + " " + n + " " + t) || true);
        Teasing.resetForTest();
        RageQuit.useForTest(new RageQuit.Exit() {
            @Override
            public void leave(Character bot) {
                exits.add("leave " + bot.getName());
            }

            @Override
            public boolean comeBack(RageQuit.Away a) {
                exits.add("back " + a.name());
                RageQuit.onBotLogin(online.get(a.charId()));
                return true;
            }
        });
        GuildFeuds.resetForTest();
        SocialGate.resetForTest();
        Reputation.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        BeefKit.useForTest(null, null, null, null);
        DramaKit.resetForTest();
        DramaNews.resetForTest(null);
        VillainArc.resetForTest();
        BotBotRivalry.resetForTest(null);
        Teasing.resetForTest();
        RageQuit.useForTest(null);
        GuildFeuds.resetForTest();
        SocialGate.resetForTest();
        Reputation.resetForTest();
    }

    Character bot(int id, String name) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setName(name);
        c.setLevel(40);
        online.put(id, c);
        return c;
    }

    /** {@code who} comes to resent {@code whom} through KSes (one direction only). */
    void grudge(int who, int whom) {
        long now = clock.get();
        for (int i = 0; i < 30 && !BotBonds.state(who, whom).hostile(); i++) {
            BotBonds.record(who, whom, BondEvent.KS, now, true);
        }
        assertTrue(BotBonds.state(who, whom).hostile(), "KSes made a rival");
        assertFalse(BotBonds.state(whom, who).hostile(), "one way only");
    }

    /** A Random whose doubles are always this value (rolls pass below it, fail at or above). */
    static Random fixed(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    // ── teasing ──

    @Test
    void teasingIsKeyedToClosenessStrangersOnlyMildly() {
        for (Teasing.Trigger t : Teasing.Trigger.values()) {
            double f = Teasing.chance(t, Teasing.Closeness.FRIEND);
            double k = Teasing.chance(t, Teasing.Closeness.KNOWN);
            double s = Teasing.chance(t, Teasing.Closeness.STRANGER);
            assertTrue(f > k && k >= s, t + ": friends tease most, strangers least");
            assertTrue(s <= 0.08, t + ": a stranger only now and then");
            assertNull(Teasing.node(t, null), "a rival never teases: that is beef");
            String strangerNode = Teasing.node(t, Teasing.Closeness.STRANGER);
            assertTrue(strangerNode == null || strangerNode.endsWith("Mild"), t + ": strangers get mild ribbing at most");
        }
        assertEquals("TeaseDeathFriend", Teasing.node(Teasing.Trigger.DEATH, Teasing.Closeness.FRIEND));
        assertEquals("TeaseDeathMild", Teasing.node(Teasing.Trigger.DEATH, Teasing.Closeness.KNOWN));
        assertNull(Teasing.node(Teasing.Trigger.SLOW_LEVEL, Teasing.Closeness.STRANGER), "a stranger knows no level history");
        assertEquals(0, Teasing.chance(Teasing.Trigger.SLOW_LEVEL, Teasing.Closeness.STRANGER));
    }

    @Test
    void closenessComesFromTheBondOrTheTieAndARivalHasNone() {
        bot(A, "Amy");
        bot(B, "Bob");
        bot(C, "Cid");
        assertEquals(Teasing.Closeness.STRANGER, Teasing.toBot(A, B));
        BotBonds.stage(A, B, AffinityState.FRIEND);
        assertEquals(Teasing.Closeness.FRIEND, Teasing.toBot(A, B));
        BotBonds.stage(A, C, AffinityState.RIVAL);
        assertNull(Teasing.toBot(A, C), "a rival has beef, not teasing");
        assertEquals(Teasing.Closeness.STRANGER, Teasing.toPlayer(A, PLAYER), "a player it has no tie with");
    }

    @Test
    void slowLevelIsTheSameLevelADayLaterAndAWeaponTwentyFiveLevelsUnderIsOutdated() {
        long now = clock.get();
        assertFalse(Teasing.stillSameLevel(A, PLAYER, 34, now), "first sighting");
        assertFalse(Teasing.stillSameLevel(A, PLAYER, 34, now + 23 * HOUR));
        assertTrue(Teasing.stillSameLevel(A, PLAYER, 34, now + 24 * HOUR), "still 34 a day later");
        assertFalse(Teasing.stillSameLevel(A, PLAYER, 35, now + 25 * HOUR), "levelled: the clock restarts");
        assertFalse(Teasing.stillSameLevel(A, PLAYER, 35, now + 30 * HOUR));
        assertTrue(Teasing.outdated(60, 30));
        assertFalse(Teasing.outdated(60, 40));
        assertFalse(Teasing.outdated(25, 0), "low levels wear what they find");
        assertFalse(Teasing.outdated(60, -1), "unknown weapon level");
        assertEquals("1st", Teasing.ordinal(1));
        assertEquals("2nd", Teasing.ordinal(2));
        assertEquals("3rd", Teasing.ordinal(3));
        assertEquals("11th", Teasing.ordinal(11));
        assertEquals("13th", Teasing.ordinal(13));
        assertEquals("22nd", Teasing.ordinal(22));
    }

    @Test
    void aTeaseModelPromptStaysOnTheGame() {
        String p = Teasing.prompt("Amy", "Bob", Teasing.Trigger.DEATH, Map.of("COUNT", "3rd"));
        assertTrue(p.contains("friendly teasing") && p.contains("game only") && p.contains("Never"));
        assertTrue(p.contains("Bob just died near you (their 3rd death today)"));
    }

    // ── bot-to-bot rivalry ──

    @Test
    void killersStartMoreBeefAndAVillainMoreStill() {
        bot(A, "Amy");
        bot(B, "Bob");
        BotBonds.stage(A, B, AffinityState.RIVAL);
        long now = clock.get();
        killer.put(A, 0.0);
        double calm = BotBotRivalry.startChance(A, B, now);
        killer.put(A, 1.0);
        double killerChance = BotBotRivalry.startChance(A, B, now);
        assertEquals(0.35, calm, 1e-9);
        assertEquals(0.85, killerChance, 1e-9);
        killer.put(A, 0.2);
        double before = BotBotRivalry.startChance(A, B, now);
        VillainArc.crossed(A, SocialProfile.Type.SOCIALIZER, SocialProfile.Type.KILLER, SocialProfile.Type.SOCIALIZER, now);
        assertEquals(Math.min(0.9, before + VillainArc.TURNED_BOOST), BotBotRivalry.startChance(A, B, now), 1e-9);
        BotBonds.stage(A, B, AffinityState.NEMESIS);
        assertEquals(0.9, BotBotRivalry.startChance(A, B, now), 1e-9, "capped");
    }

    @Test
    void aRivalPairOnTheMapIsFoundTheGrudgeHolderFirst() {
        Character a = bot(A, "Amy"), b = bot(B, "Bob"), c = bot(C, "Cid"), d = bot(D, "Dee");
        assertNull(BotBotRivalry.rivalPair(List.of(a, b, c, d), fixed(0)), "no rivals here");
        grudge(B, D); // only Bob holds the grudge
        List<Character> pair = BotBotRivalry.rivalPair(List.of(a, d, c, b), fixed(0));
        assertNotNull(pair);
        assertEquals(List.of(b, d), pair, "the one holding the grudge opens");
        assertNull(BotBotRivalry.rivalPair(List.of(a, d, c, b), fixed(0.99)), "the roll can say no");
        assertEquals(List.of(b, d), BotBotRivalry.grudgeFirst(List.of(d, b)));
        assertTrue(BotBotRivalry.beefFirst(d, b, fixed(0)));
        assertFalse(BotBotRivalry.beefFirst(a, c, fixed(0)), "no bond, no beef");
    }

    @Test
    void aBeefThreadIsGroundedInTheRealBondAndNamesOnlyTheTwo() {
        Character a = bot(A, "Amy"), b = bot(B, "Bob");
        BotBonds.stage(A, B, AffinityState.RIVAL); // staged: 3 run-ins
        BotGossip.Fact f = BotBotRivalry.beefFact(a, b);
        assertEquals("rivalry", f.kind());
        assertTrue(f.negative());
        assertTrue(f.names().isEmpty(), "no third name for the guard to catch");
        assertTrue(f.about().contains("Amy has beef with Bob") && f.about().contains("3 run-ins"), f.about());
        assertTrue(f.opener().contains("Bob"), f.opener());
        assertNull(ContentGuard.chatProblem(f.opener()), f.opener());

        // a rivalry that grew from KSes says so
        Character c = bot(C, "Cid");
        grudge(C, A);
        BotGossip.Fact ks = BotBotRivalry.beefFact(c, a);
        assertTrue(ks.about().contains("Amy ksed Cid"), ks.about());

        BotBonds.stage(A, B, AffinityState.NEMESIS);
        BotGossip.Fact nem = BotBotRivalry.beefFact(a, b);
        assertTrue(nem.about().endsWith("nemesis"), nem.about());
        String reply = BotBotRivalry.beefReply(b, a);
        assertNotNull(reply);
        assertNull(ContentGuard.chatProblem(reply), reply);
    }

    @Test
    void aKsdRivalSpeaksUpMoreThanAStrangerAndAKillerMost() {
        long now = clock.get();
        killer.put(A, 0.0);
        double stranger = BotBotRivalry.spatChance(A, false, now);
        double rival = BotBotRivalry.spatChance(A, true, now);
        killer.put(A, 1.0);
        double killerRival = BotBotRivalry.spatChance(A, true, now);
        assertEquals(0.15, stranger, 1e-9);
        assertEquals(0.6, rival, 1e-9);
        assertEquals(1.0, killerRival, 1e-9);
    }

    @Test
    void callOutsAreBotOnlyAndCappedPerHourPerDayAndPerTarget() {
        Character a = bot(A, "Amy");
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER);
        when(player.getName()).thenReturn("Pat");
        assertNull(BotBotRivalry.callout(a, player, 1, clock.get(), fixed(0), false), "a real player is never named here");
        assertTrue(megaphones.isEmpty());

        int sent = 0;
        int target = 800;
        for (int hour = 0; hour < 30; hour++) {
            long now = clock.get() + hour * HOUR;
            for (int i = 0; i < 4; i++) {
                Character t = bot(BotStore.BOT_ID_BASE + target++, "T" + target);
                if (BotBotRivalry.callout(a, t, 1, now, fixed(0), false) != null) {
                    sent++;
                }
            }
            if (hour == 0) {
                assertEquals(BotBotRivalry.CALLOUTS_PER_HOUR, sent, "two an hour");
            }
        }
        assertTrue(sent <= 2 * BotBotRivalry.CALLOUTS_PER_DAY, "at most six a UTC day (30 h span two days): " + sent);
        Character t = bot(B, "Bob");
        long later = clock.get() + 40 * DAY;
        assertNotNull(BotBotRivalry.callout(a, t, 1, later, fixed(0), false));
        assertNull(BotBotRivalry.callout(a, t, 1, later + 2 * HOUR, fixed(0), false), "once a day per target");
        assertNull(BotBotRivalry.callout(a, t, 0.04, later + 3 * DAY, fixed(0.5), false), "the roll says no");
        assertTrue(BotBotRivalry.calloutChance(1.0) > BotBotRivalry.calloutChance(0.0), "a Killer calls out more");
    }

    // ── rage quit ──

    @Test
    void onlyAnExpensiveBoomCanStartARageQuitAndRarely() {
        assertTrue(RageQuit.expensive(300_000, 0));
        assertFalse(RageQuit.expensive(299_999, 99), "the price decides when there is one");
        assertTrue(RageQuit.expensive(null, 50));
        assertFalse(RageQuit.expensive(null, 49));
        assertEquals(0.06, RageQuit.chance(0), 1e-9);
        assertEquals(0.18, RageQuit.chance(1), 1e-9);
        assertTrue(RageQuit.chance(5) <= 0.3);
        Random rng = new Random(7);
        for (int i = 0; i < 500; i++) {
            long away = RageQuit.awayMs(rng);
            assertTrue(away >= 2 * HOUR && away <= 6 * HOUR, "hours later: " + away);
        }
    }

    @Test
    void aRageQuitLeavesComesBackHoursLaterAndItsFriendsTalkAboutIt() {
        Character a = bot(A, "Amy");
        Character friend = bot(B, "Bob");
        Character other = bot(C, "Cid");
        circles.put(B, List.of(A));
        long now = clock.get();
        String said = RageQuit.quit(a, "maple sword", 3 * HOUR, false, now);
        assertNotNull(said);
        assertTrue(said.startsWith("Amy rage quits"), said);
        assertEquals(List.of("leave Amy"), exits);
        assertTrue(RageQuit.away(A, now + HOUR), "the director leaves it offline");
        BotGossip.Fact news = DramaNews.factFor(friend, other, now + MIN, new Random(1));
        assertNotNull(news, "its friend heard about it");
        assertEquals(List.of("Amy"), news.names());
        assertTrue(news.opener().contains("Amy"), news.opener());
        assertNull(DramaNews.factFor(other, friend, now + MIN, new Random(1)), "a stranger's circle does not know");
        assertNull(DramaNews.factFor(friend, a, now + MIN, new Random(1)), "not about the one it talks to");

        assertTrue(RageQuit.step(now + HOUR).isEmpty(), "not yet");
        List<String> back = RageQuit.step(now + 3 * HOUR);
        assertEquals(List.of("Amy"), back);
        assertFalse(RageQuit.away(A, now + 3 * HOUR));
        assertEquals("back Amy", exits.getLast());
        assertNotNull(DramaNews.latest(A, DramaNews.Kind.BACK, now + 3 * HOUR), "the sheepish return is news too");
        assertFalse(RageQuit.returningNow(A), "its line was said");
    }

    @Test
    void rageQuitsAreCappedPerBotPerHourAndPerDay() {
        long now = clock.get();
        Character a = bot(A, "Amy");
        assertNotNull(RageQuit.quit(a, "x", HOUR, false, now));
        assertEquals("rage quit capped", RageQuit.quit(a, "x", HOUR, false, now + 2 * HOUR), "once per bot in 3 days");
        int quits = 0;
        for (int i = 0; i < 40; i++) {
            Character b = bot(BotStore.BOT_ID_BASE + 900 + i, "B" + i);
            String r = RageQuit.quit(b, "x", HOUR, false, now + i * 10 * MIN); // 40 tries over ~6.5 h
            if (r != null && !r.equals("rage quit capped")) {
                quits++;
            }
        }
        assertTrue(quits <= 2 * 7, "two an hour at most: " + quits);
        long day2 = now - Math.floorMod(now, DAY) + DAY;
        int dayQuits = 0;
        for (int i = 0; i < 48; i++) {
            Character b = bot(BotStore.BOT_ID_BASE + 1000 + i, "C" + i);
            String r = RageQuit.quit(b, "x", HOUR, false, day2 + i * 30 * MIN);
            if (r != null && !r.equals("rage quit capped")) {
                dayQuits++;
            }
        }
        assertEquals(RageQuit.PER_DAY, dayQuits, "eight a day world-wide");
        assertNotNull(RageQuit.quit(a, "x", HOUR, false, now + 4 * DAY), "three days later it may again");
    }

    @Test
    void aStagedBotHopsAwayAndBackThroughTheRealExit() {
        // only in the online lookup, as a !qa idle bot is only in the world's storage (never in CharacterStorage)
        java.util.concurrent.atomic.AtomicInteger channel = new java.util.concurrent.atomic.AtomicInteger(1);
        client.Client cl = mock(client.Client.class);
        when(cl.getChannel()).thenAnswer(x -> channel.get());
        Character a = mock(Character.class);
        when(a.getId()).thenReturn(A);
        when(a.getName()).thenReturn("Amy");
        when(a.getClient()).thenReturn(cl);
        online.put(A, a);
        java.util.List<Integer> hops = new java.util.ArrayList<>();
        RageQuit.useForTest(new RageQuit.RealExit()); // the real exit: a staged bot hops channels
        RageQuit.useHopForTest((bot, ch) -> {
            hops.add(ch);
            channel.set(ch);
            return true;
        });
        try {
            long now = clock.get();
            assertNotNull(RageQuit.forced(a, 1302020, 25_000));
            assertEquals(List.of(2), hops, "left for another channel");
            assertTrue(RageQuit.step(now + 10_000).isEmpty(), "not yet");
            assertEquals(List.of("Amy"), RageQuit.step(now + 30_000), "back");
            assertEquals(List.of(2, 1), hops, "and hopped back to its own channel");
            assertNotNull(DramaNews.latest(A, DramaNews.Kind.BACK, now + 30_000), "it said it is back");
            assertTrue(RageQuit.describe(now + 30_000).isEmpty(), "no longer away");
        } finally {
            RageQuit.useHopForTest(null);
        }
    }

    @Test
    void aReturnThatNeverLogsInIsForgottenAfterSixHours() {
        Character a = bot(A, "Amy");
        RageQuit.useForTest(new RageQuit.Exit() {
            @Override
            public void leave(Character bot) {
            }

            @Override
            public boolean comeBack(RageQuit.Away x) {
                return false;
            }
        });
        long now = clock.get();
        RageQuit.quit(a, "x", HOUR, false, now);
        assertTrue(RageQuit.step(now + 2 * HOUR).isEmpty());
        assertTrue(RageQuit.describe(now + 2 * HOUR).get(0).startsWith("Amy"), "still due back");
        RageQuit.step(now + 8 * HOUR);
        assertTrue(RageQuit.describe(now + 8 * HOUR).isEmpty(), "given up: the director's logins take over");
    }

    // ── villain arc ──

    @Test
    void aBotTurningKillerStartsMoreBeefAndItsFriendsNoticeRedemptionWorksBack() {
        bot(A, "Amy");
        Character friend = bot(B, "Bob");
        circles.put(B, List.of(A));
        long now = clock.get();
        VillainArc.crossed(A, SocialProfile.Type.ACHIEVER, SocialProfile.Type.EXPLORER, SocialProfile.Type.ACHIEVER, now);
        assertEquals(0, VillainArc.boost(A, now), "other quadrants change nothing here");
        VillainArc.crossed(A, SocialProfile.Type.SOCIALIZER, SocialProfile.Type.KILLER, SocialProfile.Type.SOCIALIZER, now);
        assertEquals(VillainArc.TURNED_BOOST, VillainArc.boost(A, now + DAY), 1e-9);
        BotGossip.Fact f = DramaNews.factFor(friend, bot(C, "Cid"), now + MIN, new Random(2));
        assertNotNull(f);
        assertEquals("drama-turned", f.kind());
        assertTrue(f.opener().contains("Amy"), "\"he changed man\": " + f.opener());
        assertEquals(0, VillainArc.boost(A, now + 8 * DAY), "the arc fades after a week");

        VillainArc.crossed(A, SocialProfile.Type.KILLER, SocialProfile.Type.SOCIALIZER, SocialProfile.Type.SOCIALIZER,
                now + 9 * DAY);
        assertEquals(VillainArc.REDEEMED_BOOST, VillainArc.boost(A, now + 9 * DAY), 1e-9);
        assertNotNull(DramaNews.latest(A, DramaNews.Kind.REDEEMED, now + 9 * DAY));
        VillainArc.LISTENER.crossed(A, SocialProfile.Type.SOCIALIZER, SocialProfile.Type.KILLER, null, now + 10 * DAY);
        assertEquals(VillainArc.TURNED_BOOST, VillainArc.boost(A, now + 10 * DAY), 1e-9, "the S9 hook");
    }

    @Test
    void newsIsBoundedAndFades() {
        long now = clock.get();
        for (int i = 0; i < DramaNews.MAX + 20; i++) {
            DramaNews.add(DramaNews.Kind.RAGE_QUIT, BotStore.BOT_ID_BASE + 2000 + i, "N" + i, null, now);
        }
        assertEquals(DramaNews.MAX, DramaNews.recent(now).size());
        assertTrue(DramaNews.recent(now + DramaNews.TTL_MS + 1).isEmpty());
    }

    // ── guild feuds ──

    @Test
    void aFeudIsTwoGuildMatesWithAGrudgeOncePerPair() {
        Character a = bot(A, "Amy"), b = bot(B, "Bob"), c = bot(C, "Cid");
        assertTrue(GuildFeuds.feuds(List.of(a, b, c)).isEmpty());
        BotBonds.stage(A, B, AffinityState.RIVAL); // both ways
        BotBonds.stage(A, C, AffinityState.NEMESIS);
        List<GuildFeuds.Feud> feuds = GuildFeuds.feuds(List.of(a, b, c));
        assertEquals(2, feuds.size(), "one per pair, though both hold it: " + feuds);
        assertTrue(feuds.stream().anyMatch(GuildFeuds.Feud::nemesis));
        assertEquals(GuildFeuds.pair(A, B), GuildFeuds.pair(B, A));
    }

    // ── lines ──

    @Test
    void everyBeefLineIsSafeToSayAndNeverTalksAboutAi() throws Exception {
        List<String> lines = new ArrayList<>();
        for (String prefix : new String[]{"Tease", "Beef", "KsSpat", "RageQuit", "RageReturn", "News", "VillainNotice",
                "RedeemedNotice", "GuildFeud"}) {
            List<String> some = ContentGuardTest.lines("DramaDialogue.yaml", "Drama", prefix);
            assertFalse(some.isEmpty(), prefix);
            lines.addAll(some);
        }
        lines.addAll(ContentGuardTest.lines("CoupleDialogue.yaml", "Crush", null));
        lines.addAll(ContentGuardTest.lines("MegaphoneDialogue.yaml", "MegaphoneBroadcast", "DramaBotCallout"));
        lines.addAll(ContentGuardTest.lines("MegaphoneDialogue.yaml", "MegaphoneBroadcast", "DramaCoupleBreakup"));
        assertTrue(lines.size() > 100, "lines read: " + lines.size());
        for (String line : lines) {
            String filled = line.replace("{PLAYER}", "Bob").replace("{OTHER}", "Cid").replace("{PARTNER}", "Amy")
                    .replace("{ITEM}", "maple sword").replace("{WEAPON}", "wooden club").replace("{LEVEL}", "45")
                    .replace("{COUNT}", "3");
            assertNull(ContentGuard.chatProblem(filled), line);
            assertFalse(ContentGuard.aiTalk(filled), line);
            assertTrue(filled.length() <= 60, "short: " + line);
            assertTrue(line.chars().allMatch(ch -> ch < 128), "ASCII only: " + line);
        }
    }

    @Test
    void theScriptedLinesAreThere() {
        assertNotNull(DramaKit.pick("RageQuit", Map.of("ITEM", "maple sword")));
        assertNotNull(DramaKit.pick("RageReturn", Map.of()));
        assertNotNull(DramaKit.pick("KsSpatVictim", Map.of("OTHER", "Bob")));
        assertNotNull(DramaKit.pick("TeaseDeathFriend", Map.of("PLAYER", "Bob", "COUNT", "2nd")));
        assertNotNull(DramaKit.pick("TeaseSlowLevel", Map.of("PLAYER", "Bob", "LEVEL", "34")));
    }
}
