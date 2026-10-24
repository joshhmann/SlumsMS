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
