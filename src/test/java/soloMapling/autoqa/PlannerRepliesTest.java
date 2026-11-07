package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * t-pl-1's judgement on a made-up transcript: invented bots, invented player hashes, no server. Each rule of
 * {@link PlannerReplies#judge} fails on its own row and passes when the row is right.
 */
class PlannerRepliesTest {

    @TempDir
    Path dir;

    private static final int TOWN = 100000000;   // Henesys: no monsters
    private static final int MOBS = 104040000;   // a map with monsters
    private static final long T = 1_790_000_000_000L;
    /** The server started a minute before the rows below, so every row in them is this build's. */
    private static final long BOOT = T - 60_000;

    /** The verdict's first evidence line: how many replies, planned, towns. */
    private static String head(int replies, int planned, int town) {
        return replies + " replies to players since this server started " + java.time.Instant.ofEpochMilli(BOOT)
                + ", " + planned + " planned, " + town + " in a town";
    }

    /** A row the previous build wrote, before {@code boot}. */
    private static String skipped(int rows) {
        return rows + " transcript row(s) from before this server started were skipped";
    }

    private static PlannerReplies.Row said(long at, String line, int map, String move) {
        return new PlannerReplies.Row(at, "Botty", "9f2a", line, map, move, "none", false);
    }

    private static PlannerReplies.Row opened(long at, String bot, String player) {
        return new PlannerReplies.Row(at, bot, player, "hey", TOWN, "hello", "none", true);
    }

    private static String ev(Check.Verdict v) {
        return String.join(" | ", v.evidence());
    }

    @Test
    void aTownReplyThatClaimsGrindingFails() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(said(T, "just grinding skeletons rn", TOWN, "hello")), true, BOOT);
        assertEquals(Check.Status.FAIL, v.status(), ev(v));
        assertTrue(ev(v).contains("grinding claimed in a town (map " + TOWN + ")"), ev(v));
        assertTrue(ev(v).contains("just grinding skeletons rn"), ev(v));
    }

    @Test
    void theSameLineOnAMapWithMonstersIsFine() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(said(T, "just grinding skeletons rn", MOBS, "hello")), true, BOOT);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
    }

    @Test
    void aTownBotThatSaysWhereItIsPasses() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(said(T, "hey, just chillin in henesys", TOWN, "hello")), true, BOOT);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
        assertTrue(ev(v).contains(head(1, 1, 1)), ev(v));
    }

    @Test
    void aPlannedLineWithALiteralSlotFails() {
        Check.Verdict v = PlannerReplies.judge(List.of(
                new PlannerReplies.Row(T, "Botty", "9f2a", "try {spot} for a bit", MOBS, "train_spot", "literal", false)),
                true, BOOT);
        assertEquals(Check.Status.FAIL, v.status(), ev(v));
        assertTrue(ev(v).contains("a {slot} in a planned line (train_spot)"), ev(v));
    }

    @Test
    void anOlderLineNobodyPlannedIsNotJudgedForSlots() {
        // rows written before m02 carry no move: unplanned, so the slot rule cannot say who left it
        Check.Verdict v = PlannerReplies.judge(List.of(
                new PlannerReplies.Row(T, "Botty", "9f2a", "try {spot} for a bit", MOBS, null, "literal", false)), true, BOOT);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
    }

    @Test
    void twoBotsOpeningChatsOnOnePlayerInsideAnHourFail() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(opened(T, "Botty", "9f2a"), opened(T + 10 * 60_000, "Nibbles", "9f2a")), true, BOOT);
        assertEquals(Check.Status.FAIL, v.status(), ev(v));
        assertTrue(ev(v).contains("player 9f2a heard 2 bot-started chats in 10 min from: Botty, Nibbles"), ev(v));
    }

    @Test
    void twoBotsAnHourApartAreFine() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(opened(T, "Botty", "9f2a"), opened(T + PlannerReplies.HOUR_MS + 60_000, "Nibbles", "9f2a")), true, BOOT);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
    }

    @Test
    void oneBotGreetingTwoPlayersIsNotTooMuch() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(opened(T, "Botty", "9f2a"), opened(T + 60_000, "Botty", "1b3c")), true, BOOT);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
    }

    @Test
    void aMapThreadIsNotAReplyToAPlayer() {
        Check.Verdict v = PlannerReplies.judge(List.of(
                new PlannerReplies.Row(T, "Botty", "-", "grinding lorangs is slow kekeke", TOWN, null, "none", false),
                said(T + 1, "hey", TOWN, "hello")), true, BOOT);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
        assertTrue(ev(v).contains(head(1, 1, 1)), ev(v));
    }

    @Test
    void withoutThePlannerSwitchThereIsNothingToJudge() {
        Check.Verdict v = PlannerReplies.judge(List.of(said(T, "just grinding skeletons rn", TOWN, "hello")), false,
                BOOT);
        assertEquals(Check.Status.SKIP, v.status(), ev(v));
        assertTrue(ev(v).contains("-Dsolomapling.convo.planner=true"), ev(v));
    }

    @Test
    void aQuietWindowSkipsRatherThanPasses() {
        Check.Verdict v = PlannerReplies.judge(List.of(), true, BOOT);
        assertEquals(Check.Status.SKIP, v.status(), ev(v));
        assertTrue(ev(v).contains("no bot reply to a player since this server started"), ev(v));
    }

    @Test
    void readsTheTranscriptFileTheServerWrites() throws Exception {
        // the shape ConvoQuality writes: one JSON object a line; a torn last line must not lose the rest
        String jsonl = "{\"at\":" + T + ",\"bot\":\"Botty\",\"player\":\"9f2a\",\"asked\":\"hey\",\"line\":\"just grinding"
                + " skeletons rn\",\"slots\":\"none\",\"tier\":\"LLM\",\"source\":\"#7\",\"voice\":\"lower\",\"ms\":900,"
                + "\"map\":" + TOWN + ",\"move\":\"hello\"}\n"
                + "{\"at\":" + (T + 1000) + ",\"bot\":\"Nibbles\",\"player\":\"1b3c\",\"line\":\"hey\",\"slots\":\"none\","
                + "\"map\":" + TOWN + ",\"move\":\"hello\",\"open\":true}\n"
                + "{\"at\":" + (T + 2000) + ",\"bot\":\"torn";
        java.time.LocalDate day = java.time.Instant.ofEpochMilli(T).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        Path file = dir.resolve("transcript-" + day + ".jsonl");
        Files.writeString(file, jsonl);
        List<PlannerReplies.Row> rows = PlannerReplies.load(dir, T - 1, T + 60_000);
        assertEquals(2, rows.size(), rows.toString());
        assertEquals("Botty", rows.get(0).bot());
        assertEquals("hello", rows.get(0).move());
        assertEquals(TOWN, rows.get(0).map());
        assertTrue(rows.get(1).open(), "the open flag is what the hourly rule reads");
        assertEquals(Check.Status.FAIL, PlannerReplies.judge(rows, true, BOOT).status(), "the grinding line is in there");
    }

    @Test
    void aRowFromBeforeTheBootIsIgnored() {
        // the TEST run of 2026-11-01: one grinding-in-town reply the previous build worded, one from this build
        long boot = T + 60_000;
        Check.Verdict v = PlannerReplies.judge(List.of(
                said(T, "im at rina s house grinding shrooms rn, u can come by if u want", TOWN, null),
                said(boot + 120_000, "hey, just chillin in henesys", TOWN, "hello")), true, boot);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
        assertTrue(ev(v).contains(skipped(1)), ev(v));
        assertTrue(ev(v).contains("1 replies to players since this server started"), ev(v));
    }

    @Test
    void aBadRowSinceTheBootStillFails() {
        long boot = T - 1;
        Check.Verdict v = PlannerReplies.judge(List.of(
                said(T, "im at rina s house grinding shrooms rn", TOWN, null),
                said(T + 60_000, "just grinding skeletons rn", MOBS, null)), true, boot);
        assertEquals(Check.Status.FAIL, v.status(), ev(v));
        assertTrue(ev(v).contains("grinding claimed in a town (map " + TOWN + ")"), ev(v));
        assertTrue(ev(v).contains("2 replies to players since this server started"), ev(v));
        assertFalse(ev(v).contains("were skipped"), ev(v));
    }

    @Test
    void nothingSinceTheBootSkips() {
        long boot = T + PlannerReplies.HOUR_MS;
        Check.Verdict v = PlannerReplies.judge(List.of(
                said(T, "im at rina s house grinding shrooms rn", TOWN, null),
                new PlannerReplies.Row(T + 1000, "Botty", "-", "kekeke", TOWN, null, "none", false)), true, boot);
        assertEquals(Check.Status.SKIP, v.status(), ev(v));
        assertTrue(ev(v).contains("no bot reply to a player since this server started"), ev(v));
        assertTrue(ev(v).contains(skipped(2)), ev(v));
    }

    @Test
    void withoutABootTimeEveryRowInTheWindowCounts() {
        // an unknown boot (the metric unreadable) must not silently drop rows
        Check.Verdict v = PlannerReplies.judge(List.of(said(T, "just grinding skeletons rn", TOWN, "hello")), true, 0);
        assertEquals(Check.Status.FAIL, v.status(), ev(v));
        assertTrue(ev(v).contains("1 replies to players in the last 24 h"), ev(v));
    }

    @Test
    void aWindowWithNoFilesReadsNothing() {
        assertEquals(List.of(), PlannerReplies.load(dir, T - PlannerReplies.WINDOW_MS, T));
    }
}
