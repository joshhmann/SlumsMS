package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 6: the daily scored sample. */
class ConvoDailyScoreTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        System.clearProperty("solomapling.convo.dataDir");
        System.clearProperty("solomapling.convo.transcriptDir");
    }

    private static Map<String, Object> e(String line, String asked, String voice, String source) {
        return new java.util.LinkedHashMap<>(Map.of("line", line, "asked", asked, "voice", voice, "source", source,
                "tier", "llm"));
    }

    @Test
    void scoresEachAxis() {
        assertEquals(1, ConvoDailyScore.naturalness("lol ya ant tunnel is ok", "where do u train", java.util.Set.of()));
        assertTrue(ConvoDailyScore.naturalness("Certainly, however I would suggest Henesys.", null, java.util.Set.of()) < 1);
        assertTrue(ConvoDailyScore.naturalness("where do u train at", "where do u train at", java.util.Set.of()) < 1,
                "an echo");
        assertEquals(0, ConvoDailyScore.neverAi("lol im an ai so idk"));
        assertEquals(1, ConvoDailyScore.neverAi("lol im a bishop"));
        assertEquals(0, ConvoDailyScore.contentGuard("ur mom is ugly"));
        assertEquals(1, ConvoDailyScore.inCharacter("lol ya", "lower"));
        assertEquals(0.5, ConvoDailyScore.inCharacter("LOL YA SURE THING", "lower"));
        assertEquals(1, ConvoDailyScore.inCharacter("Sure, see you there.", "proper"));
    }

    @Test
    void writesTheDaysPageAndLatest() throws Exception {
        System.setProperty("solomapling.convo.dataDir", dir.resolve("data").toString());
        System.setProperty("solomapling.convo.transcriptDir", dir.resolve("logs").toString());
        LocalDate day = LocalDate.of(2026, 10, 21);
        Files.createDirectories(dir.resolve("logs"));
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            rows.add(Json.write(e(i % 10 == 0 ? "As an AI I cannot" : "lol ya line " + i, "hey", i % 2 == 0 ? "lower" : "proper",
                    i % 3 == 0 ? "bank" : "llm")));
        }
        Files.write(dir.resolve("logs").resolve("transcript-2026-10-21.jsonl"), rows);
        Path out = ConvoDailyScore.runFor(day);
        assertTrue(Files.exists(out) && Files.exists(dir.resolve("data/quality/latest.json")));
        Map<?, ?> page = (Map<?, ?>) Json.parse(Files.readString(out));
        assertEquals(80L, ((Number) page.get("lines")).longValue());
        assertEquals((long) ConvoDailyScore.SAMPLE, ((Number) page.get("sampled")).longValue());
        Map<?, ?> avg = (Map<?, ?>) page.get("averages");
        assertTrue(((Number) avg.get("neverAi")).doubleValue() < 1, "the AI line is caught: " + avg);
        assertEquals(10, ((List<?>) page.get("worst")).size());
        assertTrue(((Map<?, ?>) page.get("averagesBySource")).containsKey("bank"));
    }
}
