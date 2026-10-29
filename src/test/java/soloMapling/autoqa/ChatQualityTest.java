package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 inference checks: the daily chat-quality scoring, on transcript rows like the test server's. */
class ChatQualityTest {

    static ChatQuality.Line reply(long at, String bot, String voice, String asked, String line) {
        return new ChatQuality.Line(at, bot, "c5e69024", asked, line, voice, "llm", 104040000, "llm");
    }

    static ChatQuality.Line thread(long at, String bot, String line) {
        return new ChatQuality.Line(at, bot, "-", "(thread: looking for a party)", line, "lower", "thread-llm", 100000000,
                "thread");
    }

    @Test
    void stiffnessFlags() {
        assertTrue(ChatQuality.stiffFlags("ya ya just chillin lol u leveling tonight or nah", "lower").isEmpty());
        assertEquals(List.of("proper-case full sentence, no slang"),
                ChatQuality.stiffFlags("That sounds like a great plan for today.", "lower"));
        assertTrue(ChatQuality.stiffFlags("That sounds like a great plan for today.", "proper").isEmpty(),
                "a proper voice may write proper sentences");
        assertTrue(ChatQuality.stiffFlags("Feel free to let me know if you need anything", "proper")
                .contains("assistant phrasing"));
        assertTrue(ChatQuality.stiffFlags("i think the best place to train at your level is probably the ant tunnel because "
                + "the mobs there give a lot of exp and they drop decent stuff too honestly", "lower").get(0).startsWith("long"));
    }

    @Test
    void repeatsAcrossBotsAndNearDuplicates() {
        List<ChatQuality.Line> l = List.of(reply(1, "a", "lower", "hi", "yo whats up"),
                reply(2, "b", "lower", "hey", "yo whats up!!"),
                reply(3, "c", "lower", "sup", "lf party for kerning pq tbh"),
                reply(4, "d", "lower", "sup", "lf party for kerning pq tbh idk"),
                reply(5, "e", "lower", "sup", "something else entirely"));
        boolean[] r = ChatQuality.repeats(l);
        assertFalse(r[0]);
        assertTrue(r[1], "the same line from another bot");
        assertFalse(r[2]);
        assertTrue(r[3], "a near-duplicate");
        assertFalse(r[4]);
    }

    @Test
    void questions() {
        assertTrue(ChatQuality.isQuestion("where are you?"));
        assertTrue(ChatQuality.isQuestion("where do u train"));
        assertFalse(ChatQuality.isQuestion("oh nice"));
        assertTrue(ChatQuality.dodges("where do u train", "lol"));
        assertFalse(ChatQuality.dodges("u wanna train?", "nah"));
        assertFalse(ChatQuality.dodges("where are you?", "im at henesys rn, come by if u want"));
    }

