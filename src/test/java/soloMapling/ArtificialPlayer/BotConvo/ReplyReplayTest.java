package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * m02 section 6, the replay check: four days of TEST replies to players (2026-10-28..31, checked in as a fixture) are
 * re-read with the rule triage and the planner. The bug this ticket is about shows up before: replies that say the bot
 * is grinding where it stands in town, and lines three or more bots said word for word. After the planner's own scripts
 * say the same moves, there are none of either.
 */
class ReplyReplayTest {

    @Test
    void theProblemIsInTheRecordedTrafficAndThePlanScriptsHaveNone() throws IOException {
        ReplyReplay.Result r = ReplyReplay.run(ReplyReplay.rows());
        System.out.println("replay: " + r.replies() + " replies, " + r.town() + " on a town map, before: "
                + r.claimsBefore() + " grinding claims, " + r.repeatsBefore().size() + " lines from 3+ bots; planned "
                + r.planned() + " " + r.byMove() + ", scripts " + r.scripts() + ", after: " + r.claimsAfter()
                + " grinding claims, " + r.repeatsAfter().size() + " lines from 3+ bots in 10 min (" + r.topBefore()
                + " before)");
        assertTrue(r.replies() > 700, "the fixture is the four days of replies: " + r.replies());
        assertEquals(r.replies(), r.sources().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(r.town() > 200, "most replies to players happen in towns: " + r.town());
        assertTrue(r.claimsBefore() > 0, "the record has replies claiming to grind in town: " + r.claimsBefore());
        assertFalse(r.topBefore().equals("none"), "the record has lines several bots said word for word");
        assertTrue(r.planned() > 0, "the planner covers some of this traffic: " + r.byMove());
        assertEquals(0, r.claimsAfter(), "a planner script never says it grinds in a town: " + r.claimSamples());
        assertEquals(0, r.repeatsAfter().size(), "no planner script is said by three bots: " + r.repeatsAfter());
    }

    @Test
    void theClaimCountIsEveryRowNotJustTheTowns() throws IOException {
        int claims = 0;
        for (ReplyReplay.Row row : ReplyReplay.rows()) {
            if (StateOfMind.claimsGrindingInTown(row.line())) {
                claims++;
            }
        }
        assertEquals(claims, ReplyReplay.run(ReplyReplay.rows()).claimsBefore(), "same count both ways");
    }

    @Test
    void theFixtureIsPlayerDirectedReplies() throws IOException {
        for (ReplyReplay.Row row : ReplyReplay.rows()) {
            assertFalse(row.bot().isBlank());
            assertFalse(row.line().isBlank());
            assertTrue(row.source().equals("llm") || row.source().equals("bank") || row.source().equals("scripted"),
                    row.source());
        }
    }
}
