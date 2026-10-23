package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* 1.5.0 rc6 (QA t-af-1): "deepen the chat depending on level like where they grind, whats new". */
class LevelTalkTest {

    @Test
    void theJobLadderAndTheNextGoalFollowTheLevel() {
        assertEquals("still a beginner", LevelTalk.stage(8));
        assertEquals("1st job at 10", LevelTalk.nextGoal(8));
        assertEquals("late 1st job", LevelTalk.stage(22));
        assertEquals("2nd job at 30", LevelTalk.nextGoal(22));
        assertEquals("late 2nd job", LevelTalk.stage(66));
        assertEquals("3rd job at 70", LevelTalk.nextGoal(66));
        assertEquals("3rd job", LevelTalk.stage(79));
        assertEquals("4th job at 120", LevelTalk.nextGoal(79));
        assertEquals("4th job", LevelTalk.stage(150));
    }

    @Test
    void theLineCarriesLevelGrindAndNewsAndStaysShort() {
        String line = LevelTalk.line(79, "Henesys Hunting Ground I", 4,
                List.of("amoria wedding going on at amoria", "bot guilds started guild wars on each other"));
        assertTrue(line.contains("3rd job") && line.contains("4th job at 120"), line);
        assertTrue(line.contains("you grind at henesys hunting ground i (mobs lv 4)"), line);
        assertTrue(line.contains("New on the server: amoria wedding"), line);
        assertTrue(line.contains("only when it fits"), line);
        assertTrue(line.length() <= LevelTalk.MAX_CHARS, line.length() + ": " + line);
    }

    @Test
    void unknownPartsAreLeftOut() {
        String line = LevelTalk.line(40, null, -1, List.of());
        assertFalse(line.contains("grind at"));
        assertFalse(line.contains("New on the server"));
        assertTrue(line.contains("early 2nd job"));
    }

    @Test
    void aDifferentFeatureEachDayInTurn() {
        Set<String> seen = new HashSet<>();
        long day = 86_400_000L;
        for (int d = 0; d < LevelTalk.NEW_FEATURES.size(); d++) {
            seen.add(LevelTalk.featureOfTheDay(1_000L * day + d * day, ZoneId.of("UTC")));
        }
        assertEquals(LevelTalk.NEW_FEATURES.size(), seen.size());
    }

    @Test
    void noBotNoLine() {
        assertEquals("", LevelTalk.promptLine(null, 0));
    }
}
