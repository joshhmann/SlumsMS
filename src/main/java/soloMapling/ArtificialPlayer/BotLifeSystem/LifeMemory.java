package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.*;

/**
 * The small saved part of a bot's daily life: its seeded profile, the bounded drift learned on top of it, its goals
 * and its life events.
 *
 * <p>v2 (1.6.0 S1) keeps the seed and the learned change apart. {@code seed.bartle} is the bot's seeded point on
 * Bartle's chart, its plasticity and its pull-back rate; {@code delta.bartle} is the drift vector (dx, dy) from that
 * point, never longer than {@code plasticity x 1.2}. {@code seed.blend} / {@code delta.blend} are the seven activity
 * channels and their own small delta (each within +-{@link #MAX_CHANNEL_DELTA}), for specific likes. The effective
 * profile is seed + delta, so slice S9's learning can decay back toward the seed. v2 lives in its own table ({@link LifeMemoryStore}, migration 028), not in the
 * roster's {@code ActivityState}, where any non-training session used to overwrite it.
 *
 * <p>v1 (the 1.6.0 sprint shape, a drifted seven-channel blend inside {@code ActivityState.life}) is read once: its
 * drift against the v1 seed becomes the v2 delta and the memory is marked {@link #migrated()} so it gets written.
 */
public final class LifeMemory {
    public static final int VERSION = 2;
    /** How far the seven-channel activity delta may move one channel from its seed. */
    public static final double MAX_CHANNEL_DELTA = .15;
    public enum Event {
        JOB_ADVANCE(SocialProfile.Push.JOB_ADVANCE), MARRIAGE(SocialProfile.Push.MARRIAGE),
        GUILD_WAR(SocialProfile.Push.GUILD_WAR), BIG_DROP(SocialProfile.Push.BIG_DROP), BOOM(SocialProfile.Push.BOOM);

        /** Which way this event moves the bot on Bartle's chart (the direction table is {@link SocialProfile.Push}). */
        public final SocialProfile.Push push;

        Event(SocialProfile.Push push) { this.push = push; }
    }
    private SocialProfile socialSeed;
    private double plasticity, pullBack, driftX, driftY;
    private LifeProfile seed;
    private final EnumMap<LifeProfile.Archetype, Double> delta = new EnumMap<>(LifeProfile.Archetype.class);
    private SocialProfile social;
    private LifeProfile profile;
    private List<LifeGoals.Goal> goals = List.of();
    /** 1.6.0 S8: one plan per goal (same order), and the soft facts the bot made true by finishing steps. */
    private List<LifePlanner.Plan> plans = List.of();
    private final EnumSet<LifePlanner.Fact> soft = EnumSet.noneOf(LifePlanner.Fact.class);
    private int lastJob = -1;
    private long driftDay = -1, lastSocial, savedAt;
    private int changesToday;
    private final EnumMap<Event, Long> eventAt = new EnumMap<>(Event.class);
    private boolean restored, migrated, dirty, touched;
    // 1.6.0 S9 (LifeLearning): the confirmed quadrant (hysteresis), the day the pull-back last ran, the day's learning
    // budget per kind, each channel's running success rate, and the last few quadrant crossings.
    private SocialProfile.Type quadrant;
    private long learnDay = -1, budgetDay = -1;
    private final Map<String, Integer> budget = new TreeMap<>();
    private final EnumMap<LifeProfile.Archetype, Double> successRate = new EnumMap<>(LifeProfile.Archetype.class);
    private int crossings;
    private final ArrayDeque<Crossing> crossed = new ArrayDeque<>();
    /** How many crossings a memory keeps (the newest); the count of all of them is {@link #crossings()}. */
    public static final int KEEP_CROSSINGS = 4;
    /**
     * 1.6.1 (ticket 114): what the bot remembers of its last chats with other bots ("party for Kerning PQ", "went to
     * Ellinia together"), newest last, at most {@link #KEEP_MET}; a later greeting or chat can bring it up.
     */
    public record Met(int otherId, String name, String what, long at) {}
    public static final int KEEP_MET = 6;
    private final ArrayDeque<Met> met = new ArrayDeque<>();
    /** Days of pull-back applied at most in one step (a bot away for months comes back to its seed, not past it). */
    static final int MAX_PULL_DAYS = 60;

