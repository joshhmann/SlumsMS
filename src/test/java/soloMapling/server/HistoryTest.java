package soloMapling.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** History is kept (owner 2026-10-27): nothing is deleted by default, old day files are gzipped and still readable. */
class HistoryTest {

    private static final Pattern DAYS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.jsonl");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @TempDir
    Path dir;

    private void day(int ago, String text) throws IOException {
        Files.writeString(dir.resolve(TODAY.minusDays(ago) + ".jsonl"), text);
    }

    @Test
    void defaultsKeepForeverAndGzipAfterThirtyDays() {
        assertEquals(0, History.keepDays("nothingSetsThis"));
        assertEquals(30, History.gzipDays("nothingSetsThis"));
    }

    @Test
    void aSweepWithTheDefaultKnobsDeletesNothingAndGzipsOnlyOldDays() throws IOException {
        day(0, "today\n");
        day(30, "edge\n");
        day(31, "old\n");
        day(900, "ancient\n");
        Files.writeString(dir.resolve("notes.txt"), "not a day file");

        History.Result r = History.sweep(dir, DAYS, TODAY, History.gzipDays("x"), History.keepDays("x"));

        assertEquals(0, r.deleted());
        assertEquals(2, r.gzipped());
        assertTrue(Files.exists(dir.resolve(TODAY + ".jsonl")), "today stays plain");
        assertTrue(Files.exists(dir.resolve(TODAY.minusDays(30) + ".jsonl")), "30 days old is not yet over the line");
        assertTrue(Files.exists(dir.resolve(TODAY.minusDays(31) + ".jsonl.gz")));
        assertFalse(Files.exists(dir.resolve(TODAY.minusDays(31) + ".jsonl")));
        assertTrue(Files.exists(dir.resolve(TODAY.minusDays(900) + ".jsonl.gz")));
        assertTrue(Files.exists(dir.resolve("notes.txt")));
        assertEquals(0, History.sweep(dir, DAYS, TODAY, 30, 0).gzipped(), "a second pass has nothing left to do");
    }

    @Test
    void gzippedDaysReadLikePlainOnes() throws IOException {
        day(40, "a\nb\n");
        History.sweep(dir, DAYS, TODAY, 30, 0);
        Path asked = dir.resolve(TODAY.minusDays(40) + ".jsonl");
        assertTrue(History.exists(asked));
        assertEquals(List.of("a", "b"), History.readLines(asked));
        assertEquals("a\nb\n", History.readString(asked));
        assertThrows(IOException.class, () -> History.readString(dir.resolve("2020-01-01.jsonl")));
        assertFalse(History.exists(dir.resolve("2020-01-01.jsonl")));
    }

    @Test
    void aKeepKnobDeletesAfterThatManyDaysPlainOrGzipped() throws IOException {
        day(10, "recent\n");
        day(50, "old\n");
        day(500, "older\n");
        History.sweep(dir, DAYS, TODAY, 30, 0); // the 50 and 500 day files become .gz

        History.Result r = History.sweep(dir, DAYS, TODAY, 30, 100);

        assertEquals(1, r.deleted());
        assertTrue(Files.exists(dir.resolve(TODAY.minusDays(10) + ".jsonl")));
        assertTrue(Files.exists(dir.resolve(TODAY.minusDays(50) + ".jsonl.gz")));
        assertFalse(Files.exists(dir.resolve(TODAY.minusDays(500) + ".jsonl.gz")));
    }

    @Test
    void zeroGzipDaysTurnsCompressionOff() throws IOException {
        day(400, "old\n");
        assertEquals(0, History.sweep(dir, DAYS, TODAY, 0, 0).gzipped());
        assertTrue(Files.exists(dir.resolve(TODAY.minusDays(400) + ".jsonl")));
    }
}
