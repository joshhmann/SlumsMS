package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.autoqa.SocialTraceEntry.Stage;
import soloMapling.autoqa.SocialTraceEntry.Status;
import soloMapling.autoqa.SocialTraceEntry.Verdict;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.autoqa.SocialTraceJudgeTest.A;
import static soloMapling.autoqa.SocialTraceJudgeTest.B;
import static soloMapling.autoqa.SocialTraceJudgeTest.T0;

/**
 * 1.6.0 tune: a TRAINING thread whose asker takes the tip decides "go train at &lt;map&gt;", and ACT is ok once the
 * bot stands on that map inside the window; SENSE checks the places a training line names against the bots' levels
 * (TEST trace 2026-10-26: "Fire Boars at 63", "wild boars in Kerning subway", "KPQ in Ludibrium").
 */
class SocialTraceTrainingTipTest {

    List<Map<String, Object>> written;

    @BeforeEach
    void quietTrace() {
        SocialTrace.resetForTest();
        written = new ArrayList<>();
        SocialTrace.sink = written::add;
        SocialTrace.endReader = e -> Map.of("sameParty", false);
        SocialTrace.rules = SocialTraceJudgeTest.RULES;
    }

    @AfterEach
    void reset() {
        SocialTrace.resetForTest();
        SocialTrace.mapOf = id -> -1;
    }

    static SocialTraceEntry training(int aLevel, int bLevel) {
        SocialTraceEntry e = SocialTraceJudgeTest.thread("TRAINING", "Shroom93 is level " + aLevel + ", aiming for 30");
        e.aLevel = aLevel;
        e.bLevel = bLevel;
        e.minLevel = Math.min(aLevel, bLevel);
        e.source = "llm";
        e.planned = 2;
        return e;
    }

    static EnumMap<Stage, Verdict> judge(SocialTraceEntry e) {
        return SocialTraceJudge.judge(e, SocialTraceJudgeTest.RULES);
    }

    @Test
    void aFollowedTipDecidesAndActsWhenTheBotArrives() {
        SocialTraceEntry e = training(25, 40);
        SocialTraceJudgeTest.say(e, 3_000, A, "where u training at 25?");
        SocialTraceJudgeTest.say(e, 6_000, B, "try ant tunnel tbh");
        SocialTrace.add(e);
        SocialTrace.onForTest(true);
        e.decided = "go train at Ant Tunnel (105050200)";
        e.goalBot = A;
        e.goalMap = 105050200;
        SocialTrace.mapOf = id -> 104000000;
        SocialTrace.watchGoals(T0 + 60_000);
        assertEquals(Status.FAIL, judge(e).get(Stage.ACT).status(), "not there yet");
        SocialTrace.mapOf = id -> id == A ? 105050200 : 100000000;
        SocialTrace.watchGoals(T0 + 5 * 60_000);
        SocialTrace.watchGoals(T0 + 6 * 60_000);
        EnumMap<Stage, Verdict> v = judge(e);
        assertEquals(Status.OK, v.get(Stage.DECIDE).status());
        assertEquals("go train at Ant Tunnel (105050200)", v.get(Stage.DECIDE).reason());
        assertEquals(Status.OK, v.get(Stage.ACT).status(), v.get(Stage.ACT).reason());
        assertTrue(v.get(Stage.ACT).reason().startsWith("arrive after 300 s"), v.get(Stage.ACT).reason());
        assertEquals(1, e.events.size(), "one arrival");
        assertEquals(Status.OK, v.get(Stage.SENSE).status(), v.get(Stage.SENSE).reason());
    }

    @Test
    void anArrivalAfterTheWindowIsNoAct() {
        SocialTraceEntry e = training(25, 40);
        SocialTraceJudgeTest.say(e, 3_000, A, "where u training at 25?");
        SocialTraceJudgeTest.say(e, 6_000, B, "try ant tunnel tbh");
        SocialTrace.add(e);
        e.decided = "go train at Ant Tunnel (105050200)";
        e.goalBot = A;
        e.goalMap = 105050200;
        SocialTrace.mapOf = id -> 105050200;
        SocialTrace.watchGoals(T0 + SocialTraceJudge.ACT_WINDOW_MS + 1);
        assertEquals(Status.FAIL, judge(e).get(Stage.ACT).status());
    }

    @Test
    void aTipNotTakenDecidesNothing() {
        SocialTraceEntry e = training(25, 40);
        SocialTraceJudgeTest.say(e, 3_000, A, "where u training at 25?");
        SocialTraceJudgeTest.say(e, 6_000, B, "try ant tunnel tbh");
        assertEquals(Status.NONE, judge(e).get(Stage.DECIDE).status());
        assertTrue(judge(e).get(Stage.DECIDE).reason().contains("1 in 3"), judge(e).get(Stage.DECIDE).reason());
    }

    @Test
    void senseFlagsPlacesThatDoNotFitTheLevels() {
        String[][] wrong = {
                {"63", "63", "some guy xp!!! juz watch teh fire boars lolz xD"},
                {"30", "30", "go kerning subway porfa, wild boars r good xp bro"},
                {"32", "78", "go kpq in ludibrium tbh decent exp for ur lvl"},
        };
        for (String[] w : wrong) {
            SocialTraceEntry e = training(Integer.parseInt(w[0]), Integer.parseInt(w[1]));
            SocialTraceJudgeTest.say(e, 3_000, A, "where should i train lol");
            SocialTraceJudgeTest.say(e, 6_000, B, w[2]);
            Verdict sense = judge(e).get(Stage.SENSE);
            assertEquals(Status.FAIL, sense.status(), w[2]);
            assertTrue(sense.reason().startsWith("wrong place"), sense.reason());
        }
        SocialTraceEntry ok = training(25, 30);
        SocialTraceJudgeTest.say(ok, 3_000, A, "where should i train lol");
        SocialTraceJudgeTest.say(ok, 6_000, B, "ant tunnel or wild boars in perion tbh");
        assertEquals(Status.OK, judge(ok).get(Stage.SENSE).status(), judge(ok).get(Stage.SENSE).reason());
        // a spot that fits either bot (the asker or the speaker itself) passes
        SocialTraceEntry own = training(50, 67);
        SocialTraceJudgeTest.say(own, 3_000, A, "where do u grind");
        SocialTraceJudgeTest.say(own, 6_000, B, "lost time, the exp is fine");
        assertFalse(judge(own).get(Stage.SENSE).reason().startsWith("wrong place"), judge(own).get(Stage.SENSE).reason());
    }
}