    /** 1.6.0 S9: a QUADRANT_CROSSED life event: the bot's point settled in another quadrant ({@code at} epoch ms). */
    public record Crossing(SocialProfile.Type from, SocialProfile.Type to, long at) {
        public Crossing {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
        }
    }

    public LifeMemory(int characterId, Object saved) {
        reset(characterId);
        if (!(saved instanceof Map<?, ?> map)) return;
        try {
            int version = map.get("version") instanceof Number v ? v.intValue() : -1;
            if (version == 1) {
                readV1(characterId, map);
                migrated = true;
                dirty = true;
            } else if (version == 2) {
                readV2(map);
            } else {
                return;
            }
            readCommon(map);
            restored = true;
        } catch (RuntimeException invalid) {
            reset(characterId);
            goals = List.of(); plans = List.of(); soft.clear();
            lastJob = -1; lastSocial = 0; driftDay = -1; changesToday = 0; eventAt.clear(); met.clear();
            savedAt = 0; restored = false; migrated = false; dirty = false;
        }
    }

    private void reset(int characterId) {
        socialSeed = SocialProfile.forCharacter(characterId);
        plasticity = SocialProfile.plasticityOf(SocialProfile.slotOf(characterId));
        pullBack = SocialProfile.pullBackOf(plasticity);
        driftX = 0; driftY = 0;
        seed = LifeProfile.fromSocial(socialSeed, characterId);
        delta.clear();
        social = socialSeed;
        profile = seed;
        quadrant = socialSeed.leading();
        learnDay = -1; budgetDay = -1; budget.clear(); successRate.clear(); crossings = 0; crossed.clear();
    }

    private void readV1(int characterId, Map<?, ?> map) {
        if (!(map.get("blend") instanceof Map<?, ?> weights)) return;
        // v1 drift was relative to the v1 seed; carry it over as a bounded v2 delta on the new Bartle seed.
        var v1Seed = LifeProfile.forBotV1(characterId).blend();
        var v1Blend = archetypes(weights);
        new LifeProfile(v1Blend, seed.grindDurationMs(), seed.townDurationMs()); // validates
        for (var type : LifeProfile.Archetype.values()) {
            double moved = v1Blend.get(type) - v1Seed.get(type);
            if (Math.abs(moved) > 1e-12) delta.put(type, clampDelta(moved));
        }
        recompute();
    }

    private void readV2(Map<?, ?> map) {
        if (map.get("seed") instanceof Map<?, ?> seeds) {
            // A stored seed wins over today's formula, so a bot keeps who it is if the seeding ever changes.
            if (seeds.get("bartle") instanceof Map<?, ?> bartle) {
                socialSeed = new SocialProfile(((Number) bartle.get("x")).doubleValue(),
                        ((Number) bartle.get("y")).doubleValue());
                double p = ((Number) bartle.get("plasticity")).doubleValue();
                if (!(p >= 0 && p <= 1)) throw new IllegalArgumentException("plasticity");
                plasticity = p;
                pullBack = bartle.get("pullBack") instanceof Number n && n.doubleValue() >= 0
                        ? n.doubleValue() : SocialProfile.pullBackOf(plasticity);
            }
            if (seeds.get("blend") instanceof Map<?, ?> blend) {
                seed = new LifeProfile(archetypes(blend), seed.grindDurationMs(), seed.townDurationMs());
            }
        }
        if (map.get("delta") instanceof Map<?, ?> deltas) {
            if (deltas.get("bartle") instanceof Map<?, ?> bartle) {
                driftX = bartle.get("dx") instanceof Number n ? n.doubleValue() : 0;
                driftY = bartle.get("dy") instanceof Number n ? n.doubleValue() : 0;
                // Re-cap on load: the stored drift can never take the point past the plasticity radius or the chart.
                SocialProfile at = socialSeed.drifted(driftX, driftY, plasticity);
                driftX = at.x() - socialSeed.x(); driftY = at.y() - socialSeed.y();
            }
            if (deltas.get("blend") instanceof Map<?, ?> blend) for (var type : LifeProfile.Archetype.values()) {
                if (blend.get(type.name()) instanceof Number n) delta.put(type, clampDelta(n.doubleValue()));
            }
        }
        savedAt = Math.max(0, number(map, "savedAt", 0).longValue());
        recompute();
        quadrant = social.leading(); // a memory saved before S9 starts where it stands: no crossing burst at deploy
        readLearning(map);
    }

