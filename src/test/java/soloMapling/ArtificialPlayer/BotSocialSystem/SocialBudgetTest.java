package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialBudget.Limits;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialBudget.Outcome;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialBudget.Reason;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialBudget.Verdict;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 6a: the loop breaker. Every limit trips and releases on a manual clock, an A-B ping-pong stops at
 * the turn limit, a failing act backs off by doubling, refusals are counted per reason. New code: there was no guard
 * before (the fail-before is the whole class not existing); the BotTalk hook is covered in BotTalkBudgetTest.
 */
class SocialBudgetTest {

    static final long S = 1_000L, MIN = 60_000L;
    static final long MAP = 7, OTHER_MAP = 8;
    static final int A = 1_000_000_001, B = 1_000_000_002, C = 1_000_000_003, D = 1_000_000_004;

    String saved;

    @BeforeEach
    void fresh() {
        saved = System.getProperty(SocialBudget.SWITCH);
        System.clearProperty(SocialBudget.SWITCH);
        SocialBudget.resetForTest();
    }

    @AfterEach
    void restore() {
        SocialBudget.resetForTest();
        SocialSim.reset();
        if (saved == null) System.clearProperty(SocialBudget.SWITCH);
        else System.setProperty(SocialBudget.SWITCH, saved);
    }

    /** Everything wide open except what a test narrows. */
    static Open open() {
        return new Open();
    }

    static final class Open {
        final Map<String, Long> cooldowns = new HashMap<>();
        int actor = 1_000_000, map = 1_000_000, global = 1_000_000, turns = 1_000_000, fails = 1_000_000;
        long turnWindow = 90_000, base = 30_000, max = 3_600_000;

        Open cooldown(String act, long ms) { cooldowns.put(act, ms); return this; }
        Open actor(int n) { actor = n; return this; }
        Open map(int n) { map = n; return this; }
        Open global(int n) { global = n; return this; }
        Open turns(int n, long window) { turns = n; turnWindow = window; return this; }
        Open backoff(int after, long baseMs, long maxMs) { fails = after; base = baseMs; max = maxMs; return this; }

        Limits build() {
            return new Limits(0, Map.copyOf(cooldowns), actor, map, global, turns, turnWindow, fails, base, max,
                    6 * 60 * MIN);
        }
    }

    static Verdict ask(int actor, int target, long map, String act, long now) {
        return SocialBudget.allow(actor, target, map, act, now);
    }

    static void did(int actor, int target, long map, String act, long now) {
        SocialBudget.record(new Outcome(actor, target, map, act, now, true));
    }

    static void failed(int actor, int target, long map, String act, long now) {
        SocialBudget.record(new Outcome(actor, target, map, act, now, false));
    }

    @Test
    void aPairCooldownTripsPerActKindAndReleasesOnTime() {
        SocialBudget.useLimitsForTest(open().cooldown("greet", 10 * MIN).cooldown("chat", MIN).build());
        long t = 1_000_000;
        assertTrue(ask(A, B, MAP, "greet", t).allowed());
        did(A, B, MAP, "greet", t);
        Verdict v = ask(B, A, MAP, "greet", t + 9 * MIN);   // the pair is unordered
        assertFalse(v.allowed());
        assertEquals(Reason.PAIR_COOLDOWN, v.reason());
        assertEquals(t + 10 * MIN, v.retryAtMs());
        assertTrue(ask(A, B, MAP, "chat", t + S).allowed(), "another kind of act is its own cooldown");
        assertTrue(ask(A, C, MAP, "greet", t + S).allowed(), "another pair is not held");
        assertTrue(ask(A, B, MAP, "greet", t + 10 * MIN).allowed(), "released exactly when the gap is over");
    }

    @Test
    void anAskKindSharesItsCooldownAcrossTheAskThatWasMade() {
        SocialBudget.useLimitsForTest(open().cooldown("ask", 20 * S).build());
        did(A, B, MAP, "ask:PARTY", 0);
        assertEquals(Reason.PAIR_COOLDOWN, ask(A, B, MAP, "ask:TRADE", 5 * S).reason());
        assertTrue(ask(A, B, MAP, "ask:TRADE", 20 * S).allowed());
    }

