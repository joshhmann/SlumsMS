package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Feasible;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Goal;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Relation;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Answer;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Act;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Offer;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Plan;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Step;
import soloMapling.ArtificialPlayer.BotGossip;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePq;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;
import soloMapling.server.BotTiming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 say-do (ticket 114: bots ask each other to party, come along, help, trade, "then they really do it"). TEST
 * 2026-10-28 numbers the talk acts did not back: come-along 7 of 178 arrived (a yes only tilted the next decision),
 * 20 PQ yeses broke on "LFG rules now say no" at the act, plain parties were dropped when the asker stepped through a
 * portal and never told the say-do ledger, help never applied to a helper on the same map, 17 story yeses kept
 * nothing. Each act kind: the yes is carried out, or the bot that cannot says no.
 */
class BotTalkSayDoTest {

    static final int HENESYS = 100000000, ELLINIA = 101000000;

    @BeforeEach
    void setUp() {
        BotTalk.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotTalk.resetForTest();
        TalkPromises.useForTest(null);
    }

    static Character chr(int slot, String name, int level) {
        return TalkPlannerTest.chr(200 + slot, name, level);
    }

    private static BotTalk.Live live(Plan plan) {
        return new BotTalk.Live(7, plan, 1, ELLINIA, null, "TALK_" + plan.ask);
    }

    // ── come along ──

    @Test
    void aComeAlongYesHandsEachTripOverTheAskerFirstThenEachFriendAFewSecondsApart() {
        Character a = chr(1, "Ann", 30), b = chr(2, "Bob", 31), c = chr(3, "Cat", 32);
        List<long[]> trips = new ArrayList<>();
        BotTalk.trips = (id, town, until, delay) -> trips.add(new long[]{id, town, delay});
        Offer offer = TalkPlannerTest.comeAlong(HENESYS);
        Plan plan = new Plan(List.of(a, b, c), true, false);
        plan.ask = Ask.COME_ALONG;
        BotTalk.Result r = BotTalk.realAct(live(plan), new Act(Ask.COME_ALONG, a, List.of(b, c), offer, null));
        assertTrue(r.ok(), r.text());
        assertEquals(3, trips.size(), "the asker and both friends are sent, not only nudged");
        assertEquals(a.getId(), trips.get(0)[0]);
        assertEquals(b.getId(), trips.get(1)[0]);
        assertEquals(c.getId(), trips.get(2)[0]);
        for (long[] t : trips) {
            assertEquals(HENESYS, t[1]);
        }
        assertTrue(trips.get(0)[2] < 2_000, "the asker leaves first");
        assertTrue(trips.get(1)[2] - trips.get(0)[2] >= 2_000 && trips.get(1)[2] - trips.get(0)[2] < 6_000,
                "a friend follows a few seconds later");
        assertTrue(trips.get(2)[2] - trips.get(1)[2] >= 2_000, "and the next a few seconds after that");
    }

    @Test
    void anAskerWhoSaidItWasGoingGoesEvenWhenNobodyComes() {
        Character a = chr(4, "Ann", 30), b = chr(5, "Bob", 31);
        TalkPlannerTest.FakeSense s = new TalkPlannerTest.FakeSense()
                .mind(a, 0.8, 0.1, 0.5, Goal.COMPANY, 0.8, -1).mind(b, 0.8, 0.1, 0.5, Goal.COMPANY, 0.8, -1)
                .bond(a, b, new Relation("friend", 0.6, true));
        s.offers.put(Ask.COME_ALONG, TalkPlannerTest.comeAlong(HENESYS));
        s.accept.put(b.getId(), Feasible.no("on its way somewhere (life_travel)"));
        Plan p = TalkPlanner.plan(List.of(a, b), false, Ask.COME_ALONG, s, false, 0, new Random(5));
        assertEquals(Answer.NO, p.decisions.get(b.getId()).answer());
        Step acting = p.steps.stream().filter(x -> x.act != null).findFirst().orElse(null);
        assertNotNull(acting, "\"going henesys, coming?\" is a claim: the asker goes");
        assertEquals(a, acting.act.asker());
        assertTrue(acting.act.yes().isEmpty(), "alone");

        List<long[]> trips = new ArrayList<>();
        BotTalk.trips = (id, town, until, delay) -> trips.add(new long[]{id, town, delay});
        BotTalk.Result r = BotTalk.realAct(live(p), acting.act);
        assertTrue(r.ok());
        assertEquals(1, trips.size());
        assertEquals(a.getId(), trips.get(0)[0]);
    }