    /** 1.6.0 S9: the learning block; anything unreadable is dropped (the drift itself is already read and capped). */
    private void readLearning(Map<?, ?> map) {
        if (!(map.get("learn") instanceof Map<?, ?> learn)) return;
        try {
            if (learn.get("quadrant") instanceof String q) quadrant = SocialProfile.Type.valueOf(q);
            learnDay = number(learn, "day", -1).longValue();
            budgetDay = number(learn, "budgetDay", -1).longValue();
            if (learn.get("budget") instanceof Map<?, ?> used) used.forEach((k, v) -> {
                if (v instanceof Number n && budget.size() < 64) budget.put(String.valueOf(k), Math.max(0, n.intValue()));
            });
            if (learn.get("rate") instanceof Map<?, ?> rates) for (var type : LifeProfile.Archetype.values()) {
                if (rates.get(type.name()) instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 1) {
                    successRate.put(type, n.doubleValue());
                }
            }
            crossings = Math.max(0, number(learn, "crossings", 0).intValue());
            if (learn.get("crossed") instanceof List<?> rows) for (Object row : rows) {
                if (row instanceof Map<?, ?> c && crossed.size() < KEEP_CROSSINGS) crossed.addLast(new Crossing(
                        SocialProfile.Type.valueOf((String) c.get("from")), SocialProfile.Type.valueOf((String) c.get("to")),
                        ((Number) c.get("at")).longValue()));
            }
        } catch (RuntimeException unreadable) {
            quadrant = social.leading();
            learnDay = -1; budgetDay = -1; budget.clear(); successRate.clear(); crossings = 0; crossed.clear();
        }
    }

    private void readCommon(Map<?, ?> map) {
        if (map.get("goals") instanceof List<?> rows) {
            List<LifeGoals.Goal> loaded = new ArrayList<>();
            for (Object row : rows) {
                if (loaded.size() == 2) break;
                if (row instanceof Map<?, ?> g) loaded.add(new LifeGoals.Goal(
                        LifeGoals.Kind.valueOf((String) g.get("kind")), ((Number) g.get("level")).intValue(),
                        (String) g.get("description"), ((Number) g.get("pressure")).doubleValue()));
            }
            goals = List.copyOf(loaded);
        }
        readPlans(map);
        lastJob = number(map, "lastJob", -1).intValue();
        lastSocial = Math.max(0, number(map, "lastSocial", 0).longValue());
        driftDay = number(map, "driftDay", -1).longValue();
        changesToday = Math.max(0, Math.min(4, number(map, "changesToday", 0).intValue()));
        if (map.get("events") instanceof Map<?, ?> events) for (Event event : Event.values()) {
            if (events.get(event.name()) instanceof Number n) eventAt.put(event, n.longValue());
        }
        readMet(map);
    }

    /** 1.6.1: the bot-chat notes; an unreadable row is skipped, never fatal. */
    private void readMet(Map<?, ?> map) {
        met.clear();
        if (!(map.get("met") instanceof List<?> rows)) return;
        for (Object row : rows) {
            if (met.size() >= KEEP_MET) break;
            try {
                if (row instanceof Map<?, ?> m && m.get("id") instanceof Number id && m.get("what") instanceof String what) {
                    met.addLast(new Met(id.intValue(), m.get("name") instanceof String n ? n : "", what,
                            m.get("at") instanceof Number at ? at.longValue() : 0));
                }
            } catch (RuntimeException skipped) { /* next row */ }
        }
    }