    @Test
    void anActorRateCapTripsAndTheOldestActLeavesTheWindow() {
        SocialBudget.useLimitsForTest(open().actor(3).build());
        long t = 5 * MIN;
        for (int i = 0; i < 3; i++) {
            assertTrue(ask(A, 100 + i, 50 + i, "chat", t + i * S).allowed());
            did(A, 100 + i, 50 + i, "chat", t + i * S);
        }
        Verdict v = ask(A, 200, 99, "chat", t + 10 * S);
        assertEquals(Reason.ACTOR_RATE, v.reason());
        assertEquals(t + MIN, v.retryAtMs(), "when the first of the three leaves the minute");
        assertTrue(ask(B, 200, 99, "chat", t + 10 * S).allowed(), "another bot is not held");
        assertTrue(ask(A, 200, 99, "chat", t + MIN).allowed(), "released");
    }

    @Test
    void aMapRateCapTripsAndReleases() {
        SocialBudget.useLimitsForTest(open().map(2).build());
        did(A, B, MAP, "chat", 0);
        did(C, D, MAP, "chat", 10 * S);
        Verdict v = ask(1_000_000_010, 1_000_000_011, MAP, "chat", 20 * S);
        assertEquals(Reason.MAP_RATE, v.reason());
        assertTrue(ask(1_000_000_010, 1_000_000_011, OTHER_MAP, "chat", 20 * S).allowed(), "another map is not held");
        assertTrue(ask(1_000_000_010, 1_000_000_011, MAP, "chat", MIN).allowed(), "released when the first leaves");
    }

    @Test
    void aGlobalRateCapTripsAndReleases() {
        SocialBudget.useLimitsForTest(open().global(4).build());
        for (int i = 0; i < 4; i++) {
            did(10 + i, 20 + i, 30 + i, "greet", i * S);
        }
        assertEquals(Reason.GLOBAL_RATE, ask(90, 91, 92, "greet", 5 * S).reason());
        assertEquals(Reason.GLOBAL_RATE, ask(93, 94, 95, "chat", 30 * S).reason(), "any act, anywhere");
        assertTrue(ask(90, 91, 92, "greet", MIN).allowed());
    }

    @Test
    void aPingPongBetweenOnePairStopsAtTheTurnLimitAndIsQuietUntilItIsSilent() {
        SocialBudget.useLimitsForTest(open().turns(6, 90 * S).build());
        long t = 10 * MIN;
        int turns = 0;
        for (int i = 0; i < 50; i++) {
            int actor = i % 2 == 0 ? A : B, target = i % 2 == 0 ? B : A;   // A->B, B->A, A->B...
            Verdict v = ask(actor, target, MAP, "chat", t + i * 5 * S);
            if (!v.allowed()) {
                assertEquals(Reason.TURN_LIMIT, v.reason());
                break;
            }
            did(actor, target, MAP, "chat", t + i * 5 * S);
            turns++;
        }
        assertEquals(6, turns, "six turns of back-and-forth, then it stops");
        long last = t + 5 * 5 * S;
        Verdict stuck = ask(A, B, MAP, "chat", last + 60 * S);
        assertEquals(Reason.TURN_LIMIT, stuck.reason(), "still inside the quiet window");
        assertEquals(last + 90 * S, stuck.retryAtMs());
        assertFalse(ask(B, A, MAP, "chat", last + 80 * S).allowed(), "both directions are held");
        assertTrue(ask(A, B, MAP, "chat", last + 90 * S).allowed(), "silent for the window: a new exchange");
        did(A, B, MAP, "chat", last + 90 * S);
        assertTrue(ask(B, A, MAP, "chat", last + 95 * S).allowed(), "the count started over at one");
        assertTrue(ask(A, C, MAP, "chat", last + 60 * S).allowed(), "another pair was never held");
    }

    @Test
    void oneBotSpeakingAgainIsNoReplyAndASlowReplyStartsANewExchange() {
        SocialBudget.useLimitsForTest(open().turns(3, 60 * S).build());
        for (int i = 0; i < 10; i++) {       // A keeps greeting B and B never answers
            assertTrue(ask(A, B, MAP, "greet", i * 5 * S).allowed(), "turn " + i);
            did(A, B, MAP, "greet", i * 5 * S);
        }
        SocialBudget.resetForTest();
        SocialBudget.useLimitsForTest(open().turns(3, 60 * S).build());
        for (int i = 0; i < 10; i++) {       // A and B alternate, but five minutes apart
            int actor = i % 2 == 0 ? A : B, target = i % 2 == 0 ? B : A;
            assertTrue(ask(actor, target, MAP, "chat", i * 5 * MIN).allowed(), "turn " + i);
            did(actor, target, MAP, "chat", i * 5 * MIN);
        }
    }