    @Test
    void aBotThatCannotGoNowSaysNoWithItsReasonInsteadOfAYesItBreaks() {
        Character a = chr(6, "Ann", 30), b = chr(7, "Bob", 31);
        LifeRuntime travelling = mock(LifeRuntime.class);
        when(travelling.commitBlocker(anyLong())).thenReturn("on its way somewhere (life_travel)");
        BotTalk.lives = id -> travelling;
        Feasible f = new BotTalk.WorldSense().canAccept(b, a, TalkPlannerTest.comeAlong(HENESYS), 0);
        assertFalse(f.ok());
        assertEquals("on its way somewhere (life_travel)", f.why(), "the true reason, so the no says it");
    }

    @Test
    void theBotsWhoseYesIsATripOrAStayHoldUntilTheAct() {
        Character a = chr(21, "Ann", 30), b = chr(22, "Bob", 31), c = chr(23, "Cat", 31);
        Plan come = new Plan(List.of(a, b, c), true, false);
        come.ask = Ask.COME_ALONG;
        come.steps.add(stepWith(a, new Act(Ask.COME_ALONG, a, List.of(b), TalkPlannerTest.comeAlong(HENESYS), null)));
        assertEquals(List.of(a, b), BotTalk.promisers(come), "the asker and the yes; Cat said no");

        Plan duo = new Plan(List.of(a, b), false, false);
        duo.ask = Ask.PARTY;
        duo.steps.add(stepWith(b, new Act(Ask.PARTY, a, List.of(b), new Offer(Ask.PARTY, "a duo", Map.of(), null, null), null)));
        assertTrue(BotTalk.promisers(duo).isEmpty(), "a duo party needs nobody to stay put");

        Plan pq = new Plan(List.of(a, b), false, false);
        pq.ask = Ask.PARTY;
        pq.steps.add(stepWith(b, new Act(Ask.PARTY, a, List.of(b), BotTalk.pqOffer(lfg(LfgParty.Why.YES)), null)));
        assertEquals(List.of(a, b), BotTalk.promisers(pq), "both of an LFG pair take the trip");

        Plan help = new Plan(List.of(a, b), false, false);
        help.ask = Ask.HELP;
        help.steps.add(stepWith(b, new Act(Ask.HELP, a, List.of(b), new Offer(Ask.HELP, "x", Map.of(), 1, null), null)));
        assertEquals(List.of(b), BotTalk.promisers(help), "the helper");
    }

    private static Step stepWith(Character speaker, Act act) {
        TalkPlannerTest.FakeSense s = new TalkPlannerTest.FakeSense().mind(speaker, 0.5, 0.1, 0.5, Goal.NONE, 0, -1);
        Plan p = TalkPlanner.plan(List.of(speaker, chr(99, "Zed", 30)), true, null,
                s.mind(chr(99, "Zed", 30), 0.5, 0.1, 0.5, Goal.NONE, 0, -1), false, 0, new Random(1));
        Step st = p.steps.get(0);
        st.act = act;
        return st;
    }

    // ── help ──