    /** 1.6.0 S8: the saved plans (one per goal, dropped if they no longer line up) and soft facts; never fatal. */
    private void readPlans(Map<?, ?> map) {
        try {
            List<LifePlanner.Plan> loaded = new ArrayList<>();
            if (map.get("plans") instanceof List<?> rows) for (Object row : rows) {
                if (loaded.size() == 2) break;
                if (row instanceof Map<?, ?> p) loaded.add(LifePlanner.read(p));
            }
            boolean aligned = loaded.size() == goals.size();
            for (int i = 0; aligned && i < loaded.size(); i++) aligned = loaded.get(i).goal() == goals.get(i).kind();
            plans = aligned ? List.copyOf(loaded) : List.of();
            soft.clear();
            if (map.get("soft") instanceof List<?> names) for (Object name : names) {
                LifePlanner.Fact fact = LifePlanner.Fact.valueOf((String) name);
                if (LifePlanner.SOFT.contains(fact)) soft.add(fact);
            }
        } catch (RuntimeException unreadable) {
            plans = List.of(); // a plan is cheap to make again; the rest of the profile stays
            soft.clear();
        }
    }

    private static EnumMap<LifeProfile.Archetype, Double> archetypes(Map<?, ?> weights) {
        var out = new EnumMap<LifeProfile.Archetype, Double>(LifeProfile.Archetype.class);
        for (var type : LifeProfile.Archetype.values()) out.put(type, ((Number) weights.get(type.name())).doubleValue());
        return out;
    }

    static double clampDelta(Double value) {
        if (value == null || !Double.isFinite(value)) return 0;
        return Math.max(-MAX_CHANNEL_DELTA, Math.min(MAX_CHANNEL_DELTA, value));
    }

    private void recompute() {
        social = socialSeed.drifted(driftX, driftY, plasticity);
        profile = applyDelta(seed, delta);
    }

    /** seed + delta per channel (delta already bounded), floored at 0 and renormalised to sum to 1. */
    static LifeProfile applyDelta(LifeProfile seed, Map<LifeProfile.Archetype, Double> delta) {
        if (delta.isEmpty()) return seed;
        var moved = new EnumMap<LifeProfile.Archetype, Double>(LifeProfile.Archetype.class);
        double total = 0;
        for (var type : LifeProfile.Archetype.values()) {
            double v = Math.max(0, seed.blend().get(type) + delta.getOrDefault(type, 0.0));
            moved.put(type, v);
            total += v;
        }
        if (total <= 0) return seed;
        double sum = 0;
        for (var type : LifeProfile.Archetype.values()) {
            double v = moved.get(type) / total;
            moved.put(type, v);
            sum += v;
        }
        moved.merge(LifeProfile.Archetype.GRINDER, 1 - sum, Double::sum);
        return new LifeProfile(moved, seed.grindDurationMs(), seed.townDurationMs());
    }

    private static Number number(Map<?, ?> map, String key, Number fallback) {
        return map.get(key) instanceof Number n ? n : fallback;
    }

    /** The effective seven-channel profile (seed + delta). */
    public synchronized LifeProfile profile() { return profile; }
    /** The seven-channel seed, never changed by drift or learning. */
    public synchronized LifeProfile seed() { return seed; }
    /** The effective point on Bartle's chart (seed + drift). */
    public synchronized SocialProfile social() { return social; }
    /** The seeded point, never changed by drift or learning. */
    public synchronized SocialProfile socialSeed() { return socialSeed; }
    /** How impressionable this bot is, 0..1: scales every push and the drift radius (plasticity x 1.2). */
    public synchronized double plasticity() { return plasticity; }
    /** The pull back toward the seed point per day, for S9 to apply (S1 stores it and does nothing with it). */
    public synchronized double pullBack() { return pullBack; }
    /** The drift vector {dx, dy} from the seed point. */
    public synchronized double[] drift() { return new double[]{driftX, driftY}; }
    /** The learned seven-channel delta (each within +-MAX_CHANNEL_DELTA); empty when nothing moved. */
    public synchronized Map<LifeProfile.Archetype, Double> delta() { return Map.copyOf(delta); }

