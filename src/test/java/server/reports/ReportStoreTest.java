package server.reports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportStoreTest {
    @TempDir Path dir;

    static class MutableClock extends Clock {
        long now = 1_700_000_000_000L;
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    static ReportStore.Context ctx(String name) {
        return new ReportStore.Context(name, 7, 1, 2, 100000000, "Henesys", 10, -20, null);
    }

    ReportStore store(MutableClock clk) { return new ReportStore(dir.resolve("r/reports.jsonl"), clk); }

    ReportStore.Result submit(ReportStore s, String type, String text, String who) throws Exception {
        return s.submit(type, text, ctx(who)).get();
    }

    @Test
    void idsIncreaseAndSurviveRestart() throws Exception {
        MutableClock clk = new MutableClock();
        ReportStore a = store(clk);
        assertEquals(1, submit(a, "bug", "first problem", "A").id());
        assertEquals(2, submit(a, "feature", "second thing", "A").id());
        ReportStore b = store(clk); // "restart"
        assertEquals(3, submit(b, "bug", "third problem", "B").id());
        assertEquals(3, Files.readAllLines(dir.resolve("r/reports.jsonl")).size());
    }

    @Test
    void lengthChecks() throws Exception {
        ReportStore s = store(new MutableClock());
        assertEquals(ReportStore.Status.TOO_SHORT, submit(s, "bug", "abcd", "A").status());
        assertEquals(ReportStore.Status.TOO_SHORT, submit(s, "bug", "   ab  ", "A").status());
        assertEquals(ReportStore.Status.TOO_LONG, submit(s, "bug", "x".repeat(2001), "A").status());
        assertEquals(ReportStore.Status.OK, submit(s, "bug", "x".repeat(2000), "A").status());
        assertEquals(ReportStore.Status.OK, submit(s, "bug", "abcde", "A").status());
    }

    @Test
    void perCharacterHourAndDayLimits() throws Exception {
        MutableClock clk = new MutableClock();
        ReportStore s = store(clk);
        for (int i = 0; i < 5; i++) assertEquals(ReportStore.Status.OK, submit(s, "bug", "problem " + i, "A").status());
        assertEquals(ReportStore.Status.RATE_LIMITED, submit(s, "bug", "problem x", "a").status());
        assertEquals(ReportStore.Status.OK, submit(s, "bug", "other person", "B").status());
        // 4 hourly batches fill the day (20), then the day cap holds
        for (int batch = 1; batch < 4; batch++) {
            clk.now += 3_700_000L;
            for (int i = 0; i < 5; i++) assertEquals(ReportStore.Status.OK, submit(s, "bug", "problem " + i, "A").status());
        }
        clk.now += 3_700_000L;
        assertEquals(ReportStore.Status.RATE_LIMITED, submit(s, "bug", "problem y", "A").status());
        clk.now += 24 * 3_600_000L;
        assertEquals(ReportStore.Status.OK, submit(s, "bug", "problem z", "A").status());
    }

    @Test
    void globalLimit() throws Exception {
        ReportStore s = store(new MutableClock());
        for (int i = 0; i < ReportStore.GLOBAL_HOUR; i++) {
            assertEquals(ReportStore.Status.OK, submit(s, "bug", "problem", "P" + i).status());
        }
        assertEquals(ReportStore.Status.RATE_LIMITED, submit(s, "bug", "problem", "Other").status());
    }

    @Test
    void rejectedReportsDoNotUseAnId() throws Exception {
        ReportStore s = store(new MutableClock());
        submit(s, "bug", "ab", "A");
        assertEquals(1, submit(s, "bug", "long enough", "A").id());
    }

    @Test
    void jsonEscapingAndFields() throws Exception {
        ReportStore s = store(new MutableClock());
        String text = "He said \"hi\"\nline2\ttab \\ back \u00e9\u65e5\u672c \ud83d\ude00 \u0001";
        submit(s, "feature", text, "Na\"me");
        String line = Files.readAllLines(dir.resolve("r/reports.jsonl")).get(0);
        assertFalse(line.contains("\n"));
        assertTrue(line.contains("\"details\":\"He said \\\"hi\\\"\\nline2\\ttab \\\\ back \u00e9\u65e5\u672c \ud83d\ude00 \\u0001\""), line);
        assertTrue(line.contains("\"character\":\"Na\\\"me\""));
        assertTrue(line.contains("\"where\":\"Henesys (100000000) ch2\""));
        assertTrue(line.contains("\"source\":\"game\""));
        assertTrue(line.contains("\"type\":\"feature\""));
        assertTrue(line.contains("\"accountId\":7"));
        assertTrue(line.contains("\"time\":\"2023-11-14T22:13:20Z\""));
        assertTrue(line.contains("\"title\":\"He said \\\"hi\\\" line2 tab"), "title collapses whitespace: " + line);
    }

    @Test
    void titleIsTruncatedWithoutSplittingSurrogates() {
        String text = "\ud83d\ude00".repeat(300);
        String json = ReportStore.toJson(1, "bug", text, ctx("A"), Instant.EPOCH);
        String title = json.substring(json.indexOf("\"title\":\"") + 9, json.indexOf("\",\"details\""));
        assertEquals(140, title.codePointCount(0, title.length()));
    }

    @Test
    void writeFailureIsReported() throws Exception {
        Path blocker = dir.resolve("file");
        Files.writeString(blocker, "x");
        ReportStore s = new ReportStore(blocker.resolve("sub/reports.jsonl"), new MutableClock());
        assertEquals(ReportStore.Status.WRITE_FAILED, s.submit("bug", "problem here", ctx("A")).get().status());
    }
}
