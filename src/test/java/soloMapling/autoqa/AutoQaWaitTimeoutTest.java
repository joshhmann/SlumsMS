package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.autoqa.AutoQaRunnerTest.world;
import static soloMapling.autoqa.Check.Status.FAIL;
import static soloMapling.autoqa.Check.Status.PASS;

/**
 * b222 (owner 2026-10-30, ticket 164: "a check must not go green without testing"): a wait that runs out FAILs the
 * check with a "timed out waiting for ... after N s" line. Before, it only noted it and an expectation that passed on
 * an earlier line (t-td-2's staged quote, t-af-3's nemesis state) made the check green although the waited-for thing
 * never happened. Each of these checks passes on the old Steps and fails on the new.
 */
class AutoQaWaitTimeoutTest {

    private static AutoQaRunner.CheckResult runOne(AutoQaRunnerTest.FakeWorld w, Check c) {
        return new AutoQaRunner(w).run(List.of(c), false, null).get(0);
    }

    private static boolean says(AutoQaRunner.CheckResult r, String text) {
        return r.evidence().stream().anyMatch(e -> e.contains(text));
    }

    @Test
    void aWaitForThatTimesOutFailsTheCheckEvenWhenTheExpectationsWouldPass() {
        AutoQaRunnerTest.FakeWorld w = world();
        // the staging line satisfies the expectation; the wait is for something that never comes
        Check c = Check.auto("t-td-2", "ask").step(Steps.run("tr-3"), Steps.say("how much"),
                        Steps.waitFor("TRADE QUOTE ", 15))
                .expect(Expects.has("STAGE staged")).build();
        AutoQaRunner.CheckResult r = runOne(w, c);
        assertEquals(FAIL, r.status(), r.toString());
        assertEquals("timed out waiting for /TRADE QUOTE / after 15 s", r.reason());
        assertTrue(says(r, "timed out waiting for /TRADE QUOTE / after 15 s"), r.evidence().toString());
    }

    @Test
    void anUntilThatTimesOutFailsWithHowFarItGot() {
        AutoQaRunnerTest.FakeWorld w = world();
        Check c = Check.auto("t-cb-4", "manners").step(Steps.run("cb-4"), Steps.qaDo("roast"),
                        Steps.until("RIVALRY ROAST ", 4, 30))
                .expect(Expects.has("RIVALRY ROAST ")).build();
        AutoQaRunner.CheckResult r = runOne(w, c);
        assertEquals(FAIL, r.status(), r.toString());
        assertEquals("timed out waiting for 4 x /RIVALRY ROAST / after 30 s: saw 2", r.reason());
    }

    @Test
    void anUntilProbeThatNeverPassesFailsWithTheLastAnswer() {
        AutoQaRunnerTest.FakeWorld w = world();
        Check c = Check.auto("t-bb-1", "buddy").step(Steps.untilProbe("nope", 20))
                .expect(Expects.probe("ok")).build();
        AutoQaRunner.CheckResult r = runOne(w, c);
        assertEquals(FAIL, r.status(), r.toString());
        assertEquals("timed out waiting for probe nope to pass after 20 s: last answer FAIL not fine", r.reason());
    }

    @Test
    void aGuildInviteThatNeverComesFails() {
        AutoQaRunnerTest.FakeWorld w = world();
        Check c = Check.auto("t-gu-1", "join").step(Steps.run("gu-1"), Steps.acceptGuildInvite(10))
                .expect(Expects.has("STAGE staged")).build();
        AutoQaRunner.CheckResult r = runOne(w, c);
        assertEquals(FAIL, r.status(), r.toString());
        assertEquals("timed out waiting for a guild invite after 10 s", r.reason());
    }

    @Test
    void aWaitOnAnUnknownPlaceholderFailsInsteadOfPassingThrough() {
        AutoQaRunnerTest.FakeWorld w = world();
        Check c = Check.auto("t-af-1", "x").step(Steps.waitFor("CHAT {nobody}: ", 5))
                .expect(Expects.has("CHAT")).build();
        AutoQaRunner.CheckResult r = runOne(w, c);
        assertEquals(FAIL, r.status(), r.toString());
        assertTrue(r.reason().contains("unknown placeholder"), r.reason());
    }