    /** Moves the point one push along {@code push} (S9's outcome learning calls this too); true when it moved. */
    public synchronized boolean push(SocialProfile.Push push) {
        double[] next = socialSeed.push(driftX, driftY, push, plasticity);
        if (next[0] == driftX && next[1] == driftY) return false;
        driftX = next[0]; driftY = next[1];
        social = socialSeed.drifted(driftX, driftY, plasticity);
        changed();
        return true;
    }
    public synchronized List<LifeGoals.Goal> goals() { return goals; }
    public synchronized long lastSocial() { return lastSocial; }
    /** When this memory was last saved (epoch ms, 0 = never or v1); the newer of two copies wins on load. */
    public synchronized long savedAt() { return savedAt; }
    /** Whether a saved v1 or v2 state was read (false: a fresh seed). */
    public synchronized boolean restored() { return restored; }
    /** Whether this was read from the v1 shape and still needs its first v2 write. */
    public synchronized boolean migrated() { return migrated; }
    /** Whether something changed since the last {@link #takeDirty}. */
    public synchronized boolean dirty() { return dirty; }

    public synchronized void talked(long now) {
        if (now > lastSocial) { lastSocial = now; changed(); }
    }

    /** 1.6.1 (ticket 114): note a chat with another bot; one note per bot (the newest), {@link #KEEP_MET} in all. */
    public synchronized void noteMet(int otherId, String name, String what, long now) {
        if (otherId == 0 || what == null || what.isBlank()) return;
        met.removeIf(m -> m.otherId() == otherId);
        met.addLast(new Met(otherId, name == null ? "" : name, what.length() > 80 ? what.substring(0, 80) : what, now));
        while (met.size() > KEEP_MET) met.pollFirst();
        changed();
    }

    /** The newest note about a chat with {@code otherId}, or null. */
    public synchronized Met metWith(int otherId) {
        for (Met m : met) if (m.otherId() == otherId) return m;
        return null;
    }

    /** Every note, oldest first. */
    public synchronized List<Met> met() { return List.copyOf(met); }

    public synchronized void refresh(int level, int job, OptionalInt weaponLevel, long now) {
        refresh(LifeGoals.Facts.basic(level, job, weaponLevel), now);
    }

    /**
     * Brings the goals up to date with the game: finished goals drop out, new ones are seeded into the free slots
     * (1.6.0 S8: a Bartle aspiration when {@code facts.social()} is known). A finished or new goal clears the soft
     * plan facts; the plans themselves are refreshed by {@link #plan}.
     */
    public synchronized void refresh(LifeGoals.Facts facts, long now) {
        int level = facts.level(), job = facts.jobId();
        OptionalInt weaponLevel = facts.weaponRequiredLevel();
        if (lastJob >= 0 && lastJob != job) event(Event.JOB_ADVANCE, now);
        if (lastJob != job) changed();
        lastJob = job;
        var seeded = facts.social() == null ? LifeGoals.seed(level, job, weaponLevel) : LifeGoals.seed(facts);
        List<LifeGoals.Goal> pending = new ArrayList<>();
        for (var goal : goals) {
            boolean complete = switch (goal.kind()) {
                case JOB_ADVANCE, FOURTH_JOB -> LifeGoals.nextAdvance(job) != goal.targetLevel();
                case WEAPON_UPGRADE -> weaponLevel.isPresent() && weaponLevel.getAsInt() >= goal.targetLevel();
                case LEVEL -> level >= goal.targetLevel();
                // Aspirations are only judged when the facts are known (the S8 runtime passes them).
                case MARRIAGE, GUILD, ZHELM, WEALTH -> facts.social() != null && LifeGoals.met(goal, facts);
            };
            if (!complete) pending.add(goal);
        }
        for (var goal : seeded) if (pending.size() < 2 && pending.stream().noneMatch(g -> g.kind() == goal.kind())) {
            pending.add(goal);
        }
        var next = pending.stream().map(g -> new LifeGoals.Goal(g.kind(), g.targetLevel(), g.description(),
                g.kind() == LifeGoals.Kind.JOB_ADVANCE || g.kind() == LifeGoals.Kind.FOURTH_JOB
                ? Math.min(1, level / (double) g.targetLevel())
                : g.kind() == LifeGoals.Kind.WEAPON_UPGRADE && weaponLevel.isPresent()
                ? Math.max(0, Math.min(1, (g.targetLevel() - weaponLevel.getAsInt()) / 30.0)) : g.pressure())).toList();
        if (!next.equals(goals)) {
            changed();
            if (!next.stream().map(LifeGoals.Goal::kind).toList().equals(goals.stream().map(LifeGoals.Goal::kind).toList())) {
                soft.clear(); // a new goal starts from what the game can tell
            }
        }
        goals = next;
    }

