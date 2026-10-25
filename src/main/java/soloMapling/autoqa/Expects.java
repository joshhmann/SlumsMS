package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static soloMapling.autoqa.Check.Expect;
import static soloMapling.autoqa.Check.Status;
import static soloMapling.autoqa.Check.Verdict;

/**
 * What must be true at the end of a check. Patterns match a line as {@code "KIND message"} (see {@link AutoQaLine}),
 * so {@code "RIVALRY ROAST facts for"} is a hook line and {@code "WHISPER "} something the player's client got.
 * Evidence is the matching lines (at most a few), or the reason nothing matched.
 */
public final class Expects {

    static final int EVIDENCE = 4;
    /** Emoji and other pictographs: bot chat must never carry them. */
    static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F900}-\\x{1F9FF}]");

    private Expects() {
    }

    static Expect of(String describe, ExpectBody body) {
        return new Expect() {
            @Override
            public Verdict eval(CheckRun run) throws InterruptedException {
                return body.eval(run);
            }

            @Override
            public String describe() {
                return describe;
            }
        };
    }

    @FunctionalInterface
    interface ExpectBody {
        Verdict eval(CheckRun run) throws InterruptedException;
    }

    /** At least one line matches. */
    public static Expect has(String regex) {
        return atLeast(1, regex, null, null);
    }

    /** At least one line matches after the mark. */
    public static Expect hasAfter(String mark, String regex) {
        return atLeast(1, regex, mark, null);
    }

    public static Expect atLeast(int n, String regex) {
        return atLeast(n, regex, null, null);
    }

    public static Expect atLeast(int n, String regex, String afterMark, String beforeMark) {
        String where = (afterMark == null ? "" : " after " + afterMark) + (beforeMark == null ? "" : " before " + beforeMark);
        return of("at least " + n + " x /" + regex + "/" + where, r -> {
            List<AutoQaLine> m = r.matching(regex, afterMark, beforeMark);
            if (m.size() >= n) {
                return new Verdict(Status.PASS, evidence(m));
            }
            return Verdict.fail("expected " + n + " x /" + r.fill(regex) + "/" + where + ", saw " + m.size());
        });
    }

    /** No line matches. */
    public static Expect lacks(String regex) {
        return atMost(0, regex, null, null);
    }

    public static Expect lacksBefore(String mark, String regex) {
        return atMost(0, regex, null, mark);
    }

    public static Expect lacksAfter(String mark, String regex) {
        return atMost(0, regex, mark, null);
    }

    public static Expect atMost(int n, String regex, String afterMark, String beforeMark) {
        String where = (afterMark == null ? "" : " after " + afterMark) + (beforeMark == null ? "" : " before " + beforeMark);
        return of("at most " + n + " x /" + regex + "/" + where, r -> {
            if (r.compile(regex) == null) {
                return Verdict.fail("unknown placeholder in /" + regex + "/: nothing to judge");
            }
            List<AutoQaLine> m = r.matching(regex, afterMark, beforeMark);
            if (m.size() <= n) {
                return Verdict.pass("/" + r.fill(regex) + "/" + where + ": " + m.size() + " (max " + n + ")");
            }
            List<String> ev = new ArrayList<>();
            ev.add(m.size() + " x /" + r.fill(regex) + "/" + where + " (max " + n + "):");
            ev.addAll(evidence(m));
            return new Verdict(Status.FAIL, ev);
        });
    }

    /** A matches before B does (both must appear). */
    public static Expect inOrder(String first, String then) {
        return of("/" + first + "/ before /" + then + "/", r -> {
            List<AutoQaLine> a = r.matching(first, null, null);
            List<AutoQaLine> b = r.matching(then, null, null);
            if (a.isEmpty() || b.isEmpty()) {
                return Verdict.fail("missing: " + (a.isEmpty() ? "/" + first + "/ " : "") + (b.isEmpty() ? "/" + then + "/" : ""));
            }
            return a.get(0).timeMs() <= b.get(0).timeMs() ? Verdict.pass(a.get(0).toString(), b.get(0).toString())
                    : Verdict.fail("wrong order:", b.get(0).toString(), a.get(0).toString());
        });
    }

    /** Any of the patterns matches at least once. */
    public static Expect anyOf(String... regexes) {
        return of("any of " + String.join(" | ", regexes), r -> {
            for (String re : regexes) {
                List<AutoQaLine> m = r.matching(re, null, null);
                if (!m.isEmpty()) {
                    return new Verdict(Status.PASS, evidence(m));
                }
            }
            return Verdict.fail("none of " + String.join(" | ", regexes));
        });
    }

    /** No bot line the player heard or the log holds carries an emoji. */
    public static Expect noEmoji() {
        return of("no emoji in bot chat", r -> {
            for (AutoQaLine l : r.lines()) {
                if (("CHAT".equals(l.kind()) || "WHISPER".equals(l.kind()) || "NOTICE".equals(l.kind())
                        || "BOT_CHAT".equals(l.kind())) && EMOJI.matcher(l.message()).find()) {
                    return Verdict.fail("emoji: " + l);
                }
            }
            return Verdict.pass("no emoji");
        });
    }

    /** A server-side fact ({@link AutoQaWorld#probe}); its first line says PASS / FAIL / SKIP. */
    public static Expect probe(String what, String... args) {
        return of("probe " + what + " " + String.join(" ", args), r -> {
            String[] filled = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                filled[i] = r.fill(args[i]);
            }
            List<String> out = r.world.probe(what, r.actor(1), filled);
            if (out.isEmpty()) {
                return Verdict.fail("probe " + what + ": no answer");
            }
            String first = out.get(0);
            Status st = first.startsWith("PASS") ? Status.PASS : first.startsWith("SKIP") ? Status.SKIP : Status.FAIL;
            return new Verdict(st, out.size() > 8 ? out.subList(0, 8) : out);
        });
    }

    /**
     * A custom rule over the check's lines: the function returns null when it holds, else what is wrong.
     */
    public static Expect rule(String describe, Function<CheckRun, String> problem) {
        return of(describe, r -> {
            String p = problem.apply(r);
            return p == null ? Verdict.pass(describe) : Verdict.fail(p);
        });
    }

    /**
     * Every number captured by group 1 of the pattern lies in [min, max] (prices, delays, gaps); at least one must.
     */
    public static Expect numbersWithin(String regex, double min, double max) {
        return of("numbers of /" + regex + "/ within [" + min + ", " + max + "]", r -> {
            Pattern p = r.compile(regex);
            if (p == null) {
                return Verdict.fail("unknown placeholder in /" + regex + "/");
            }
            List<String> bad = new ArrayList<>();
            int seen = 0;
            for (AutoQaLine l : r.lines()) {
                Matcher m = p.matcher(l.text());
                while (m.find()) {
                    double v = parseAmount(m.group(1));
                    seen++;
                    if (Double.isNaN(v) || v < min || v > max) {
                        bad.add(m.group(1) + " in " + l);
                    }
                }
            }
            if (seen == 0) {
                return Verdict.fail("no /" + regex + "/ to check");
            }
            return bad.isEmpty() ? Verdict.pass(seen + " value(s) within [" + min + ", " + max + "]")
                    : new Verdict(Status.FAIL, bad.size() > EVIDENCE ? bad.subList(0, EVIDENCE) : bad);
        });
    }

    /**
     * The LLM judge grades the bot lines matching the pattern (group 1 = the line; the player's line before it is
     * the context) with the rubric; SKIP when no judge is configured. At most {@code max} lines are sent.
     */
    public static Expect judged(String regex, String setting, int max) {
        return of("LLM judge: /" + regex + "/ (" + setting + ")", r -> {
            LlmJudge judge = r.world.judge();
            if (judge == null || !judge.available()) {
                return Verdict.skip("no LLM judge configured (BOT_LLM_CLOUD_* or BOT_LLM_*)");
            }
            Pattern p = r.compile(regex);
            if (p == null) {
                return Verdict.fail("unknown placeholder in /" + regex + "/");
            }
            List<AutoQaLine> lines = r.lines();
            List<String> evidence = new ArrayList<>();
            int sent = 0;
            boolean failed = false;
            String previous = "";
            for (AutoQaLine l : lines) {
                if ("qa".equals(l.source()) && "STEP".equals(l.kind())) {
                    continue;
                }
                Matcher m = p.matcher(l.text());
                if (!m.find()) {
                    if (l.isHeard() && "CHAT".equals(l.kind()) && l.message().startsWith(r.fill("{me}") + ": ")) {
                        previous = l.message().substring(l.message().indexOf(": ") + 2);
                    }
                    continue;
                }
                if (sent >= max || r.judgeCalls >= judge.maxCalls()) {
                    break;
                }
                String reply = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : l.message();
                LlmJudge.Grade g = judge.grade(setting, previous, reply);
                sent++;
                r.judgeCalls++;
                evidence.add((g.pass() ? "ok " : "BAD ") + "\"" + reply + "\" - " + g.reason());
                failed |= !g.pass() && g.decided();
            }
            if (sent == 0) {
                return Verdict.fail("no bot line matched /" + r.fill(regex) + "/ to judge");
            }
            return new Verdict(failed ? Status.FAIL : Status.PASS, evidence);
        });
    }

    /**
     * rc6 say-do (owner goal): every action a bot claimed in this check ("omw", "inv me", "brb pots") was backed by
     * the action. Waits (up to {@code maxWaitS}) for the check's open claims to be judged, then FAILs on any strong
     * {@code SAYDO UNBACKED} line, with the line and why as evidence. Weak claims ("coming" alone) are listed, not failed.
     * <p>
     * rc7 scope: only the claims said by the bots this check staged are judged. The CONVO topic carries every bot
     * line on the map, so an ambient bot advertising "S&gt; 10% glove att" in the background of t-cv-4 (an unrelated
     * passer-by, or the AI-run conversation the map runs on its own) landed in the check's evidence and failed it. A
     * passer-by's unbacked ad is still a say-do finding: it stays a {@code SAYDO UNBACKED ... map <id>} line on the
     * CONVO topic and a row in the daily say-do report, it is just not this check's verdict.
     */
    public static Expect sayDo(int maxWaitS) {
        return of("say-do: every action a bot claimed was done", r -> {
            long until = r.world.now() + maxWaitS * 1000L;
            while (soloMapling.ArtificialPlayer.BotConvo.SayDo.openSince(r.startMs) > 0 && r.world.now() < until) {
                r.world.sleep(5000);
            }
            int open = soloMapling.ArtificialPlayer.BotConvo.SayDo.openSince(r.startMs);
            Set<String> speakers = stagedSpeakers(r);
            List<AutoQaLine> backedAll = r.matching("CONVO SAYDO BACKED ", null, null);
            List<AutoQaLine> unbackedAll = r.matching("CONVO SAYDO UNBACKED ", null, null);
            List<AutoQaLine> backed = bySpeaker(backedAll, speakers);
            List<AutoQaLine> unbacked = bySpeaker(unbackedAll, speakers);
            return sayDoVerdict(open, backed, unbacked,
                    backedAll.size() + unbackedAll.size() - backed.size() - unbacked.size(), speakers.isEmpty());
        });
    }

    /**
     * The say-do judgement over the claims of one conversation: FAIL on a strong unbacked claim, list the weak ones,
     * and say how many claims of other bots were left out (they are reported elsewhere, not judged here).
     */
    static Verdict sayDoVerdict(int open, List<AutoQaLine> backed, List<AutoQaLine> unbacked, int otherBots,
                                boolean unscoped) {
        List<String> ev = new ArrayList<>();
        int strong = 0;
        for (AutoQaLine l : unbacked) {
            boolean weak = l.message().contains(" (weak) ");
            if (!weak) {
                strong++;
            }
            if (ev.size() < EVIDENCE + 2) {
                ev.add((weak ? "weak: " : "") + l.message());
            }
        }
        ev.add(0, backed.size() + " backed, " + unbacked.size() + " unbacked (" + strong + " strong)"
                + (open > 0 ? ", " + open + " still open" : "")
                + (otherBots > 0 ? ", " + otherBots + " claim(s) by other bots (not judged here)" : "")
                + (unscoped ? ", no staged bot known: every claim on the map counted" : ""));
        return new Verdict(strong > 0 ? Status.FAIL : Status.PASS, ev);
    }

    /** The names of the bots this check staged ({bot}, {bot2} ...): the speakers its say-do judgement is about. */
    static Set<String> stagedSpeakers(CheckRun r) {
        Set<String> out = new LinkedHashSet<>();
        for (int id : r.stagedBots()) {
            String name = r.world().nameOf(id);
            if (name != null) {
                out.add(name);
            }
        }
        return out;
    }

    /** {@code SAYDO UNBACKED sell ZhelmRamen98 [pack:...] map 100000000 "S> 10% glove att" (why)} -> the bot name. */
    static final Pattern SAYDO_SPEAKER = Pattern.compile("^SAYDO (?:BACKED|UNBACKED) \\S+(?: \\(weak\\))? (.+?) \\[");

    /** The lines a bot said; every line when no staged bot is known (nothing to scope to). */
    static List<AutoQaLine> bySpeaker(List<AutoQaLine> lines, Set<String> speakers) {
        if (speakers.isEmpty()) {
            return lines;
        }
        List<AutoQaLine> out = new ArrayList<>();
        for (AutoQaLine l : lines) {
            Matcher m = SAYDO_SPEAKER.matcher(l.message());
            if (m.find() && speakers.contains(m.group(1).trim())) {
                out.add(l);
            }
        }
        return out;
    }

    static List<String> evidence(List<AutoQaLine> m) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < m.size() && i < EVIDENCE; i++) {
            out.add(m.get(i).toString());
        }
        if (m.size() > EVIDENCE) {
            out.add("... " + (m.size() - EVIDENCE) + " more");
        }
        return out;
    }

    /** "1.5m" / "300k" / "1,200,000" / "42" -> a number; NaN when it is none. */
    static double parseAmount(String s) {
        if (s == null) {
            return Double.NaN;
        }
        String t = s.trim().toLowerCase().replace(",", "");
        double mult = 1;
        if (t.endsWith("k")) {
            mult = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("b")) {
            mult = 1_000_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Double.parseDouble(t) * mult;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