    @Test
    void sayDoPerSourceAndTopUnbacked() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("bot", "a", "source", "reply-llm", "kind", "come", "line", "omw", "backed", true, "why", "walk", "weak", false));
        rows.add(Map.of("bot", "b", "source", "pack:SocialHotPotato/RealLife", "kind", "away", "line", "brb dinner",
                "backed", false, "why", "kept chatting", "weak", false));
        rows.add(Map.of("bot", "c", "source", "pack:SocialHotPotato/RealLife", "kind", "away", "line", "brb shower",
                "backed", false, "why", "kept playing", "weak", false));
        rows.add(Map.of("bot", "d", "source", "thread-llm", "kind", "come", "line", "coming", "backed", false, "why", "x",
                "weak", true));
        Map<String, Object> m = ChatQuality.sayDo(rows);
        assertEquals(3L, m.get("claims"));
        assertEquals(1L, m.get("backed"));
        assertEquals(1L, m.get("weakClaims"));
        Map<?, ?> src = (Map<?, ?>) ((Map<?, ?>) m.get("bySource")).get("pack:SocialHotPotato/RealLife");
        assertEquals(0.0, src.get("rate"));
        Map<?, ?> top = (Map<?, ?>) ((List<?>) m.get("topUnbacked")).get(0);
        assertEquals("away", top.get("kind"));
        assertEquals(2L, top.get("unbacked"));
        assertEquals(2, ((List<?>) top.get("examples")).size());
    }

    /**
     * 1.6.1 say-do: the claims bots make talking to each other are counted on their own. TEST 2026-10-28 read "say-do
     * 100% backed (144172 claims)" from shop ads while bot talk was 69% of 314: the headline says both, and bot talk
     * under 80% (20+ claims) is a problem of its own.
     */
    @Test
    void botTalkSayDoIsShownAndJudgedApartFromTheAds() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            rows.add(Map.of("bot", "m" + i, "source", "ad", "kind", "sell", "line", "S> ilbis", "backed", true, "why",
                    "has the item", "weak", false));
        }
        for (int i = 0; i < 30; i++) {
            rows.add(Map.of("bot", "t" + i, "source", i % 2 == 0 ? "talk-lines" : "talk-bank", "kind", "go_to",
                    "line", "going henesys, coming?", "backed", i < 18, "why", i < 18 ? "travel" : "never went",
                    "weak", false));
        }
        Map<String, Object> sd = ChatQuality.sayDo(rows);
        Map<?, ?> talk = (Map<?, ?>) sd.get("talk");
        assertEquals(30L, talk.get("claims"));
        assertEquals(0.6, talk.get("rate"));
        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("lines", 10);
        report.put("relevance", Map.of("score", 0.9, "judged", 0));
        report.put("stiffness", Map.of("score", 0.0));
        report.put("repetition", Map.of("score", 0.0));
        report.put("unanswered", Map.of("unanswered", 0, "questions", 0));
        report.put("sayDo", sd);
        String head = ChatQuality.headline(report);
        assertTrue(head.contains("say-do 95% backed (230 claims; bot talk 60% of 30)"), head);
        assertTrue(ChatQuality.problems(report).contains("bot talk say-do 60% backed < 80%"),
                String.valueOf(ChatQuality.problems(report)));
    }

    @Test
    void theReportJudgesInBatchesAndFailsOverTheThresholds() throws Exception {
        List<ChatQuality.Line> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add(reply(i * 1000L, "bot" + i, "lower", "how are you", i % 2 == 0
                    ? "im good lol hbu" : "Certainly. I would be happy to help you with that today."));
        }
        lines.add(thread(40_000, "x", "lf party orbis pq"));
        lines.add(thread(41_000, "y", "same lol need 2 more"));
        int[] calls = {0};
        LlmJudge judge = LlmJudge.of((sys, user) -> {
            calls[0]++;
            StringBuilder a = new StringBuilder("[");
            int n = user.split("\n   reply: ").length - 1;
            for (int k = 1; k <= n; k++) {
                a.append(k > 1 ? "," : "").append("{\"n\":").append(k).append(",\"relevant\":").append(k % 2 == 1)
                        .append(",\"answers\":null}");
            }
            return a.append("]").toString();
        }, 5);
        Map<String, Object> r = ChatQuality.report(lines, List.of(), judge, 5, 0, 50_000);
        assertEquals(2, calls[0], "31 pairs (the first thread line answers nothing): two batches of 20");
        Map<?, ?> rel = (Map<?, ?>) r.get("relevance");
        assertEquals(31, rel.get("judged"));
        Map<?, ?> st = (Map<?, ?>) r.get("stiffness");
        assertEquals(15, st.get("stiff"));
        List<String> problems = ChatQuality.problems(r);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("relevance")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("stiffness")), problems.toString());
        assertEquals(15, ((List<?>) r.get("worst")).size());
        assertTrue(Json.isValid(Json.write(r)));
        assertTrue(String.valueOf(r.get("headline")).startsWith("chat 24h: 32 lines; relevance"));
    }

    @Test
    void noJudgeNoRelevanceAndNoRelevanceFailure() throws Exception {
        List<ChatQuality.Line> lines = List.of(reply(1, "a", "lower", "where do u train", "lol"));
        Map<String, Object> r = ChatQuality.report(lines, List.of(), null, 5, 0, 10);
        assertNull(((Map<?, ?>) r.get("relevance")).get("score"));
        assertEquals(1, ((Map<?, ?>) r.get("unanswered")).get("unanswered"), "a one-word dodge, without a judge");
        assertTrue(ChatQuality.problems(r).isEmpty());
    }

    @Test
    void readsTheTranscriptAndTheSayDoFile(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path convo = dir.resolve("convo");
        Files.createDirectories(convo);
        String day = java.time.LocalDate.now().toString();
        Files.writeString(convo.resolve("transcript-" + day + ".jsonl"),
                "{\"at\":" + (now - 1000) + ",\"bot\":\"wizz\",\"player\":\"c5e69024\",\"asked\":\"hi\",\"line\":\"yo\","
                        + "\"tier\":\"scripted\",\"source\":\"bank\",\"bankId\":2,\"voice\":\"lower\",\"ms\":1124,\"map\":104040000}\n"
                        + "{\"at\":" + (now - 2 * 86_400_000L) + ",\"bot\":\"old\",\"line\":\"too old\"}\ntorn{\n");
        assertEquals(1, ChatQuality.load(convo, now - 86_400_000L, now).size());
        Path saydo = soloMapling.ArtificialPlayer.BotConvo.SayDo.file(dir, now);
        Files.createDirectories(saydo.getParent());
        Files.writeString(saydo, "{\"at\":" + (now - 5000) + ",\"resolvedAt\":" + (now - 1000)
                + ",\"bot\":\"a\",\"source\":\"llm\",\"kind\":\"come\",\"line\":\"omw\",\"backed\":false,\"weak\":false}\n");
        assertEquals(1, ChatQuality.loadSayDo(dir, now - 86_400_000L, now).size());
    }

    @Test
    void theJudgesRelevanceAnswerIsRead() {
        List<LlmJudge.Relevance> r = LlmJudge.readRelevance("sure: [{\"n\":2,\"relevant\":false,\"answers\":true},"
                + "{\"n\":9,\"relevant\":true}]", 3);
        assertFalse(r.get(0).decided());
        assertTrue(r.get(1).decided());
        assertFalse(r.get(1).relevant());
        assertEquals(Boolean.TRUE, r.get(1).answers());
        assertFalse(r.get(2).decided());
        assertFalse(LlmJudge.readRelevance("no idea", 2).get(0).decided());
    }

    @Test
    void aCutOffAnswerStillYieldsItsCompleteRowsAndTheCapFitsABatch() {
        // 1.6.0: the 120-token cap cut every 20-pair answer mid-array, so TEST judged 0 replies for days
        List<LlmJudge.Relevance> r = LlmJudge.readRelevance("[{\"n\":1,\"relevant\":true,\"answers\":null},"
                + "{\"n\":2,\"relevant\":false,\"answers\":false},{\"n\":3,\"relev", 3);
        assertTrue(r.get(0).decided());
        assertTrue(r.get(0).relevant());
        assertTrue(r.get(1).decided());
        assertFalse(r.get(1).relevant());
        assertFalse(r.get(2).decided());
        String row = "{\"n\":20,\"relevant\":false,\"answers\":false},";
        assertTrue(LlmJudge.MAX_TOKENS >= LlmJudge.BATCH * (row.length() / 3), "a full batch's answer fits the cap");
    }

    @Test
    void theCloudFallsBackToTheLocalModel() throws Exception {
        LlmJudge.Transport spent = (s, u) -> null;
        LlmJudge.Transport local = (s, u) -> "local";
        assertEquals("local", LlmJudge.withFallback(spent, local).complete("s", "u"));
        assertEquals("cloud", LlmJudge.withFallback((s, u) -> "cloud", local).complete("s", "u"));
    }
}
