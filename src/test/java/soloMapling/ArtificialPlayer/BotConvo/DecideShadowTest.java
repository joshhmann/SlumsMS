package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.server.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 chat-model shadow run: never blocks, drops when full, one JSONL row per line, and the scorecard math. */
class DecideShadowTest {

    /** A line the rules leave open, so the current model is asked (and the shadow offered it). */
    static final String OPEN_LINE = "anyone know when the event starts";

    static String answers(String intent, String tone, String con) {
        return "{\"answers\":{\"convo_intent\":{\"choice\":\"" + intent + "\",\"confidence\":0.9},"
                + "\"convo_tone\":{\"choice\":\"" + tone + "\",\"confidence\":0.9},"
                + "\"con\":{\"choice\":\"" + con + "\",\"confidence\":0.9}}}";
    }

    @AfterEach
    void off() {
        DecideShadow.install(null);
    }

    @Test
    void offeringNeverBlocksTheLiveReadNorChangesIt() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        ChatDecide.Transport hangs = (body, ms) -> {
            never.await(); // a shadow model that never answers
            return answers("party", "neutral", "normal");
        };
        List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<>());
        DecideShadow shadow = new DecideShadow(List.of(new DecideShadow.Model("slow", "decide", hangs)), 4, 60_000,
                400, "test", "gliner", rows::add).start();
        ChatDecide live = new ChatDecide((body, ms) -> answers("talk", "neutral", "normal"), 400, 1000);
        ConvoSense.Read without = ConvoSense.read(OPEN_LINE, live, "Alice");
        DecideShadow.install(shadow);
        long started = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            ConvoSense.Read with = ConvoSense.read(OPEN_LINE, live, "Alice");
            assertEquals(without.describe(), with.describe(), "the shadow never changes the live read");
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(ms < 2000, "50 reads with a hung shadow model took " + ms + " ms");
        Map<String, Object> c = shadow.counters();
        assertEquals(50L, c.get("offered"), "every judged line is offered: " + c);
        assertTrue(((Number) c.get("dropped")).longValue() >= 40, "a full queue drops: " + c);
        assertTrue(rows.isEmpty(), "nothing written while the model hangs");
        never.countDown();
    }

    @Test
    void aFullQueueDropsTheLineInsteadOfWaiting() {
        DecideShadow shadow = new DecideShadow(List.of(), 2, 1000, 400, "test", "gliner", r -> { });
        ConvoSense.Read read = ConvoSense.rules(OPEN_LINE);
        for (int i = 0; i < 5; i++) {
            shadow.submit(new DecideShadow.Job(0, OPEN_LINE, null, ChatTriage.rules(OPEN_LINE), Map.of(), 1, read,
                    0.4, 0.8, 0.4));
        }
        Map<String, Object> c = shadow.counters();
        assertEquals(5L, c.get("offered"));
        assertEquals(3L, c.get("dropped"));
        assertEquals(2, c.get("queued"));
    }

    @Test
    void rulesOnlyLinesAndAnOffShadowCostNothing() {
        DecideShadow.install(null);
        ChatDecide live = new ChatDecide((body, ms) -> answers("talk", "neutral", "normal"), 400, 1000);
        // greeting: rules alone, no model call, nothing offered (and no shadow installed: a plain no-op)
        assertEquals(ConvoSense.Source.RULE, ConvoSense.read("wanna party?", live, "Bob").intentSource());
        assertNull(DecideShadow.get());
    }

    @Test
    void oneJsonlRowPerLineWithEveryModelsReadAndAgreement(@TempDir Path dir) throws Exception {
        ChatDecide.Transport same = (body, ms) -> answers("talk", "neutral", "normal");
        ChatDecide.Transport differs = (body, ms) -> {
            assertTrue(body.contains("\"model\":\"OneJev1.6-0.8B\""), "the wire model id is the shadow's: " + body);
            return answers("guide", "neutral", "normal");
        };
        ChatDecide.Transport broken = (body, ms) -> {
            throw new IllegalStateException("HTTP 503");
        };
        ChatDecide.Transport slow = (body, ms) -> {
            Thread.sleep(450);
            return answers("guide", "neutral", "normal");
        };
        DecideShadow shadow = new DecideShadow(List.of(new DecideShadow.Model("gliclass", "decide", same),
                new DecideShadow.Model("onejev", "OneJev1.6-0.8B", differs),
                new DecideShadow.Model("down", "decide", broken),
                new DecideShadow.Model("slow", "decide", slow)), 8, 2000, 400, "live", "gliner",
                row -> DecideShadow.append(dir, row));
        Map<String, ChatDecide.Answer> current = ChatDecide.parse(answers("talk", "neutral", "normal"), ConvoSense.ASKED)
                .orElseThrow();
        ChatTriage.Result byRule = ChatTriage.rules(OPEN_LINE);
        ConvoSense.Read read = ConvoSense.combine(OPEN_LINE, byRule, current, 0.4, 0.8, 0.4);
        long at = 1_793_000_000_000L;
        DecideShadow.append(dir, shadow.judge(new DecideShadow.Job(at, OPEN_LINE, "Alice", byRule, current, 87, read,
                0.4, 0.8, 0.4)));

        Path file = dir.resolve("2026-10-26.jsonl"); // the UTC day of 'at'
        assertEquals(DecideShadow.file(dir.getParent(), at).getFileName(), file.getFileName());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) Json.parse(lines.get(0));
        assertEquals(List.of("at", "time", "src", "speaker", "text", "current", "shadows", "agree"),
                new ArrayList<>(row.keySet()));
        assertEquals(OPEN_LINE, row.get("text"));
        assertEquals("live", row.get("src"));
        String speaker = (String) row.get("speaker");
        assertEquals(10, speaker.length());
        assertFalse(speaker.toLowerCase().contains("alice"), "the speaker is a hash");
        assertEquals(DecideShadow.speakerHash("alice"), speaker, "stable and case-blind");

        Map<?, ?> cur = (Map<?, ?>) row.get("current");
        assertEquals("gliner", cur.get("model"));
        assertEquals(true, cur.get("ok"));
        assertEquals(87L, ((Number) cur.get("ms")).longValue());
        assertEquals("talk", cur.get("intent"));
        assertEquals("talk@0.90", ((Map<?, ?>) cur.get("raw")).get("convo_intent"));

        Map<?, ?> shadows = (Map<?, ?>) row.get("shadows");
        assertEquals(List.of("gliclass", "onejev", "down", "slow"), new ArrayList<>(shadows.keySet()));
        Map<?, ?> g = (Map<?, ?>) shadows.get("gliclass");
        for (String k : List.of("intent", "intentSource", "tone", "con", "raw", "ok", "ms", "late", "timeout", "agreeIntent",
                "agree")) {
            assertTrue(g.containsKey(k), "shadow field " + k + ": " + g);
        }
        assertEquals(true, g.get("agree"));
        Map<?, ?> o = (Map<?, ?>) shadows.get("onejev");
        assertEquals("guide", o.get("intent"));
        assertEquals("decide", o.get("intentSource"));
        assertEquals(false, o.get("agree"));
        assertEquals(false, o.get("agreeIntent"));
        Map<?, ?> d = (Map<?, ?>) shadows.get("down");
        assertEquals(false, d.get("ok"));
        assertTrue(String.valueOf(d.get("error")).contains("HTTP 503"));
        assertEquals("talk", d.get("intent"), "a failed shadow scores as rules only");
        Map<?, ?> s = (Map<?, ?>) shadows.get("slow");
        assertEquals(true, s.get("ok"));
        assertEquals(true, s.get("late"));
        assertEquals("guide@0.90", ((Map<?, ?>) s.get("raw")).get("convo_intent"), "a late raw answer is still logged");
        assertEquals("talk", s.get("intent"), "but scored as production would: rules only past the deadline");
        assertEquals(Map.of("gliclass", true, "onejev", false, "down", true, "slow", true), row.get("agree"));
    }

    @Test
    void theWorkerWritesQueuedLines() throws Exception {
        List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<>());
        DecideShadow shadow = new DecideShadow(List.of(new DecideShadow.Model("gliclass", "decide",
                (body, ms) -> answers("talk", "neutral", "normal"))), 8, 1000, 400, "replay", "gliner", rows::add).start();
        DecideShadow.install(shadow);
        ConvoSense.read(OPEN_LINE, new ChatDecide((body, ms) -> answers("talk", "neutral", "normal"), 400, 1000), "x");
        assertTrue(shadow.drain(5000));
        assertEquals(1, rows.size());
        assertEquals("replay", rows.get(0).get("src"));
    }

    @Test
    void withoutTextARowKeepsOnlyTheLengthAndScores() throws Exception {
        List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<>());
        DecideShadow shadow = new DecideShadow(List.of(new DecideShadow.Model("onejev", "OneJev1.6-0.8B",
                (body, ms) -> answers("talk", "neutral", "normal"))), 8, 1000, 400, "live", "gliner", rows::add)
                .keepText(false).start();
        DecideShadow.install(shadow);
        ConvoSense.read(OPEN_LINE, new ChatDecide((body, ms) -> answers("talk", "neutral", "normal"), 400, 1000), "x");
        assertTrue(shadow.drain(5000));
        assertEquals(1, rows.size());
        Map<String, Object> r = rows.get(0);
        assertFalse(r.containsKey("text"), "no chat text on live: " + r);
        assertEquals(OPEN_LINE.length(), r.get("len"));
        assertFalse(Json.write(r).contains("event starts"), "the line never reaches the row");
        assertNotNull(r.get("agree"));
    }

    // ── the scorecard ──

    static Map<String, Object> row(long currentMs, Object... shadows) {
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        for (int i = 0; i < shadows.length; i += 2) {
            s.put((String) shadows[i], shadows[i + 1]);
        }
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("time", "t");
        r.put("text", "line " + currentMs);
        r.put("current", Map.of("model", "gliner", "ok", true, "ms", currentMs, "intent", "talk", "tone", "neutral",
                "con", "normal"));
        r.put("shadows", s);
        return r;
    }

    static Map<String, Object> shadow(boolean ok, long ms, boolean agree, boolean agreeIntent, boolean timeout, boolean late) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ok", ok);
        m.put("ms", ms);
        m.put("agree", agree);
        m.put("agreeIntent", agreeIntent);
        m.put("timeout", timeout);
        m.put("late", late);
        m.put("intent", agree ? "talk" : "guide");
        m.put("tone", "neutral");
        m.put("con", "normal");
        return m;
    }

    @Test
    void percentilesAreNearestRank() {
        List<Long> v = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            v.add(i * 10);
        }
        Collections.shuffle(v, new java.util.Random(1));
        assertEquals(100, DecideShadowReport.percentile(v, 50));
        assertEquals(190, DecideShadowReport.percentile(v, 95));
        assertEquals(200, DecideShadowReport.percentile(v, 100));
        assertEquals(0, DecideShadowReport.percentile(List.of(), 95));
        assertEquals(7, DecideShadowReport.percentile(List.of(7L), 95));
    }

    @Test
    void theSummaryCountsAgreementLatencyErrorsAndTimeouts() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // gliclass: 4 lines, 3 agree, 4 intents agree, all answered (100, 120, 140, 500 ms: one late)
        // onejev: 4 lines, 1 agree, 2 intents agree, one error and one timeout
        rows.add(row(80, "gliclass", shadow(true, 100, true, true, false, false), "onejev", shadow(true, 90, true, true, false, false)));
        rows.add(row(90, "gliclass", shadow(true, 120, true, true, false, false), "onejev", shadow(false, 5, false, false, false, false)));
        rows.add(row(100, "gliclass", shadow(true, 140, true, true, false, false), "onejev", shadow(false, 2000, false, true, true, true)));
        rows.add(row(110, "gliclass", shadow(true, 500, false, true, false, true), "onejev", shadow(true, 95, false, false, false, false)));
        List<DecideShadowReport.Stats> stats = DecideShadowReport.summarize(rows);
        assertEquals(3, stats.size());
        DecideShadowReport.Stats cur = stats.get(0);
        assertEquals("gliner (current)", cur.model());
        assertEquals(4, cur.lines());
        assertEquals(90, cur.p50());
        assertEquals(110, cur.p95());
        DecideShadowReport.Stats g = stats.get(1);
        assertEquals("gliclass", g.model());
        assertEquals(75.0, g.agreePct());
        assertEquals(100.0, g.agreeIntentPct());
        assertEquals(120, g.p50());
        assertEquals(500, g.p95());
        assertEquals(0, g.errors());
        assertEquals(1, g.late());
        DecideShadowReport.Stats o = stats.get(2);
        assertEquals(25.0, o.agreePct());
        assertEquals(50.0, o.agreeIntentPct());
        assertEquals(2, o.answered());
        assertEquals(1, o.errors());
        assertEquals(1, o.timeouts());
        assertEquals(90, o.p50(), "latency only over answered lines");
        assertEquals(95, o.p95());

        var dis = DecideShadowReport.disagreements(rows, null);
        assertEquals(4, dis.size());
        assertEquals(3, DecideShadowReport.disagreements(rows, "onejev").size());
        assertEquals(1, DecideShadowReport.disagreements(rows, "gliclass").size());
        List<String> text = DecideShadowReport.lines(rows, 2);
        assertTrue(text.get(2).startsWith("gliclass: 4 lines, agree 75.0% (intent 100.0%), p50 120 ms p95 500 ms"),
                text.toString());
        assertTrue(text.contains("Disagreements (4, latest 2):"), text.toString());
        assertEquals(text.size(), 1 + 3 + 1 + 2);
        assertNotNull(DecideShadowReport.json(rows).get("disagreements"));
    }

    @Test
    void theSummaryReadsTheFilesItWrote(@TempDir Path dir) throws Exception {
        DecideShadow.append(dir, row(80, "gliclass", shadow(true, 100, true, true, false, false)));
        Files.writeString(dir.resolve("junk.jsonl"), "{not json\n\n", StandardCharsets.UTF_8);
        List<Map<String, Object>> rows = DecideShadowReport.load(List.of(dir));
        assertEquals(1, rows.size());
        assertEquals(100.0, DecideShadowReport.summarize(rows).get(1).agreePct());
    }

    // ── the replay input ──

    @Test
    void theReplayReadsPlayerLinesFromTranscripts() {
        List<String> file = List.of(
                "{\"at\":1,\"bot\":\"CutieSilver\",\"player\":\"3626345b\",\"asked\":null,\"line\":\"yo {player} lol\"}",
                "{\"at\":2,\"bot\":\"MuffinCandy\",\"player\":\"-\",\"asked\":\"(thread: gossip)\",\"line\":\"kekeke\"}",
                "{\"at\":3,\"bot\":\"B0wBow\",\"player\":\"6a5903f9\",\"asked\":\"Where is the henesys PQ?\",\"line\":\"its in kpq\"}",
                "{\"at\":4,\"bot\":\"B0wBow\",\"player\":\"6a5903f9\",\"asked\":\"where is the  henesys pq?\",\"line\":\"ok\"}",
                "plain text line",
                "{broken");
        var players = DecideShadowReplay.parse(file, false);
        assertEquals(List.of("Where is the henesys PQ?", "where is the  henesys pq?", "plain text line"),
                players.stream().map(DecideShadowReplay.Line::text).toList());
        assertEquals("player:6a5903f9", players.get(0).speaker());
        assertEquals(2, DecideShadowReplay.distinct(players).size(), "case and spacing repeats once");
        var all = DecideShadowReplay.parse(file, true);
        assertTrue(all.stream().anyMatch(l -> l.text().equals("yo u lol")), "placeholders become 'u'");
        assertNotEquals(players.size(), all.size());
    }

    @Test
    void offWithoutTheProperty() {
        System.clearProperty(DecideShadow.PROPERTY);
        assertNull(DecideShadow.fromProperties("live"));
        System.setProperty(DecideShadow.PROPERTY, "gliclass,onejev");
        System.setProperty("slums.autoqa.dir", "target/decide-shadow-test");
        try {
            DecideShadow s = DecideShadow.fromProperties("live");
            assertEquals(List.of("gliclass", "onejev"), s.names());
        } finally {
            System.clearProperty(DecideShadow.PROPERTY);
            System.clearProperty("slums.autoqa.dir");
        }
    }
}
