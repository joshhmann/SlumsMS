package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.6.0 (owner 2026-10-26) t-ex-1 against the lines ex-1 writes (ExpeditionScenarios), and its hint. */
class ExpeditionCatalogTest {

    static final String HINT = "STAGE told GM: Joined with you: Clock1, Clock2, Clock3, Clock4, Clock5; members 6 of at"
            + " least 6.";
    static final String HINT2 = "STAGE told GM: The log has one join line per party bot; the outsider never joins. Stop"
            + " ends the expedition.";

    @Test
    void allPartyBotsJoinedAndTheOutsiderStayedOut() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-ex-1", HINT, HINT2,
                "PQ EXPED BOT JOIN Clock1 -> ZAKUM", "PQ EXPED BOT JOIN Clock2 -> ZAKUM",
                "EX1 EX1 RESULT members=6 min=6 joined=5/5 outsider=out again=0",
                "EX1 EX1 PASS [Clock1, Clock2, Clock3, Clock4, Clock5]");
        for (Check.Verdict x : v) {
            assertEquals(Check.Status.PASS, x.status(), x.toString());
        }
    }

    @Test
    void aMissedBotOrAnOutsiderFailsAndTheHintAloneProvesNothing() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-ex-1", HINT, HINT2, "PQ EXPED BOT JOIN Clock1 -> ZAKUM",
                "EX1 EX1 RESULT members=3 min=6 joined=2/5 outsider=in again=0", "EX1 EX1 FAIL [Clock1, Clock2]");
        assertEquals(List.of(Check.Status.PASS, Check.Status.FAIL, Check.Status.FAIL, Check.Status.PASS),
                v.stream().map(Check.Verdict::status).toList());
        var hintOnly = AutoQaCatalogPatternTest.eval("t-ex-1", HINT, HINT2);
        assertEquals(List.of(Check.Status.FAIL, Check.Status.FAIL, Check.Status.FAIL, Check.Status.PASS),
                hintOnly.stream().map(Check.Verdict::status).toList());
    }
}
