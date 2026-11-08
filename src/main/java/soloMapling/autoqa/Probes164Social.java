package soloMapling.autoqa;

import client.Character;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActions;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialBudget;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMetrics;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Ticket 271, the social pilot: read-only auto-QA checks that sample what the social layer has already done on the
 * running server. None of them stages a scene, moves a bot or writes a counter: they read the counters and the loaded
 * minds of the bots that are online now, so they cost one pass over the online bots and nothing else.
 *
 * <ul>
 *   <li>{@code t-ms-1} modes: after enough bot-minutes the census holds bots in all three modes, the counters show
 *   switches out of training and back into it, the type swap was refused on at most 5% of switches, and no bot went
 *   over the three-a-hour cap.</li>
 *   <li>{@code t-sm-1} minds: a sample of online bots with a loaded mind has no NaN, a mood inside [-1, 1], and at
 *   least two minds have moved off their seed values (an event landed).</li>
 *   <li>{@code t-sa-1} acts: at least three kinds of social act landed, errors and budget refusals stay under their
 *   shares, the turn cap is applied, and no single kind is refused more often than it lands.</li>
 *   <li>{@code t-pi-1} pilot: the Henesys pilot's own counters, read through a reflection seam, so the check compiles
 *   and runs before {@code SocialPilot} exists (it SKIPs "pilot not built").</li>
 * </ul>
 *
 * Every check SKIPs - never fails - when its switch is off or the population is too small to say anything, and the
 * SKIP line names the reason. The verdict rules are static and pure, for tests. The "after N minutes" gate is the
 * accumulated counter itself (a bot asks for a mode decision at most once a minute), so the check does not block the
 * QA thread waiting.
 */
final class Probes164Social {

    static final List<String> NAMES = List.of("modeSwitch", "socialMind", "socialActs", "socialPilot");

    private Probes164Social() {
    }

    static List<String> probe(String what, SyntheticPlayer p, String... a) {
        try {
            return switch (what) {
                case "modeSwitch" -> modeSwitch();
                case "socialMind" -> socialMind();
                case "socialActs" -> socialActs();
                case "socialPilot" -> socialPilot();
                default -> null;
            };
        } catch (Exception e) {
            return List.of("FAIL probe " + what + " threw " + e);
        }
    }

    // ── t-ms-1: the three modes ──

    /** Bots the check needs to have been through a mode decision. */
    static final int MS_MIN_BOTS = 8;
    /** Bot-minutes of mode decisions before the check says anything. */
    static final int MS_MIN_MINUTES = 30;
    /** Switches before "both directions" means anything. */
    static final int MS_MIN_SWITCHES = 3;
    /** Share of switches whose type swap may be refused by the game. */
    static final double MS_SWAP_REFUSED_SHARE = 0.05;

    /** Why the mode check cannot say anything yet, or null when it can. */
    static String modeSwitchSkip(long evaluations, int trackedBots) {
        if (trackedBots < MS_MIN_BOTS) {
            return "only " + trackedBots + " roster bots have been through a mode decision (need " + MS_MIN_BOTS
                    + " online)";
        }
        long need = (long) MS_MIN_BOTS * MS_MIN_MINUTES;
        if (evaluations < need) {
            return "the brain has made " + evaluations + " mode decisions (need " + need + ": " + MS_MIN_BOTS
                    + " bots x " + MS_MIN_MINUTES + " min)";
        }
        return null;
    }

    /**
     * What is wrong with the mode census and counters, or null when nothing is. {@code census} is keyed by mode name
     * (all three present), {@code stats} is {@link ModeSwitch#stats()}.
     */
    static String modeSwitchProblem(Map<String, Integer> census, Map<String, Long> stats, int maxPerBotHour) {
        for (ModeSwitch.Mode m : ModeSwitch.Mode.values()) {
            if (census.getOrDefault(m.name(), 0) == 0) {
                return "no bot is in " + m.name().toLowerCase(Locale.ROOT) + " mode (census " + census + ")";
            }
        }
        long out = 0;
        long back = 0;
        for (Map.Entry<String, Long> e : stats.entrySet()) {
            if (!e.getKey().startsWith("switch ")) continue;
            String pair = e.getKey().substring("switch ".length());
            if (pair.startsWith("TRAINING>")) out += e.getValue();
            if (pair.endsWith(">TRAINING")) back += e.getValue();
        }
        long switches = stats.getOrDefault("switches", 0L);
        if (switches < MS_MIN_SWITCHES) {
            return "only " + switches + " mode switch(es) so far (need " + MS_MIN_SWITCHES + ")";
        }
        if (out == 0) {
            return "no bot has left training for social or town mode in " + switches + " switch(es)";
        }
        if (back == 0) {
            return "no bot has come back to training mode in " + switches + " switch(es)";
        }
        long refused = stats.getOrDefault("refused swap refused", 0L);
        if (refused > switches * MS_SWAP_REFUSED_SHARE) {
            return "the type swap was refused " + refused + " time(s) in " + switches + " switch(es) (cap "
                    + (int) (MS_SWAP_REFUSED_SHARE * 100) + "%)";
        }
        if (maxPerBotHour > ModeSwitch.MAX_PER_HOUR) {
            return "a bot switched " + maxPerBotHour + " times in the last hour (cap " + ModeSwitch.MAX_PER_HOUR + ")";
        }
        return null;
    }

