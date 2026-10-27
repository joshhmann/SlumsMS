package soloMapling.ArtificialPlayer.BotConvo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ticket 59: the names a chat line mentions - maps, mobs, items and players - resolved to real ids. GLiNER Decide has
 * no span question (probed 2026-10-22: only {@code choice} and {@code score}), so this is rules: a small alias table
 * for what players actually type ("fm", "kpq", "sleepy", "henny"), then the String.wz names (longest n-gram first),
 * and the names of the characters on the map for players.
 *
 * <p>The String.wz index is built once in the background ({@link #warmUp}); until it is ready only the aliases
 * answer. Tests install their own {@link Names}.
 */
public final class ConvoEntities {

    private static final Logger log = LoggerFactory.getLogger(ConvoEntities.class);

    public enum Kind { MAP, MOB, ITEM, PLAYER }

    /** One name found in a line. */
    public record Entity(Kind kind, String name, int id) {
    }

    /** Everything found in one line. */
    public record Found(List<Entity> all) {
        public static final Found NONE = new Found(List.of());

        public boolean isEmpty() {
            return all.isEmpty();
        }

        public Entity first(Kind kind) {
            for (Entity e : all) {
                if (e.kind() == kind) {
                    return e;
                }
            }
            return null;
        }

        public Found with(List<Entity> more) {
            if (more.isEmpty()) {
                return this;
            }
            List<Entity> out = new ArrayList<>(all);
            out.addAll(more);
            return new Found(List.copyOf(out));
        }

        public String describe() {
            StringBuilder s = new StringBuilder();
            for (Entity e : all) {
                s.append(s.length() == 0 ? "" : ",").append(e.kind().name().toLowerCase(Locale.ROOT)).append(':')
                        .append(e.name());
            }
            return s.toString();
        }
    }

    /** Name tables: normalised name -> id, per kind. */
    public interface Names {
        Map<String, Integer> maps();

        Map<String, Integer> mobs();

        Map<String, Integer> items();
    }

    /** What players type for places (normalised) -> the map a bot walks to. Checked against String.wz 2026-10-22. */
    static final Map<String, Integer> MAP_ALIASES = Map.ofEntries(
            Map.entry("henesys", 100000000), Map.entry("henny", 100000000), Map.entry("hene", 100000000),
            Map.entry("ellinia", 101000000), Map.entry("elinia", 101000000), Map.entry("perion", 102000000),
            Map.entry("kerning", 103000000), Map.entry("kerning city", 103000000), Map.entry("kpq", 103000000),
            Map.entry("lith", 104000000), Map.entry("lith harbor", 104000000), Map.entry("sleepywood", 105040300),
            Map.entry("sleepy", 105040300), Map.entry("nautilus", 120000000), Map.entry("fm", 910000000),
            Map.entry("free market", 910000000), Map.entry("orbis", 200000000), Map.entry("opq", 200000000),
            Map.entry("el nath", 211000000), Map.entry("elnath", 211000000), Map.entry("ludi", 220000000),
            Map.entry("ludibrium", 220000000), Map.entry("lpq", 220000000), Map.entry("aqua", 230000000),
            Map.entry("aquarium", 230000000), Map.entry("leafre", 240000000), Map.entry("mu lung", 250000000),
            Map.entry("mulung", 250000000), Map.entry("herb town", 251000000), Map.entry("nlc", 600000000),
            Map.entry("new leaf city", 600000000), Map.entry("amoria", 680000000), Map.entry("omega", 221000000),
            Map.entry("omega sector", 221000000), Map.entry("ariant", 260000000), Map.entry("magatia", 261000000),
            Map.entry("ant tunnel", 105050000), Map.entry("ant tunnel i", 105050000));

    private static final Set<String> STOP = Set.of("the", "a", "an", "to", "in", "at", "of", "on", "me", "u", "you", "ur",
            "my", "i", "im", "is", "it", "and", "or", "for", "with", "help", "come", "go", "can", "pls", "plz", "lol",
            "ok", "kill", "these", "this", "that", "them", "some", "any", "how", "much", "where", "what", "who", "real",
            "quick", "here", "there", "now", "rn", "get", "got", "need", "want", "wanna", "have", "has", "map", "town",
            "store", "shop", "sell", "buy", "trade", "show", "way", "street", "road", "hidden");

    private static volatile Names names;
    private static volatile boolean warming;

    private ConvoEntities() {
    }

    /** Test seam (null = aliases only until {@link #warmUp}). */
    public static void useForTest(Names testNames) {
        names = testNames;
    }

    /** Builds the String.wz index on a background thread, once. Safe to call often. */
    public static void warmUp() {
        if (names != null || warming) {
            return;
        }
        synchronized (ConvoEntities.class) {
            if (names != null || warming) {
                return;
            }
            warming = true;
        }
        Thread.ofVirtual().name("convo-names").start(() -> {
            try {
                names = WzNames.load();
            } catch (RuntimeException | LinkageError e) {
                log.warn("Chat entity names from String.wz unavailable; aliases only", e);
            }
        });
    }

    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Maps, mobs and items a line names. */
    public static Found find(String line) {
        String text = norm(line);
        if (text.isEmpty()) {
            return Found.NONE;
        }
        String[] words = text.split(" ");
        List<Entity> out = new ArrayList<>();
        boolean[] used = new boolean[words.length];
        Names n = names;
        for (int len = Math.min(5, words.length); len >= 1; len--) {
            for (int i = 0; i + len <= words.length; i++) {
                if (anyUsed(used, i, len)) {
                    continue;
                }
                String gram = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + len));
                if (len == 1 && !MAP_ALIASES.containsKey(gram) && (STOP.contains(gram) || gram.length() < 3)) {
                    continue;
                }
                Entity hit = match(gram, len, n);
                if (hit != null) {
                    out.add(hit);
                    for (int k = i; k < i + len; k++) {
                        used[k] = true;
                    }
                }
            }
        }
        return out.isEmpty() ? Found.NONE : new Found(List.copyOf(out));
    }

    private static Entity match(String gram, int len, Names n) {
        Integer alias = MAP_ALIASES.get(gram);
        if (alias != null) {
            return new Entity(Kind.MAP, gram, alias);
        }
        if (n == null) {
            return null;
        }
        Integer map = n.maps().get(gram);
        if (map != null && (len > 1 || gram.length() >= 5)) {
            return new Entity(Kind.MAP, gram, map);
        }
        Integer mob = n.mobs().get(gram);
        if (mob == null && gram.endsWith("s")) {
            mob = n.mobs().get(gram.substring(0, gram.length() - 1)); // "zombie mushrooms"
        }
        if (mob != null && (len > 1 || gram.length() >= 4)) {
            return new Entity(Kind.MOB, gram.endsWith("s") && !n.mobs().containsKey(gram)
                    ? gram.substring(0, gram.length() - 1) : gram, mob);
        }
        Integer item = n.items().get(gram);
        if (item != null && (len > 1 || gram.length() >= 5)) {
            return new Entity(Kind.ITEM, gram, item);
        }
        return null;
    }

    private record WordIndex(Names of, Set<String> words) {
    }

    private static volatile WordIndex nameWords;

    /**
     * 1.6.0 tune: whether this word (lower case, 4+ letters) is part of any map, mob or item name ("snowboard" of
     * "Sky Snowboard", "beach" of "Pig Beach"), plural too: such a word is a thing, never a made-up person
     * ({@link NameGuard}). False until the String.wz index is warm.
     */
    public static boolean nameWord(String lower) {
        Names n = names;
        if (n == null || lower == null || lower.length() < 4) {
            return false;
        }
        WordIndex index = nameWords;
        if (index == null || index.of() != n) {
            Set<String> built = new java.util.HashSet<>();
            for (Map<String, Integer> table : List.of(n.maps(), n.mobs(), n.items())) {
                for (String name : table.keySet()) {
                    for (String part : name.split(" ")) {
                        if (part.length() >= 4 && !part.chars().allMatch(Character::isDigit)) {
                            built.add(part);
                        }
                    }
                }
            }
            index = new WordIndex(n, Set.copyOf(built));
            nameWords = index;
        }
        Set<String> w = index.words();
        return w.contains(lower) || lower.length() > 4 && lower.endsWith("s")
                && w.contains(lower.substring(0, lower.length() - 1));
    }

    private static boolean anyUsed(boolean[] used, int from, int len) {
        for (int k = from; k < from + len; k++) {
            if (used[k]) {
                return true;
            }
        }
        return false;
    }

    /** Players a line names, from the names of the characters around (exact word, case-insensitive, xX stripped). */
    public static List<Entity> players(String line, Collection<String> nearbyNames) {
        String text = " " + norm(line) + " ";
        List<Entity> out = new ArrayList<>();
        for (String name : nearbyNames) {
            String n = norm(name);
            String bare = n.replaceAll("^x+|x+$", "");
            if ((n.length() >= 3 && text.contains(" " + n + " ")) || (bare.length() >= 4 && text.contains(" " + bare + " "))) {
                out.add(new Entity(Kind.PLAYER, name, -1));
            }
        }
        return out;
    }

    /** The real index from String.wz (Map.img, Mob.img, the item names). */
    static final class WzNames implements Names {
        final Map<String, Integer> maps = new HashMap<>();
        final Map<String, Integer> mobs = new HashMap<>();
        final Map<String, Integer> items = new HashMap<>();

        static WzNames load() {
            WzNames w = new WzNames();
            provider.Data mapData = provider.DataProviderFactory.getDataProvider(provider.wz.WZFiles.STRING).getData("Map.img");
            for (provider.Data region : mapData.getChildren()) {
                for (provider.Data map : region.getChildren()) {
                    int id;
                    try {
                        id = Integer.parseInt(map.getName());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (id >= 300_000_000 && id != 600000000 && id != 680000000) {
                        continue; // event, quest and instance maps: a bot never walks there from chat
                    }
                    String name = norm(provider.DataTool.getString("mapName", map, ""));
                    if (!name.isEmpty()) {
                        w.maps.merge(name, id, Math::min);
                    }
                }
            }
            provider.Data mobData = provider.DataProviderFactory.getDataProvider(provider.wz.WZFiles.STRING).getData("Mob.img");
            for (provider.Data mob : mobData.getChildren()) {
                try {
                    String name = norm(provider.DataTool.getString("name", mob, ""));
                    if (!name.isEmpty()) {
                        w.mobs.merge(name, Integer.parseInt(mob.getName()), Math::min);
                    }
                } catch (NumberFormatException ignored) {
                    // not a mob entry
                }
            }
            for (tools.Pair<Integer, String> item : server.ItemInformationProvider.getInstance().getAllItems()) {
                String name = norm(item.getRight());
                if (!name.isEmpty() && item.getLeft() < 5_000_000) {
                    w.items.merge(name, item.getLeft(), Math::min);
                }
            }
            log.info("Chat entity names: {} maps, {} mobs, {} items", w.maps.size(), w.mobs.size(), w.items.size());
            return w;
        }

        @Override
        public Map<String, Integer> maps() {
            return maps;
        }

        @Override
        public Map<String, Integer> mobs() {
            return mobs;
        }

        @Override
        public Map<String, Integer> items() {
            return items;
        }
    }
}
