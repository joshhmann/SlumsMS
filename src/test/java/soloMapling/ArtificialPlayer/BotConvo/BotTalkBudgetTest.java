package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Act;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Plan;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialBudget;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 271 stage 6a: BotTalk asks {@link SocialBudget} before a greet or chat ({@code run}) and before an ask act is
 * carried out ({@code carryOut}), and reports how each ask act went. The budget is a safety net: a first act is never
 * held, so existing pacing is unchanged until a loop builds up.
 */
class BotTalkBudgetTest {

    static final int ELLINIA = 101000000;
    final AtomicLong clock = new AtomicLong(50_000_000);

    @BeforeEach
    void setUp() {
        BotTalk.resetForTest();
        SocialSim.useForTest(clock::get, 3);
    }

    @AfterEach
    void tearDown() {
        BotTalk.resetForTest();
        SocialSim.reset();
    }

    static BotTalk.Live live(Plan plan) {
        return new BotTalk.Live(7, plan, 1, ELLINIA, null, "TALK_X");
    }

    static Act ask(Ask what, Character a, Character b) {
        return new Act(what, a, List.of(b), null, null);
    }

    @Test
    void aFailingAskActIsHeldBackAfterThreeFailuresAndRunsAgainWhenTheBackoffEnds() {
        Character a = BotTalkSayDoTest.chr(41, "Ann", 30), b = BotTalkSayDoTest.chr(42, "Bob", 31);
        AtomicInteger calls = new AtomicInteger();
        BotTalk.doer = (live, act) -> {
            calls.incrementAndGet();
            return new BotTalk.Result(false, "trade_failed", "no deal");
        };
        Plan plan = new Plan(List.of(a, b), true, false);
        for (int i = 0; i < 3; i++) {
            assertEquals("trade_failed", BotTalk.carryOut(live(plan), ask(Ask.TRADE, a, b)).kind());
            clock.addAndGet(20_000);   // past the 15 s pair gap each time, so only the failures count
        }
        BotTalk.Result held = BotTalk.carryOut(live(plan), ask(Ask.TRADE, a, b));
        assertFalse(held.ok());
        assertEquals("budget", held.kind());
        assertTrue(held.text().contains("backoff"), held.text());
        assertEquals(3, calls.get(), "the held ask never reached the act");
        assertEquals(1L, SocialBudget.counters().get("denied backoff"));

        clock.addAndGet(30_000);
        assertEquals("trade_failed", BotTalk.carryOut(live(plan), ask(Ask.TRADE, a, b)).kind(), "30 s later it may try");
        assertEquals(4, calls.get());
    }

    @Test
    void aSecondAskOfTheSamePairInsideTheGapIsHeldAndASuccessIsReportedAsOne() {
        Character a = BotTalkSayDoTest.chr(43, "Ann", 30), b = BotTalkSayDoTest.chr(44, "Bob", 31);
        AtomicInteger calls = new AtomicInteger();
        BotTalk.doer = (live, act) -> {
            calls.incrementAndGet();
            return new BotTalk.Result(true, "party", "partied");
        };
        Plan plan = new Plan(List.of(a, b), true, false);
        assertTrue(BotTalk.carryOut(live(plan), ask(Ask.PARTY, a, b)).ok());
        clock.addAndGet(5_000);
        BotTalk.Result held = BotTalk.carryOut(live(plan), ask(Ask.TRADE, b, a));
        assertEquals("budget", held.kind());
        assertTrue(held.text().contains("pair-cooldown"), held.text());
        assertEquals(1, calls.get());
        clock.addAndGet(10_000);
        assertTrue(BotTalk.carryOut(live(plan), ask(Ask.TRADE, b, a)).ok(), "released at 15 s");
        assertEquals(0L, SocialBudget.counters().get("failuresRecorded"));
    }

    @Test
    void anActThatThrowsCountsAsAFailureAndStillThrows() {
        Character a = BotTalkSayDoTest.chr(45, "Ann", 30), b = BotTalkSayDoTest.chr(46, "Bob", 31);
        BotTalk.doer = (live, act) -> {
            throw new IllegalStateException("boom");
        };
        Plan plan = new Plan(List.of(a, b), true, false);
        assertThrows(IllegalStateException.class, () -> BotTalk.carryOut(live(plan), ask(Ask.HELP, a, b)));
        assertEquals(1L, SocialBudget.counters().get("failuresRecorded"));
    }

    @Test
    void switchedOffTheActsRunAsBefore() {
        System.setProperty(SocialBudget.SWITCH, "false");
        try {
            Character a = BotTalkSayDoTest.chr(47, "Ann", 30), b = BotTalkSayDoTest.chr(48, "Bob", 31);
            BotTalk.doer = (live, act) -> new BotTalk.Result(false, "trade_failed", "no deal");
            Plan plan = new Plan(List.of(a, b), true, false);
            for (int i = 0; i < 10; i++) {
                assertEquals("trade_failed", BotTalk.carryOut(live(plan), ask(Ask.TRADE, a, b)).kind());
            }
        } finally {
            System.clearProperty(SocialBudget.SWITCH);
        }
    }

    @Test
    void aGreetOrChatTheBudgetRefusesNeverStartsAndLeavesTheBotsFree() {
        Character a = BotTalkSayDoTest.chr(49, "Ann", 30), b = BotTalkSayDoTest.chr(50, "Bob", 31);
        MapleMap map = mock(MapleMap.class);
        when(map.getChannelId()).thenReturn(1);
        when(map.getId()).thenReturn(ELLINIA);
        long now = clock.get();
        SocialBudget.record(new SocialBudget.Outcome(a.getId(), b.getId(), BotTalk.key(1, ELLINIA), SocialBudget.GREET,
                now, true));   // they just greeted
        Plan held = BotTalk.run(map, List.of(b, a), true, null, false, "test", now + 1_000, new Random(1));
        assertNull(held, "greeting again straight away is held back");
        assertEquals(0, BotTalk.stats().get("inTalk"), "nobody was marked as talking");
        assertEquals(0L, BotTalk.stats().get("greets"));
        assertEquals(1L, SocialBudget.counters().get("denied pair-cooldown"));
    }
}