    /** 1.6.0 S8: the plan per goal (same order as {@link #goals}); empty before the first plan. */
    public synchronized List<LifePlanner.Plan> plans() { return plans; }

    /** The soft plan facts this bot made true by finishing steps. */
    public synchronized Set<LifePlanner.Fact> softFacts() {
        return soft.isEmpty() ? Set.of() : Set.copyOf(soft);
    }

    /**
     * Re-plans against this world when a goal or a fact changed (else keeps the stored plans, no search). True when
     * the plans changed (and the memory is dirty, so they are saved).
     */
    public synchronized boolean plan(LifePlanner.World world) {
        var next = LifePlanner.update(goals, world, plans);
        if (next == plans) return false;
        boolean differs = !next.equals(plans);
        plans = next;
        if (differs) changed();
        return differs;
    }

    /**
     * {@code !qa bl-11}: puts a goal in the second slot (the aspiration's), as if the bot had chosen it. It stays until
     * it is met, like any goal; the next plan includes it.
     */
    public synchronized void stageGoal(LifeGoals.Goal goal) {
        List<LifeGoals.Goal> next = new ArrayList<>();
        if (!goals.isEmpty()) next.add(goals.getFirst());
        next.add(goal);
        goals = List.copyOf(next);
        plans = List.of();
        soft.clear();
        changed();
    }

    /** A finished step made a soft fact true (the outcome callback); true when it was new. */
    public synchronized boolean soften(LifePlanner.Fact fact) {
        if (!LifePlanner.SOFT.contains(fact) || !soft.add(fact)) return false;
        changed();
        return true;
    }

    /** At most four tiny nudges per day and one per event type per hour; no repeated tick-driven drift. */
    public synchronized boolean event(Event event, long now) {
        if (now < 0 || now - eventAt.getOrDefault(event, Long.MIN_VALUE / 2) < 3_600_000) return false;
        long day = now / 86_400_000;
        if (day != driftDay) { driftDay = day; changesToday = 0; }
        if (changesToday >= 4) return false;
        var target = switch (event) {
            case JOB_ADVANCE -> LifeProfile.Archetype.GRINDER;
            case MARRIAGE -> LifeProfile.Archetype.SOCIALITE;
            case GUILD_WAR -> LifeProfile.Archetype.HELPER;
            case BIG_DROP -> LifeProfile.Archetype.MERCHANT;
            case BOOM -> LifeProfile.Archetype.SLACKER;
        };
        // The v1 nudge (x.995 everywhere, +.005 on the target), recorded as a change of the delta, never the seed.
        var nudged = new EnumMap<LifeProfile.Archetype, Double>(LifeProfile.Archetype.class);
        profile.blend().forEach((type, value) -> nudged.put(type, value * .995));
        nudged.merge(target, .005, Double::sum);
        for (var type : LifeProfile.Archetype.values()) {
            double moved = clampDelta(nudged.get(type) - seed.blend().get(type));
            if (Math.abs(moved) > 1e-12) delta.put(type, moved); else delta.remove(type);
        }
        profile = applyDelta(seed, delta);
        push(event.push); // and the point on Bartle's chart, scaled by plasticity
        eventAt.put(event, now); changesToday++;
        changed();
        return true;
    }

    // ── 1.6.0 S9: outcome learning (the rules and rates live in LifeLearning; this is the bounded, saved state) ──

    /**
     * Takes one unit of today's learning budget for {@code kind} (at most {@code perKind} a day for it and
     * {@code total} a day for the bot's kinds of the same group: {@code "ch:<channel>"} likes, or chart pushes). False when the day's budget is spent; the counters roll over at the UTC day.
     */
    public synchronized boolean takeBudget(String kind, int perKind, int total, long now) {
        long day = Math.floorDiv(now, 86_400_000L);
        if (day != budgetDay) { budgetDay = day; budget.clear(); }
        int used = budget.getOrDefault(kind, 0);
        // The total counts kinds of the same group: "ch:..." likes apart from chart pushes.
        int colon = kind.indexOf(':');
        String group = colon < 0 ? null : kind.substring(0, colon + 1);
        int all = 0;
        for (var e : budget.entrySet()) {
            if (group == null ? e.getKey().indexOf(':') < 0 : e.getKey().startsWith(group)) all += e.getValue();
        }
        if (used >= perKind || all >= total) return false;
        budget.put(kind, used + 1);
        changed();
        return true;
    }

