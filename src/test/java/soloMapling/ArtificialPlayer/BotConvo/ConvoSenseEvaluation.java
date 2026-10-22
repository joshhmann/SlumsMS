package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.server.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ticket 59's GLiNER numbers, replayed offline: the labelled lines ({@code chat/convo-lines.tsv}) against the Decide
 * answers recorded by {@code tools/convo/decide_probe.py} ({@code chat/convo-decide-answers.json}). Used by
 * {@link ConvoSenseTest}; {@code main} prints the table for docs/tickets/59.
 */
public final class ConvoSenseEvaluation {

    record Line(String text, ConvoIntent intent, ConvoSense.Tone tone, String entities) {
    }

    /** Accuracy counts. {@code falseActions}: a talk line read as a request (it would act). */
    record Score(int n, int ruleIntent, int decideIntent, int combinedIntent, int ruleTone, int decideTone,
                 int combinedTone, int falseActions, int ruleCovered, List<String> misses) {
        double pct(int x) {
            return 100.0 * x / n;
        }
    }

    static final String TUNING = "/chat/convo-lines.tsv";
    static final String TUNING_ANSWERS = "/chat/convo-decide-answers.json";
    static final String HOLDOUT = "/chat/convo-holdout.tsv";
    static final String HOLDOUT_ANSWERS = "/chat/convo-holdout-answers.json";

    static List<Line> lines() throws IOException {
        return lines(TUNING);
    }

    static List<Line> lines(String file) throws IOException {
        List<Line> out = new ArrayList<>();
        for (String raw : read(file).split("\n")) {
            if (raw.isBlank() || raw.startsWith("#")) {
                continue;
            }
            String[] f = raw.split("\t");
            out.add(new Line(f[0], ConvoIntent.parse(f[1]), ConvoSense.Tone.valueOf(f[2].toUpperCase(Locale.ROOT)),
                    f.length > 3 ? f[3] : "-"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, ChatDecide.Answer>> answers(String file) throws IOException {
        Map<String, Object> root = (Map<String, Object>) Json.parse(read(file));
        Map<String, Map<String, ChatDecide.Answer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : root.entrySet()) {
            Map<String, Object> qs = (Map<String, Object>) e.getValue();
            Map<String, ChatDecide.Answer> a = new LinkedHashMap<>();
            put(a, qs, "flat", ConvoSense.INTENT.name());
            put(a, qs, "tone", ConvoSense.TONE.name());
            put(a, qs, "con", ChatTriage.CON.name());
            out.put(e.getKey(), a);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, ChatDecide.Answer> a, Map<String, Object> qs, String from, String to) {
        if (qs.get(from) instanceof Map<?, ?> m) {
            a.put(to, new ChatDecide.Answer(String.valueOf(m.get("choice")), ((Number) m.get("confidence")).doubleValue()));
        }
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = ConvoSenseEvaluation.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Score score(double intentMin, double toneMin) throws IOException {
        return score(TUNING, TUNING_ANSWERS, intentMin, toneMin);
    }

    static Score score(String set, String answerFile, double intentMin, double toneMin) throws IOException {
        List<Line> lines = lines(set);
        Map<String, Map<String, ChatDecide.Answer>> answers = answers(answerFile);
        int ri = 0, di = 0, ci = 0, rt = 0, dt = 0, ct = 0, fa = 0, cov = 0;
        List<String> misses = new ArrayList<>();
        for (Line l : lines) {
            Map<String, ChatDecide.Answer> a = answers.getOrDefault(l.text(), Map.of());
            ConvoSense.Read rules = ConvoSense.rules(l.text());
            ConvoSense.Read both = ConvoSense.combine(l.text(), ChatTriage.rules(l.text()), a, intentMin, toneMin, 0.4);
            ri += rules.intent() == l.intent() ? 1 : 0;
            cov += rules.intentSource() == ConvoSense.Source.RULE ? 1 : 0;
            ChatDecide.Answer flat = a.get(ConvoSense.INTENT.name());
            di += flat != null && ConvoIntent.parse(flat.choice()) == l.intent() ? 1 : 0;
            ci += both.intent() == l.intent() ? 1 : 0;
            if (both.intent() != l.intent()) {
                misses.add(l.text() + " -> " + both.intent().label() + "/" + both.intentSource().name().toLowerCase(Locale.ROOT)
                        + " (want " + l.intent().label() + ")");
            }
            if (l.intent() == ConvoIntent.TALK && both.intent() != ConvoIntent.TALK) {
                fa++;
            }
            rt += rules.tone() == l.tone() ? 1 : 0;
            ChatDecide.Answer tone = a.get(ConvoSense.TONE.name());
            dt += tone != null && tone.choice().equalsIgnoreCase(l.tone().name()) ? 1 : 0;
            ct += both.tone() == l.tone() ? 1 : 0;
        }
        return new Score(lines.size(), ri, di, ci, rt, dt, ct, fa, cov, misses);
    }

    public static void main(String[] args) throws IOException {
        for (String[] set : new String[][]{{TUNING, TUNING_ANSWERS}, {HOLDOUT, HOLDOUT_ANSWERS}})
        for (double[] mins : new double[][]{{0.3, 0.8}, {0.4, 0.8}, {0.5, 0.8}, {0.5, 0.6}, {0.6, 0.8}}) {
            double min = mins[0];
            Score s = score(set[0], set[1], min, mins[1]);
            System.out.printf(Locale.ROOT, "%s toneMin %.1f ", set[0], mins[1]);
            System.out.printf(Locale.ROOT, "intentMin %.1f: intent rules %.0f%% (cover %.0f%%) decide %.0f%% combined %.0f%%"
                            + " | tone rules %.0f%% decide %.0f%% combined %.0f%% | false actions %d%n", min,
                    s.pct(s.ruleIntent()), s.pct(s.ruleCovered()), s.pct(s.decideIntent()), s.pct(s.combinedIntent()),
                    s.pct(s.ruleTone()), s.pct(s.decideTone()), s.pct(s.combinedTone()), s.falseActions());
        }
        score(0.5, 0.8).misses().forEach(m -> System.out.println("  tuning miss " + m));
        score(HOLDOUT, HOLDOUT_ANSWERS, 0.5, 0.8).misses().forEach(m -> System.out.println("  holdout miss " + m));
    }
}
