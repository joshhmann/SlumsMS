package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.6.0 (owner 2026-10-26) t-bw-1 against the lines bw-1 writes (BotWatchScenarios), and its hint. */
class BotWatchCatalogTest {

    static final String START = "BW1 START Watching 20 bots for 10 min, a look every 30 s: {GRIND=10, HANG_OUT=10}.";
    static final String HINT = "STAGE told GM: A 10-minute bot watch of 20 bots starts now; nothing is staged.";
    static final String HINT2 = "STAGE told GM: Each bot's verdict and the run's result are logged at the end; the file "
            + "is data/autoqa/botwatch-latest.json.";

    @Test
    void aPassingWatchPasses() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-bw-1", HINT, HINT2, START,
                "BW1 BOT Mochi GRIND OK gained EXP on map 104040000",
                "BW1 SUMMARY 18/20 bots OK (90%), idle 1, mismatch 1, teleporty 0, logged out 0 over 10 min",
                "BW1 PASS 18/20 bots OK (90%), idle 1, mismatch 1, teleporty 0, logged out 0 over 10 min");
        for (Check.Verdict x : v) {
            assertEquals(Check.Status.PASS, x.status(), x.toString());
        }
    }

    @Test
    void aFailingWatchFailsAndTheHintAloneProvesNothing() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-bw-1", HINT, HINT2, START,
                "BW1 SUMMARY 12/20 bots OK (60%), idle 5, mismatch 3, teleporty 0, logged out 0 over 10 min",
                "BW1 FAIL 60% OK (need 80%); 25% idle (at most 10%)");
        assertEquals(List.of(Check.Status.PASS, Check.Status.PASS, Check.Status.FAIL, Check.Status.FAIL),
                v.stream().map(Check.Verdict::status).toList());
        var hintOnly = AutoQaCatalogPatternTest.eval("t-bw-1", HINT, HINT2);
        assertEquals(List.of(Check.Status.FAIL, Check.Status.FAIL, Check.Status.FAIL, Check.Status.PASS),
                hintOnly.stream().map(Check.Verdict::status).toList());
    }
}
