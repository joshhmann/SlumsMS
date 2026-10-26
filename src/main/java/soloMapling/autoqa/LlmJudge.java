package soloMapling.autoqa;

import config.ServerConfig;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.CloudBudget;
import soloMapling.ArtificialPlayer.BotBrainSystem.OpenAICompatibleBrain;
import soloMapling.server.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grades a bot's chat line for the conversation checks with an LLM: on-topic, coherent with the line before it, in
 * character for a 2008 MapleStory player, PG-13, and never reveals it is an AI or a bot. Uses the configured cloud
 * provider (BOT_LLM_CLOUD_*) when there is one, else the local model (BOT_LLM_*). The API key is never logged or
 * put in evidence. Calls are bounded per run (AUTOQA_JUDGE_MAX_CALLS) and each has the provider's timeout.
 * <p>
 * Some failures need no model: a line that says it is an AI or a language model fails at once ({@link #REVEALS}).
 */
public final class LlmJudge {

    /** The model's answer for one line. {@code decided} = false when the model could not be asked or read. */
    public record Grade(boolean pass, boolean decided, String reason) {
    }

    /** Sends a system + user prompt, returns the model's text or null (tests pass a fake). */
    public interface Transport {
        String complete(String system, String user) throws InterruptedException;
    }

    static final Pattern REVEALS = Pattern.compile("(?i)\\b(as an ai|an ai (language )?model|language model|i'?m an ai|"
            + "i am an ai|i'?m a bot|i am a bot|chatgpt|openai|i'?m (just )?a program|artificial intelligence)\\b");

    static final String SYSTEM = "You grade one chat line said by a player character in MapleStory (2008 era) to another "
            + "player. Answer with one JSON object only, no other text: {\"on_topic\":true|false,\"coherent\":true|false,"
            + "\"in_character\":true|false,\"pg13\":true|false,\"reveals_ai\":true|false,\"reason\":\"<10 words\"}. "
            + "on_topic: it responds to what was said or the situation. coherent: it makes sense after the previous "
            + "line. in_character: it is something a player would type to a friend in 2008 MapleStory; slang, lowercase "
            + "and typos are fine but slang is NOT required and a plain friendly sentence is in character (only a "
            + "customer-service or assistant tone, such as offering help with a task, is not). pg13: no slurs, no sexual "
            + "content, insults only about the "
            + "game. reveals_ai: it says or implies it is an AI, a bot, a model or a program. The names of the players "
            + "are given and are never a reveal by themselves: a line that only says a name, however it is spelled, is "
            + "not saying it is an AI.";

    private final Transport transport;
    private final int maxCalls;
    private final String describe;

    LlmJudge(Transport transport, int maxCalls, String describe) {
        this.transport = transport;
        this.maxCalls = maxCalls;
        this.describe = describe;
    }

    /** The judge from config.yaml: the cloud provider if set, else the local model, else unavailable. */
    public static LlmJudge fromConfig(ServerConfig cfg) {
        int max = Math.max(0, cfg.AUTOQA_JUDGE_MAX_CALLS);
        if (cfg.BOT_LLM_CLOUD_BASE_URL != null && !cfg.BOT_LLM_CLOUD_BASE_URL.isBlank()) {
            String key = cfg.BOT_LLM_CLOUD_API_KEY == null || cfg.BOT_LLM_CLOUD_API_KEY.isBlank()
                    ? System.getenv("SOLOMAPLING_LLM_CLOUD_API_KEY") : cfg.BOT_LLM_CLOUD_API_KEY;
            // rc6 bug hunt: the judge's cloud calls are charged to the server's one daily cloud budget
            CloudBudget budget = BotBrains.cloudBudget(cfg);
            Transport cloud = budgeted(budget, wire(cfg.BOT_LLM_CLOUD_BASE_URL.trim(), cfg.BOT_LLM_CLOUD_MODEL, key,
                    Math.max(cfg.BOT_LLM_CLOUD_TIMEOUT_MS, 15_000), true));
            if (cfg.BOT_LLM_BASE_URL != null && !cfg.BOT_LLM_BASE_URL.isBlank()) {
                // rc6: the cheap local fallback when the cloud budget is spent (or the cloud does not answer)
                String lkey = cfg.BOT_LLM_API_KEY == null || cfg.BOT_LLM_API_KEY.isBlank()
                        ? System.getenv("SOLOMAPLING_LLM_API_KEY") : cfg.BOT_LLM_API_KEY;
                Wire local = wire(cfg.BOT_LLM_BASE_URL.trim(), cfg.BOT_LLM_MODEL, lkey,
                        Math.max(cfg.BOT_LLM_TIMEOUT_MS, 20_000), false);
                return new LlmJudge(withFallback(cloud, (system, user) -> textOf(local.post(system, user))), max,
                        "cloud " + cfg.BOT_LLM_CLOUD_MODEL + " (local " + cfg.BOT_LLM_MODEL + " when the budget is spent)");
            }
            return new LlmJudge(cloud, max, "cloud " + cfg.BOT_LLM_CLOUD_MODEL);
        }
        if (cfg.BOT_LLM_BASE_URL != null && !cfg.BOT_LLM_BASE_URL.isBlank()) {
            String key = cfg.BOT_LLM_API_KEY == null || cfg.BOT_LLM_API_KEY.isBlank()
                    ? System.getenv("SOLOMAPLING_LLM_API_KEY") : cfg.BOT_LLM_API_KEY;
            Wire local = wire(cfg.BOT_LLM_BASE_URL.trim(), cfg.BOT_LLM_MODEL, key, Math.max(cfg.BOT_LLM_TIMEOUT_MS, 20_000),
                    false);
            return new LlmJudge((system, user) -> {
                return textOf(local.post(system, user));
            }, max, "local " + cfg.BOT_LLM_MODEL);
        }
        return new LlmJudge(null, 0, "none");
    }

    public static LlmJudge of(Transport transport, int maxCalls) {
        return new LlmJudge(transport, maxCalls, "test");
    }

    public boolean available() {
        return transport != null && maxCalls > 0;
    }

    public int maxCalls() {
        return maxCalls;
    }

    /** "cloud deepseek-chat" / "local qwen9b" / "none" - never the key. */
    public String describe() {
        return describe;
    }

    /** Grades one bot line ({@code reply}) said after the player's {@code previous} line, in a setting. */
    public Grade grade(String setting, String previous, String reply) throws InterruptedException {
        if (REVEALS.matcher(reply).find()) {
            return new Grade(false, true, "reveals it is an AI/bot");
        }
        if (Expects.EMOJI.matcher(reply).find()) {
            return new Grade(false, true, "emoji");
        }
        if (!available()) {
            return new Grade(true, false, "no judge");
        }
        String user = "Setting: " + setting + "\nPrevious line (the other player): "
                + (previous == null || previous.isBlank() ? "(none)" : previous) + "\nLine to grade: " + reply;
        String answer = transport.complete(SYSTEM, user);
        return read(answer);
    }

    /** Reads the model's JSON (the first {...} in the text). Unreadable = undecided, never a fail. */
    static Grade read(String answer) {
        if (answer == null) {
            return new Grade(true, false, "judge did not answer");
        }
        Matcher m = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(answer);
        if (!m.find()) {
            return new Grade(true, false, "judge answer unreadable");
        }
        Object parsed;
        try {
            parsed = Json.parse(m.group());
        } catch (RuntimeException e) {
            return new Grade(true, false, "judge answer unreadable");
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return new Grade(true, false, "judge answer unreadable");
        }
        StringBuilder bad = new StringBuilder();
        for (String k : List.of("on_topic", "coherent", "in_character", "pg13")) {
            if (Boolean.FALSE.equals(map.get(k))) {
                bad.append(bad.length() == 0 ? "" : ", ").append("not ").append(k);
            }
        }
        if (Boolean.TRUE.equals(map.get("reveals_ai"))) {
            bad.append(bad.length() == 0 ? "" : ", ").append("reveals AI");
        }
        Object reason = map.get("reason");
        String why = (bad.length() == 0 ? "ok" : bad.toString()) + (reason == null ? "" : " (" + reason + ")");
        return new Grade(bad.length() == 0, true, why);
    }

    /** The first transport's answer, else the second's (rc6: cloud first, the local model when it is not there). */
    static Transport withFallback(Transport first, Transport second) {
        return (system, user) -> {
            String a = first.complete(system, user);
            return a != null ? a : second.complete(system, user);
        };
    }

    // ── rc6: batched relevance for the daily chat-quality report ──

    /** A line and the reply to it. */
    public record Pair(String asked, String reply) {
    }

    /** The judge's view of one pair; {@code answers} null when the first line asked nothing. */
    public record Relevance(boolean relevant, Boolean answers, boolean decided) {
        static final Relevance UNDECIDED = new Relevance(true, null, false);
    }

    /** Pairs per call: one call grades this many replies. */
    public static final int BATCH = 20;

    static final String RELEVANCE_SYSTEM = "You rate chat replies between players of MapleStory (2008 era). You get "
            + "numbered pairs: the line a player or bot said, then the reply to it. Answer with one JSON array only, no "
            + "other text: [{\"n\":1,\"relevant\":true|false,\"answers\":true|false|null}, ...]. relevant: the reply "
            + "responds to that line (casual follow-up small talk counts; a non sequitur or a canned line that ignores it "
            + "does not). answers: if the first line is a question, whether the reply answers it (\"idk\" counts as an "
            + "answer); null if the first line is not a question.";

    /** Grades up to {@link #BATCH} pairs in one call. Unreadable or no judge: every pair undecided. */
    public List<Relevance> relevance(List<Pair> pairs) throws InterruptedException {
        List<Relevance> out = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            out.add(Relevance.UNDECIDED);
        }
        if (!available() || pairs.isEmpty()) {
            return out;
        }
        StringBuilder user = new StringBuilder();
        for (int i = 0; i < pairs.size() && i < BATCH; i++) {
            Pair p = pairs.get(i);
            user.append(i + 1).append(". line: ").append(clip(p.asked())).append("\n   reply: ").append(clip(p.reply()))
                    .append('\n');
        }
        String answer = transport.complete(RELEVANCE_SYSTEM, user.toString());
        return readRelevance(answer, pairs.size());
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) : t;
    }

    /** Reads the judge's JSON array; pairs it does not mention stay undecided. */
    static List<Relevance> readRelevance(String answer, int n) {
        List<Relevance> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Relevance.UNDECIDED);
        }
        if (answer == null) {
            return out;
        }
        // row by row, not the whole array: an answer cut off at max_tokens still yields its complete rows (1.6.0: a
        // 20-pair batch needs ~300 tokens, the old 120-token cap cut every answer and nothing was ever judged)
        Matcher m = Pattern.compile("\\{[^{}]*}").matcher(answer.substring(Math.max(0, answer.indexOf('['))));
        while (m.find()) {
            Object o;
            try {
                o = Json.parse(m.group());
            } catch (RuntimeException e) {
                continue;
            }
            if (!(o instanceof Map<?, ?> row) || !(row.get("n") instanceof Number num)) {
                continue;
            }
            int i = num.intValue() - 1;
            if (i < 0 || i >= n || !(row.get("relevant") instanceof Boolean rel)) {
                continue;
            }
            Object ans = row.get("answers");
            out.set(i, new Relevance(rel, ans instanceof Boolean b ? b : null, true));
        }
        return out;
    }

    /** The judge's answer at most (its max_tokens): the worst case a cloud call reserves. */
    static final int MAX_TOKENS = BATCH * 16 + 32; // one ~15-token row per pair, plus slack

    /** One chat-completions POST: the 2xx response body, or null. */
    interface Wire {
        String post(String system, String user) throws InterruptedException;
    }

    /**
     * A cloud transport that charges {@code budget} like every other cloud call (1.5.0 rc6 bug hunt): the worst case
     * is reserved first (no call when it doesn't fit under the day's cap), and the call's real cost is settled from
     * its own response's usage, else estimated. No budget, no cloud call.
     */
    static Transport budgeted(CloudBudget budget, Wire wire) {
        return (system, user) -> {
            if (budget == null) {
                return null;
            }
            long inChars = (system == null ? 0 : system.length()) + (user == null ? 0 : user.length());
            double worst = budget.cost((inChars + 1) / 2 + 16, MAX_TOKENS);
            if (!budget.reserve(worst)) {
                AutoQa.log.info("Auto-QA judge: skipped, the cloud budget is spent for today ({})", budget.describe());
                return null;
            }
            String body = null;
            try {
                body = wire.post(system, user);
            } finally {
                long[] used = body == null ? new long[]{-1, -1} : OpenAICompatibleBrain.usageOf(body);
                String text = textOf(body);
                long in = used[0] > 0 ? used[0] : CloudBudget.estimateTokens(system) + CloudBudget.estimateTokens(user);
                long out = used[0] > 0 ? Math.max(1, used[1]) : CloudBudget.estimateTokens(text) + 4;
                budget.settle(worst, in, out);
            }
            return textOf(body);
        };
    }

    private static String textOf(String body) {
        try {
            return body == null ? null : OpenAICompatibleBrain.replyTextOf(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Wire wire(String baseUrl, String model, String key, int timeoutMs, boolean cloud) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (system, user) -> {
            Map<String, Object> body = new LinkedHashMap<>();
            if (model != null && !model.isBlank()) {
                body.put("model", model);
            }
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            body.put("max_tokens", MAX_TOKENS);
            body.put("temperature", 0.0);
            body.put("stream", false);
            if (cloud) {
                body.put("reasoning_effort", "none");
            } else {
                body.put("chat_template_kwargs", Map.of("enable_thinking", false));
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/$", "") + "/chat/completions"))
                    .timeout(Duration.ofMillis(timeoutMs)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
            if (key != null && !key.isBlank()) {
                req.header("Authorization", "Bearer " + key);
            }
            try {
                HttpResponse<String> r = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) {
                    AutoQa.log.warn("Auto-QA judge: HTTP {} from {}", r.statusCode(), cloud ? "the cloud provider" : "the local model");
                    return null;
                }
                return r.body();
            } catch (IOException e) {
                AutoQa.log.warn("Auto-QA judge: request failed ({})", e.getClass().getSimpleName());
                return null;
            }
        };
    }
}
