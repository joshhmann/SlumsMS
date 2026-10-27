package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The scorecard for the {@link DecideShadow} log: per model, how often it agrees with the current model (all of
 * intent/tone/con, and intent alone, both as the bot would act on them), its latency p50/p95, errors, timeouts and
 * late answers (over the live deadline), and the lines where it disagrees, for review.
 *
 * <p>In game: {@code !decideshadow status [day]}. Offline:
 * {@code java -cp target/Cosmic.jar soloMapling.ArtificialPlayer.BotConvo.DecideShadowReport [--json] [--disagreements N] <file or dir>...}
 * (or {@code tools/convo/decide-shadow summary}).
 */
public final class DecideShadowReport {

    private DecideShadowReport() {
    }

    /** One model's numbers. {@code agreePct} etc. are over lines it answered or not (every row it appears in). */
    public record Stats(String model, int lines, int answered, int agree, int agreeIntent, int errors, int timeouts,
                        int late, long p50, long p95) {

        public double agreePct() {
            return pct(agree, lines);
        }

        public double agreeIntentPct() {
            return pct(agreeIntent, lines);
        }

        public String line() {
            return String.format(Locale.ROOT, "%s: %d lines, agree %.1f%% (intent %.1f%%), p50 %d ms p95 %d ms, "
                    + "errors %d, timeouts %d, late %d", model, lines, agreePct(), agreeIntentPct(), p50, p95, errors,
                    timeouts, late);
        }
    }

    /** One line a shadow model reads differently. */
    public record Disagreement(String model, String time, String text, String current, String shadow) {
        public String line() {
            return model + ": \"" + text + "\" current " + current + " | " + model + " " + shadow;
        }
    }

    static double pct(int part, int whole) {
        return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 10.0;
    }

