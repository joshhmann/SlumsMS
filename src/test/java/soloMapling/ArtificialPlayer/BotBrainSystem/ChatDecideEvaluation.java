package soloMapling.ArtificialPlayer.BotBrainSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Ticket 36's evaluation: asks the real Decide endpoint the chat questions for every labelled line and prints the
 * accuracy of the model, the rules and the two combined, per question. A test-scope main, not a unit test (it needs
 * the network): one call per line (all questions in one request), two at a time.
 *
 * <pre>
 *   java -cp target/test-classes:target/classes:$(deps) \
 *       soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecideEvaluation http://192.168.0.17:8900
 * </pre>
 */
public final class ChatDecideEvaluation {

    private ChatDecideEvaluation() {
    }

    record Scored(ChatLabelledSet.Line line, Map<String, ChatDecide.Answer> answers, long ms) {
    }

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : System.getenv().getOrDefault("BOT_DECIDE_URL", "http://192.168.0.17:8900");
        ChatDecide decide = ChatDecide.forUrl(url, 5000, 1000);
        for (String file : List.of(ChatLabelledSet.TUNING, ChatLabelledSet.HOLDOUT)) {
            System.out.println("== " + file);
            System.out.println(run(decide, ChatLabelledSet.load(file)));
        }
    }

    static String run(ChatDecide decide, List<ChatLabelledSet.Line> set) throws Exception {
        List<ChatDecide.Question> questions = List.of(ChatTriage.TIER, ChatTriage.INTENT, ChatTriage.CON);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Scored>> futures = new ArrayList<>();
        for (ChatLabelledSet.Line line : set) {
            futures.add(pool.submit(() -> {
                long t = System.nanoTime();
                Optional<Map<String, ChatDecide.Answer>> a = decide.ask(line.text(), questions).get();
                return new Scored(line, a.orElse(Map.of()), (System.nanoTime() - t) / 1_000_000);
            }));
        }
        List<Scored> scored = new ArrayList<>();
        for (Future<Scored> f : futures) {
            scored.add(f.get());
        }
        pool.shutdown();
        return report(scored);
    }

    static String report(List<Scored> scored) {
        StringBuilder out = new StringBuilder();
        int n = scored.size();
        List<Long> ms = scored.stream().map(Scored::ms).sorted().toList();
        out.append(String.format(Locale.ROOT, "lines=%d  latency p50=%dms p90=%dms max=%dms (3 questions per call)%n", n,
                ms.get(n / 2), ms.get((int) (n * 0.9)), ms.get(n - 1)));
        out.append("| Question | Labels | Decide | Decide >= 0.5 (coverage) | Rules | Rules + Decide >= 0.5 |\n");
        out.append("|---|---|---|---|---|---|\n");

        // Tier: the model answers filler/reply; the rules answer the 4-way tier (and the binary falls out of it).
        int tierDecide = 0, tierRules = 0, tierRulesBinary = 0;
        for (Scored s : scored) {
            ChatDecide.Answer a = s.answers().get("tier");
            if (a != null && a.choice().equals(s.line().fillerOrReply())) {
                tierDecide++;
            }
            String rules = ChatLabelledSet.tierOf(ChatTriage.kind(s.line().text()));
            if (rules.equals(s.line().tier())) {
                tierRules++;
            }
            if ((rules.equals("llm") ? "reply" : "filler").equals(s.line().fillerOrReply())) {
                tierRulesBinary++;
            }
        }
        out.append(String.format(Locale.ROOT, "| tier (filler/reply) | 2 | %s | - | %s (4-way: %s) | - |%n",
                pct(tierDecide, n), pct(tierRulesBinary, n), pct(tierRules, n)));

        int[] intent = score(scored, "intent", s -> s.line().intent(),
                s -> ChatTriage.intentRule(s.line().text()).map(i -> i.name().toLowerCase(Locale.ROOT)).orElse("none"));
        out.append(String.format(Locale.ROOT, "| intent | 6 | %s | %s (%s) | %s | %s |%n", pct(intent[0], n),
                pct(intent[1], intent[2]), pct(intent[2], n), pct(intent[3], n), pct(intent[4], n)));
        int[] con = score(scored, "con", s -> s.line().con(),
                s -> ChatTriage.conRule(s.line().text()).map(c -> c.name().toLowerCase(Locale.ROOT)).orElse("normal"));
        out.append(String.format(Locale.ROOT, "| con | 5 | %s | %s (%s) | %s | %s |%n", pct(con[0], n),
                pct(con[1], con[2]), pct(con[2], n), pct(con[3], n), pct(con[4], n)));

        // The con flag as a detector: false flags on normal lines are what hurts a real player.
        out.append('\n').append(flagStats(scored, "decide", s -> decideCon(s, 0.0)))
                .append(flagStats(scored, "decide>=0.5", s -> decideCon(s, 0.5)))
                .append(flagStats(scored, "rules", s -> ruleCon(s)))
                .append(flagStats(scored, "rules+decide>=0.5", s -> {
                    String r = ruleCon(s);
                    return r.equals("normal") ? decideCon(s, 0.5) : r;
                }));

        out.append("\nThreshold sweep (rules first, then Decide at or above the threshold):\n");
        for (double t : new double[]{0.0, 0.3, 0.35, 0.4, 0.45, 0.5, 0.6}) {
            int intentRight = 0;
            for (Scored s : scored) {
                String r = ChatTriage.intentRule(s.line().text()).map(i -> i.name().toLowerCase(Locale.ROOT)).orElse(null);
                ChatDecide.Answer a = s.answers().get("intent");
                String got = r != null ? r : a != null && a.confidence() >= t ? a.choice() : "none";
                if (got.equals(s.line().intent())) {
                    intentRight++;
                }
            }
            out.append(String.format(Locale.ROOT, "  t=%.2f intent %s  ", t, pct(intentRight, scored.size())))
                    .append(flagStats(scored, "", s -> {
                        String r = ruleCon(s);
                        return r.equals("normal") ? decideCon(s, t) : r;
                    }));
        }

        out.append("\nMisses of the combined intent / con:\n");
        for (Scored s : scored) {
            String ci = combined(s, "intent", ChatTriage.intentRule(s.line().text())
                    .map(i -> i.name().toLowerCase(Locale.ROOT)).orElse(null), "none");
            String cc = combined(s, "con", ChatTriage.conRule(s.line().text())
                    .map(c -> c.name().toLowerCase(Locale.ROOT)).orElse(null), "normal");
            if (!ci.equals(s.line().intent()) || !cc.equals(s.line().con())) {
                out.append(String.format(Locale.ROOT, "  %-45s intent %s->%s  con %s->%s  (decide %s / %s)%n",
                        s.line().text(), s.line().intent(), ci, s.line().con(), cc, show(s.answers().get("intent")),
                        show(s.answers().get("con"))));
            }
        }
        return out.toString();
    }

    private static String show(ChatDecide.Answer a) {
        return a == null ? "-" : String.format(Locale.ROOT, "%s %.2f", a.choice(), a.confidence());
    }

    private static String decideCon(Scored s, double min) {
        ChatDecide.Answer a = s.answers().get("con");
        return a == null || a.confidence() < min ? "normal" : a.choice();
    }

    private static String ruleCon(Scored s) {
        return ChatTriage.conRule(s.line().text()).map(c -> c.name().toLowerCase(Locale.ROOT)).orElse("normal");
    }

    private static String flagStats(List<Scored> scored, String name, java.util.function.Function<Scored, String> pick) {
        int tp = 0, fp = 0, fn = 0, kindRight = 0;
        for (Scored s : scored) {
            boolean truth = !s.line().con().equals("normal");
            String got = pick.apply(s);
            boolean flagged = !got.equals("normal");
            if (flagged && truth) {
                tp++;
                if (got.equals(s.line().con())) {
                    kindRight++;
                }
            } else if (flagged) {
                fp++;
            } else if (truth) {
                fn++;
            }
        }
        return String.format(Locale.ROOT, "con flag %-18s precision %s  recall %s  false flags on normal lines %d  "
                + "right kind %d/%d%n", name, pct(tp, tp + fp), pct(tp, tp + fn), fp, kindRight, tp);
    }

    private static String combined(Scored s, String question, String rule, String fallback) {
        if (rule != null) {
            return rule;
        }
        ChatDecide.Answer a = s.answers().get(question);
        return a != null && a.confidence() >= 0.5 ? a.choice() : fallback;
    }

    /** {decide right, decide right when >= 0.5, count >= 0.5, rules right, combined right}. */
    private static int[] score(List<Scored> scored, String question, java.util.function.Function<Scored, String> truth,
                               java.util.function.Function<Scored, String> rules) {
        int[] r = new int[5];
        String fallback = question.equals("intent") ? "none" : "normal";
        for (Scored s : scored) {
            String want = truth.apply(s);
            ChatDecide.Answer a = s.answers().get(question);
            if (a != null && a.choice().equals(want)) {
                r[0]++;
            }
            if (a != null && a.confidence() >= 0.5) {
                r[2]++;
                if (a.choice().equals(want)) {
                    r[1]++;
                }
            }
            String byRule = rules.apply(s);
            if (byRule.equals(want)) {
                r[3]++;
            }
            String comb = !byRule.equals(fallback) ? byRule : a != null && a.confidence() >= 0.5 ? a.choice() : fallback;
            if (comb.equals(want)) {
                r[4]++;
            }
        }
        return r;
    }

    private static String pct(int k, int n) {
        return n == 0 ? "-" : String.format(Locale.ROOT, "%d/%d = %.0f%%", k, n, 100.0 * k / n);
    }
}
