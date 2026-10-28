package server.reports;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarnLogRingTest {

    @Test
    void boundedToItsCapacity() {
        WarnLogRing r = new WarnLogRing(5);
        for (int i = 0; i < 50; i++) {
            r.add(new WarnLogRing.Line(i, "WARN", "line " + i + " Bob"));
        }
        assertEquals(5, r.size());
        assertEquals(List.of("line 45 Bob", "line 49 Bob"),
                List.of(r.snapshot().getFirst().text(), r.snapshot().getLast().text()));
    }

    @Test
    void filtersByNameIdTimeAndPrivacy() {
        WarnLogRing r = new WarnLogRing(100);
        r.add(new WarnLogRing.Line(1_000, "WARN", "X - too old about Bob"));
        r.add(new WarnLogRing.Line(5_000, "WARN", "X - NPC 9000 for Bob is not coded"));
        r.add(new WarnLogRing.Line(5_001, "ERROR", "X - Bobby did something"));           // another name
        r.add(new WarnLogRing.Line(5_002, "WARN", "X - stuck warp for chr 123"));          // by id
        r.add(new WarnLogRing.Line(5_003, "WARN", "X - item 123 dropped"));                // a bare number is not the id
        r.add(new WarnLogRing.Line(5_004, "WARN", "X - chr 1234 is someone else"));
        r.add(new WarnLogRing.Line(5_005, "WARN", "X - wrong password for Bob"));          // private
        r.add(new WarnLogRing.Line(5_006, "WARN", "X - Bob connected from 10.0.0.2"));     // private
        r.add(new WarnLogRing.Line(5_007, "ERROR", "X - quest failed for BOB, id=123"));
        List<String> got = r.linesFor("Bob", 123, 2_000, 50);
        assertEquals(3, got.size(), got.toString());
        assertTrue(got.get(0).endsWith("WARN X - NPC 9000 for Bob is not coded"), got.get(0));
        assertTrue(got.get(0).startsWith("1970-01-01T00:00:05.000Z"), got.get(0));
        assertTrue(got.get(1).contains("chr 123"));
        assertTrue(got.get(2).contains("quest failed"));
        assertEquals(1, r.linesFor("Bob", 123, 2_000, 1).size(), "max keeps the newest");
        assertTrue(r.linesFor("Bob", 123, 2_000, 1).getFirst().contains("quest failed"));
    }

    @Test
    void longLinesAreCut() {
        String cut = WarnLogRing.cut("a\nb" + "x".repeat(1000));
        assertEquals(WarnLogRing.MAX_LINE + 3, cut.length());
        assertFalse(cut.contains("\n"));
    }

    @Test
    void installAttachesToTheRootLoggerOnce() {
        WarnLogRing.install();
        WarnLogRing.install(); // twice is fine
        var ctx = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        assertTrue(ctx.getConfiguration().getRootLogger().getAppenders().containsKey(WarnLogRing.NAME));
    }

    @Test
    void appendKeepsWarnAndErrorWithTheExceptionButNoStack() {
        WarnLogRing r = new WarnLogRing(10);
        r.append(event(org.apache.logging.log4j.Level.INFO, "info is not kept for Zed", null));
        r.append(event(org.apache.logging.log4j.Level.WARN, "ring check for chr 4242 Zed", null));
        r.append(event(org.apache.logging.log4j.Level.ERROR, "boom for Zed", new IllegalStateException("bad state")));
        List<String> got = r.linesFor("Zed", 4242, 0, 10);
        assertEquals(2, got.size(), got.toString());
        assertTrue(got.get(0).contains("WARN WarnLogRingTest - ring check for chr 4242 Zed"), got.get(0));
        assertTrue(got.get(1).endsWith("ERROR WarnLogRingTest - boom for Zed [java.lang.IllegalStateException: bad state]"), got.get(1));
    }

    private static org.apache.logging.log4j.core.LogEvent event(org.apache.logging.log4j.Level level, String msg, Throwable t) {
        return org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder().setLevel(level)
                .setLoggerName("server.reports.WarnLogRingTest").setTimeMillis(10_000)
                .setMessage(new org.apache.logging.log4j.message.SimpleMessage(msg)).setThrown(t).build();
    }
}