    @Test
    void onlyABotGrindingTheAskersMapCanSayYesToHelp() {
        Character a = chr(8, "Ann", 30), b = chr(9, "Bob", 40);
        LifeRuntime inTown = mock(LifeRuntime.class);
        when(inTown.commitBlocker(anyLong())).thenReturn(null);
        when(inTown.phase()).thenReturn("LIFE_DWELL");
        BotTalk.lives = id -> inTown;
        Offer help = new Offer(Ask.HELP, "Ant Tunnel", Map.of("spot", "Ant Tunnel"), 105050000, null);
        Feasible f = new BotTalk.WorldSense().canAccept(b, a, help, 0);
        assertFalse(f.ok());
        assertEquals("not training here", f.why());
    }

    @Test
    void aHelpYesPartiesTheTwoAndKeepsTheHelperGrindingHere() {
        Character a = mockBot(1000090301, 105050000, 1);
        Character b = mockBot(1000090302, 105050000, 1);
        LifeRuntime helper = mock(LifeRuntime.class);
        when(helper.helpHere(eq(a.getId()), anyLong(), anyLong())).thenReturn(true);
        BotTalk.lives = id -> id == b.getId() ? helper : null;
        net.server.world.Party party = mock(net.server.world.Party.class);
        when(party.getId()).thenReturn(77);
        when(a.getParty()).thenReturn(null, party);
        try (var parties = mockStatic(Party.class); var sayDo = mockStatic(SayDo.class);
             var timing = mockStatic(BotTiming.class)) {
            parties.when(() -> Party.createParty(any(), anyBoolean())).thenReturn(true);
            parties.when(() -> Party.joinParty(any(), anyInt(), anyBoolean())).thenReturn(true);
            Plan plan = new Plan(List.of(a, b), false, false);
            plan.ask = Ask.HELP;
            Offer help = new Offer(Ask.HELP, "Ant Tunnel", Map.of("spot", "Ant Tunnel"), 105050000, null);
            BotTalk.Result r = BotTalk.realAct(live(plan), new Act(Ask.HELP, a, List.of(b), help, null));
            assertTrue(r.ok(), r.text());
            org.mockito.Mockito.verify(helper).helpHere(eq(a.getId()), anyLong(), anyLong());
            parties.verify(() -> Party.joinParty(b, 77, true));
            sayDo.verify(() -> SayDo.event(b, SayDo.Ev.PARTY, 105050000));
        }
    }

    // ── party ──

    static Character mockBot(int id, int mapId, int channel) {
        Character c = mock(Character.class);
        MapleMap m = mock(MapleMap.class);
        when(m.getChannelId()).thenReturn(channel);
        when(m.getId()).thenReturn(mapId);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("bot" + id);
        when(c.getMap()).thenReturn(m);
        when(c.getMapId()).thenReturn(mapId);
        return c;
    }

    @Test
    void aDuoYesIsARealPartyEvenAfterTheAskerSteppedOntoTheNextMapAndTheLedgerHearsIt() {
        Character a = mockBot(1000090311, 104040001, 2); // walked on through the portal
        Character b = mockBot(1000090312, 104040000, 2);
        net.server.world.Party party = mock(net.server.world.Party.class);
        when(party.getId()).thenReturn(78);
        when(a.getParty()).thenReturn(null, party);
        try (var parties = mockStatic(Party.class); var sayDo = mockStatic(SayDo.class);
             var timing = mockStatic(BotTiming.class)) {
            parties.when(() -> Party.createParty(any(), anyBoolean())).thenReturn(true);
            parties.when(() -> Party.joinParty(any(), anyInt(), anyBoolean())).thenReturn(true);
            BotTalk.Result r = BotTalk.plainParty(a, List.of(b), 104040000);
            assertTrue(r.ok(), r.text());
            parties.verify(() -> Party.joinParty(b, 78, true));
            sayDo.verify(() -> SayDo.event(a, SayDo.Ev.PARTY, 104040001), org.mockito.Mockito.times(1));
            sayDo.verify(() -> SayDo.event(b, SayDo.Ev.PARTY, 104040000), org.mockito.Mockito.times(1));
        }
    }

    static LfgParty.Plan lfg(LfgParty.Why why) {
        return new LfgParty.Plan(1000090321, "Ann", 25, 1000090322, "Bob", 26, LifePq.lobbyFor(25), why,
                why == LfgParty.Why.YES ? 1L : 0L, LfgParty.Why.YES);
    }