    @Test
    void waitsThatGetWhatTheyWaitForStillPass() {
        AutoQaRunnerTest.FakeWorld w = world();
        Check c = Check.auto("t-rv-1", "roast").step(Steps.run("rv-1"), Steps.qaDo("roast"),
                        Steps.until("RIVALRY ROAST ", 2, 10), Steps.waitForSince(null, "RIVALRY ROAST mock", 10))
                .expect(Expects.has("RIVALRY ROAST mock")).build();
        assertEquals(PASS, runOne(w, c).status());
    }

    @Test
    void quietForPassesOnSilenceAndFailsTheMomentTheLineShowsUpInsideTheWindow() {
        AutoQaRunnerTest.FakeWorld w = world();
        Check quiet = Check.auto("t-af-3", "quiet").step(Steps.run("af-3"), Steps.quietFor("RIVALRY ROAST ", 30))
                .expect(Expects.has("STAGE staged")).build();
        long t0 = w.now;
        assertEquals(PASS, runOne(w, quiet).status());
        assertTrue(w.now - t0 >= 30_000, "a quiet window is waited out in full");

        // a line from before the window does not count
        Check before = Check.auto("t-af-3", "before").step(Steps.run("af-3"), Steps.quietFor("STAGE staged", 30))
                .expect(Expects.has("STAGE staged")).build();
        assertEquals(PASS, runOne(world(), before).status());

        AutoQaRunnerTest.FakeWorld noisy = world();
        noisy.onSay = (fw, line) -> {
            AutoQaRunnerTest.suite(fw, line);
            if (line.startsWith("!qa run af-3")) {
                fw.later(fw.now + 8_000, "RIVALRY", "ROAST mock Bot42 -> AutoQaOne: lol"); // 8 s on: in the window
            }
        };
        long n0 = noisy.now;
        AutoQaRunner.CheckResult r = runOne(noisy, quiet);
        assertEquals(FAIL, r.status(), r.toString());
        assertTrue(r.reason().startsWith("not quiet: /RIVALRY ROAST / showed up after "), r.reason());
        assertTrue(noisy.now - n0 < 30_000, "it fails at once, not at the end of the window");
    }

    @Test
    void anOptionalWaitNeverFailsAndSaysWhy() {
        AutoQaRunnerTest.FakeWorld w = world();
        Check c = Check.auto("t-ev-2", "invasion").step(Steps.run("ev-2"),
                        Steps.optionalWait("WEVENT BOT DIED ", 20, "a natural death is welcome"))
                .expect(Expects.has("STAGE staged")).build();
        AutoQaRunner.CheckResult r = runOne(w, c);
        assertEquals(PASS, r.status(), r.toString());
        assertTrue(says(r, "optional window: no /WEVENT BOT DIED / in 20 s (a natural death is welcome)"),
                r.evidence().toString());
    }

    @Test
    void waitForSinceSeesALineFromBeforeThePreviousStep() {
        AutoQaRunnerTest.FakeWorld w = world();
        // the staging line came during !qa run: two steps later a plain waitFor no longer sees it
        Check plain = Check.auto("t-bl-3", "plain").step(Steps.run("bl-3"), Steps.waitSeconds(5),
                Steps.waitSeconds(5), Steps.waitFor("STAGE staged", 10)).expect(Expects.has("STAGE staged")).build();
        assertEquals(FAIL, runOne(w, plain).status());
        Check since = Check.auto("t-bl-3", "since").step(Steps.run("bl-3"), Steps.waitSeconds(5),
                        Steps.waitSeconds(5), Steps.waitForSince(null, "STAGE staged", 10))
                .expect(Expects.has("STAGE staged")).build();
        assertEquals(PASS, runOne(world(), since).status());
    }

    @Test
    void theResultCitesTheQaLogTheScenarioWrote() {
        AutoQaRunnerTest.FakeWorld w = world();
        w.onSay = (fw, line) -> {
            AutoQaRunnerTest.suite(fw, line);
            if (line.startsWith("!qa run af-1")) {
                fw.actors.get(0).hear("YELLOW", "Logging to logs/qa/af-1-20261030T190258Z.log. Stops in 15 min, or !qa stop.");
            }
        };
        Check c = Check.auto("t-af-1", "friend").step(Steps.run("af-1")).expect(Expects.has("STAGE staged")).build();
        AutoQaRunner.CheckResult r = runOne(w, c);
        assertEquals(PASS, r.status());
        assertEquals(List.of("logs/qa/af-1-20261030T190258Z.log"), r.logs());
        Check probeOnly = Check.auto("t-bg-1", "probe").expect(Expects.probe("ok")).build();
        assertEquals(List.of(), runOne(world(), probeOnly).logs());
    }
}
