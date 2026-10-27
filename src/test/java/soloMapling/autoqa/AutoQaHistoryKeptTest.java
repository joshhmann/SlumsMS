package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auto-QA history (social trace, health, say-do, botwatch, reports) is gzipped after a month, never deleted. */
class AutoQaHistoryKeptTest {

    @TempDir
    Path dir;

    private static LocalDate ago(int days) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(days);
    }

    @Test
    void oldDayFilesAreGzippedNotDeletedAndTheReadersStillFindThem() throws IOException {
        for (String sub : new String[]{"social-trace", "health", "saydo"}) {
            Files.createDirectories(dir.resolve(sub));
            Files.writeString(dir.resolve(sub).resolve(ago(60) + ".jsonl"), "{\"a\":1}\n");
            Files.writeString(dir.resolve(sub).resolve(ago(1) + ".jsonl"), "{\"a\":2}\n");
        }
        Files.createDirectories(dir.resolve("botwatch"));
        Files.writeString(dir.resolve("botwatch").resolve("botwatch-" + ago(60) + "T1030Z.json"), "{}");
        Files.writeString(dir.resolve("report-" + ago(400) + ".json"), "{\"day\":\"old\"}");
        Files.writeString(dir.resolve("report-" + ago(0) + ".json"), "{\"day\":\"new\"}");

        assertEquals(5, AutoQa.compressHistory(dir));

        assertTrue(Files.exists(dir.resolve("social-trace").resolve(ago(60) + ".jsonl.gz")));
        assertFalse(Files.exists(dir.resolve("social-trace").resolve(ago(60) + ".jsonl")));
        assertTrue(Files.exists(dir.resolve("social-trace").resolve(ago(1) + ".jsonl")), "recent stays plain");
        assertTrue(Files.exists(dir.resolve("botwatch").resolve("botwatch-" + ago(60) + "T1030Z.json.gz")));
        assertTrue(Files.exists(dir.resolve("report-" + ago(0) + ".json")));

        // the readers
        assertEquals(1, ChatQuality.jsonl(dir.resolve("saydo").resolve(ago(60) + ".jsonl")).size());
        Map<String, Object> report = AutoQaReport.read(dir.resolve("report-" + ago(400) + ".json"));
        assertEquals("old", report.get("day"));
        assertEquals(0, AutoQa.compressHistory(dir), "nothing more to do");
    }
}