    /** Nearest-rank percentile (p in 0-100) of the values; 0 when empty. */
    static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    /** Per model, in first-seen order; the current model first, under its own name (agreement 100% by definition). */
    public static List<Stats> summarize(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> byModel = new LinkedHashMap<>();
        List<Long> currentMs = new ArrayList<>();
        int currentErrors = 0;
        String currentName = "current";
        for (Map<String, Object> row : rows) {
            if (row.get("current") instanceof Map<?, ?> c) {
                currentName = String.valueOf(c.get("model") == null ? currentName : c.get("model"));
                if (Boolean.TRUE.equals(c.get("ok"))) {
                    currentMs.add(num(c.get("ms")));
                } else {
                    currentErrors++;
                }
            }
            if (row.get("shadows") instanceof Map<?, ?> shadows) {
                for (Map.Entry<?, ?> e : shadows.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> s) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sm = (Map<String, Object>) s;
                        byModel.computeIfAbsent(String.valueOf(e.getKey()), k -> new ArrayList<>()).add(sm);
                    }
                }
            }
        }
        List<Stats> out = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> row : rows) {
            if (row.get("current") instanceof Map<?, ?>) {
                n++;
            }
        }
        out.add(new Stats(currentName + " (current)", n, currentMs.size(), n, n, currentErrors, 0, 0,
                percentile(currentMs, 50), percentile(currentMs, 95)));
        for (Map.Entry<String, List<Map<String, Object>>> e : byModel.entrySet()) {
            int answered = 0, agree = 0, agreeIntent = 0, errors = 0, timeouts = 0, late = 0;
            List<Long> ms = new ArrayList<>();
            for (Map<String, Object> s : e.getValue()) {
                boolean ok = Boolean.TRUE.equals(s.get("ok"));
                if (ok) {
                    answered++;
                    ms.add(num(s.get("ms")));
                } else if (Boolean.TRUE.equals(s.get("timeout"))) {
                    timeouts++;
                } else {
                    errors++; // transport or schema trouble, not a timeout
                }
                if (Boolean.TRUE.equals(s.get("late"))) {
                    late++;
                }
                if (Boolean.TRUE.equals(s.get("agree"))) {
                    agree++;
                }
                if (Boolean.TRUE.equals(s.get("agreeIntent"))) {
                    agreeIntent++;
                }
            }
            out.add(new Stats(e.getKey(), e.getValue().size(), answered, agree, agreeIntent, errors, timeouts, late,
                    percentile(ms, 50), percentile(ms, 95)));
        }
        return out;
    }

    /** Every line a model reads differently from the current one (optionally one model), oldest first. */
    public static List<Disagreement> disagreements(List<Map<String, Object>> rows, String onlyModel) {
        List<Disagreement> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!(row.get("shadows") instanceof Map<?, ?> shadows) || !(row.get("current") instanceof Map<?, ?> c)) {
                continue;
            }
            for (Map.Entry<?, ?> e : shadows.entrySet()) {
                String model = String.valueOf(e.getKey());
                if (onlyModel != null && !onlyModel.equalsIgnoreCase(model)) {
                    continue;
                }
                if (e.getValue() instanceof Map<?, ?> s && Boolean.FALSE.equals(s.get("agree"))) {
                    out.add(new Disagreement(model, String.valueOf(row.get("time")), String.valueOf(row.get("text")),
                            brief(c), brief(s)));
                }
            }
        }
        return out;
    }

    private static String brief(Map<?, ?> m) {
        return m.get("intent") + "/" + m.get("tone") + "/" + m.get("con")
                + (m.get("raw") instanceof Map<?, ?> raw && !raw.isEmpty() ? " " + raw.values() : "")
                + (m.get("error") != null ? " (" + m.get("error") + ")" : "");
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    /** The summary as text lines (the GM command and the CLI). */
    public static List<String> lines(List<Map<String, Object>> rows, int maxDisagreements) {
        List<String> out = new ArrayList<>();
        out.add("Decide shadow: " + rows.size() + " lines");
        for (Stats s : summarize(rows)) {
            out.add(s.line());
        }
        List<Disagreement> d = disagreements(rows, null);
        if (maxDisagreements > 0 && !d.isEmpty()) {
            out.add("Disagreements (" + d.size() + ", latest " + Math.min(maxDisagreements, d.size()) + "):");
            for (Disagreement x : d.subList(Math.max(0, d.size() - maxDisagreements), d.size())) {
                out.add(x.line());
            }
        }
        return out;
    }

    /** The summary as JSON-ready data. */
    public static Map<String, Object> json(List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lines", rows.size());
        List<Object> models = new ArrayList<>();
        for (Stats s : summarize(rows)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("model", s.model());
            x.put("lines", s.lines());
            x.put("answered", s.answered());
            x.put("agreePct", s.agreePct());
            x.put("agreeIntentPct", s.agreeIntentPct());
            x.put("p50Ms", s.p50());
            x.put("p95Ms", s.p95());
            x.put("errors", s.errors());
            x.put("timeouts", s.timeouts());
            x.put("late", s.late());
            models.add(x);
        }
        m.put("models", models);
        List<Object> dis = new ArrayList<>();
        for (Disagreement d : disagreements(rows, null)) {
            dis.add(Map.of("model", d.model(), "time", d.time(), "text", d.text(), "current", d.current(),
                    "shadow", d.shadow()));
        }
        m.put("disagreements", dis);
        return m;
    }

    /** Rows from JSONL files or directories of them (unreadable lines skipped). */
    public static List<Map<String, Object>> load(List<Path> paths) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Path p : paths) {
            List<Path> files = new ArrayList<>();
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(f -> f.getFileName().toString().endsWith(".jsonl")).sorted().forEach(files::add);
                }
            } else if (Files.exists(p)) {
                files.add(p);
            }
            for (Path f : files) {
                for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (l.isBlank()) {
                        continue;
                    }
                    try {
                        if (Json.parse(l) instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> row = (Map<String, Object>) m;
                            rows.add(row);
                        }
                    } catch (IllegalArgumentException e) {
                        // a half-written last line: skip
                    }
                }
            }
        }
        return rows;
    }

    public static void main(String[] args) throws IOException {
        boolean json = false;
        int max = 50;
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--json" -> json = true;
                case "--disagreements" -> max = Integer.parseInt(args[++i]);
                default -> paths.add(Path.of(args[i]));
            }
        }
        if (paths.isEmpty()) {
            paths.add(DecideShadow.dir(soloMapling.autoqa.AutoQa.dataDir()));
        }
        List<Map<String, Object>> rows = load(paths);
        if (json) {
            System.out.println(Json.write(json(rows)));
        } else {
            lines(rows, max).forEach(System.out::println);
        }
    }
}
