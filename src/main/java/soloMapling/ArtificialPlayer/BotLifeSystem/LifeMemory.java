package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.*;

/** Small saved part of a bot's daily life, stored inside the existing roster activity JSON. */
public final class LifeMemory {
    public enum Event { JOB_ADVANCE, MARRIAGE, GUILD_WAR, BIG_DROP, BOOM }
    private LifeProfile profile;
    private List<LifeGoals.Goal> goals = List.of();
    private int lastJob = -1;
    private long driftDay = -1, lastSocial;
    private int changesToday;
    private final EnumMap<Event, Long> eventAt = new EnumMap<>(Event.class);

    public LifeMemory(int characterId, Object saved) {
        profile = LifeProfile.forBot(characterId);
        if (!(saved instanceof Map<?, ?> map)) return;
        try {
            if (!(map.get("version") instanceof Number v) || v.intValue() != 1) return;
            var blend = new EnumMap<LifeProfile.Archetype, Double>(LifeProfile.Archetype.class);
            if (map.get("blend") instanceof Map<?, ?> weights) {
                for (var type : LifeProfile.Archetype.values()) blend.put(type, ((Number) weights.get(type.name())).doubleValue());
                profile = new LifeProfile(blend, profile.grindDurationMs(), profile.townDurationMs());
            }
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
        } catch (RuntimeException invalid) {
            profile = LifeProfile.forBot(characterId);
            goals = List.of(); lastJob = -1; lastSocial = 0; driftDay = -1; changesToday = 0; eventAt.clear();
        }
    }

    private static Number number(Map<?, ?> map, String key, Number fallback) {
        return map.get(key) instanceof Number n ? n : fallback;
    }

    public synchronized LifeProfile profile() { return profile; }
    public synchronized List<LifeGoals.Goal> goals() { return goals; }
    public synchronized long lastSocial() { return lastSocial; }
    public synchronized void talked(long now) { lastSocial = Math.max(lastSocial, now); }

    public synchronized void refresh(int level, int job, OptionalInt weaponLevel, long now) {
        if (lastJob >= 0 && lastJob != job) event(Event.JOB_ADVANCE, now);
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
        goals = pending.stream().map(g -> new LifeGoals.Goal(g.kind(), g.targetLevel(), g.description(),
                g.kind() == LifeGoals.Kind.JOB_ADVANCE ? Math.min(1, level / (double) g.targetLevel())
                : g.kind() == LifeGoals.Kind.WEAPON_UPGRADE && weaponLevel.isPresent()
                ? Math.max(0, Math.min(1, (g.targetLevel() - weaponLevel.getAsInt()) / 30.0)) : g.pressure())).toList();
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
        var blend = new EnumMap<LifeProfile.Archetype, Double>(LifeProfile.Archetype.class);
        profile.blend().forEach((type, value) -> blend.put(type, value * .995));
        blend.merge(target, .005, Double::sum);
        profile = new LifeProfile(blend, profile.grindDurationMs(), profile.townDurationMs());
        eventAt.put(event, now); changesToday++;
        return true;
    }

    public synchronized Map<String, Object> save() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 1);
        Map<String, Double> blend = new LinkedHashMap<>();
        profile.blend().forEach((type, value) -> blend.put(type.name(), value));
        out.put("blend", blend);
        out.put("goals", goals.stream().map(g -> Map.of("kind", g.kind().name(), "level", g.targetLevel(),
                "description", g.description(), "pressure", g.pressure())).toList());
        out.put("lastJob", lastJob); out.put("lastSocial", lastSocial);
        out.put("driftDay", driftDay); out.put("changesToday", changesToday);
        Map<String, Long> events = new LinkedHashMap<>();
        eventAt.forEach((event, at) -> events.put(event.name(), at));
        out.put("events", events);
        return out;
    }
}