    private static List<String> modeSwitch() {
        if (!ModeSwitch.enabled()) {
            return List.of("SKIP mode switching is off (-Dslums.modeswitch=false, or the brain does not reach town"
                    + " and social bots)");
        }
        Map<String, Long> stats = ModeSwitch.stats();
        Map<ModeSwitch.Mode, Integer> census = ModeSwitch.census();
        int tracked = 0;
        Map<String, Integer> byName = new TreeMap<>();
        for (Map.Entry<ModeSwitch.Mode, Integer> e : census.entrySet()) {
            tracked += e.getValue();
            byName.put(e.getKey().name(), e.getValue());
        }
        long evaluations = stats.getOrDefault("evaluations", 0L);
        String skip = modeSwitchSkip(evaluations, tracked);
        if (skip != null) return List.of("SKIP " + skip);
        Map<Integer, Integer> perBot = ModeSwitch.switchesLastHour(System.currentTimeMillis());
        int worst = 0;
        for (int n : perBot.values()) worst = Math.max(worst, n);
        String problem = modeSwitchProblem(byName, stats, worst);
        if (problem != null) return List.of("FAIL " + problem);
        List<String> out = new ArrayList<>();
        out.add("PASS modes " + census + "; " + stats.getOrDefault("switches", 0L) + " switch(es) in "
                + evaluations + " decision(s)");
        for (Map.Entry<String, Long> e : stats.entrySet()) {
            if (e.getKey().startsWith("switch ")) out.add("switched " + e.getKey().substring("switch ".length())
                    + " x" + e.getValue());
        }
        long refused = 0;
        for (Map.Entry<String, Long> e : stats.entrySet()) {
            if (e.getKey().startsWith("refused ")) {
                refused += e.getValue();
                out.add("held back: " + e.getKey().substring("refused ".length()) + " x" + e.getValue());
            }
        }
        out.add("swap refused " + stats.getOrDefault("refused swap refused", 0L) + " of "
                + stats.getOrDefault("switches", 0L) + " switch(es); worst bot " + worst + "/h (cap "
                + ModeSwitch.MAX_PER_HOUR + "); " + refused + " refusal(s) by reason");
        return out;
    }

    // ── t-sm-1: the minds ──

    /** Online bots with a loaded mind the check samples. */
    static final int SM_MIN_BOTS = 5;
    /** Minds that must have moved off their seed values. */
    static final int SM_MIN_MOVED = 2;
    /** A seeded need sits in [0.2, 0.5); outside this band it has grown or been satisfied. */
    static final double SM_SEED_LOW = 0.19;
    static final double SM_SEED_HIGH = 0.51;

    /** One sampled mind, as the verdict needs it. */
    record MindRead(int botId, double mood, boolean movedEmotion, boolean movedNeed, boolean finite) {
    }

    /** Why the mind check cannot say anything yet, or null when it can. */
    static String socialMindSkip(int loaded, long decisions) {
        if (decisions <= 0) return "the social layer has not made a decision yet";
        if (loaded < SM_MIN_BOTS) {
            return "only " + loaded + " online bot(s) have a loaded mind (need " + SM_MIN_BOTS + ")";
        }
        return null;
    }

    /** What is wrong with the sampled minds, or null when nothing is. */
    static String socialMindProblem(List<MindRead> sample) {
        for (MindRead m : sample) {
            if (!m.finite()) return "bot " + m.botId() + " has a NaN or infinite value in its mind";
            if (m.mood() < -1 || m.mood() > 1) {
                return "bot " + m.botId() + " has mood " + m.mood() + " outside [-1, 1]";
            }
        }
        int moved = 0;
        for (MindRead m : sample) {
            if (m.movedEmotion() || m.movedNeed()) moved++;
        }
        if (moved < SM_MIN_MOVED) {
            return "only " + moved + " of " + sample.size() + " loaded mind(s) have moved off their seed values"
                    + " (need " + SM_MIN_MOVED + ")";
        }
        return null;
    }

