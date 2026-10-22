package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ticket 59, owner decision 6: the daily transcript sample with automatic scores. Reads one day's transcript
 * ({@code logs/convo/transcript-<date>.jsonl}, written by {@link ConvoQuality}), samples up to {@link #SAMPLE} lines,
 * scores each 0-1 on four axes, and writes {@code data/convo/quality/<date>.json} and {@code latest.json} - the data
 * for the owner's quality page. Cheap rules, no LLM: the page shows where to look, the ratings say what is right.
 *
 * <ul>
 *   <li><b>naturalness:</b> short like chat, not an echo of the player, not a repeat, no assistant words, one thought;</li>
 *   <li><b>inCharacter:</b> the casing and length of the bot's own voice, and the server's voice check passes;</li>
 *   <li><b>neverAi:</b> no "I'm an AI / a bot / a program", no model or prompt talk;</li>
 *   <li><b>contentGuard:</b> PG-13 and game-only insults ({@link ContentGuard}).</li>
 * </ul>
 * Run by the server at {@code solomapling.convo.scoreHour} (4) for the day before, by {@code !rate score [date]}, or
 * offline: {@code java -cp Cosmic.jar soloMapling.ArtificialPlayer.BotConvo.ConvoDailyScore <transcript> <out.json>}.
 */
public final class ConvoDailyScore {

    static final int SAMPLE = 60;

    private static final Pattern AI_REVEAL = Pattern.compile("(?i)(\\bi'?m (an? )?(ai|bot|robot|program|npc|language model)"
            + "\\b|\\bi am (an? )?(ai|bot|robot|program)\\b|as an ai|language model|chat ?gpt|\\bllm\\b|my (prompt|instructions"
            + "|programming)|i'?m not (a )?real|artificial intelligence)");
    private static final Pattern ASSISTANT = Pattern.compile("(?i)\\b(certainly|however|additionally|feel free|happy to"
            + " help|let me know|i'?d be glad|great question|absolutely)\\b");

    /** One scored line. */
    public record Scored(Map<String, Object> entry, double naturalness, double inCharacter, double neverAi,
                         double contentGuard) {
        double overall() {
            return (naturalness + inCharacter + neverAi + contentGuard) / 4;
        }
    }

    private ConvoDailyScore() {
    }

    // ── scoring ──

    static Set<String> words(String s) {
        Set<String> w = new HashSet<>();
        for (String x : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (!x.isBlank()) {
                w.add(x);
            }
        }
        return w;
    }

    static double naturalness(String line, String asked, Set<String> seenElsewhere) {
        double s = 1;
        if (line.length() > 60) {
            s -= 0.3;
        }
        if (asked != null && !asked.isBlank()) {
            Set<String> a = words(asked);
            Set<String> b = words(line);
            Set<String> inter = new HashSet<>(a);
            inter.retainAll(b);
            Set<String> union = new HashSet<>(a);
            union.addAll(b);
            if (!union.isEmpty() && b.size() >= 3 && (double) inter.size() / union.size() >= 0.6) {
                s -= 0.3; // parrots the player
            }
        }
        if (seenElsewhere.contains(line.toLowerCase(Locale.ROOT))) {
            s -= 0.2;
        }
        if (ASSISTANT.matcher(line).find()) {
            s -= 0.3;
        }
        if (line.split("[.!?]+\\s").length > 2 || line.chars().filter(c -> c == '!').count() > 2) {
            s -= 0.2;
        }
        return Math.max(0, s);
    }

    static double inCharacter(String line, String voice) {
        if (soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard.chatProblem(line) != null
                && !"ai talk".equals(ContentGuard.chatProblem(line))) {
            return 0;
        }
        ConvoPersona.Voice v = ConvoPersona.Voice.parse(voice);
        if (v == null) {
            return 1;
        }
        long letters = line.chars().filter(java.lang.Character::isLetter).count();
        long upper = line.chars().filter(java.lang.Character::isUpperCase).count();
        double upperShare = letters == 0 ? 0 : (double) upper / letters;
        return switch (v) {
            case LOWER, KOREAN, SPANISH -> upperShare <= 0.15 ? 1 : 0.5;
            case PROPER -> !line.isEmpty() && java.lang.Character.isUpperCase(line.charAt(0)) ? 1 : 0.6;
            case TERSE -> words(line).size() <= 7 ? 1 : 0.6;
            case LEET -> 1;
        };
    }

    static double neverAi(String line) {
        return AI_REVEAL.matcher(line).find() ? 0 : 1;
    }

    static double contentGuard(String line) {
        return ContentGuard.problem(line) == null ? 1 : 0;
    }

    /** Scores entries ({@code line}, {@code asked}, {@code voice} keys). */
    static List<Scored> score(List<Map<String, Object>> entries) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> e : entries) {
            counts.merge(String.valueOf(e.get("line")).toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        Set<String> repeated = new HashSet<>();
        counts.forEach((k, v) -> {
            if (v > 1) {
                repeated.add(k);
            }
        });
        List<Scored> out = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            String line = String.valueOf(e.get("line"));
            String asked = e.get("asked") instanceof String a ? a : null;
            String voice = e.get("voice") instanceof String v ? v : null;
            out.add(new Scored(e, naturalness(line, asked, repeated), inCharacter(line, voice), neverAi(line),
                    contentGuard(line)));
        }
        return out;
    }

    // ── the day's file ──

    /** Reads a JSONL transcript; bad rows are skipped. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> read(Path transcript) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isRegularFile(transcript)) {
            return out;
        }
        for (String row : Files.readAllLines(transcript, StandardCharsets.UTF_8)) {
            try {
                if (Json.parse(row) instanceof Map<?, ?> m && m.get("line") instanceof String) {
                    out.add((Map<String, Object>) m);
                }
            } catch (RuntimeException ignored) {
                // a torn line
            }
        }
        return out;
    }

    /** The page data for one day's entries, sampled (seeded by the day) and scored. */
    static Map<String, Object> page(String day, List<Map<String, Object>> all, List<Map<String, Object>> ratings, long now) {
        List<Map<String, Object>> sample = new ArrayList<>(all);
        Collections.shuffle(sample, new Random(day.hashCode()));
        if (sample.size() > SAMPLE) {
            sample = sample.subList(0, SAMPLE);
        }
        List<Scored> scored = score(sample);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("day", day);
        page.put("generatedAt", now);
        page.put("lines", all.size());
        page.put("sampled", scored.size());
        page.put("byTier", countBy(all, "tier"));
        page.put("bySource", countBy(all, "source"));
        page.put("byVoice", countBy(all, "voice"));
        page.put("averages", averages(scored));
        page.put("averagesByVoice", averagesBy(scored, "voice"));
        page.put("averagesBySource", averagesBy(scored, "source"));
        Map<String, Object> r = new LinkedHashMap<>();
        long good = ratings.stream().filter(x -> "good".equals(x.get("rating"))).count();
        r.put("good", good);
        r.put("bad", ratings.size() - good);
        r.put("items", ratings);
        page.put("ratings", r);
        List<Scored> worst = new ArrayList<>(scored);
        worst.sort(Comparator.comparingDouble(Scored::overall));
        page.put("worst", rows(worst.subList(0, Math.min(10, worst.size()))));
        page.put("samples", rows(scored));
        return page;
    }

    private static Map<String, Long> countBy(List<Map<String, Object>> all, String key) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> e : all) {
            out.merge(String.valueOf(e.getOrDefault(key, "?")), 1L, Long::sum);
        }
        return out;
    }

    private static Map<String, Object> averages(List<Scored> s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("naturalness", round(s.stream().mapToDouble(Scored::naturalness).average().orElse(0)));
        m.put("inCharacter", round(s.stream().mapToDouble(Scored::inCharacter).average().orElse(0)));
        m.put("neverAi", round(s.stream().mapToDouble(Scored::neverAi).average().orElse(0)));
        m.put("contentGuard", round(s.stream().mapToDouble(Scored::contentGuard).average().orElse(0)));
        m.put("overall", round(s.stream().mapToDouble(Scored::overall).average().orElse(0)));
        return m;
    }

    private static Map<String, Object> averagesBy(List<Scored> s, String key) {
        Map<String, List<Scored>> groups = new LinkedHashMap<>();
        for (Scored x : s) {
            groups.computeIfAbsent(String.valueOf(x.entry().getOrDefault(key, "?")), k -> new ArrayList<>()).add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        groups.forEach((k, v) -> out.put(k, averages(v)));
        return out;
    }

    private static List<Object> rows(List<Scored> s) {
        List<Object> out = new ArrayList<>();
        for (Scored x : s) {
            Map<String, Object> m = new LinkedHashMap<>(x.entry());
            m.put("naturalness", round(x.naturalness()));
            m.put("inCharacter", round(x.inCharacter()));
            m.put("neverAi", round(x.neverAi()));
            m.put("contentGuard", round(x.contentGuard()));
            m.put("overall", round(x.overall()));
            out.add(m);
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** Scores one day on the server: reads the transcript and the day's ratings, writes the day's JSON and latest. */
    public static Path runFor(LocalDate day) throws IOException {
        List<Map<String, Object>> all = read(ConvoQuality.transcriptFile(day));
        List<Map<String, Object>> ratings = new ArrayList<>();
        for (Map<String, Object> r : read(ConvoQuality.ratingsFile())) {
            if (String.valueOf(r.get("day")).equals(day.toString())) {
                ratings.add(r);
            }
        }
        Map<String, Object> page = page(day.toString(), all, ratings, System.currentTimeMillis());
        Path dir = Path.of(ConvoSwitches.str("dataDir", "data/convo"), "quality");
        Files.createDirectories(dir);
        Path out = dir.resolve(day + ".json");
        String json = Json.write(page);
        Files.writeString(out, json);
        Files.writeString(dir.resolve("latest.json"), json);
        return out;
    }

    /** Offline: {@code ConvoDailyScore <transcript.jsonl> <out.json>}. */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("usage: ConvoDailyScore <transcript.jsonl> <out.json>");
            return;
        }
        Path in = Path.of(args[0]);
        String day = in.getFileName().toString().replaceAll("[^0-9-]", "").replaceAll("^-+|-+$", "");
        Map<String, Object> page = page(day.isEmpty() ? "sample" : day, read(in), List.of(), System.currentTimeMillis());
        Files.writeString(Path.of(args[1]), Json.write(page));
        System.out.println("wrote " + args[1] + ": " + page.get("averages"));
    }
}
