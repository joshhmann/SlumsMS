package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.autoqa.BotWatch;
import soloMapling.autoqa.BotWatchJudge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 1.6.0 (owner 2026-10-26) bot watch: bw-1 logs each verdict and PASS/FAIL, and its hint never matches the check. */
class BotWatchScenarioTest {
    @TempDir Path directory;

    private final AtomicReference<Consumer<BotWatch.Result>> done = new AtomicReference<>();
    private int stops;

    @AfterEach
    void restore() {
        BotWatchScenarios.Bw1Watch.starter = (n, m, by, d) -> BotWatch.get().start(n, m, by, d);
        BotWatchScenarios.Bw1Watch.stopper = () -> BotWatch.get().stop();
    }

    private QaSuite suite(List<String> answer) {
        BotWatchScenarios.Bw1Watch.starter = (n, m, by, d) -> {
            assertEquals(20, n);
            assertEquals(10, m);
            done.set(d);
            return answer;
        };
        BotWatchScenarios.Bw1Watch.stopper = () -> stops++;
        return QaTestSupport.suite(BotWatchScenarios.all(), mock(QaWorld.class), directory,
                new QaTestSupport.ManualScheduler());
    }

    static BotWatch.Result result(BotWatchJudge.Verdict... verdicts) {
        List<Map.Entry<String, BotWatchJudge.Verdict>> v = new java.util.ArrayList<>();
        for (BotWatchJudge.Verdict x : verdicts) {
            v.add(Map.entry("GRIND", x));
        }
        BotWatchJudge.Summary s = BotWatchJudge.summarize(v);
        return new BotWatch.Result(s, s.line(10), List.of("Mochi GRIND OK gained EXP on map 104040000"),
                BotWatchJudge.checkProblem(s), null, Map.of());
    }

    @Test
    void bw1LogsEveryBotTheSummaryAndPass() throws Exception {
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = suite(List.of("Watching 20 bots for 10 min, a look every 30 s: {GRIND=10, HANG_OUT=10}."));
        var answer = suite.start(gm, "bw-1", new String[0]);
        assertTrue(answer.getFirst().startsWith("QA bw-1 running"), answer.toString());
        done.get().accept(result(BotWatchJudge.Verdict.OK, BotWatchJudge.Verdict.OK, BotWatchJudge.Verdict.OK,
                BotWatchJudge.Verdict.OK, BotWatchJudge.Verdict.OK));
        String log = Files.readString(suite.runOf(1).logPath());
        suite.stop(1, "done");
        assertTrue(log.contains("BW1 START Watching 20 bots"), log);
        assertTrue(log.contains("BW1 BOT Mochi GRIND OK gained EXP"), log);
        assertTrue(log.contains("BW1 SUMMARY 5/5 bots OK (100%)"), log);
        assertTrue(log.contains("BW1 PASS 5/5 bots OK"), log);
        assertFalse(log.contains("BW1 FAIL"), log);
        assertEquals(0, stops, "a finished watch is not stopped again");
    }

    @Test
    void bw1FailsBelowTheBarAndStopsAnUnfinishedWatch() throws Exception {
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = suite(List.of("Watching 20 bots for 10 min, a look every 30 s: {GRIND=20}."));
        suite.start(gm, "bw-1", new String[0]);
        done.get().accept(result(BotWatchJudge.Verdict.OK, BotWatchJudge.Verdict.IDLE, BotWatchJudge.Verdict.IDLE,
                BotWatchJudge.Verdict.OK, BotWatchJudge.Verdict.OK));
        String log = Files.readString(suite.runOf(1).logPath());
        assertTrue(log.contains("BW1 FAIL 60% OK (need 80%); 40% idle (at most 10%)"), log);
        suite.stop(1, "done");

        QaSuite again = suite(List.of("Watching 20 bots for 10 min, a look every 30 s: {GRIND=20}."));
        again.start(gm, "bw-1", new String[0]);
        again.stop(1, "!qa stop");
        assertEquals(1, stops, "a run stopped early stops its watch");
    }

    @Test
    void bw1RefusesWhileAWatchRuns() {
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = suite(List.of("A bot watch is already running (!botwatch Admin, 12 min 0 s left): !botwatch status."));
        // auto-QA reads "already running" as a precondition (Steps.PRECONDITION): t-bw-1 SKIPs, it does not FAIL
        QaRefusal r = org.junit.jupiter.api.Assertions.assertThrows(QaRefusal.class,
                () -> suite.start(gm, "bw-1", new String[0]));
        assertTrue(r.getMessage().contains("already running"), r.getMessage());
    }

    @Test
    void theHintNeverMatchesTheChecksOwnPatterns() throws Exception {
        // the known bug class: a waitFor/lacks matched the GM-facing hint ("STAGE told GM: ...") instead of the result
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = suite(List.of("Watching 20 bots for 10 min, a look every 30 s: {GRIND=20}."));
        suite.start(gm, "bw-1", new String[0]);
        String log = Files.readString(suite.runOf(1).logPath());
        suite.stop(1, "done");
        List<String> told = log.lines().filter(l -> l.contains("told GM: ")).toList();
        assertFalse(told.isEmpty(), log);
        for (String p : List.of("BW1 (PASS|FAIL) ", "BW1 FAIL ", "BW1 PASS ", "BW1 SUMMARY ", "BW1 START ")) {
            for (String l : told) {
                assertFalse(Pattern.compile(p).matcher(l).find(), p + " matched the hint: " + l);
            }
        }
    }
}
