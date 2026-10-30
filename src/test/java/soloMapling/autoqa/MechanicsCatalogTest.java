package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.7.0 ticket 130: t-mx-1 against the lines mx-1 and the run write (MechanicsScenarios, MechanicsRuns). */
class MechanicsCatalogTest {

    static final String HINT = "STAGE told GM: 4 bots are in Kerning PQ stage 1 (Kerning1). They ask Cloto, farm"
            + " Ligator coupons, hand them in for passes and give the passes to their leader.";
    static final String HINT2 = "STAGE told GM: The log has a RUN line per step (answers, hand-ins with wrong tries,"
            + " drops) and a verdict line at the end; the bots leave 20 s after.";

    static Check.Status[] statuses(List<Check.Verdict> v) {
        return v.stream().map(Check.Verdict::status).toArray(Check.Status[]::new);
    }

    @Test
    void aClearInTimePasses() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-mx-1", HINT, HINT2,
                "MX1 RUN start kpq stage 1 in Kerning1: 1 leader = leader, 2 = answerer, 3 = answerer, 4 = answerer",
                "MX1 MX1 START kpq stage 1 with 4 bots in Kerning1",
                "MX1 NPC Aria -> 9020001: \"Here's the question. Collect the same number of coupons as...\"",
                "MX1 NPC Aria -> 9020001 with 9 x 4001007: \"I'm sorry, but that is not the right answer!...\"",
                "MX1 RUN stage 1 cleared in 312 s (2 wrong tries shown)",
                "MX1 RUN end kpq in Kerning1: cleared after 312 s, 2 wrong tries shown",
                "MX1 MX1 PASS cleared stage 1 in 312 s (limit 900 s) by 4 bots, 2 wrong tries shown");
        for (Check.Verdict x : v) {
            assertEquals(Check.Status.PASS, x.status(), x.toString());
        }
    }

    @Test
    void aTimeoutOrAScriptFailureFailsAndTheHintsProveNothing() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-mx-1", HINT, HINT2,
                "MX1 MX1 START kpq stage 1 with 4 bots in Kerning1",
                "MX1 NPC Aria -> 9020001 FAILED: npc/9020001.js for a bot: java.lang.NullPointerException",
                "MX1 RUN end kpq in Kerning1: time budget of 15 min ran out on stage 1",
                "MX1 MX1 FAIL time budget of 15 min ran out on stage 1");
        assertEquals(List.of(Check.Status.PASS, Check.Status.FAIL, Check.Status.FAIL, Check.Status.FAIL,
                Check.Status.FAIL), List.of(statuses(v)));
        var hintsOnly = AutoQaCatalogPatternTest.eval("t-mx-1", HINT, HINT2);
        assertEquals(List.of(Check.Status.FAIL, Check.Status.FAIL, Check.Status.FAIL, Check.Status.PASS,
                Check.Status.PASS), List.of(statuses(hintsOnly)));
    }
}
