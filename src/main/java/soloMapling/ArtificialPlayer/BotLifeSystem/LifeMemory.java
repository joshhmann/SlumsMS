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
    private int lastJob = -1;
    private long driftDay = -1, lastSocial, savedAt;
    private int changesToday;
    private final EnumMap<Event, Long> eventAt = new EnumMap<>(Event.class);
    private boolean restored, migrated, dirty, touched;

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
            goals = List.of(); lastJob = -1; lastSocial = 0; driftDay = -1; changesToday = 0; eventAt.clear();
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
        lastJob = number(map, "lastJob", -1).intValue();
        lastSocial = Math.max(0, number(map, "lastSocial", 0).longValue());
        driftDay = number(map, "driftDay", -1).longValue();
        changesToday = Math.max(0, Math.min(4, number(map, "changesToday", 0).intValue()));
        if (map.get("events") instanceof Map<?, ?> events) for (Event event : Event.values()) {
            if (events.get(event.name()) instanceof Number n) eventAt.put(event, n.longValue());
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

    public synchronized void refresh(int level, int job, OptionalInt weaponLevel, long now) {
        if (lastJob >= 0 && lastJob != job) event(Event.JOB_ADVANCE, now);
        if (lastJob != job) changed();
        lastJob = job;
        var seeded = LifeGoals.seed(level, job, weaponLevel);
        List<LifeGoals.Goal> pending = new ArrayList<>();
        for (var goal : goals) {
            boolean complete = switch (goal.kind()) {
                case JOB_ADVANCE -> seeded.stream().noneMatch(next -> next.kind() == LifeGoals.Kind.JOB_ADVANCE
                        && next.targetLevel() == goal.targetLevel());
                case WEAPON_UPGRADE -> weaponLevel.isPresent() && weaponLevel.getAsInt() >= goal.targetLevel();
                case LEVEL -> level >= goal.targetLevel();
            };
            if (!complete) pending.add(goal);
        }
        for (var goal : seeded) if (pending.size() < 2 && pending.stream().noneMatch(g -> g.kind() == goal.kind())) {
            pending.add(goal);
        }
        var next = pending.stream().map(g -> new LifeGoals.Goal(g.kind(), g.targetLevel(), g.description(),
                g.kind() == LifeGoals.Kind.JOB_ADVANCE ? Math.min(1, level / (double) g.targetLevel())
                : g.kind() == LifeGoals.Kind.WEAPON_UPGRADE && weaponLevel.isPresent()
                ? Math.max(0, Math.min(1, (g.targetLevel() - weaponLevel.getAsInt()) / 30.0)) : g.pressure())).toList();
        if (!next.equals(goals)) changed();
        goals = next;
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
        out.put("lastJob", lastJob); out.put("lastSocial", lastSocial);
        out.put("driftDay", driftDay); out.put("changesToday", changesToday);
        Map<String, Long> events = new LinkedHashMap<>();
        eventAt.forEach((event, at) -> events.put(event.name(), at));
        out.put("events", events);
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
