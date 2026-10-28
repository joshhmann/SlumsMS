package soloMapling.achievements;

import com.esotericsoftware.yamlbeans.YamlReader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/** Reads achievements.yaml strictly: any mistake rejects the whole file. */
public final class GoalCatalog {
    public static final String DATA_PATH = "soloMapling/achievements/achievements.yaml";
    private static final Set<String> KEYS = Set.of("id", "title", "text", "medal", "trigger", "target", "mobs",
            "scripts", "maps");
    private static final int MEDAL_MIN = 1142000, MEDAL_MAX = 1142999;

    private final List<Goal> goals;
    private final Map<String, Goal> byId = new LinkedHashMap<>();

    public GoalCatalog(List<Goal> goals) {
        this.goals = List.copyOf(goals);
        for (Goal g : goals) {
            byId.put(g.id(), g);
        }
    }

    public List<Goal> goals() {
        return goals;
    }

    public Goal get(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Goal> forTrigger(Trigger t) {
        List<Goal> out = new ArrayList<>();
        for (Goal g : goals) {
            if (g.trigger() == t) {
                out.add(g);
            }
        }
        return out;
    }

    public static GoalCatalog read(Path file, IntPredicate knownItem) throws IOException {
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(new YamlReader(in).read(), knownItem);
        }
    }

    public static GoalCatalog parse(Object root, IntPredicate knownItem) {
        if (!(root instanceof Map<?, ?> top) || !(top.get("goals") instanceof List<?> list) || top.size() != 1) {
            throw new IllegalArgumentException("expected only a top-level 'goals:' list");
        }
        List<Goal> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<Integer> medals = new HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("a goal is not a map: " + o);
            }
            for (Object k : m.keySet()) {
                if (!KEYS.contains(String.valueOf(k))) {
                    throw new IllegalArgumentException("unknown key '" + k + "' in goal " + m.get("id"));
                }
            }
            String id = text(m, "id", true).toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9][a-z0-9-]{0,31}") || !ids.add(id)) {
                throw new IllegalArgumentException("bad or duplicate goal id '" + id + "'");
            }
            Trigger t = Trigger.parse(text(m, "trigger", true));
            int medal = num(m, "medal", id, 0);
            if (medal < MEDAL_MIN || medal > MEDAL_MAX || !medals.add(medal) || !knownItem.test(medal)) {
                throw new IllegalArgumentException("goal " + id + ": medal " + medal
                        + " is not a distinct 1142xxx item in the server's data");
            }
            int target = num(m, "target", id, 1);
            if (target < 1 || target > 100_000) {
                throw new IllegalArgumentException("goal " + id + ": target " + target);
            }
            List<Integer> mobs = ints(m.get("mobs"), id);
            List<Integer> maps = ints(m.get("maps"), id);
            List<String> scripts = new ArrayList<>();
            if (m.get("scripts") instanceof List<?> sl) {
                for (Object s : sl) {
                    scripts.add(String.valueOf(s).toLowerCase(Locale.ROOT));
                }
            }
            if ((t == Trigger.MOB_KILL) != !mobs.isEmpty() || (t == Trigger.CLEAR) != !scripts.isEmpty()
                    || (t == Trigger.VISIT) != !maps.isEmpty() || maps.size() > 20) {
                throw new IllegalArgumentException("goal " + id + ": mobs/scripts/maps do not fit trigger " + t);
            }
            out.add(new Goal(id, text(m, "title", true), text(m, "text", true), medal, t, target, List.copyOf(mobs),
                    List.copyOf(scripts), List.copyOf(maps)));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no goals");
        }
        return new GoalCatalog(out);
    }

    private static String text(Map<?, ?> m, String key, boolean required) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            if (required) {
                throw new IllegalArgumentException("goal " + m.get("id") + ": missing " + key);
            }
            return "";
        }
        return String.valueOf(v).trim();
    }

    private static int num(Map<?, ?> m, String key, String id, int dflt) {
        Object v = m.get(key);
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("goal " + id + ": " + key + " is not a number");
        }
    }

    private static List<Integer> ints(Object v, String id) {
        List<Integer> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                try {
                    out.add(Integer.parseInt(String.valueOf(o).trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("goal " + id + ": '" + o + "' is not a number");
                }
            }
        }
        return out;
    }
}
