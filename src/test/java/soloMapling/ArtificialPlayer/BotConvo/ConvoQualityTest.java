package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.ArtificialPlayer.BotStore;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 6: !rate on the last line, bank weights, the tune file. */
class ConvoQualityTest {
    @TempDir
    Path dir;

    @BeforeEach
    void quiet() {
        ConvoQuality.writeFilesForTest(false);
        ConvoQuality.resetForTest();
        ConvoTune.useFileForTest(dir.resolve("tune.json"));
    }

    @AfterEach
    void reset() {
        ConvoQuality.writeFilesForTest(true);
        ConvoQuality.resetForTest();
        ConvoTune.useFileForTest(null);
    }

    private static Character chr(int id, String name) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setName(name);
        return c;
    }

    private static final ConvoPersona LOWER = new ConvoPersona(ConvoPersona.Voice.LOWER, 0, 0, 0, 0, List.of("lol"));

    @Test
    void nothingToRateYet() {
        assertTrue(ConvoQuality.rate(chr(4001, "Josh"), false, "").get(0).startsWith("No bot line"));
    }

    @Test
    void aBadBankLineIsDownWeightedThenBlacklisted() {
        Character bot = chr(BotStore.BOT_ID_BASE + 9, "Kimchi");
        Character josh = chr(4002, "Josh");
        ConvoQuality.said(bot, josh, "lol ya", new ConvoLines.Line("lol ya", 77, "bank"), "SCRIPTED", LOWER, "hey", 900);
        List<String> first = ConvoQuality.rate(josh, false, "boring");
        assertTrue(first.get(1).contains("x0.25"), first.toString());
        assertEquals(0.25, ConvoQuality.weightOf(77), 1e-9);
        ConvoQuality.rate(josh, false, "");
        assertEquals(0, ConvoQuality.weightOf(77), 1e-9);
        assertEquals(1, ConvoQuality.weightOf(78), 1e-9, "other lines untouched");
    }

    @Test
    void aGoodBankLineGainsWeightUpToThree() {
        Character bot = chr(BotStore.BOT_ID_BASE + 9, "Kimchi");
        Character josh = chr(4003, "Josh");
        ConvoQuality.said(bot, josh, "omw", new ConvoLines.Line("omw", 5, "bank"), "SCRIPTED", LOWER, "come", 900);
        for (int i = 0; i < 5; i++) {
            ConvoQuality.rate(josh, true, "");
        }
        assertEquals(3, ConvoQuality.weightOf(5), 1e-9);
    }

    @Test
    void aBadModelLineGoesToTheTuneFile() {
        Character bot = chr(BotStore.BOT_ID_BASE + 9, "Kimchi");
        Character josh = chr(4004, "Josh");
        ConvoQuality.said(bot, josh, "Certainly, I can help.", new ConvoLines.Line("Certainly, I can help.", -1, "llm"),
                "LLM", LOWER, "help", 1200);
        List<String> out = ConvoQuality.rate(josh, false, "assistant voice \"certainly\"");
        assertTrue(out.get(1).contains("don't-say"), out.toString());
        assertEquals("certainly", ConvoTune.violation("certainly not"));
        assertTrue(ConvoQuality.describeLast(josh).get(0).contains("Kimchi: \"Certainly, I can help.\" (llm"));
    }

    @Test
    void aThreadLineOnYourMapCanBeRatedToo() {
        Character bot = chr(BotStore.BOT_ID_BASE + 9, "Kimchi");
        Character josh = chr(4005, "Josh");
        ConvoQuality.threadLine(bot, josh.getMapId(), "lf1 kpq", "llm", "looking for a party");
        assertTrue(ConvoQuality.describeLast(josh).get(0).contains("lf1 kpq"));
        assertTrue(ConvoQuality.rate(josh, true, "").get(1).contains("good example"));
    }

    @Test
    void thePlayersNameNeverReachesTheTranscriptOrTheRatings() throws Exception {
        // review 2026-10-22: recognition and buddy lines carried the name in the text itself
        System.setProperty("solomapling.convo.transcriptDir", dir.resolve("logs").toString());
        System.setProperty("solomapling.convo.dataDir", dir.resolve("data").toString());
        ConvoQuality.writeFilesForTest(true);
        try {
            Character bot = chr(BotStore.BOT_ID_BASE + 9, "Kimchi");
            Character josh = chr(4007, "xJoshx");
            ConvoQuality.said(bot, josh, "oh hey xJoshx, josh u still a page?", new ConvoLines.Line("x", -1, "llm"), "LLM",
                    LOWER, "yo its XJOSHX", 700);
            ConvoQuality.rate(josh, false, "");
            ConvoQuality.flushForTest();
            String transcript = java.nio.file.Files.readString(ConvoQuality.transcriptFile(java.time.LocalDate.now()));
            String ratings = java.nio.file.Files.readString(ConvoQuality.ratingsFile());
            for (String text : List.of(transcript, ratings)) {
                assertTrue(!text.toLowerCase().contains("josh"), text);
                assertTrue(text.contains("{player}"), text);
            }
        } finally {
            System.clearProperty("solomapling.convo.transcriptDir");
            System.clearProperty("solomapling.convo.dataDir");
        }
    }

    @Test
    void anonymiseReplacesWholeNamesOnly() {
        assertEquals("hi {player}, {player} here", ConvoQuality.anonymise("hi Pinky, pinky here", "Pinky"));
        assertEquals("pinkyy is someone else", ConvoQuality.anonymise("pinkyy is someone else", "Pinky"));
        assertEquals("{player} and {player}", ConvoQuality.anonymise("xKaizx and kaiz", "xKaizx"));
    }

    @Test
    void thePlayerIsAHashNeverAName() {
        assertTrue(!ConvoQuality.hash(4006).contains("4006"));
        assertEquals(ConvoQuality.hash(4006), ConvoQuality.hash(4006));
        assertEquals("-", ConvoQuality.hash(0));
    }

    @Test
    void timingIsCountedFromThePlayersLineNotFromThePlan() {
        // t-cv-4 on TEST 2026-10-24: a split reply whose brain had already taken 855 ms logged "+150 ms" for its first
        // part and was failed as "planned faster than 900 ms" although the player waited 1,005 ms for it.
        List<HumanTiming.Part> parts = List.of(new HumanTiming.Part(150, "not much,"), new HumanTiming.Part(4900, "gg"));
        assertEquals(List.of(1_005L, 5_905L), ConvoQuality.cumulativeMs(parts, 855));
        assertEquals(List.of(150L), ConvoQuality.cumulativeMs(List.of(new HumanTiming.Part(150, "hi")), 0));
        assertEquals(List.of(0L), ConvoQuality.cumulativeMs(List.of(new HumanTiming.Part(0, "hi")), -40));
    }

    @Test
    void theDailyScoreRunsAtTheConfiguredHour() {
        java.time.ZoneId zone = java.time.ZoneId.of("America/Chicago");
        long at3 = java.time.LocalDateTime.of(2026, 10, 22, 3, 0).atZone(zone).toInstant().toEpochMilli();
        assertEquals(90 * 60_000L, ConvoQuality.msUntilNext(4, 30, at3, zone));
        long at5 = java.time.LocalDateTime.of(2026, 10, 22, 5, 0).atZone(zone).toInstant().toEpochMilli();
        assertEquals((23 * 60 + 30) * 60_000L, ConvoQuality.msUntilNext(4, 30, at5, zone));
    }
}