    @Test
    void aRepeatedlyFailingActBacksOffAndEachBackoffDoubles() {
        SocialBudget.useLimitsForTest(open().backoff(3, 30 * S, 10 * MIN).build());
        long t = 0;
        failed(A, B, MAP, "ask:TRADE", t);
        failed(A, B, MAP, "ask:TRADE", t + S);
        assertTrue(ask(A, B, MAP, "ask:TRADE", t + 2 * S).allowed(), "two failures: still no backoff");
        failed(A, B, MAP, "ask:TRADE", t + 2 * S);                       // third failure: 30 s
        Verdict v = ask(A, B, MAP, "ask:TRADE", t + 3 * S);
        assertEquals(Reason.BACKOFF, v.reason());
        assertEquals(t + 2 * S + 30 * S, v.retryAtMs());
        assertTrue(ask(A, C, MAP, "ask:TRADE", t + 3 * S).allowed(), "another target");
        assertTrue(ask(A, B, MAP, "ask:PARTY", t + 3 * S).allowed(), "another act");
        assertTrue(ask(C, B, MAP, "ask:TRADE", t + 3 * S).allowed(), "another actor");

        long second = t + 32 * S;
        assertTrue(ask(A, B, MAP, "ask:TRADE", second).allowed(), "the 30 s are over");
        failed(A, B, MAP, "ask:TRADE", second);                          // fourth: 60 s
        assertEquals(second + 60 * S, ask(A, B, MAP, "ask:TRADE", second + S).retryAtMs());
        long third = second + 60 * S;
        failed(A, B, MAP, "ask:TRADE", third);                           // fifth: 120 s
        assertEquals(third + 120 * S, ask(A, B, MAP, "ask:TRADE", third + S).retryAtMs());
        long fourth = third + 120 * S;
        failed(A, B, MAP, "ask:TRADE", fourth);                          // sixth: 240 s
        assertEquals(fourth + 240 * S, ask(A, B, MAP, "ask:TRADE", fourth + S).retryAtMs());

        long fifth = fourth + 240 * S;
        for (int i = 0; i < 6; i++) failed(A, B, MAP, "ask:TRADE", fifth);   // keeps doubling up to the cap
        assertEquals(fifth + 10 * MIN, ask(A, B, MAP, "ask:TRADE", fifth + S).retryAtMs(), "capped at ten minutes");
    }

    @Test
    void aSuccessEndsTheStreakAndAnOldStreakIsForgotten() {
        SocialBudget.useLimitsForTest(open().backoff(2, 30 * S, 10 * MIN).build());
        failed(A, B, MAP, "ask:HELP", 0);
        did(A, B, MAP, "ask:HELP", S);
        failed(A, B, MAP, "ask:HELP", 2 * S);
        assertTrue(ask(A, B, MAP, "ask:HELP", 3 * S).allowed(), "fail, success, fail is not a streak");
        failed(A, B, MAP, "ask:HELP", 3 * S);
        assertEquals(Reason.BACKOFF, ask(A, B, MAP, "ask:HELP", 4 * S).reason());
        long later = 3 * S + 7 * 60 * MIN;                                // far past the 6 h reset
        failed(A, B, MAP, "ask:HELP", later);
        assertTrue(ask(A, B, MAP, "ask:HELP", later + S).allowed(), "a stale streak starts again at one");
    }

    @Test
    void aDeniedAskNeverExtendsItsOwnPenalty() {
        SocialBudget.useLimitsForTest(open().backoff(1, 30 * S, 10 * MIN).build());
        failed(A, B, MAP, "ask:TRADE", 0);
        for (int i = 1; i < 30; i++) {
            ask(A, B, MAP, "ask:TRADE", i * S);          // refused 29 times: nothing is recorded
        }
        assertTrue(ask(A, B, MAP, "ask:TRADE", 30 * S).allowed(), "30 s after the failure, whatever was asked since");
    }

    @Test
    void everyRefusalIsCountedUnderItsReason() {
        SocialBudget.useLimitsForTest(open().cooldown("chat", MIN).actor(100).backoff(1, 30 * S, MIN).build());
        assertTrue(ask(A, B, MAP, "chat", 0).allowed());
        did(A, B, MAP, "chat", 0);
        ask(A, B, MAP, "chat", S);
        ask(A, B, MAP, "chat", 2 * S);
        failed(C, D, MAP, "ask:TRADE", 0);
        ask(C, D, MAP, "ask:TRADE", S);
        Map<String, Long> c = SocialBudget.counters();
        assertEquals(1, c.get("allowed"));
        assertEquals(2, c.get("denied pair-cooldown"));
        assertEquals(1, c.get("denied backoff"));
        assertEquals(0, c.get("denied turn-limit"));
        assertEquals(0, c.get("denied actor-rate"));
        assertEquals(0, c.get("denied map-rate"));
        assertEquals(0, c.get("denied global-rate"));
        assertEquals(3, c.get("denied"));
        assertEquals(2, c.get("recorded"));
        assertEquals(1, c.get("failuresRecorded"));
        assertEquals(1, c.get("backoffsStarted"));
    }

