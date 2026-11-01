package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                List.of(said(T, "just grinding skeletons rn", TOWN, "hello")), true);
        assertEquals(Check.Status.FAIL, v.status(), ev(v));
        assertTrue(ev(v).contains("grinding claimed in a town (map " + TOWN + ")"), ev(v));
        assertTrue(ev(v).contains("just grinding skeletons rn"), ev(v));
    }

    @Test
    void theSameLineOnAMapWithMonstersIsFine() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(said(T, "just grinding skeletons rn", MOBS, "hello")), true);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
    }

    @Test
    void aTownBotThatSaysWhereItIsPasses() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(said(T, "hey, just chillin in henesys", TOWN, "hello")), true);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
        assertTrue(ev(v).contains("1 replies to players in 24 h, 1 planned, 1 in a town"), ev(v));
    }

    @Test
    void aPlannedLineWithALiteralSlotFails() {
        Check.Verdict v = PlannerReplies.judge(List.of(
                new PlannerReplies.Row(T, "Botty", "9f2a", "try {spot} for a bit", MOBS, "train_spot", "literal", false)),
                true);
        assertEquals(Check.Status.FAIL, v.status(), ev(v));
        assertTrue(ev(v).contains("a {slot} in a planned line (train_spot)"), ev(v));
    }

    @Test
    void anOlderLineNobodyPlannedIsNotJudgedForSlots() {
        // rows written before m02 carry no move: unplanned, so the slot rule cannot say who left it
        Check.Verdict v = PlannerReplies.judge(List.of(
                new PlannerReplies.Row(T, "Botty", "9f2a", "try {spot} for a bit", MOBS, null, "literal", false)), true);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
    }

    @Test
    void twoBotsOpeningChatsOnOnePlayerInsideAnHourFail() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(opened(T, "Botty", "9f2a"), opened(T + 10 * 60_000, "Nibbles", "9f2a")), true);
        assertEquals(Check.Status.FAIL, v.status(), ev(v));
        assertTrue(ev(v).contains("player 9f2a heard from 2 bots in 10 min: Botty, Nibbles"), ev(v));
    }

    @Test
    void twoBotsAnHourApartAreFine() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(opened(T, "Botty", "9f2a"), opened(T + PlannerReplies.HOUR_MS + 60_000, "Nibbles", "9f2a")), true);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
    }

    @Test
    void oneBotGreetingTwoPlayersIsNotTooMuch() {
        Check.Verdict v = PlannerReplies.judge(
                List.of(opened(T, "Botty", "9f2a"), opened(T + 60_000, "Botty", "1b3c")), true);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
    }

    @Test
    void aMapThreadIsNotAReplyToAPlayer() {
        Check.Verdict v = PlannerReplies.judge(List.of(
                new PlannerReplies.Row(T, "Botty", "-", "grinding lorangs is slow kekeke", TOWN, null, "none", false),
                said(T + 1, "hey", TOWN, "hello")), true);
        assertEquals(Check.Status.PASS, v.status(), ev(v));
        assertTrue(ev(v).contains("1 replies to players in 24 h"), ev(v));
    }

    @Test
    void withoutThePlannerSwitchThereIsNothingToJudge() {
        Check.Verdict v = PlannerReplies.judge(List.of(said(T, "just grinding skeletons rn", TOWN, "hello")), false);
        assertEquals(Check.Status.SKIP, v.status(), ev(v));
        assertTrue(ev(v).contains("-Dsolomapling.convo.planner=true"), ev(v));
    }

    @Test
    void aQuietWindowSkipsRatherThanPasses() {
        Check.Verdict v = PlannerReplies.judge(List.of(), true);
        assertEquals(Check.Status.SKIP, v.status(), ev(v));
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
        assertEquals(Check.Status.FAIL, PlannerReplies.judge(rows, true).status(), "the grinding line is in there");
    }

    @Test
    void aWindowWithNoFilesReadsNothing() {
        assertEquals(List.of(), PlannerReplies.load(dir, T - PlannerReplies.WINDOW_MS, T));
    }
}
