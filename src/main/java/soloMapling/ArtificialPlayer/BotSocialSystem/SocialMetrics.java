package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch;
import soloMapling.ArtificialPlayer.BotSM;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ticket 271 stage 6a: what the social layer looks like from outside, for the inspect API ({@code /bots/{cid}}),
 * {@code !botwhy} and the dashboard. Everything here is memory only and cheap: a bot's {@link SocialMind} is read
 * from {@link LifeMemoryStore#peek} (never a table read; a bot whose memory is not loaded has {@code null}), its mode
 * from the running state machine's class, its bonds from {@link BotBonds}' in-memory sets. Nothing here writes,
 * logs or changes a mood: a read of a mind decays it to {@code now} and stores nothing.
 */
public final class SocialMetrics {

    /** An emotion within this of its baseline counts as at rest and is not listed. */
    static final double REST = 0.02;
    static final int BOND_ROWS = 6;

    private static final Pattern BOND_TOTALS = Pattern.compile("bonds (\\d+) held by (\\d+) bots");

    private SocialMetrics() {
    }

    // ── stage 4: social act counters ──

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> ACTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** One act outcome: {@code kind} and what became of it ({@code ok}, {@code rejected}, {@code refused}, {@code budget}, {@code error}). */
    public static void countAct(SocialActKind kind, String outcome) {
        ACTS.computeIfAbsent(kind.name() + "." + outcome, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    /** A low-cardinality reason key: the text before the first ':' (max 40 chars), digits stripped, no names. */
    public static String shortReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        int colon = reason.indexOf(':');
        String s = (colon > 0 ? reason.substring(0, colon) : reason).replaceAll("[0-9,]+", "").trim();
        if (s.length() > 40) {
            s = s.substring(0, 40).trim();
        }
        return s.isEmpty() ? "unknown" : s;
    }

    /** A free-standing decision counter ({@code decisions}, {@code decided}, {@code idle}, {@code skipped-...}). */
    public static void countDecision(String what) {
        ACTS.computeIfAbsent("decision." + what, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    /** Every act and decision counter, sorted (stable shape for the dashboard and the QA log). */
    public static Map<String, Long> actCounters() {
        Map<String, Long> out = new java.util.TreeMap<>();
        ACTS.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    /** Test seam: forgets the act counters. */
    public static void resetActCountersForTest() {
        ACTS.clear();
    }

    /** One emotion as read now: its value and how far that is from where it settles. */
    public record Felt(SocialMind.Emotion emotion, double value, double deviation) {
    }

    /** The bot's mode ({@code training}, {@code social}, {@code town}), or null for a type outside the three. */
    public static String mode(BotSM bot) {
        ModeSwitch.Mode m = ModeSwitch.Mode.of(bot);
        return m == null ? null : m.name().toLowerCase(Locale.ROOT);
    }

    /** This bot's loaded mind read as a plain snapshot at {@code now}, or null when its memory is not in RAM. */
    public static Snapshot snapshot(int botId, long now) {
        LifeMemory memory = LifeMemoryStore.peek(botId);
        return memory == null ? null : memory.mind(m -> read(m, now));
    }

    /** What a mind says at one moment. */
    public record Snapshot(double mood, Map<SocialMind.Trait, Double> traits, List<Felt> emotions,
                           Map<SocialMind.Need, Double> needs, SocialMind.Need strongest, double strongestValue) {
    }

    static Snapshot read(SocialMind m, long now) {
        List<Felt> felt = new ArrayList<>();
        for (SocialMind.Emotion e : SocialMind.Emotion.values()) {
            double v = m.emotion(e, now);
            felt.add(new Felt(e, v, Math.abs(v - e.baseline)));
        }
        felt.sort(Comparator.comparingDouble(Felt::deviation).reversed());
        Map<SocialMind.Need, Double> needs = new java.util.EnumMap<>(SocialMind.Need.class);
        for (SocialMind.Need n : SocialMind.Need.values()) {
            needs.put(n, m.need(n, now));
        }
        SocialMind.Need top = m.strongest(now);
        return new Snapshot(m.mood(now), new java.util.EnumMap<>(m.traits()), List.copyOf(felt), needs, top,
                needs.get(top));
    }

    /** The {@code n} emotions furthest from rest, strongest first; calm emotions are left out. */
    public static List<Felt> topEmotions(Snapshot s, int n) {
        List<Felt> out = new ArrayList<>();
        for (Felt f : s.emotions()) {
            if (f.deviation() < REST || out.size() >= n) break;
            out.add(f);
        }
        return out;
    }

    public static String moodWord(double mood) {
        if (mood > 0.4) return "happy";
        if (mood > 0.1) return "content";
        if (mood > -0.1) return "neutral";
        if (mood > -0.4) return "low";
        return "miserable";
    }

    // ── the inspect document ──

    /**
     * The {@code mind} object of {@code /bots/{cid}}: {@code mood}, {@code moodWord}, {@code traits} (all 12),
     * {@code emotions} (every emotion's value now), {@code topEmotions} ({@code [{name, value}]}, up to 3, none at
     * rest), {@code needs} (all 8) and {@code strongestNeed} ({@code {name, value}}); null when the bot's memory is not
     * in RAM.
     */
    public static Map<String, Object> mindView(int botId, long now) {
        Snapshot s = snapshot(botId, now);
        if (s == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mood", round(s.mood()));
        out.put("moodWord", moodWord(s.mood()));
        Map<String, Object> traits = new LinkedHashMap<>();
        s.traits().forEach((t, v) -> traits.put(key(t), round(v)));
        out.put("traits", traits);
        Map<String, Object> emotions = new LinkedHashMap<>();
        for (SocialMind.Emotion e : SocialMind.Emotion.values()) {
            for (Felt f : s.emotions()) {
                if (f.emotion() == e) emotions.put(key(e), round(f.value()));
            }
        }
        out.put("emotions", emotions);
        List<Object> top = new ArrayList<>();
        for (Felt f : topEmotions(s, 3)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", key(f.emotion()));
            row.put("value", round(f.value()));
            top.add(row);
        }
        out.put("topEmotions", top);
        Map<String, Object> needs = new LinkedHashMap<>();
        s.needs().forEach((n, v) -> needs.put(key(n), round(v)));
        out.put("needs", needs);
        Map<String, Object> strongest = new LinkedHashMap<>();
        strongest.put("name", key(s.strongest()));
        strongest.put("value", round(s.strongestValue()));
        out.put("strongestNeed", strongest);
        return out;
    }

    /**
     * The {@code bonds} object of {@code /bots/{cid}}: {@code count}, {@code max} and the strongest {@value #BOND_ROWS}
     * as {@code {otherId, state, score, kind, hostile}}; null when bonds are switched off.
     */
    public static Map<String, Object> bondsView(int botId) {
        if (!BotBonds.enabled()) return null;
        List<BotBonds.View> all = BotBonds.bondsOf(botId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", all.size());
        out.put("max", BotBonds.maxBonds());
        List<Object> rows = new ArrayList<>();
        for (BotBonds.View v : all.subList(0, Math.min(BOND_ROWS, all.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("otherId", v.otherId());
            row.put("state", v.state().label());
            row.put("score", Math.round(v.score()));
            row.put("kind", v.kind());
            row.put("hostile", v.hostile());
            rows.add(row);
        }
        out.put("top", rows);
        return out;
    }

    // ── !botwhy ──

    /**
     * The lines {@code !botwhy} adds: the bot's mode, then its mind (mood, its two strongest emotions, its strongest
     * need). Never empty, never throws on a bot with no loaded memory.
     */
    public static List<String> whyLines(int botId, BotSM bot, long now) {
        List<String> lines = new ArrayList<>(2);
        String mode = bot == null ? null : mode(bot);
        lines.add("Mode " + (mode == null ? "other (not training, social or town)" : mode)
                + (ModeSwitch.enabled() ? "" : " (mode switching off)"));
        Snapshot s = snapshot(botId, now);
        if (s == null) {
            lines.add("Mind: not loaded (no social state in memory for this bot yet)");
            return lines;
        }
        StringBuilder b = new StringBuilder("Mind: mood ").append(signed(s.mood())).append(" (")
                .append(moodWord(s.mood())).append("); ");
        List<Felt> top = topEmotions(s, 2);
        if (top.isEmpty()) {
            b.append("emotions at rest");
        } else {
            b.append("feels ");
            for (int i = 0; i < top.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(key(top.get(i).emotion())).append(' ').append(String.format(Locale.ROOT, "%.2f", top.get(i).value()));
            }
        }
        b.append("; strongest need ").append(key(s.strongest())).append(' ')
                .append(String.format(Locale.ROOT, "%.2f", s.strongestValue()));
        lines.add(b.toString());
        return lines;
    }

    // ── ticket 271 stage 5: intentions ──

    /** The {@code !botwhy} line of this bot's live intents (memory only; never empty). */
    public static List<String> intentLines(int botId, long now) {
        return List.of(SocialIntents.whyLine(botId, now));
    }

    /** Intent counters for the dashboard: created, kept, broken, expired, cancelled, refused, deferred, and each by kind. */
    public static Map<String, Long> intentCounters() {
        return SocialIntents.counters();
    }

    // ── the dashboard's bond counts ──

    /**
     * Bond totals from {@link BotBonds#stats()} (in-memory sets, no DB): {@code bonds}, {@code holders} (bots holding
     * at least one) and the raw {@code text}. The counts are null if the stats line ever stops saying
     * "bonds N held by M bots" (a test pins that line).
     */
    public static Map<String, Object> bondTotals() {
        String text = BotBonds.stats();
        Matcher m = BOND_TOTALS.matcher(text);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean found = m.find();
        out.put("bonds", found ? Long.parseLong(m.group(1)) : null);
        out.put("holders", found ? Long.parseLong(m.group(2)) : null);
        out.put("text", text);
        return out;
    }

    // ── helpers ──

    static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static String signed(double v) {
        return String.format(Locale.ROOT, "%+.2f", v);
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
