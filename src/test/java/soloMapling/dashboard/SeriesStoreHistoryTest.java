package soloMapling.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.server.History;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dashboard's series and daily files are kept: after a month they are gzipped, still listed and still read. */
class SeriesStoreHistoryTest {

    @TempDir
    Path dir;

    @SuppressWarnings("unchecked")
    @Test
    void daysOlderThanAMonthAreGzippedKeptListedAndRolledUpWhenMissing() throws IOException {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate old = today.minusDays(200), recent = today.minusDays(5);
        Files.createDirectories(dir.resolve("series"));
        Files.createDirectories(dir.resolve("daily"));
        String series = "{\"day\":\"%s\",\"points\":[{\"t\":1,\"on\":10},{\"t\":2,\"on\":20}]}";
        Files.writeString(dir.resolve("series/" + old + ".json"), series.formatted(old));
        Files.writeString(dir.resolve("series/" + recent + ".json"), series.formatted(recent));
        Files.writeString(dir.resolve("daily/" + recent + ".json"), "{}");

        SeriesStore store = new SeriesStore(dir, ZoneOffset.UTC);
        store.add(Map.of("t", 3, "on", 5), System.currentTimeMillis()); // first add: catch-up + sweep

        assertTrue(Files.exists(dir.resolve("series/" + old + ".json.gz")), "kept, compressed");
        assertFalse(Files.exists(dir.resolve("series/" + old + ".json")));
        assertTrue(Files.exists(dir.resolve("series/" + recent + ".json")));
        // the missing rollup of the old day was built from the compressed series, then gzipped too
        assertEquals(20.0 / 1.0, ((Number) ((Map<String, Object>) Json.parse(
                History.readString(dir.resolve("daily/" + old + ".json")))).get("onlineMax")).doubleValue());
        Map<String, Object> index = (Map<String, Object>) Json.parse(Files.readString(dir.resolve("days.json")));
        assertTrue(((List<String>) index.get("series")).contains(old.toString()), "still listed for the dashboard page");
        assertTrue(((List<String>) index.get("daily")).contains(old.toString()));
    }
}
