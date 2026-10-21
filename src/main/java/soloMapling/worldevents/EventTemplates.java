package soloMapling.worldevents;

import com.esotericsoftware.yamlbeans.YamlReader;

import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and checks {@code WorldEvents.yaml} (ticket 21). The file is the owner's: what can happen, where, how often
 * and what it gives. A template with a mistake is skipped and reported, never half-loaded; the rest still load.
 * <p>
 * Path: {@code -Dsolomapling.worldEvents.file}, default {@value #DEFAULT_PATH} (read from the server's working
 * folder like the other SoloMapling YAML files).
 */
public final class EventTemplates {

    public static final String DEFAULT_PATH = "src/main/java/soloMapling/worldevents/WorldEvents.yaml";
    /** No event runs longer than this, whatever the file says (spec: every event has an end time). */
    public static final int MAX_MINUTES = 120;
    /** A hot spot is a bonus, not a rate change: at most double EXP on its one map. */
    public static final double MAX_EXP_BONUS = 2.0;
    static final int MAX_WAVE_COUNT = 40;

    public record Result(List<EventTemplate> templates, List<String> errors) {
    }

    private static volatile Result loaded;

    private EventTemplates() {
    }

    public static String path() {
        return System.getProperty("solomapling.worldEvents.file", DEFAULT_PATH);
    }

    public static Result get() {
        Result r = loaded;
        if (r == null) {
            r = reload();
        }
        return r;
    }

    public static synchronized Result reload() {
        Result r;
        try (FileReader in = new FileReader(path())) {
            r = parse(in);
        } catch (Exception e) {
            r = new Result(List.of(), List.of("cannot read " + path() + ": " + e.getMessage()));
        }
        loaded = r;
        return r;
    }

    /** Test seam: use these templates (null reloads from the file on next use). */
    static void install(Result result) {
        loaded = result;
    }

    public static EventTemplate byId(String id) {
        for (EventTemplate t : get().templates()) {
            if (t.id().equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }

    public static Result parse(Reader in) throws Exception {
        Object root = new YamlReader(in).read();
        List<EventTemplate> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (!(root instanceof Map<?, ?> rootMap) || !(rootMap.get("templates") instanceof List<?> list)) {
            errors.add("no 'templates:' list");
            return new Result(List.of(), errors);
        }
        Set<String> ids = new HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                errors.add("a template that is not a map: " + o);
                continue;
            }
            List<String> problems = new ArrayList<>();
            EventTemplate t = read(m, problems);
            String label = String.valueOf(m.get("id"));
            if (t != null && !ids.add(t.id().toLowerCase(java.util.Locale.ROOT))) {
                problems.add("duplicate id");
            }
            if (!problems.isEmpty() || t == null) {
                for (String p : problems) {
                    errors.add(label + ": " + p);
                }
                continue;
            }
            out.add(t);
        }
        return new Result(List.copyOf(out), List.copyOf(errors));
    }

