package soloMapling.autoqa;

import config.YamlConfig;
import soloMapling.ArtificialPlayer.BotConvo.ConvoQuality;
import soloMapling.ArtificialPlayer.BotConvo.SayDo;
import soloMapling.server.History;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * rc6 inference checks: the daily chat-quality summary. Scores the last 24 h of bot replies to players and bot
 * threads from the ConvoQuality transcripts ({@code logs/convo/transcript-<date>.jsonl}) and the say-do verdicts
 * ({@code data/autoqa/saydo/<day>.jsonl}):
 * <ul>
 *   <li><b>relevance:</b> does the reply answer the line it follows - the LLM judge, {@link LlmJudge#BATCH} pairs per
 *   call, at most {@link #MAX_JUDGE_CALLS} calls, charged to the server's cloud budget (the local model when it is
 *   spent; nothing judged, the score is null and its threshold is skipped);</li>
 *   <li><b>repetition:</b> the same or a near-identical line again (any bot, any player);</li>
 *   <li><b>stiffness:</b> a bot whose voice is not "proper" typing proper-case full sentences with no slang,
 *   assistant phrasing ("feel free", "let me know"), or a reply far longer than chat;</li>
 *   <li><b>unanswered:</b> a player's question the reply did not answer (the judge; without one, a 1-2 word dodge);</li>
 *   <li><b>say-do:</b> the share of action claims ("omw", "brb pots", "S> ilbis") the bot then did, per source and
 *   kind, and the top unbacked kinds with examples.</li>
 * </ul>
 * Writes {@code data/autoqa/chat-quality-<UTC date>.json} with the scores and the 15 worst lines (the player as a
 * hash, a name in a line as {player}). Runs after every auto-QA run and once a day. Pure parts are package-visible
 * for {@code ChatQualityTest}.
 */
public final class ChatQuality {

    /** FAIL below this share of judged replies on topic (with at least {@link #MIN_JUDGED} judged). */
    public static final double RELEVANCE_MIN = 0.70;
    public static final int MIN_JUDGED = 10;
    /** FAIL above this share of stiff lines (with at least {@link #MIN_LINES} lines). */
    public static final double STIFFNESS_MAX = 0.25;
    public static final int MIN_LINES = 20;
    /** FAIL below this share of strong claims backed (with at least {@link #MIN_CLAIMS} claims). */
    public static final double SAYDO_MIN = 0.50;
    public static final int MIN_CLAIMS = 20;
    static final int MAX_JUDGE_CALLS = 5;
    static final int WORST = 15;

    private ChatQuality() {
    }

    /** One transcript row. {@code player} is the transcript's hash ("-" for a thread line). */
    public record Line(long at, String bot, String player, String asked, String line, String voice, String source,
                       int map, String tier) {
        boolean thread() {
            return "-".equals(player) || (asked != null && asked.startsWith("(thread"));
        }
    }

    // ── reading ──

    /** The transcript rows between two times, from the day files that can hold them. */
    static List<Line> load(Path transcriptDir, long fromMs, long toMs) {
        List<Line> out = new ArrayList<>();
        LocalDate first = Instant.ofEpochMilli(fromMs).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate last = Instant.ofEpochMilli(toMs).atZone(ZoneId.systemDefault()).toLocalDate();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            Path f = transcriptDir.resolve("transcript-" + d + ".jsonl");
            for (Map<String, Object> m : jsonl(f)) {
                Line l = line(m);
                if (l != null && l.at() >= fromMs && l.at() <= toMs) {
                    out.add(l);
                }
            }
        }
        out.sort(Comparator.comparingLong(Line::at));
        return out;
    }

    static Line line(Map<String, Object> m) {
        if (!(m.get("line") instanceof String text) || !(m.get("at") instanceof Number at)) {
            return null;
        }
        return new Line(at.longValue(), str(m.get("bot")), str(m.get("player")), str(m.get("asked")), text,
                str(m.get("voice")), str(m.get("source")), m.get("map") instanceof Number n ? n.intValue() : 0,
                str(m.get("tier")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> jsonl(Path f) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!History.exists(f)) {
            return out;
        }
        try {
            for (String row : History.readLines(f)) {
                try {
                    if (Json.parse(row) instanceof Map<?, ?> m) {
                        out.add((Map<String, Object>) m);
                    }
                } catch (RuntimeException ignored) {
                    // a torn row
                }
            }
        } catch (IOException e) {
            AutoQa.log.debug("chat quality: {} unreadable", f);
        }
        return out;
    }

    /** The say-do rows between two times (the UTC day files that can hold them). */
    static List<Map<String, Object>> loadSayDo(Path autoqaDir, long fromMs, long toMs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (long t = fromMs - TimeUnit.DAYS.toMillis(1); t <= toMs + TimeUnit.DAYS.toMillis(1); t += TimeUnit.DAYS.toMillis(1)) {
            for (Map<String, Object> m : jsonl(SayDo.file(autoqaDir, t))) {
                if (m.get("resolvedAt") instanceof Number at && at.longValue() >= fromMs && at.longValue() <= toMs) {
                    out.add(m);
                }
            }
        }
        // a day file read twice (the loop's overlap) would double rows: keep each (at, bot, kind) once
        Set<String> seen = new HashSet<>();
        out.removeIf(m -> !seen.add(m.get("at") + "|" + m.get("bot") + "|" + m.get("kind") + "|" + m.get("line")));
        return out;
    }

    // ── stiffness ──

    static final Pattern SLANG = Pattern.compile("(?i)(\\b(lol|lmao|lmfao|rofl|ya|ye|yea|yeah|yep|nah|nope|u|ur|r|idk|"
            + "tbh|ngl|imo|gg|ez|kk|k|rn|nvm|omg|omfg|pls|plz|thx|ty|np|brb|gtg|afk|wat|wut|kinda|gonna|wanna|gotta|dunno|"
            + "haha|hahaha|hehe|kekeke|kek|jaja|xd|xD|ppl|bc|cuz|cus|lvl|exp|ks|pq|fm|hp|mp|ok|okay|sup|yo|bro|dude|"
            + "noob|nub|n00b|pro|lmk|ikr|smh|fr|irl|w/e|ofc|prob|probs|tho|thru|ty|tyvm|sry|srry|nty|gz|grats|meso|mesos|"
            + "hmm|hm|eh|uh|um|ah|oh|aw|aww|lolz|xp|ima|imma|ain'?t|y'?all|yall)\\b|[:;]\\)|[:;]p|:d|\\^\\^|<3|T_T|xd)");
    static final Pattern ASSISTANT = Pattern.compile("(?i)\\b(certainly|however|additionally|furthermore|feel free|"
            + "happy to help|glad to help|let me know|i'?d be glad|i'?d be happy|great question|absolutely|i understand|"
            + "i apologi[sz]e|how can i (help|assist)|is there anything|you'?re welcome|no problem at all|i hope this|"
            + "i recommend|i'?d recommend|it'?s important to|as a player|please note|in conclusion|i'?m here to)\\b");
    static final int LONG_CHARS = 90;
    static final int LONG_WORDS = 18;

    /** Why a line reads stiff for its voice (empty: it does not). */
    static List<String> stiffFlags(String line, String voice) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isBlank()) {
            return out;
        }
        String t = line.trim();
        boolean proper = "proper".equalsIgnoreCase(voice);
        int words = t.split("\\s+").length;
        if (ASSISTANT.matcher(t).find()) {
            out.add("assistant phrasing");
        }
        if (t.length() > LONG_CHARS || words > LONG_WORDS) {
            out.add("long (" + words + " words)");
        }
        if (!proper && words >= 4 && java.lang.Character.isUpperCase(t.charAt(0)) && t.matches(".*[.!?]$")
                && !SLANG.matcher(t).find()) {
            out.add("proper-case full sentence, no slang");
        }
        return out;
    }

    // ── repetition ──

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\{[a-z]+}", "x").replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\d+", "0").replaceAll("\\s+", " ").trim();
    }

    static Set<String> words(String s) {
        Set<String> w = new HashSet<>();
        for (String x : norm(s).split(" ")) {
            if (!x.isEmpty()) {
                w.add(x);
            }
        }
        return w;
    }

    /** For each line, whether it repeats (exactly or near: >= 80% of the words of a 3+ word line) an earlier one. */
    static boolean[] repeats(List<Line> lines) {
        boolean[] out = new boolean[lines.size()];
        Map<String, Integer> exact = new HashMap<>();
        List<Set<String>> earlier = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String n = norm(lines.get(i).line());
            Set<String> w = words(lines.get(i).line());
            if (exact.containsKey(n) && n.length() > 3) {
                out[i] = true;
            } else if (w.size() >= 3) {
                for (Set<String> e : earlier) {
                    if (e.size() >= 3 && jaccard(w, e) >= 0.8) {
                        out[i] = true;
                        break;
                    }
                }
            }
            exact.merge(n, 1, Integer::sum);
            if (earlier.size() < 3000) {
                earlier.add(w);
            }
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    // ── questions ──

    static final Pattern QUESTION = Pattern.compile("(?i)(\\?\\s*$|^(who|what|wat|wut|where|when|why|how|which|do|does|"
            + "did|are|r|is|can|could|would|will|wanna|want|u|you|have|has|any|anyone)\\b.*\\?)|^(who|what|where|when|why|"
            + "how|which|whats|wheres|hows|wat|wut)\\b");

    static boolean isQuestion(String s) {
        return s != null && QUESTION.matcher(s.trim()).find();
    }

    /** Without a judge: a reply of one or two words that is not a yes/no/idk answer dodges the question. */
    static boolean dodges(String asked, String reply) {
        if (reply == null) {
            return true;
        }
        String r = norm(reply);
        int words = r.isEmpty() ? 0 : r.split(" ").length;
        return words <= 2 && !r.matches(".*\\b(yes|ya|ye|yeah|yep|no|nah|nope|idk|sure|ok|okay|k|kk|maybe|0|lv\\w*|"
                + "henesys|ellinia|perion|kerning|orbis|ludi|fm)\\b.*");
    }

    // ── say-do ──

    /** The say-do part: strong claims per source and kind, overall rate, the top unbacked kinds with examples. */
    static Map<String, Object> sayDo(List<Map<String, Object>> rows) {
        Map<String, long[]> bySource = new TreeMap<>();
        Map<String, long[]> byKind = new TreeMap<>();
        Map<String, List<String>> unbackedExamples = new TreeMap<>();
        long claims = 0, backed = 0, weak = 0;
        for (Map<String, Object> r : rows) {
            if (Boolean.TRUE.equals(r.get("weak"))) {
                weak++;
                continue;
            }
            boolean b = Boolean.TRUE.equals(r.get("backed"));
            String src = String.valueOf(r.get("source"));
            String kind = String.valueOf(r.get("kind"));
            claims++;
            backed += b ? 1 : 0;
            long[] s = bySource.computeIfAbsent(src, k -> new long[2]);
            s[0]++;
            s[1] += b ? 1 : 0;
            long[] k = byKind.computeIfAbsent(kind, x -> new long[2]);
            k[0]++;
            k[1] += b ? 1 : 0;
            if (!b) {
                List<String> ex = unbackedExamples.computeIfAbsent(kind, x -> new ArrayList<>());
                if (ex.size() < 3) {
                    ex.add(r.get("bot") + " [" + src + " @ " + r.getOrDefault("map", "?") + "]: \""
                            + r.get("line") + "\" (" + r.get("why") + ")");
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claims", claims);
        m.put("backed", backed);
        m.put("rate", claims == 0 ? null : round3((double) backed / claims));
        m.put("weakClaims", weak);
        Map<String, Object> src = new LinkedHashMap<>();
        bySource.forEach((k, v) -> src.put(k, rate(v)));
        m.put("bySource", src);
        Map<String, Object> kinds = new LinkedHashMap<>();
        byKind.forEach((k, v) -> kinds.put(k, rate(v)));
        m.put("byKind", kinds);
        List<Map<String, Object>> top = new ArrayList<>();
        byKind.entrySet().stream().filter(e -> e.getValue()[0] > e.getValue()[1])
                .sorted((a, b) -> Long.compare(b.getValue()[0] - b.getValue()[1], a.getValue()[0] - a.getValue()[1]))
                .limit(5).forEach(e -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("kind", e.getKey());
                    t.put("unbacked", e.getValue()[0] - e.getValue()[1]);
                    t.put("of", e.getValue()[0]);
                    t.put("examples", unbackedExamples.getOrDefault(e.getKey(), List.of()));
                    top.add(t);
                });
        m.put("topUnbacked", top);
        return m;
    }

    private static Map<String, Object> rate(long[] v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claims", v[0]);
        m.put("backed", v[1]);
        m.put("rate", v[0] == 0 ? null : round3((double) v[1] / v[0]));
        return m;
    }

    // ── the report ──

    /**
     * Scores the lines. {@code judge} may be null or unavailable (relevance then null); at most {@code maxCalls}
     * judge calls. Pure apart from the judge.
     */
    static Map<String, Object> report(List<Line> lines, List<Map<String, Object>> sayDoRows, LlmJudge judge, int maxCalls,
                                      long fromMs, long toMs) throws InterruptedException {
        int n = lines.size();
        // what each line answers: the player's line, or for a thread line the thread line before it on that map
        String[] answers = new String[n];
        Map<Integer, Line> lastThreadOnMap = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Line l = lines.get(i);
            if (l.thread()) {
                Line prev = lastThreadOnMap.get(l.map());
                answers[i] = prev != null && l.at() - prev.at() <= 120_000 && !prev.bot().equals(l.bot()) ? prev.line() : null;
                lastThreadOnMap.put(l.map(), l);
            } else {
                answers[i] = l.asked();
            }
        }
        // relevance (and answered questions) by the judge, batched
        Boolean[] relevant = new Boolean[n];
        Boolean[] answered = new Boolean[n];
        int calls = 0;
        String judgeName = judge == null ? "none" : judge.describe();
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (answers[i] != null && !answers[i].isBlank()) {
                todo.add(i);
            }
        }
        // spread the sample over the day when there are more pairs than the calls can grade
        int capacity = Math.max(0, maxCalls) * LlmJudge.BATCH;
        if (todo.size() > capacity && capacity > 0) {
            List<Integer> spread = new ArrayList<>();
            double step = (double) todo.size() / capacity;
            for (int k = 0; k < capacity; k++) {
                spread.add(todo.get((int) (k * step)));
            }
            todo = spread;
        }
        if (judge != null && judge.available()) {
            for (int start = 0; start < todo.size() && calls < maxCalls; start += LlmJudge.BATCH) {
                List<Integer> batch = todo.subList(start, Math.min(todo.size(), start + LlmJudge.BATCH));
                List<LlmJudge.Pair> pairs = new ArrayList<>();
                for (int i : batch) {
                    pairs.add(new LlmJudge.Pair(answers[i], lines.get(i).line()));
                }
                List<LlmJudge.Relevance> got = judge.relevance(pairs);
                calls++;
                for (int k = 0; k < batch.size(); k++) {
                    LlmJudge.Relevance r = got.get(k);
                    if (r.decided()) {
                        relevant[batch.get(k)] = r.relevant();
                        answered[batch.get(k)] = r.answers();
                    }
                }
            }
        }
        boolean[] rep = repeats(lines);
        int judged = 0, onTopic = 0, stiff = 0, repeated = 0, questions = 0, unanswered = 0;
        Map<String, int[]> stiffByVoice = new TreeMap<>();
        List<Map<String, Object>> scored = new ArrayList<>();
        Map<String, Integer> repeatCounts = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Line l = lines.get(i);
            List<String> flags = new ArrayList<>();
            int bad = 0;
            if (relevant[i] != null) {
                judged++;
                if (relevant[i]) {
                    onTopic++;
                } else {
                    flags.add("off topic");
                    bad += 3;
                }
            }
            List<String> sf = stiffFlags(l.line(), l.voice());
            int[] v = stiffByVoice.computeIfAbsent(l.voice() == null ? "?" : l.voice(), k -> new int[2]);
            v[0]++;
            if (!sf.isEmpty()) {
                stiff++;
                v[1]++;
                flags.addAll(sf);
                bad += 2 * sf.size();
            }
            if (rep[i]) {
                repeated++;
                flags.add("repeat");
                bad += 1;
            }
            repeatCounts.merge(norm(l.line()), 1, Integer::sum);
            if (!l.thread() && isQuestion(l.asked())) {
                questions++;
                boolean dodged = answered[i] != null ? !answered[i] : relevant[i] == null && dodges(l.asked(), l.line());
                if (dodged) {
                    unanswered++;
                    flags.add("question unanswered");
                    bad += 2;
                }
            }
            if (!flags.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("bad", bad);
                m.put("at", Instant.ofEpochMilli(l.at()).toString());
                m.put("bot", l.bot());
                m.put("voice", l.voice());
                m.put("source", l.source());
                m.put("player", l.player());
                m.put("asked", answers[i]);
                m.put("line", l.line());
                m.put("flags", flags);
                scored.add(m);
            }
        }
        // the say-do misses are worst lines too
        for (Map<String, Object> r : sayDoRows) {
            if (Boolean.TRUE.equals(r.get("weak")) || Boolean.TRUE.equals(r.get("backed"))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bad", 3);
            m.put("at", r.get("at") instanceof Number t ? Instant.ofEpochMilli(t.longValue()).toString() : null);
            m.put("bot", r.get("bot"));
            m.put("source", r.get("source"));
            m.put("player", r.get("target"));
            m.put("line", r.get("line"));
            m.put("flags", List.of("said " + r.get("kind") + " but did not (" + r.get("why") + ")"));
            scored.add(m);
        }
        scored.sort((a, b) -> Integer.compare((Integer) b.get("bad"), (Integer) a.get("bad")));
        List<Map<String, Object>> top = new ArrayList<>(scored.subList(0, Math.min(WORST, scored.size())));
        for (Map<String, Object> m : top) {
            m.remove("bad");
        }
        List<Map<String, Object>> mostRepeated = new ArrayList<>();
        repeatCounts.entrySet().stream().filter(e -> e.getValue() > 1).sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5).forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("line", e.getKey());
                    m.put("times", e.getValue());
                    mostRepeated.add(m);
                });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", Instant.ofEpochMilli(fromMs).toString());
        out.put("to", Instant.ofEpochMilli(toMs).toString());
        out.put("lines", n);
        out.put("replies", lines.stream().filter(l -> !l.thread()).count());
        out.put("threadLines", lines.stream().filter(Line::thread).count());
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("score", judged == 0 ? null : round3((double) onTopic / judged));
        rel.put("judged", judged);
        rel.put("onTopic", onTopic);
        rel.put("judgeCalls", calls);
        rel.put("judge", judgeName);
        out.put("relevance", rel);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("score", n == 0 ? null : round3((double) stiff / n));
        st.put("stiff", stiff);
        Map<String, Object> byVoice = new LinkedHashMap<>();
        stiffByVoice.forEach((k, v) -> byVoice.put(k, v[1] + "/" + v[0]));
        st.put("byVoice", byVoice);
        out.put("stiffness", st);
        Map<String, Object> re = new LinkedHashMap<>();
        re.put("score", n == 0 ? null : round3((double) repeated / n));
        re.put("repeats", repeated);
        re.put("mostRepeated", mostRepeated);
        out.put("repetition", re);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("questions", questions);
        q.put("unanswered", unanswered);
        q.put("score", questions == 0 ? null : round3((double) unanswered / questions));
        out.put("unanswered", q);
        out.put("sayDo", sayDo(sayDoRows));
        out.put("worst", top);
        out.put("headline", headline(out));
        return out;
    }

    /** One line for test-status and {@code !autoqa report}. */
    @SuppressWarnings("unchecked")
    static String headline(Map<String, Object> r) {
        Map<String, Object> rel = (Map<String, Object>) r.get("relevance");
        Map<String, Object> st = (Map<String, Object>) r.get("stiffness");
        Map<String, Object> re = (Map<String, Object>) r.get("repetition");
        Map<String, Object> q = (Map<String, Object>) r.get("unanswered");
        Map<String, Object> sd = (Map<String, Object>) r.get("sayDo");
        return "chat 24h: " + r.get("lines") + " lines; relevance " + pct(rel.get("score")) + " (" + rel.get("judged")
                + " judged); stiff " + pct(st.get("score")) + "; repeats " + pct(re.get("score")) + "; unanswered "
                + q.get("unanswered") + "/" + q.get("questions") + " questions; say-do " + pct(sd.get("rate")) + " backed ("
                + sd.get("claims") + " claims)";
    }

    private static String pct(Object o) {
        return o instanceof Number d ? Math.round(d.doubleValue() * 100) + "%" : "n/a";
    }

    static double round3(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    /** What is wrong against the thresholds (empty: nothing). */
    @SuppressWarnings("unchecked")
    static List<String> problems(Map<String, Object> r) {
        List<String> out = new ArrayList<>();
        Map<String, Object> rel = (Map<String, Object>) r.get("relevance");
        if (rel.get("score") instanceof Number s && ((Number) rel.get("judged")).intValue() >= MIN_JUDGED
                && s.doubleValue() < RELEVANCE_MIN) {
            out.add("relevance " + pct(s) + " < " + pct(RELEVANCE_MIN));
        }
        Map<String, Object> st = (Map<String, Object>) r.get("stiffness");
        if (st.get("score") instanceof Number s && ((Number) r.get("lines")).intValue() >= MIN_LINES
                && s.doubleValue() > STIFFNESS_MAX) {
            out.add("stiffness " + pct(s) + " > " + pct(STIFFNESS_MAX));
        }
        Map<String, Object> sd = (Map<String, Object>) r.get("sayDo");
        if (sd.get("rate") instanceof Number s && ((Number) sd.get("claims")).intValue() >= MIN_CLAIMS
                && s.doubleValue() < SAYDO_MIN) {
            out.add("say-do " + pct(s) + " backed < " + pct(SAYDO_MIN));
        }
        return out;
    }

    // ── on the server ──

    private static volatile Map<String, Object> last;

    /** The last summary written (or null). */
    public static Map<String, Object> last() {
        return last;
    }

    /** Scores the last 24 h now and writes {@code chat-quality-<UTC date>.json}; returns the summary. */
    public static synchronized Map<String, Object> runNow() {
        long now = System.currentTimeMillis();
        long from = now - TimeUnit.HOURS.toMillis(24);
        Path transcripts = ConvoQuality.transcriptFile(LocalDate.now()).getParent();
        List<Line> lines = load(transcripts == null ? Path.of("logs", "convo") : transcripts, from, now);
        List<Map<String, Object>> rows = loadSayDo(AutoQa.dataDir(), from, now);
        LlmJudge judge;
        try {
            judge = LlmJudge.fromConfig(YamlConfig.config.server);
        } catch (RuntimeException | LinkageError e) {
            judge = null;
        }
        Map<String, Object> r;
        try {
            r = report(lines, rows, judge, MAX_JUDGE_CALLS, from, now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return last;
        }
        r.put("generatedAt", Instant.ofEpochMilli(now).toString());
        r.put("sayDoSinceBoot", SayDo.stats());
        r.put("problems", problems(r));
        String name = "chat-quality-" + HealthSweeper.DAY.format(Instant.ofEpochMilli(now)) + ".json";
        try {
            AutoQa.writeAtomic(AutoQa.dataDir().resolve(name), Json.write(r));
        } catch (IOException e) {
            AutoQa.log.warn("Auto-QA could not write {}: {}", name, e.toString());
        }
        r.put("file", name);
        last = r;
        AutoQa.log.info("Auto-QA {}", r.get("headline"));
        return r;
    }

    /** The summary as it goes into latest.json: the headline, the scores, the problems, the file. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> brief(Map<String, Object> r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headline", r.get("headline"));
        m.put("relevance", ((Map<String, Object>) r.get("relevance")).get("score"));
        m.put("stiffness", ((Map<String, Object>) r.get("stiffness")).get("score"));
        m.put("repetition", ((Map<String, Object>) r.get("repetition")).get("score"));
        m.put("unanswered", ((Map<String, Object>) r.get("unanswered")).get("unanswered"));
        m.put("sayDo", ((Map<String, Object>) r.get("sayDo")).get("rate"));
        m.put("problems", r.get("problems"));
        m.put("file", r.get("file"));
        m.put("generatedAt", r.get("generatedAt"));
        return m;
    }

    private static boolean scheduled;

    /** Once a day at {@code hourUtc}:30 (the hour before the nightly run). */
    static synchronized void scheduleDaily(int hourUtc) {
        if (scheduled) {
            return;
        }
        scheduled = true;
        long first = AutoQaService.msUntilNext(Math.floorMod(hourUtc, 24), System.currentTimeMillis()) + 30 * 60_000L;
        soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
            try {
                runNow();
            } catch (RuntimeException e) {
                AutoQa.log.warn("Auto-QA chat quality failed", e);
            }
        }, first, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
    }

    /** The t-cq-1 expectation: a fresh summary; FAIL over the thresholds, the worst lines as evidence. */
    @SuppressWarnings("unchecked")
    static Check.Expect expectation() {
        return Expects.of("chat quality: relevance >= " + pct(RELEVANCE_MIN) + ", stiffness <= " + pct(STIFFNESS_MAX)
                + ", say-do >= " + pct(SAYDO_MIN), r -> {
            Map<String, Object> q = r.world.chatQuality();
            if (q == null) {
                return Check.Verdict.skip("no chat-quality summary here");
            }
            List<String> ev = new ArrayList<>();
            ev.add(String.valueOf(q.get("headline")));
            List<String> bad = problems(q);
            if (((Map<String, Object>) q.get("relevance")).get("score") == null) {
                ev.add("relevance not judged (" + ((Map<String, Object>) q.get("relevance")).get("judge") + ")");
            }
            for (Object o : (List<Object>) q.getOrDefault("worst", List.of())) {
                if (ev.size() >= 12) {
                    break;
                }
                Map<String, Object> w = (Map<String, Object>) o;
                ev.add("  " + w.get("bot") + ": \"" + w.get("line") + "\" " + w.get("flags"));
            }
            if (bad.isEmpty()) {
                return new Check.Verdict(Check.Status.PASS, ev);
            }
            ev.add(0, String.join("; ", bad));
            return new Check.Verdict(Check.Status.FAIL, ev);
        });
    }
}