    @Test
    void switchedOffEverythingIsAllowedAndNothingIsTracked() {
        System.setProperty(SocialBudget.SWITCH, "false");
        SocialBudget.useLimitsForTest(open().cooldown("chat", MIN).build());
        assertFalse(SocialBudget.enabled());
        for (int i = 0; i < 20; i++) {
            assertTrue(ask(A, B, MAP, "chat", i).allowed());
            did(A, B, MAP, "chat", i);
            failed(A, B, MAP, "ask:TRADE", i);
        }
        assertEquals(0, SocialBudget.counters().get("recorded"));
        assertEquals(0, SocialBudget.counters().get("allowed"));
        System.setProperty(SocialBudget.SWITCH, "true");
        assertTrue(SocialBudget.enabled());
        System.clearProperty(SocialBudget.SWITCH);
        assertTrue(SocialBudget.enabled(), "on by default");
    }

    @Test
    void theSocialClockOverloadFollowsSocialSimAndAClockStepBackDoesNotWedgeAnything() {
        AtomicLong clock = new AtomicLong(10_000_000);
        SocialSim.useForTest(clock::get, 1);
        SocialBudget.useLimitsForTest(open().cooldown("greet", 10 * MIN).build());
        assertTrue(SocialBudget.allow(A, B, MAP, "greet").allowed());
        SocialBudget.record(new Outcome(A, B, MAP, "greet", SocialSim.now(), true));
        assertEquals(Reason.PAIR_COOLDOWN, SocialBudget.allow(A, B, MAP, "greet").reason());
        clock.addAndGet(10 * MIN);
        assertTrue(SocialBudget.allow(A, B, MAP, "greet").allowed());
        SocialBudget.record(new Outcome(A, B, MAP, "greet", SocialSim.now(), true));
        clock.set(5_000);                                   // the clock jumps back past everything recorded
        assertTrue(SocialBudget.allow(A, B, MAP, "greet").allowed(), "a time in the future is no cooldown");
    }

    @Test
    void theDefaultsNeverRefuseWhatBotTalksOwnPacingLetsThrough() {
        // BotTalk's own caps: 20 greets and 12 talks a minute for the whole world, one talk per map every 2-5 min,
        // a pair greets again only after 20 min. Run the worst legal minute for an hour; nothing is refused.
        int refused = 0;
        for (int minute = 0; minute < 60; minute++) {
            long now = 100 * MIN + minute * MIN;
            for (int g = 0; g < 20; g++) {
                int a = 1_000_000_100 + g, b = 1_000_000_200 + g;      // twenty different pairs, all over the world
                long map = g % 4;
                if (minute % 20 == 0 && !ask(a, b, map, "greet", now + g * S).allowed()) refused++;
                if (minute % 20 == 0) did(a, b, map, "greet", now + g * S);
            }
            for (int t = 0; t < 12; t++) {
                int a = 1_000_000_300 + t, b = 1_000_000_400 + t;
                if (minute % 2 == 0 && !ask(a, b, 10 + t, "chat", now + 30 * S + t * S).allowed()) refused++;
                if (minute % 2 == 0) did(a, b, 10 + t, "chat", now + 30 * S + t * S);
            }
        }
        assertEquals(0, refused, SocialBudget.counters().toString());
    }

    @Test
    void thePropertiesMoveALimit() {
        System.setProperty("slums.socialbudget.actorPerMin", "2");
        System.setProperty("slums.socialbudget.greetCooldownS", "5");
        try {
            Limits l = Limits.fromProperties();
            assertEquals(2, l.actorPerMin());
            assertEquals(5_000L, l.cooldownOf("greet"));
            assertEquals(Limits.defaults().globalPerMin(), l.globalPerMin());
            assertEquals(MIN, l.cooldownOf("chat"));
        } finally {
            System.clearProperty("slums.socialbudget.actorPerMin");
            System.clearProperty("slums.socialbudget.greetCooldownS");
        }
    }
}