    @Test
    void aPqAskCarriesTheLfgRulesAnswerAndItsCapSlotFromTheStart() {
        LfgParty.Plan yes = lfg(LfgParty.Why.YES);
        Offer o = BotTalk.pqOffer(yes);
        assertNotNull(o);
        assertSame(yes, o.payload(), "the act forms this plan; it is not decided again (and refused) at the act");
        assertTrue(o.decided().ok());
        Offer low = BotTalk.pqOffer(lfg(LfgParty.Why.TOO_LOW));
        assertFalse(low.decided().ok(), "the other says no with the rules' reason: " + low.decided().why());
        assertNull(BotTalk.pqOffer(lfg(LfgParty.Why.BUDGET)), "with the cap used up nobody is asked to a PQ");
        assertNull(BotTalk.pqOffer(null));
    }

    @Test
    void theCapSlotIsKeptOnlyWhenThePqWasAskedAndTakenAndAPlanTheRulesRefusedIsNeverFormed() {
        Character a = chr(10, "Ann", 25), b = chr(11, "Bob", 26);
        LfgParty.Plan yes = lfg(LfgParty.Why.YES);
        Plan plan = new Plan(List.of(a, b), false, false);
        plan.ask = Ask.PARTY;
        plan.offer = BotTalk.pqOffer(yes);
        assertFalse(BotTalk.usesReservation(plan, yes), "no yes: the slot goes back");
        plan.decisions.put(b.getId(), new TalkBrain.Decision(Answer.YES, TalkIntent.Manner.WARM, "ok"));
        assertTrue(BotTalk.usesReservation(plan, yes));
        plan.offer = new Offer(Ask.PARTY, "a duo", Map.of(), null, null);
        assertFalse(BotTalk.usesReservation(plan, yes), "a duo was asked instead");

        LfgParty.Plan no = lfg(LfgParty.Why.PARTIED);
        BotTalk.Result r = BotTalk.realAct(live(plan), new Act(Ask.PARTY, a, List.of(b), BotTalk.pqOffer(no), null));
        assertFalse(r.ok());
        assertEquals("lfg-drop", r.kind());
    }

    // ── a story ──

    @Test
    void aStoryIsOfferedOnlyToABotThatWouldKeepIt() {
        BotGossip.clearStoriesForTest(id -> false);
        try {
            BotGossip.Fact fact = new BotGossip.Fact("ks", "Zed ksed Amy at ant tunnel", "heard Zed ksed Amy",
                    List.of("Zed", "Amy"), 0, true);
            assertTrue(BotGossip.wouldKeep(1000090331, 1000090332, fact, 1_000));
            assertNotNull(BotGossip.keep(1000090331, 1000090332, "Ann", fact, 1_000));
            assertFalse(BotGossip.wouldKeep(1000090331, 1000090332, fact, 2_000),
                    "it knows the story now: \"no way, really?\" would keep nothing");
        } finally {
            BotGossip.clearStoriesForTest(null);
        }
    }

    // ── the ledger: a yes is a promise ──

