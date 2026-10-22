package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner report 2026-10-22: one public chat log per map, the transcript, and who a nameless line answers. */
class MapChatLogTest {

    static final int MAP = 100000000;
    static final int KIKI = 20_001, MOCHI = 20_002, JOSH = 7;

    @AfterEach
    void reset() {
        MapChatLog.resetForTest();
    }

    @Test
    void everyPublicLineIsKeptWithItsSpeakerOldestFirstUpToEightAndThreeMinutes() {
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            MapChatLog.record(1, MAP, KIKI, "Kiki", "line " + i, true, t + i * 1000);
        }
        List<MapChatLog.Line> lines = MapChatLog.recent(1, MAP, t + 10_000);
        assertEquals(8, lines.size());
        assertEquals("line 2", lines.get(0).text());
        assertEquals("line 9", lines.get(7).text());
        assertTrue(MapChatLog.recent(1, MAP, t + 9_000 + MapChatLog.KEEP_MS + 1).isEmpty(), "three minutes");
        assertTrue(MapChatLog.recent(2, MAP, t + 10_000).isEmpty(), "channels are separate");
    }

    @Test
    void aBubbleTypedLetterByLetterIsOneLine() {
        MapChatLog.record(1, MAP, KIKI, "Kiki", "brb pots", true, 0);
        MapChatLog.record(1, MAP, KIKI, "Kiki", "brb pots", true, 500);
        MapChatLog.record(1, MAP, JOSH, "Josh", "brb pots", false, 600);
        assertEquals(2, MapChatLog.recent(1, MAP, 1000).size());
    }

    @Test
    void theTranscriptNamesEverySpeakerAndMarksTheBotsOwnLines() {
        MapChatLog.record(1, MAP, KIKI, "Kiki", "anyone from the east coast?", true, 0);
        MapChatLog.record(1, MAP, MOCHI, "Mochi", "pigs drop nothing lol", true, 1000);
        MapChatLog.record(1, MAP, JOSH, "Josh", "im from the east", false, 2000);
        assertEquals("Kiki (you): anyone from the east coast?\nMochi: pigs drop nothing lol\nJosh: im from the east",
                MapChatLog.transcript(MapChatLog.recent(1, MAP, 3000), KIKI));
    }

    @Test
    void aNamelessAnswerGoesToTheBotWhoseLineItAnswers() {
        long t = 0;
        MapChatLog.record(1, MAP, KIKI, "Kiki", "anyone from the east coast?", true, t);
        MapChatLog.record(1, MAP, MOCHI, "Mochi", "brb pots", true, t + 5_000);
        MapChatLog.record(1, MAP, JOSH, "Josh", "im from the east", false, t + 8_000);
        List<MapChatLog.Line> lines = MapChatLog.recent(1, MAP, t + 9_000);
        assertEquals(KIKI, MapChatLog.answeredBy(lines, JOSH, "im from the east", t + 9_000).speakerId(),
                "the word it shares with the question wins over a newer line");
        assertEquals(KIKI, MapChatLog.answeredBy(lines, JOSH, "me!", t + 9_000).speakerId(),
                "no shared word: the newest question");
        // 50 s later the question still stands, the plain line (30 s) does not
        assertEquals(KIKI, MapChatLog.answeredBy(lines, JOSH, "ya", t + 50_000).speakerId());
        assertNull(MapChatLog.answeredBy(lines, JOSH, "ya", t + 61_000), "a minute on, nothing is being answered");
    }

    @Test
    void anyRecentLineCanBeAnsweredAndPlayersAreNeverTheAddressee() {
        MapChatLog.record(1, MAP, MOCHI, "Mochi", "finally hit 30", true, 0);
        MapChatLog.record(1, MAP, 8, "Ann", "gz!", false, 1_000);
        MapChatLog.record(1, MAP, JOSH, "Josh", "nice gz", false, 2_000);
        List<MapChatLog.Line> lines = MapChatLog.recent(1, MAP, 3_000);
        assertEquals(MOCHI, MapChatLog.answeredBy(lines, JOSH, "nice gz", 3_000).speakerId());
        assertNull(MapChatLog.answeredBy(lines, JOSH, "nice gz", 40_000), "not a question: 30 s only");
    }

    @Test
    void theBotsOwnLineBeforeThePlayersAnswer() {
        MapChatLog.record(1, MAP, KIKI, "Kiki", "anyone from the east coast?", true, 0);
        MapChatLog.record(1, MAP, MOCHI, "Mochi", "lol", true, 1_000);
        MapChatLog.record(1, MAP, JOSH, "Josh", "im from the east", false, 2_000);
        List<MapChatLog.Line> lines = MapChatLog.recent(1, MAP, 3_000);
        assertEquals("anyone from the east coast?", MapChatLog.ownLineBefore(lines, KIKI, JOSH, 60_000, 3_000).text());
        assertNull(MapChatLog.ownLineBefore(lines, KIKI, JOSH, 60_000, 70_000), "too long ago");
        assertNull(MapChatLog.ownLineBefore(lines, 20_009, JOSH, 60_000, 3_000), "another bot said nothing");
    }

    @Test
    void questionsAreRecognised() {
        assertTrue(MapChatLog.isQuestion("anyone from the east coast?"));
        assertTrue(MapChatLog.isQuestion("wanna pt"));
        assertTrue(MapChatLog.isQuestion("who else is grinding here"));
        assertFalse(MapChatLog.isQuestion("pigs drop nothing lol"));
    }
}