    /** What today's budget has used so far, per kind (empty on a new day). */
    public synchronized Map<String, Integer> budgetToday(long now) {
        return Math.floorDiv(now, 86_400_000L) == budgetDay ? Map.copyOf(budget) : Map.of();
    }

    /** A channel's running success rate (0.7 before anything was seen). */
    public synchronized double successRate(LifeProfile.Archetype channel) {
        return successRate.getOrDefault(channel, .7);
    }

    /**
     * One outcome on a channel: the like moves by {@code rate x weight x (outcome - running success rate)} (an outcome
     * better than this bot is used to raises it, a worse one lowers it; one it always gets moves nothing), bounded at
     * +-{@link #MAX_CHANNEL_DELTA} from the seed; the running rate then follows the outcome. The change, or 0.
     */
    public synchronized double learnChannel(LifeProfile.Archetype channel, boolean success, double rate, double weight) {
        if (!Double.isFinite(rate) || !Double.isFinite(weight) || rate <= 0 || weight <= 0) return 0;
        double outcome = success ? 1 : 0;
        double expected = successRate.getOrDefault(channel, .7);
        successRate.put(channel, expected + .1 * (outcome - expected));
        double before = delta.getOrDefault(channel, 0.0);
        double after = clampDelta(before + rate * weight * (outcome - expected));
        if (Math.abs(after) > 1e-12) delta.put(channel, after); else delta.remove(channel);
        profile = applyDelta(seed, delta);
        changed();
        return after - before;
    }

    /**
     * The daily pull back toward the seed: for each whole day since the last step, the drift and every channel delta
     * shrink by the bot's {@link #pullBack()} share (at most {@link #MAX_PULL_DAYS} days at once). The first step only
     * starts the clock. True when something moved.
     */
    public synchronized boolean pullBackTo(long day) {
        if (learnDay < 0 || day < learnDay) {
            learnDay = day;
            return false;
        }
        long days = Math.min(MAX_PULL_DAYS, day - learnDay);
        if (days <= 0) return false;
        learnDay = day;
        double keep = Math.pow(Math.max(0, 1 - pullBack), days);
        boolean moved = false;
        if (driftX != 0 || driftY != 0) {
            driftX = tiny(driftX * keep); driftY = tiny(driftY * keep);
            moved = true;
        }
        for (var type : LifeProfile.Archetype.values()) {
            Double v = delta.get(type);
            if (v == null) continue;
            double next = tiny(v * keep);
            if (next == 0) delta.remove(type); else delta.put(type, next);
            moved = true;
        }
        if (moved) { recompute(); changed(); }
        return moved;
    }

    private static double tiny(double v) {
        return Math.abs(v) < 1e-4 ? 0 : v;
    }

    /**
     * Whether the point has settled in another quadrant. Hysteresis: each axis keeps its confirmed side until the point
     * is at least {@code hysteresis} past it on the other side, so a point sitting on the border never flaps. A
     * crossing is recorded (QUADRANT_CROSSED, the newest {@link #KEEP_CROSSINGS} kept) and returned once; else null.
     */
    public synchronized Crossing checkCrossing(double hysteresis, long now) {
        int sx = Math.abs(social.x()) >= hysteresis ? (social.x() >= 0 ? 1 : -1) : quadrant.cornerX;
        int sy = Math.abs(social.y()) >= hysteresis ? (social.y() >= 0 ? 1 : -1) : quadrant.cornerY;
        SocialProfile.Type next = sx > 0 ? (sy > 0 ? SocialProfile.Type.ACHIEVER : SocialProfile.Type.EXPLORER)
                : (sy > 0 ? SocialProfile.Type.KILLER : SocialProfile.Type.SOCIALIZER);
        if (next == quadrant) return null;
        Crossing crossing = new Crossing(quadrant, next, now);
        quadrant = next;
        crossings++;
        crossed.addLast(crossing);
        while (crossed.size() > KEEP_CROSSINGS) crossed.pollFirst();
        changed();
        return crossing;
    }