    private static List<String> socialMind() {
        if (!SocialActions.enabled()) {
            return List.of("SKIP social acts are off (-Dslums.socialacts=false), so nothing lands on a mind");
        }
        long now = System.currentTimeMillis();
        long decisions = 0;
        for (Map.Entry<String, Long> e : SocialMetrics.actCounters().entrySet()) {
            if (e.getKey().startsWith("decision.")) decisions += e.getValue();
        }
        List<MindRead> sample = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr == null || chr.getMap() == null) continue;
            SocialMetrics.Snapshot s = SocialMetrics.snapshot(chr.getId(), now);
            if (s == null) continue;
            boolean finite = Double.isFinite(s.mood());
            for (SocialMetrics.Felt f : s.emotions()) {
                if (!Double.isFinite(f.value())) finite = false;
            }
            boolean movedNeed = false;
            for (double v : s.needs().values()) {
                if (!Double.isFinite(v)) finite = false;
                if (v < SM_SEED_LOW || v > SM_SEED_HIGH) movedNeed = true;
            }
            boolean movedEmotion = !SocialMetrics.topEmotions(s, 1).isEmpty();
            sample.add(new MindRead(chr.getId(), s.mood(), movedEmotion, movedNeed, finite));
            lines.add("bot " + chr.getId() + " mood " + String.format(Locale.ROOT, "%.2f", s.mood())
                    + " (" + SocialMetrics.moodWord(s.mood()) + "), strongest need "
                    + s.strongest().name().toLowerCase(Locale.ROOT) + " "
                    + String.format(Locale.ROOT, "%.2f", s.strongestValue())
                    + (movedEmotion ? ", feels something" : "") + (movedNeed ? ", need moved" : ""));
        }
        String skip = socialMindSkip(sample.size(), decisions);
        if (skip != null) return List.of("SKIP " + skip);
        String problem = socialMindProblem(sample);
        if (problem != null) return List.of("FAIL " + problem);
        int moved = 0;
        int feels = 0;
        for (MindRead m : sample) {
            if (m.movedEmotion() || m.movedNeed()) moved++;
            if (m.movedEmotion()) feels++;
        }
        List<String> out = new ArrayList<>();
        out.add("PASS " + sample.size() + " loaded mind(s); " + moved + " moved off their seed values, " + feels
                + " feel something, " + decisions + " social decision(s)");
        out.addAll(lines.subList(0, Math.min(6, lines.size())));
        return out;
    }

    // ── t-sa-1: the acts ──

    /** Distinct kinds of act that must have landed. */
    static final int SA_MIN_KINDS = 3;
    /** Attempts before the check says anything. */
    static final long SA_MIN_ATTEMPTS = 20;
    /** Share of attempts the budget may refuse. */
    static final double SA_DENIED_SHARE = 0.5;
    /** Share of landed acts that may end in an error. */
    static final double SA_ERROR_SHARE = 0.05;
    /**
     * The turn-limit proof is synthetic (owner 2026-11-04): pair cooldowns stop real pairs before six alternating
     * turns, so organic traffic cannot show the cap biting. A scripted A/B ping-pong on fake ids asks the real rule.
     */
    static java.util.function.LongPredicate turnLimitProof = SocialBudget::turnLimitBites;
    /** Share of one kind's attempts that may be refused by a precondition. */
    static final double SA_REFUSED_SHARE = 0.5;

    /** Why the act check cannot say anything yet, or null when it can. */
    static String socialActsSkip(long allowed, long denied) {
        long attempts = allowed + denied;
        if (attempts < SA_MIN_ATTEMPTS) {
            return "only " + attempts + " social act(s) have been attempted (need " + SA_MIN_ATTEMPTS + ")";
        }
        return null;
    }

    /** What is wrong with the synthetic turn-limit proof, or null when the scripted ping-pong was refused. */
    static String turnLimitProblem(boolean bites) {
        return bites ? null : "the turn limit did not refuse a scripted A/B ping-pong past the allowed turns on two"
                + " fake ids: the cap is not being applied";
    }

    /** What is wrong with the act and budget counters, or null when nothing is. */
    static String socialActsProblem(Map<String, Long> acts, Map<String, Long> budget) {
        Map<String, long[]> byKind = new LinkedHashMap<>(); // kind -> {ok, other}
        for (Map.Entry<String, Long> e : acts.entrySet()) {
            int dot = e.getKey().lastIndexOf('.');
            if (dot <= 0 || e.getKey().startsWith("decision.")) continue;
            String kind = e.getKey().substring(0, dot);
            String outcome = e.getKey().substring(dot + 1);
            // "ACCEPT.failed: nobody could join" (1.6.6: the flow reason) is the reason breakdown of "ACCEPT.failed", already counted
            if (outcome.contains(":")) {
                // 1.6.6 accept-fail-reason step 2: a policy decline ("LFG rules said no") is the rules working, not a
                // refusal; it comes off the kind's refused count (the counter still shows)
                String reason = outcome.substring(outcome.indexOf(':') + 1).trim();
                if (SocialActions.POLICY_DECLINES.contains(reason)) {
                    byKind.computeIfAbsent(kind, k -> new long[2])[1] -= e.getValue();
                }
                continue;
            }
            long[] row = byKind.computeIfAbsent(kind, k -> new long[2]);
            if (outcome.equals("ok")) row[0] += e.getValue();
            else row[1] += e.getValue();
        }
        int kinds = 0;
        long ok = 0;
        long errors = 0;
        for (Map.Entry<String, Long> e : acts.entrySet()) {
            if (e.getKey().endsWith(".ok")) {
                ok += e.getValue();
                if (e.getValue() > 0) kinds++;
            }
            if (e.getKey().endsWith(".error")) errors += e.getValue();
        }
        if (kinds < SA_MIN_KINDS) {
            return "only " + kinds + " kind(s) of social act have landed (need " + SA_MIN_KINDS + ")";
        }
        if (errors > ok * SA_ERROR_SHARE) {
            return errors + " social act(s) ended in an error against " + ok + " that landed (cap "
                    + (int) (SA_ERROR_SHARE * 100) + "%)";
        }
        long allowed = budget.getOrDefault("allowed", 0L);
        long denied = budget.getOrDefault("denied", 0L);
        if (denied > (allowed + denied) * SA_DENIED_SHARE) {
            return "the budget refused " + denied + " of " + (allowed + denied) + " act(s) (cap "
                    + (int) (SA_DENIED_SHARE * 100) + "%)";
        }
        long turns = budget.getOrDefault("denied turn-limit", 0L);
        if (turns > allowed / 2) {
            return turns + " turn-limit refusal(s) against " + allowed + " allowed act(s): pairs are stuck in a"
                    + " capped exchange";
        }
        for (Map.Entry<String, long[]> e : byKind.entrySet()) {
            long[] row = e.getValue();
            long total = row[0] + row[1];
            if (total >= 10 && row[1] > total * SA_REFUSED_SHARE && row[0] < row[1]) {
                return e.getKey() + " was refused or held back " + row[1] + " time(s) in " + total
                        + " attempt(s): a staging problem, not a working act";
            }
        }
        return null;
    }

    private static List<String> socialActs() {
        if (!SocialActions.enabled()) {
            return List.of("SKIP social acts are off (-Dslums.socialacts=false)");
        }
        Map<String, Long> acts = SocialMetrics.actCounters();
        Map<String, Long> budget = SocialBudget.counters();
        long allowed = budget.getOrDefault("allowed", 0L);
        long denied = budget.getOrDefault("denied", 0L);
        String skip = socialActsSkip(allowed, denied);
        if (skip != null) return List.of("SKIP " + skip);
        String problem = socialActsProblem(acts, budget);
        if (problem != null) return List.of("FAIL " + problem);
        String proof = turnLimitProblem(turnLimitProof.test(System.currentTimeMillis()));
        if (proof != null) return List.of("FAIL " + proof);
        List<String> out = new ArrayList<>();
        StringBuilder kinds = new StringBuilder();
        long ok = 0;
        int n = 0;
        for (Map.Entry<String, Long> e : acts.entrySet()) {
            if (!e.getKey().endsWith(".ok") || e.getValue() <= 0) continue;
            ok += e.getValue();
            n++;
            if (kinds.length() < 160) kinds.append(n > 1 ? ", " : "").append(e.getKey(), 0, e.getKey().length() - 3)
                    .append(' ').append(e.getValue());
        }
        out.add("PASS " + n + " kind(s) of act landed, " + ok + " act(s) ok; budget allowed " + allowed
                + ", refused " + denied);
        out.add("kinds: " + kinds);
        out.add("budget: turn-limit " + budget.getOrDefault("denied turn-limit", 0L) + ", cooldown "
                + budget.getOrDefault("denied pair-cooldown", 0L) + ", backoff "
                + budget.getOrDefault("denied backoff", 0L) + ", actor " + budget.getOrDefault("denied actor-rate", 0L)
                + ", map " + budget.getOrDefault("denied map-rate", 0L) + ", world "
                + budget.getOrDefault("denied global-rate", 0L) + "; " + budget.getOrDefault("trackedPairs", 0L)
                + " pair(s) tracked");
        long errors = 0;
        for (Map.Entry<String, Long> e : acts.entrySet()) {
            if (e.getKey().endsWith(".error")) errors += e.getValue();
        }
        out.add("errors " + errors + " of " + ok + " landed act(s)");
        out.add("turn limit: synthetic A/B ping-pong on fake ids was refused after the allowed turns (proved); "
                + "organically " + budget.getOrDefault("denied turn-limit", 0L) + " refusal(s) of " + allowed
                + " allowed act(s) (info only: pair cooldowns normally stop pairs first)");
        return out;
    }

    // ── t-pi-1: the Henesys pilot ──

    /** The pilot another lane builds; this probe only reads its counters. */
    static final String PILOT = "soloMapling.ArtificialPlayer.BotSocialSystem.SocialPilot";

    /** Seams: a test replaces them; the defaults call the pilot class if it exists. */
    static Supplier<Boolean> pilotEnabled = () -> Boolean.TRUE.equals(pilotCall("enabled"));
    static Supplier<Map<String, Long>> pilotStats = Probes164Social::pilotStats;

    /** Test seam: fixed pilot answers. Pair with {@link #resetPilotForTest()}. */
    static void usePilotForTest(Supplier<Boolean> on, Supplier<Map<String, Long>> stats) {
        pilotEnabled = on;
        pilotStats = stats;
    }

    /** Test seam: back to the reflection defaults. */
    static void resetPilotForTest() {
        pilotEnabled = () -> Boolean.TRUE.equals(pilotCall("enabled"));
        pilotStats = Probes164Social::pilotStats;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> pilotStats() {
        Object o = pilotCall("stats");
        return o instanceof Map<?, ?> m ? (Map<String, Long>) m : null;
    }

    /** True once the Henesys pilot class exists; stage 8's catalogue entry is registered only then. */
    static boolean pilotPresent() {
        try {
            Class.forName(PILOT, false, Probes164Social.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** The pilot's static method, or null when the class or method is not there yet. */
    private static Object pilotCall(String method) {
        try {
            Class<?> c = Class.forName(PILOT, false, Probes164Social.class.getClassLoader());
            Method m = c.getMethod(method);
            return m.invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SocialPilot." + method + " threw " + e.getCause(), e);
        }
    }

    /** What is wrong with the pilot's counters, or null when nothing is. */
    static String pilotProblem(Map<String, Long> stats) {
        if (stats == null || stats.isEmpty()) return "the pilot reports no counters at all";
        long moved = 0;
        for (Map.Entry<String, Long> e : stats.entrySet()) {
            if (e.getValue() < 0) {
                return "the pilot's '" + e.getKey() + "' counter is negative (" + e.getValue() + ")";
            }
            if (e.getValue() > 0) moved++;
        }
        if (moved == 0) return "every pilot counter is still 0: the pilot has not done anything";
        return null;
    }

    private static List<String> socialPilot() {
        Boolean on = pilotEnabled.get();
        if (on == null) return List.of("SKIP pilot not built (no " + PILOT + ")");
        if (!on) return List.of("SKIP the pilot is off");
        Map<String, Long> stats = pilotStats.get();
        if (stats == null) return List.of("SKIP pilot not built (no " + PILOT + ".stats())");
        if (stats.getOrDefault("online", 0L) == 0L) return List.of("SKIP no pilot bot is online yet");
        String problem = pilotProblem(stats);
        if (problem != null) return List.of("FAIL " + problem);
        List<String> out = new ArrayList<>();
        out.add("PASS the pilot reports " + stats.size() + " counter(s)");
        Map<String, Long> sorted = new TreeMap<>(stats);
        for (Map.Entry<String, Long> e : sorted.entrySet()) {
            if (out.size() >= 8) break;
            out.add(e.getKey() + " " + e.getValue());
        }
        return out;
    }
}
