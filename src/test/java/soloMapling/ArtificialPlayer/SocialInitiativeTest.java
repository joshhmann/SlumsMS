package soloMapling.ArtificialPlayer;

import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeHost;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeOutcomes;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePq;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.ArtificialPlayer.SocialInitiative.Candidate;
import soloMapling.ArtificialPlayer.SocialInitiative.Kind;
import soloMapling.ArtificialPlayer.SocialInitiative.Planned;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.0 slice S6: each initiative path, its timing, its band and that SocialGate (and {@code @quiet}) holds it. */
class SocialInitiativeTest {

    static final long HOUR = TimeUnit.HOURS.toMillis(1);
    static final int PLAYER = 4242;
    static final int SLOT = 77;
    static final int BOT = BotStore.BOT_ID_BASE + SLOT;
    static final int BOT2 = BotStore.BOT_ID_BASE + 78;

    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final Recorder out = new Recorder();
    final List<long[]> scheduled = new ArrayList<>();
    final Deque<Runnable> tasks = new ArrayDeque<>();
    final Map<Integer, Character> online = new HashMap<>();
    final FakeMemoryTable memory = new FakeMemoryTable(false);
    double roll = 0.0;
    final List<LifeOutcomes.Outcome> outcomes = new ArrayList<>();
    final LifeOutcomes.Listener listener = outcomes::add;

    /** What the initiative sent, in order. */
    static final class Recorder implements SocialInitiative.Out {
        final List<String> sent = new ArrayList<>();
        boolean partyOk = true;

        @Override
        public boolean whisper(Character bot, Character player, String line, SocialGate.Act act) {
            SocialGate.Verdict v = SocialGate.check(bot, player, act); // the real sender checks it too
            if (!v.ok()) {
                return false;
            }
            sent.add("whisper " + act.key() + " " + bot.getName() + " -> " + player.getName() + ": " + line);
            return true;
        }

        @Override
        public boolean buddyChat(Character bot, Character player, String line) {
            sent.add("buddy " + bot.getName() + " -> " + player.getName() + ": " + line);
            return true;
        }

        @Override
        public void mapChat(Character bot, String line) {
            sent.add("map " + bot.getName() + ": " + line);
        }

        @Override
        public boolean partyInvite(Character bot, Character player) {
            sent.add("party invite " + bot.getName() + " -> " + player.getName());
            return partyOk;
        }

        @Override
        public boolean guildInvite(Character bot, Character player) {
            sent.add("guild invite " + bot.getName() + " -> " + player.getName());
            return true;
        }

        @Override
        public void leaveParty(Character bot) {
            sent.add("left party " + bot.getName());
        }
    }

    @BeforeEach
    void setUp() {
        SocialGate.useForTest(new SocialGateStore.Memory(), Runnable::run, clock::get);
        SocialGate.useRomanceOnForTest(id -> false);
        SocialGate.useBusyForTest(p -> false);
        BotNoiseFilter.forgetAllForTest();
        BotWhispers.resetForTest();
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        memory.install();
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        SocialInitiative.useForTest(out, (ms, task) -> {
            scheduled.add(new long[]{ms});
            tasks.add(task);
        }, clock::get, () -> roll, online::get);
        LifeOutcomes.listen(listener);
    }

    @AfterEach
    void tearDown() {
        LifeOutcomes.remove(listener);
        SocialInitiative.useForTest(null, null, null, null, null);
        SocialGate.useForTest(null, null, null);
        SocialGate.useRomanceOnForTest(null);
        SocialGate.useBusyForTest(null);
        BotNoiseFilter.forgetAllForTest();
        BotWhispers.resetForTest();
        BotAffinity.useForTest(null, null, null, null);
        BotMemory.useConnectionSourceForTest(null);
        BotMemory.useForTest(null, null, 0);
    }