    /** The confirmed quadrant (the leading type with hysteresis; starts at the seed's). */
    public synchronized SocialProfile.Type quadrant() { return quadrant; }

    /** How many times this bot's point crossed into another quadrant, ever. */
    public synchronized int crossings() { return crossings; }

    /** The newest crossings, oldest first (at most {@link #KEEP_CROSSINGS}). */
    public synchronized List<Crossing> recentCrossings() { return List.copyOf(crossed); }

    /** The day the pull-back last ran (-1: never). */
    public synchronized long learnDay() { return learnDay; }

    /** {@code !qa lr-1}: a staged, run-only bot gets this seed point and plasticity (never a roster member's). */
    public synchronized void stageSeed(SocialProfile point, double plasticity) {
        if (!(plasticity >= 0 && plasticity <= 1)) throw new IllegalArgumentException("plasticity");
        socialSeed = Objects.requireNonNull(point);
        this.plasticity = plasticity;
        pullBack = SocialProfile.pullBackOf(plasticity);
        driftX = 0; driftY = 0;
        quadrant = point.leading();
        crossings = 0; crossed.clear(); budget.clear(); budgetDay = -1; learnDay = -1;
        recompute();
        changed();
    }

    /** The v2 JSON shape, stamped with {@code now} as its save time. */
    public synchronized Map<String, Object> save(long now) {
        savedAt = Math.max(savedAt, now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        out.put("savedAt", savedAt);
        Map<String, Double> point = new LinkedHashMap<>();
        point.put("x", socialSeed.x()); point.put("y", socialSeed.y());
        point.put("plasticity", plasticity); point.put("pullBack", pullBack);
        out.put("seed", Map.of("bartle", point, "blend", names(seed.blend())));
        Map<String, Double> drift = new LinkedHashMap<>();
        drift.put("dx", driftX); drift.put("dy", driftY);
        out.put("delta", Map.of("bartle", drift, "blend", names(delta)));
        out.put("goals", goals.stream().map(g -> Map.of("kind", g.kind().name(), "level", g.targetLevel(),
                "description", g.description(), "pressure", g.pressure())).toList());
        out.put("plans", plans.stream().map(LifePlanner::save).toList());
        out.put("soft", soft.stream().map(Enum::name).toList());
        out.put("lastJob", lastJob); out.put("lastSocial", lastSocial);
        out.put("driftDay", driftDay); out.put("changesToday", changesToday);
        Map<String, Long> events = new LinkedHashMap<>();
        eventAt.forEach((event, at) -> events.put(event.name(), at));
        out.put("events", events);
        Map<String, Object> learn = new LinkedHashMap<>();
        learn.put("quadrant", quadrant.name());
        learn.put("day", learnDay);
        learn.put("budgetDay", budgetDay);
        learn.put("budget", new LinkedHashMap<>(budget));
        learn.put("rate", names(successRate));
        learn.put("crossings", crossings);
        learn.put("crossed", crossed.stream().map(c -> Map.of("from", c.from().name(), "to", c.to().name(),
                "at", c.at())).toList());
        out.put("learn", learn);
        if (!met.isEmpty()) out.put("met", met.stream().map(m -> Map.of("id", m.otherId(), "name", m.name(),
                "what", m.what(), "at", m.at())).toList());
        return out;
    }

    public Map<String, Object> save() {
        return save(System.currentTimeMillis());
    }

    /** The save to write if anything changed (and clears the flag), or null. Pair a failed write with {@link #markDirty}. */
    public synchronized Map<String, Object> takeDirty(long now) {
        if (!dirty) return null;
        dirty = false;
        migrated = false;
        return save(now);
    }

    public synchronized void markDirty() { changed(); }

    private void changed() { dirty = true; touched = true; }

    /** Nothing saved and nothing changed this run: the plain seed, which needs no row anywhere. */
    public synchronized boolean pristine() { return !restored && !touched; }

    private static <E extends Enum<E>> Map<String, Double> names(Map<E, Double> values) {
        Map<String, Double> out = new LinkedHashMap<>();
        values.forEach((type, value) -> out.put(type.name(), value));
        return out;
    }
}
