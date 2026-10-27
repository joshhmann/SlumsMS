package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.autoqa.Check.Status.FAIL;
import static soloMapling.autoqa.Check.Status.PASS;

/**
 * 1.6.0 (owner, 2026-10-27): a judged conversation check (t-cv-2, t-cv-3...) tolerates one bad grade when it judged
 * three or more replies; two bad, or one bad out of fewer than three, still FAIL; a judge outage stays undecided.
 */
class JudgedToleranceTest {
    private static final LlmJudge.Grade OK = new LlmJudge.Grade(true, true, "fine");
    private static final LlmJudge.Grade BAD = new LlmJudge.Grade(false, true, "off topic");
    private static final LlmJudge.Grade OUT = new LlmJudge.Grade(true, false, "judge did not answer");

    private static Check.Verdict verdict(LlmJudge.Grade... grades) {
        List<String> replies = new ArrayList<>();
        for (int i = 0; i < grades.length; i++) replies.add("line " + i);
        return Expects.judgedVerdict(replies, List.of(grades));
    }

    @Test void oneBadOutOfThreeIsToleratedAndTheLineIsInTheEvidence() {
        Check.Verdict v = verdict(OK, BAD, OK);
        assertEquals(PASS, v.status(), v.evidence().toString());
        assertTrue(v.evidence().get(0).startsWith("tolerated 1 bad of 3 judged: BAD \"line 1\""), v.evidence().toString());
    }

    @Test void twoBadFail() {
        Check.Verdict v = verdict(OK, BAD, OK, BAD, OK);
        assertEquals(FAIL, v.status());
        assertTrue(v.evidence().get(0).startsWith("2 bad of 5 judged"), v.evidence().toString());
    }

    @Test void oneBadOutOfFewerThanThreeFails() {
        assertEquals(FAIL, verdict(OK, BAD).status());
        assertEquals(FAIL, verdict(BAD).status());
    }

    @Test void anOutageIsNotJudgedSoItNeitherFailsNorCountsTowardsThree() {
        assertEquals(PASS, verdict(OUT, OUT, OUT).status());
        assertEquals(FAIL, verdict(OK, BAD, OUT).status(), "only two replies were judged");
    }

    @Test void aLineThatRevealsItIsAnAiIsNeverTolerated() {
        assertEquals(FAIL, verdict(OK, OK, new LlmJudge.Grade(false, true, LlmJudge.REVEALS_REASON)).status());
    }

    @Test void throughTheRunnerOneBadOfThreeRepliesPasses() {
        AutoQaRunnerTest.FakeWorld w = AutoQaRunnerTest.world();
        w.onSay = (world, line) -> {
            if (line.equals("!qa do roast3")) {
                world.qa("RIVALRY", "ROAST mock Bot42 -> AutoQaOne: lv 70 still here lol");
                world.qa("RIVALRY", "ROAST mock Bot42 -> AutoQaOne: certainly, how may i assist");
                world.qa("RIVALRY", "ROAST mock Bot42 -> AutoQaOne: nice hat noob");
            }
        };
        Check c = Check.auto("t-rv-1", "roast").step(Steps.qaDo("roast3"))
                .expect(Expects.judged("RIVALRY ROAST .*-> {me}: (.+)", "a roast", 3)).build();
        String json = "{\"on_topic\":true,\"coherent\":true,\"in_character\":%s,\"pg13\":true,\"reveals_ai\":false,\"reason\":\"%s\"}";
        w.judge = LlmJudge.of((sys, user) -> user.contains("certainly") ? String.format(json, false, "stiff")
                : String.format(json, true, "fine"), 5);
        AutoQaRunner.CheckResult res = new AutoQaRunner(w).run(List.of(c), false, null).get(0);
        assertEquals(PASS, res.status(), res.toString());
        assertTrue(res.evidence().stream().anyMatch(e -> e.contains("tolerated 1 bad of 3") && e.contains("certainly")),
                res.evidence().toString());
    }
}