    private static EventTemplate read(Map<?, ?> m, List<String> problems) {
        String id = str(m.get("id"));
        if (id == null || !id.matches("[A-Za-z0-9_\\-]{1,40}")) {
            problems.add("id must be 1-40 letters, digits, _ or -");
            return null;
        }
        EventTemplate.Kind kind = EventTemplate.Kind.parse(str(m.get("kind")));
        if (kind == null) {
            problems.add("unknown kind '" + m.get("kind") + "'");
            return null;
        }
        String name = str(m.get("name"));
        if (name == null || name.isBlank()) {
            name = id;
        }
        double weight = num(m.get("weight"), 1.0);
        if (weight < 0) {
            problems.add("weight below 0");
        }
        int minutes = (int) num(m.get("minutes"), 20);
        if (minutes < 1 || minutes > MAX_MINUTES) {
            problems.add("minutes must be 1-" + MAX_MINUTES);
        }
        int cooldown = (int) num(m.get("cooldown_minutes"), 120);
        if (cooldown < 0) {
            problems.add("cooldown_minutes below 0");
        }
        int minLevel = 0;
        int maxLevel = 0;
        if (m.get("levels") instanceof List<?> lv) {
            if (lv.size() != 2) {
                problems.add("levels must be [min, max]");
            } else {
                minLevel = (int) num(lv.get(0), 0);
                maxLevel = (int) num(lv.get(1), 0);
                if (minLevel < 1 || maxLevel < minLevel || maxLevel > 255) {
                    problems.add("levels must be 1 <= min <= max <= 255");
                }
            }
        }
        int botCap = (int) num(m.get("bot_cap"), 6);
        if (botCap < 0 || botCap > 30) {
            problems.add("bot_cap must be 0-30");
        }

        List<EventTemplate.Spot> spots = new ArrayList<>();
        if (m.get("maps") instanceof List<?> maps) {
            for (Object mo : maps) {
                int mapId = (int) num(mo, -1);
                if (mapId <= 0) {
                    problems.add("bad map id " + mo);
                } else {
                    spots.add(EventTemplate.Spot.map(mapId));
                }
            }
        }
        if (m.get("spots") instanceof List<?> sl) {
            for (Object so : sl) {
                if (!(so instanceof Map<?, ?> s)) {
                    problems.add("a spot that is not a map");
                    continue;
                }
                int mapId = (int) num(s.get("map"), -1);
                if (mapId <= 0) {
                    problems.add("a spot without a map");
                    continue;
                }
                spots.add(new EventTemplate.Spot(mapId, (int) num(s.get("mob"), 0), (int) num(s.get("item"), 0),
                        (int) num(s.get("count"), 0)));
            }
        }
        if (spots.isEmpty()) {
            problems.add("no maps or spots");
        }

        List<EventTemplate.Wave> waves = new ArrayList<>();
        if (m.get("waves") instanceof List<?> wl) {
            for (Object wo : wl) {
                if (wo instanceof Map<?, ?> w) {
                    int mob = (int) num(w.get("mob"), 0);
                    int count = (int) num(w.get("count"), 0);
                    if (mob <= 0 || count < 1 || count > MAX_WAVE_COUNT) {
                        problems.add("a wave needs a mob and a count of 1-" + MAX_WAVE_COUNT);
                    } else {
                        waves.add(new EventTemplate.Wave(mob, count));
                    }
                }
            }
        }
        int boss = (int) num(m.get("boss"), 0);
        int npc = (int) num(m.get("npc"), 0);
        double expBonus = num(m.get("exp_bonus"), 1.0);

        switch (kind) {
            case INVASION -> {
                if (waves.isEmpty()) {
                    problems.add("an invasion needs waves");
                }
                if (boss <= 0) {
                    problems.add("an invasion needs a boss");
                }
            }
            case BOSS_SIGHTING -> {
                for (EventTemplate.Spot s : spots) {
                    if (s.mobId() <= 0) {
                        problems.add("boss sighting spot on map " + s.mapId() + " has no mob");
                    }
                }
            }
            case TREASURE_HUNT -> {
                if (npc <= 0) {
                    problems.add("a treasure hunt needs an npc (the hidden chest)");
                }
            }
            case HOT_SPOT -> {
                if (expBonus <= 1.0 || expBonus > MAX_EXP_BONUS) {
                    problems.add("exp_bonus must be above 1.0 and at most " + MAX_EXP_BONUS);
                }
            }
            case WEDDING -> {
                // a bot couple's ceremony (ticket 58): just the map
            }
            case WANDERING_NPC -> {
                if (npc <= 0) {
                    problems.add("a wandering NPC needs an npc");
                }
                for (EventTemplate.Spot s : spots) {
                    if (s.itemId() <= 0 || s.count() < 1) {
                        problems.add("wandering NPC spot on map " + s.mapId() + " needs an item and a count");
                    }
                }
            }
        }

        List<EventTemplate.Reward> rewards = new ArrayList<>();
        if (m.get("rewards") instanceof List<?> rl) {
            for (Object ro : rl) {
                if (!(ro instanceof Map<?, ?> r)) {
                    continue;
                }
                int item = (int) num(r.get("item"), 0);
                int qty = (int) num(r.get("qty"), 1);
                double chance = num(r.get("chance"), 1.0);
                if (item <= 0 || qty < 1 || qty > 1000 || chance <= 0 || chance > 1) {
                    problems.add("a reward needs an item, qty 1-1000 and chance in (0, 1]");
                } else {
                    rewards.add(new EventTemplate.Reward(item, qty, chance));
                }
            }
        }
        if (kind == EventTemplate.Kind.HOT_SPOT && !rewards.isEmpty()) {
            problems.add("a hot spot has no winner, so no rewards");
        }
        if (kind == EventTemplate.Kind.WEDDING && !rewards.isEmpty()) {
            problems.add("a wedding has no winner, so no rewards");
        }
        boolean enabled = !"false".equalsIgnoreCase(String.valueOf(m.get("enabled")));
        return new EventTemplate(id, kind, name, weight, minutes, cooldown, minLevel, maxLevel, botCap, spots, waves,
                boss, npc, expBonus, rewards, enabled);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static double num(Object o, double fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