    static Character player(int id, String name, int level) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getLevel()).thenReturn(level);
        when(c.getMapId()).thenReturn(103000000);
        when(c.isLoggedinWorld()).thenReturn(true);
        return c;
    }

    static Character bot(int id, String name, int level) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getLevel()).thenReturn(level);
        when(c.getMapId()).thenReturn(103000000);
        when(c.getClient()).thenReturn(new client.BotClient(0, 1));
        return c;
    }

    private void runTasks() {
        while (!tasks.isEmpty()) {
            tasks.poll().run();
        }
    }

    private void quiet(int playerId, boolean on) {
        BotNoiseFilter.set(playerId, new BotNoiseFilter.Setting(false, on));
    }

    // ── greet at login: timing, staggering, not every login ──

    @Test
    void loginGreetingsAreStaggeredOneToThreeWithinAMinuteClosestFirst() {
        List<Candidate> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(new Candidate(BotStore.BOT_ID_BASE + i, .3 + .1 * i, .5, i == 0, 1));
        }
        DoubleSupplier[] rngs = {() -> 0.0, () -> 0.999, new java.util.Random(7)::nextDouble};
        for (DoubleSupplier rng : rngs) {
            List<Planned> plan = SocialInitiative.planGreetings(five, rng, true);
            assertEquals(3, plan.size(), "at most three bots greet");
            assertEquals(BotStore.BOT_ID_BASE + 4, plan.get(0).botId(), "the closest one first");
            long first = plan.get(0).atMs();
            assertTrue(first >= 10_000 && first <= 22_000, "the first hello 10-22 s after the login: " + first);
            for (int i = 1; i < plan.size(); i++) {
                long gap = plan.get(i).atMs() - plan.get(i - 1).atMs();
                assertTrue(gap >= 8_000 && gap <= 18_000, "staggered 8-18 s apart: " + gap);
            }
            assertTrue(plan.get(plan.size() - 1).atMs() <= 60_000, "all within a minute");
        }
    }

    @Test
    void notEveryLoginByClosenessChattinessAndBartle() {
        Candidate distant = new Candidate(BOT, .3, .1, false, 1);
        Candidate close = new Candidate(BOT, 1, .1, false, 1);
        Candidate chatty = new Candidate(BOT, 1, .9, false, 1);
        assertTrue(SocialInitiative.greetChance(distant) < SocialInitiative.greetChance(close));
        assertTrue(SocialInitiative.greetChance(close) < SocialInitiative.greetChance(chatty));
        assertTrue(SocialInitiative.greetChance(chatty) <= .9, "never certain");
        assertTrue(SocialInitiative.planGreetings(List.of(distant, close), () -> .95, false).isEmpty(),
                "a high roll: nobody says hi this time");

        SocialProfile socializer = new SocialProfile(-1, -1), killer = new SocialProfile(-1, 1),
                achiever = new SocialProfile(1, 1), explorer = new SocialProfile(1, -1);
        assertTrue(SocialInitiative.weight(socializer, Kind.GREET) > SocialInitiative.weight(explorer, Kind.GREET));
        assertTrue(SocialInitiative.weight(explorer, Kind.GREET) > SocialInitiative.weight(killer, Kind.GREET));
        for (SocialProfile p : List.of(socializer, explorer, killer)) {
            assertTrue(SocialInitiative.weight(achiever, Kind.PQ_INVITE) > SocialInitiative.weight(p, Kind.PQ_INVITE),
                    "Achievers invite to PQs most");
        }
        for (Kind k : Kind.values()) {
            assertTrue(SocialInitiative.weight(killer, k) < SocialInitiative.weight(socializer, k), "Killers least: " + k);
        }
        assertTrue(SocialInitiative.loginLine("Josh", explorer, 25, null, () -> .4).contains("ant tunnel"),
                "an Explorer's hello suggests a map for the player's level");
    }

    @Test
    void aFriendBotGreetsAtLoginByWhisperWithinAMinuteAndARelogGreetsNobody() {
        Character josh = player(PLAYER, "Josh", 25);
        Character friend = bot(BOT, "Kimchi", 25);
        online.put(PLAYER, josh);
        online.put(BOT, friend);
        assertTrue(BotAffinity.stage(BOT, PLAYER, AffinityState.FRIEND));

        SocialInitiative.onPlayerLogin(josh);
        assertEquals(1, scheduled.size());
        assertEquals(SocialInitiative.PLAN_DELAY_MS, scheduled.get(0)[0], "the plan waits for the ties to load");
        tasks.poll().run(); // the plan
        assertEquals(2, scheduled.size(), "one greeting scheduled");
        long at = SocialInitiative.PLAN_DELAY_MS + scheduled.get(1)[0];
        assertTrue(at >= 10_000 && at <= 60_000, "the hello lands 10-60 s after the login: " + at);
        tasks.poll().run(); // the hello
        assertEquals(1, out.sent.size(), out.sent.toString());
        assertTrue(out.sent.get(0).startsWith("whisper greet Kimchi -> Josh: "), out.sent.toString());

        tasks.clear();
        scheduled.clear();
        clock.addAndGet(TimeUnit.MINUTES.toMillis(5));
        SocialInitiative.onPlayerLogin(josh); // a relog five minutes later
        assertTrue(scheduled.isEmpty(), "a relog greets nobody");
        clock.addAndGet(HOUR);
        SocialInitiative.onPlayerLogin(josh);
        tasks.poll().run();
        assertEquals(1, scheduled.size(), "an hour later the same bot does not greet again at login (6 h)");
    }

    @Test
    void quietOrBusyHoldsTheLoginGreeting() {
        Character josh = player(PLAYER, "Josh", 25);
        online.put(PLAYER, josh);
        online.put(BOT, bot(BOT, "Kimchi", 25));
        quiet(PLAYER, true);
        assertFalse(SocialInitiative.greetAtLogin(BOT, PLAYER));
        assertTrue(out.sent.isEmpty(), "@quiet bots: nothing");
        quiet(PLAYER, false);
        SocialGate.useBusyForTest(p -> true);
        assertFalse(SocialInitiative.greetAtLogin(BOT, PLAYER));
        assertTrue(out.sent.isEmpty(), "busy: nothing");
        SocialGate.useBusyForTest(p -> false);
        assertTrue(SocialInitiative.greetAtLogin(BOT, PLAYER));
        assertFalse(SocialInitiative.greetAtLogin(BOT, PLAYER), "the gate's greet budget: not twice in a row");
        assertEquals(1, out.sent.size());
    }

    // ── day-later follow-ups ──

    @Test
    void aFollowUpIsDueAfterTwentyHoursAskedOnceAndMarkedDone() {
        Character josh = player(PLAYER, "Josh", 25);
        Character friend = bot(BOT, "Kimchi", 25);
        long t0 = clock.get();
        assertNotNull(BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.GOAL, "wants zhelm", 4, "item:zhelm", t0));

        clock.set(t0 + 19 * HOUR);
        assertFalse(SocialInitiative.followUp(friend, josh, true, false, null), "19 h: not due yet");
        clock.set(t0 + 21 * HOUR);
        quiet(PLAYER, true);
        assertFalse(SocialInitiative.followUp(friend, josh, true, false, null), "@quiet bots: not asked");
        assertEquals(1, BotMemory.dueFollowUps(SLOT, PLAYER, clock.get()).size(), "and not marked done");
        quiet(PLAYER, false);
        assertTrue(SocialInitiative.followUp(friend, josh, true, false, null));
        assertEquals(1, out.sent.size());
        assertTrue(out.sent.get(0).startsWith("whisper follow_up Kimchi -> Josh: ") && out.sent.get(0).contains("zhelm"),
                out.sent.toString());
        assertTrue(BotMemory.dueFollowUps(SLOT, PLAYER, clock.get()).isEmpty(), "followed up: done");
        assertFalse(SocialInitiative.followUp(friend, josh, true, false, null), "never asked twice");
    }

    @Test
    void theLoginHelloFoldsInADueFollowUp() {
        Character josh = player(PLAYER, "Josh", 25);
        online.put(PLAYER, josh);
        online.put(BOT, bot(BOT, "Kimchi", 25));
        long t0 = clock.get();
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.GOAL, "trying for 4th job", 4, "goal:4th job", t0);
        clock.set(t0 + 22 * HOUR);
        assertTrue(SocialInitiative.greetAtLogin(BOT, PLAYER));
        assertTrue(out.sent.get(0).contains("4th job"), out.sent.toString());
        assertTrue(BotMemory.dueFollowUps(SLOT, PLAYER, clock.get()).isEmpty());
    }

    @Test
    void followUpLinesAskCasuallyByTag() {
        assertTrue(SocialInitiative.followUpLine("item:zhelm", () -> 0).contains("that zhelm"));
        assertTrue(SocialInitiative.followUpLine("item:a mount", () -> 0).contains("a mount"));
        assertTrue(SocialInitiative.followUpLine("goal:4th job", () -> 0).contains("4th job"));
        assertTrue(SocialInitiative.followUpLine("goal:level 70", () -> 0).contains("70"));
        assertTrue(SocialInitiative.followUpLine("goal:zakum", () -> 0).contains("zakum"));
        assertNull(SocialInitiative.followUpLine("", () -> 0));
    }

    // ── PQ invites in the level band ──

    private MapleMap lobbyMap(Character... players) {
        MapleMap map = mock(MapleMap.class);
        when(map.getAllPlayers()).thenReturn(List.of(players));
        when(map.getChannelId()).thenReturn(1);
        return map;
    }

    @Test
    void aLobbyBotInvitesOnlyAPlayerOfThePqsBand() {
        Character lobbyBot = bot(BOT, "Kimchi", 25);
        Character tooHigh = player(PLAYER + 1, "Big", 40);
        Character partied = player(PLAYER + 2, "Busy", 26);
        when(partied.getParty()).thenReturn(mock(Party.class));
        Character josh = player(PLAYER, "Josh", 27);
        MapleMap map = lobbyMap(tooHigh, partied, josh);
        when(lobbyBot.getMap()).thenReturn(map);

        LifePq.Lobby kpq = LifePq.lobbyFor(25);
        assertTrue(SocialInitiative.inBand(kpq, 21) && SocialInitiative.inBand(kpq, 30));
        assertFalse(SocialInitiative.inBand(kpq, 20) || SocialInitiative.inBand(kpq, 31));
        assertEquals(PLAYER, SocialInitiative.pqTarget(lobbyBot, clock.get()).getId(),
                "lv 40 is out of KPQ's band, a partied player is skipped");

        when(lobbyBot.getMapId()).thenReturn(100000000);
        assertNull(SocialInitiative.pqTarget(lobbyBot, clock.get()), "not standing in its lobby: nobody");
        when(lobbyBot.getMapId()).thenReturn(103000000);

        quiet(PLAYER, true);
        assertNull(SocialInitiative.pqTarget(lobbyBot, clock.get()), "@quiet bots: not a target");
        quiet(PLAYER, false);
    }

    @Test
    void theInviteGoesThroughTheGateAndTheAnswerIsReported() {
        Character lobbyBot = bot(BOT, "Kimchi", 25);
        Character josh = player(PLAYER, "Josh", 27);
        MapleMap map = lobbyMap(josh);
        when(lobbyBot.getMap()).thenReturn(map);
        LifeHost host = mock(LifeHost.class);
        when(host.getChr()).thenReturn(lobbyBot);
        when(host.canStartLifeActivity(any(), anyLong())).thenReturn(true);

        quiet(PLAYER, true);
        assertFalse(SocialInitiative.invitePq(lobbyBot, josh, clock.get(), (ok, why) -> { }), "@quiet: no invite");
        assertTrue(out.sent.isEmpty());
        quiet(PLAYER, false);

        assertTrue(SocialInitiative.lobbyTick(host, clock.get()));
        assertEquals(2, out.sent.size(), out.sent.toString());
        assertTrue(out.sent.get(0).startsWith("map Kimchi: ") && out.sent.get(0).contains("kpq"), out.sent.toString());
        assertEquals("party invite Kimchi -> Josh", out.sent.get(1));

        // the player joins the bot's party: accepted, reported for S9's learning
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(5);
        when(lobbyBot.getParty()).thenReturn(party);
        when(josh.getParty()).thenReturn(party);
        runTasks();
        assertEquals(1, outcomes.size());
        assertEquals(Activity.INVITE_PQ, outcomes.get(0).activity());
        assertTrue(outcomes.get(0).success());
        assertEquals("accepted", outcomes.get(0).detail());

        clock.addAndGet(TimeUnit.MINUTES.toMillis(3));
        assertFalse(SocialInitiative.lobbyTick(host, clock.get()), "the same pair is not asked again for 30 min");
    }

    @Test
    void noAnswerIsADeclineAndTheBotLeavesThePartyItMadeForIt() {
        Character lobbyBot = bot(BOT, "Kimchi", 25);
        Character josh = player(PLAYER, "Josh", 27);
        MapleMap map = lobbyMap(josh);
        when(lobbyBot.getMap()).thenReturn(map);
        Party own = mock(Party.class);
        when(own.getId()).thenReturn(9);
        when(own.getMembers()).thenReturn(List.of());
        assertTrue(SocialInitiative.invitePq(lobbyBot, josh, clock.get(), (ok, why) ->
                LifeOutcomes.report(BOT, Activity.INVITE_PQ, ok, clock.get(), why)));
        when(lobbyBot.getParty()).thenReturn(own); // the invite made it a party of one
        int polls = 0;
        while (!tasks.isEmpty()) {
            tasks.poll().run();
            polls++;
        }
        assertEquals(SocialInitiative.ANSWER_MS / SocialInitiative.ANSWER_POLL_MS, polls, "it waits a minute");
        assertEquals(1, outcomes.size());
        assertFalse(outcomes.get(0).success());
        assertEquals("declined or no answer", outcomes.get(0).detail());
        assertEquals("left party Kimchi", out.sent.get(out.sent.size() - 1), "it leaves the party of one it made");
    }

    // ── buddy requests after play together ──

    @Test
    void playTogetherCountsPartyPqBossAndHelpEpisodesOfTheLastWeek() {
        long now = clock.get();
        List<BotMemory.Note> notes = List.of(
                note(BotMemory.Kind.PARTY, now - HOUR), note(BotMemory.Kind.TRADE, now - HOUR),
                note(BotMemory.Kind.PQ, now - 8 * 24 * HOUR), note(BotMemory.Kind.HELP, now - 2 * HOUR));
        assertEquals(2, SocialInitiative.togetherEpisodes(notes, now), "trade and an old PQ don't count");
        assertTrue(SocialInitiative.playedEnough(2, 0));
        assertFalse(SocialInitiative.playedEnough(1, 10));
        assertTrue(SocialInitiative.playedEnough(0, SocialInitiative.LONG_PARTY_MIN), "or one long party");
    }

    private static BotMemory.Note note(BotMemory.Kind kind, long at) {
        return new BotMemory.Note(1, SLOT, PLAYER, "x", BotMemory.Source.DIRECT, 0, 0, at, 0, kind, 3, "", 0);
    }

    @Test
    void oneEpisodeIsNotEnoughForABuddyRequest() {
        Character josh = player(PLAYER, "Josh", 25);
        when(josh.getBuddylist()).thenReturn(new client.BuddyList(20));
        online.put(PLAYER, josh);
        online.put(BOT, bot(BOT, "Kimchi", 25));
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.PARTY, "partied together", 3, "", clock.get());
        assertFalse(SocialInitiative.maybeBuddyAfterPlay(BOT, PLAYER, 0));
    }

    @Test
    void thePartyStepCountsMinutesTogether() {
        Character josh = player(PLAYER, "Josh", 25);
        online.put(PLAYER, josh);
        BotAffinity.stage(BOT2, PLAYER, AffinityState.FRIEND); // loads the player's ties: a known player
        Party party = mock(Party.class);
        net.server.world.PartyCharacter member = mock(net.server.world.PartyCharacter.class);
        when(member.getId()).thenReturn(BOT);
        when(party.getMembers()).thenReturn(List.of(member));
        when(josh.getParty()).thenReturn(party);
        for (int i = 0; i < 3; i++) {
            SocialInitiative.partyStep(clock.get());
        }
        assertEquals(3, SocialInitiative.partyMinutesForTest().get(SocialInitiative.pair(BOT, PLAYER)));
        when(josh.getParty()).thenReturn(null);
        SocialInitiative.partyStep(clock.get());
        assertTrue(SocialInitiative.partyMinutesForTest().isEmpty(), "the party ended: forgotten");
    }
}