    @Test
    void eachYesIsAPromiseJudgedByWhatTheBotThenDoes() {
        Map<Integer, TalkPromises.Where> world = new HashMap<>();
        TalkPromises.useForTest(world::get);
        Character a = chr(12, "Ann", 30), b = chr(13, "Bob", 31), c = chr(14, "Cat", 31);
        Plan plan = new Plan(List.of(a, b, c), true, false);
        plan.ask = Ask.COME_ALONG;
        plan.offer = TalkPlannerTest.comeAlong(HENESYS);
        plan.decisions.put(b.getId(), new TalkBrain.Decision(Answer.YES, TalkIntent.Manner.WARM, "ok"));
        plan.decisions.put(c.getId(), new TalkBrain.Decision(Answer.YES, TalkIntent.Manner.WARM, "ok"));
        long t0 = 1_000_000;
        BotTalk.promises(9, plan, t0);
        world.put(b.getId(), new TalkPromises.Where(HENESYS, 0, -1)); // Bob got there
        world.put(c.getId(), new TalkPromises.Where(ELLINIA, 0, -1)); // Cat never left
        assertEquals(1, TalkPromises.resolveDue(t0 + 60_000).size(), "Bob is judged at once; Cat may still go");
        List<TalkPromises.Verdict> late = TalkPromises.resolveDue(t0 + TalkPromises.windowMs(Ask.COME_ALONG));
        assertEquals(1, late.size());
        assertFalse(late.get(0).backed(), late.get(0).why());
        Map<String, Object> st = TalkPromises.stats();
        assertEquals(2L, st.get("yes"));
        assertEquals(1L, st.get("backed"));
        assertEquals(0.5, st.get("rate"));
    }

    @Test
    void aPartyYesIsBackedByOnePartyAndADealByTheTrade() {
        Map<Integer, TalkPromises.Where> world = new HashMap<>();
        TalkPromises.useForTest(world::get);
        Character a = chr(15, "Ann", 30), b = chr(16, "Bob", 31);
        long t0 = 2_000_000;
        TalkPromises.promised(1, Ask.PARTY, b, a, -1, t0);
        TalkPromises.promised(2, Ask.TRADE, b, a, -1, t0);
        TalkPromises.promised(3, Ask.HELP, b, a, -1, t0);
        world.put(a.getId(), new TalkPromises.Where(ELLINIA, 55, -1));
        world.put(b.getId(), new TalkPromises.Where(ELLINIA, 55, -1));
        TalkPromises.acted(2, Ask.TRADE, true, "sold Emerald Ore");
        List<TalkPromises.Verdict> v = TalkPromises.resolveDue(t0 + 10_000);
        assertEquals(3, v.size());
        assertTrue(v.stream().allMatch(TalkPromises.Verdict::backed), String.valueOf(v));

        TalkPromises.promised(4, Ask.PARTY, b, a, -1, t0);
        world.put(b.getId(), new TalkPromises.Where(ELLINIA, 0, -1));
        assertTrue(TalkPromises.resolveDue(t0 + 10_000).isEmpty(), "waits for its window");
        assertFalse(TalkPromises.resolveDue(t0 + TalkPromises.windowMs(Ask.PARTY)).get(0).backed());
    }

    @Test
    void aTripStillUnderWayWhenTheWindowEndsCounts() {
        Map<Integer, TalkPromises.Where> world = new HashMap<>();
        TalkPromises.useForTest(world::get);
        Character a = chr(17, "Ann", 30), b = chr(18, "Bob", 31);
        TalkPromises.promised(1, Ask.COME_ALONG, b, a, HENESYS, 0);
        world.put(b.getId(), new TalkPromises.Where(104040000, 0, HENESYS)); // walking there from far away
        assertTrue(TalkPromises.resolveDue(TalkPromises.windowMs(Ask.COME_ALONG)).get(0).backed());
    }

    // ── the words ──

    @Test
    void aBankLineNeverClaimsWhatTheActDoesNotDo() {
        assertFalse(TalkLines.claimsFit("Hold on, I can't leave el nath until I finish this",
                TalkLines.allowedClaims("come_no", false)), "a no promises nothing (\"hold on\" is a brb)");
        assertTrue(TalkLines.claimsFit("omw rn", TalkLines.allowedClaims("come_yes", true)), "a come-along yes travels");
        assertFalse(TalkLines.claimsFit("omw rn", TalkLines.allowedClaims("help_yes", true)),
                "a helper is already on the asker's map: no omw");
        assertTrue(TalkLines.claimsFit("sure, ill inv u", TalkLines.allowedClaims("help_yes", true)),
                "help parties up");
        assertTrue(TalkLines.claimsFit("sure thing", TalkLines.allowedClaims("party_no", false)));
    }
}
