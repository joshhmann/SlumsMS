package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuildChatQaRegressionTest {
    @Test
    void requiredGuildGreetingNamesTheStagedBotAndWaitsForItsReply() {
        List<String> steps = AutoQaCatalog.find("t-gu-1").steps().stream().map(Check.Step::describe).toList();
        assertTrue(steps.contains("chat type 2: {bot} hi guys"),
                "unnamed greetings may be ignored by the normal reply chance or guild cooldown");
        assertTrue(steps.stream().anyMatch(s -> s.contains("MULTICHAT guild {bot}:")),
                "wait for actual delivery rather than a fixed typing delay");
    }

    @Test
    void announcingAnAnswerWithoutDeliveringItMustFail() throws Exception {
        List<Check.Verdict> verdicts = guildExchange(false);
        assertTrue(verdicts.stream().anyMatch(v -> v.status() == Check.Status.FAIL),
                "the GUILD CHAT intent hook alone does not prove a player received guild chat");
    }

    @Test
    void aJoinedPlayerHearingTheNamedBotsGuildReplyPasses() throws Exception {
        for (Check.Verdict verdict : guildExchange(true)) {
            assertEquals(Check.Status.PASS, verdict.status(), verdict.toString());
        }
    }

    /**
     * TEST 10-31 (t-gu-1, run 4d3de9837): while the staged officer answered "{bot} hi guys", another guildmate
     * happened to say "who wants to do some pqs later" and the check counted it as a second answer to the greeting.
     * Guild chatter is not an answer: the check counts the guild's answers (the GUILD CHAT hook), not its traffic, and
     * chatter is marked in the log by the GUILD SAY hook every bot guild line goes through.
     */
    @Test
    void anotherGuildmatesChatterInsideTheWindowIsNotASecondAnswer() throws Exception {
        var world = new AutoQaRunnerTest.FakeWorld();
        Check check = AutoQaCatalog.find("t-gu-1");
        CheckRun run = new CheckRun(check, world);
        var player = (AutoQaRunnerTest.FakeActor) run.actor(1);
        run.set("bot", "Mochi");
        world.qa("BOT_GUILD", "APPLY AutoQaOne -> SnailGang: YES");
        world.qa("BOT_GUILD", "INVITE SENT Leader -> AutoQaOne for SnailGang");
        world.qa("BOT_GUILD", "PLAYER JOINED AutoQaOne -> SnailGang");
        run.mark("hi");
        world.now += 1000;
        player.hear("MULTICHAT", "guild Gangstasama: who wants to do some pqs later"); // nobody asked him
        world.qa("BOT_GUILD", "GUILD SAY Gangstasama [SnailGang]: who wants to do some pqs later");
        world.now += 1000;
        world.qa("BOT_GUILD", "GUILD CHAT AutoQaOne: hi guys -> Mochi answers");
        player.hear("MULTICHAT", "guild Mochi: sun49 hi gyus kekeke");
        world.now += 1000;
        player.hear("MULTICHAT", "guild TofuMon: brb food");
        world.qa("BOT_GUILD", "GUILD SAY TofuMon [SnailGang]: brb food");
        world.now += 1000;
        run.mark("q");
        world.now += 1000;
        run.mark("end");
        List<String> failed = new ArrayList<>();
        for (Check.Expect expect : check.expects()) {
            Check.Verdict v = expect.eval(run);
            if (v.status() != Check.Status.PASS) {
                failed.add(expect.describe() + ": " + v.evidence());
            }
        }
        assertTrue(failed.isEmpty(), failed.toString());
    }

    @Test
    void twoBotsAnsweringTheGreetingStillFails() throws Exception {
        var world = new AutoQaRunnerTest.FakeWorld();
        Check check = AutoQaCatalog.find("t-gu-1");
        CheckRun run = new CheckRun(check, world);
        var player = (AutoQaRunnerTest.FakeActor) run.actor(1);
        run.set("bot", "Mochi");
        world.qa("BOT_GUILD", "APPLY AutoQaOne -> SnailGang: YES");
        world.qa("BOT_GUILD", "INVITE SENT Leader -> AutoQaOne for SnailGang");
        world.qa("BOT_GUILD", "PLAYER JOINED AutoQaOne -> SnailGang");
        run.mark("hi");
        world.now += 1000;
        world.qa("BOT_GUILD", "GUILD CHAT AutoQaOne: hi guys -> Mochi answers");
        world.qa("BOT_GUILD", "GUILD CHAT AutoQaOne: hi guys -> Gangstasama answers");
        player.hear("MULTICHAT", "guild Mochi: hi");
        world.qa("BOT_GUILD", "GUILD SAY Mochi [SnailGang]: hi");
        player.hear("MULTICHAT", "guild Gangstasama: hi");
        world.qa("BOT_GUILD", "GUILD SAY Gangstasama [SnailGang]: hi");
        world.now += 1000;
        run.mark("q");
        world.now += 1000;
        run.mark("end");
        List<Long> fails = new ArrayList<>();
        for (Check.Expect expect : check.expects()) {
            if (expect.eval(run).status() == Check.Status.FAIL) {
                fails.add(1L);
            }
        }
        assertEquals(1, fails.size(), "a guild that piles in on the greeting must still fail the check");
    }

    /**
     * Review (round 2): the answer count is satisfied while a second bot answers through a path the answer hook never
     * logs. PickleCurse's line reaches the player but no {@code BOT_GUILD GUILD SAY} hook marks it, so it was not
     * ordinary guild chat: the check must FAIL, and only on that.
     */
    @Test
    void aSecondBotAnsweringOutsideTheAnswerHookFails() throws Exception {
        var world = new AutoQaRunnerTest.FakeWorld();
        Check check = AutoQaCatalog.find("t-gu-1");
        CheckRun run = new CheckRun(check, world);
        var player = (AutoQaRunnerTest.FakeActor) run.actor(1);
        run.set("bot", "Mochi");
        world.qa("BOT_GUILD", "APPLY AutoQaOne -> SnailGang: YES");
        world.qa("BOT_GUILD", "INVITE SENT Leader -> AutoQaOne for SnailGang");
        world.qa("BOT_GUILD", "PLAYER JOINED AutoQaOne -> SnailGang");
        run.mark("hi");
        world.now += 1000;
        world.qa("BOT_GUILD", "GUILD CHAT AutoQaOne: hi guys -> Mochi answers");
        world.qa("BOT_GUILD", "GUILD SAY Mochi [SnailGang]: hi hi");
        player.hear("MULTICHAT", "guild Mochi: hi hi");
        world.now += 1000;
        player.hear("MULTICHAT", "guild PickleCurse: sun49 hi gyus kekeke"); // no GUILD SAY: answered another way
        world.now += 1000;
        run.mark("q");
        world.now += 1000;
        run.mark("end");
        List<String> failed = new ArrayList<>();
        for (Check.Expect expect : check.expects()) {
            Check.Verdict v = expect.eval(run);
            if (v.status() != Check.Status.PASS) {
                failed.add(expect.describe() + ": " + v.evidence());
            }
        }
        assertEquals(1, failed.size(), failed.toString());
        assertTrue(failed.get(0).contains("no second bot answered the guild line outside the answer hook"),
                failed.toString());
        assertTrue(failed.get(0).contains("PickleCurse: sun49 hi gyus kekeke"), failed.toString());
    }

    private List<Check.Verdict> guildExchange(boolean delivered) throws Exception {
        var world = new AutoQaRunnerTest.FakeWorld();
        Check check = AutoQaCatalog.find("t-gu-1");
        CheckRun run = new CheckRun(check, world);
        var player = (AutoQaRunnerTest.FakeActor) run.actor(1);
        run.set("bot", "Mochi");
        world.qa("BOT_GUILD", "APPLY AutoQaOne -> SnailGang: YES");
        world.qa("BOT_GUILD", "INVITE SENT Leader -> AutoQaOne for SnailGang");
        world.qa("BOT_GUILD", "PLAYER JOINED AutoQaOne -> SnailGang");
        run.mark("hi");
        world.now += 1000;
        world.qa("BOT_GUILD", "GUILD CHAT AutoQaOne: hi guys -> Mochi answers");
        if (delivered) {
            player.hear("MULTICHAT", "guild Mochi: hey welcome");
        }
        world.now += 1000;
        run.mark("q");
        world.now += 1000;
        run.mark("end");
        List<Check.Verdict> verdicts = new ArrayList<>();
        for (Check.Expect expect : check.expects()) {
            verdicts.add(expect.eval(run));
        }
        return verdicts;
    }
}
