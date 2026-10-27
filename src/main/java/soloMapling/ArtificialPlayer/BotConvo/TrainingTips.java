package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotGrindSystem.LevelingPath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 tune: TRAINING threads talk about real places. The TEST trace (2026-10-26) had "wild boars in Kerning
 * subway", "KPQ in Ludibrium", "Fire Boars at 63": the model made places up. The training bots' own leveling path
 * ({@link LevelingPath}, LevelingPath.yaml: level bands, per job group, the maps they grind) is the truth here: the
 * thread writer gets the real spots for the asker's level, the line bank's grounded lines name one, and the social
 * trace's SENSE checks every place a training line names against the levels the path trains there
 * ({@link #wrongPlaces}).
 *
 * <p>A tip can be followed: after a TRAINING thread about one in three askers that are training bots go to the spot
 * the other suggested, if it is a real grind map for their level ({@link #offer}; the bot takes it on its next
 * decision through its normal grind path, {@link #take}).
 */
public final class TrainingTips {

    /** One spot: the map a bot walks to, and what players call it ("Ant Tunnel", "Kerning subway"). */
    public record Spot(int mapId, String name, String area) {
        /** For the prompt: "Terrace Hall (Ludibrium)". */
        public String described() {
            return area == null ? name : name + " (" + area + ")";
        }
    }

    private TrainingTips() {
    }

    // ── the spots ──

    /** Areas that are no place a player names ("Dungeon: Ant Tunnel II" is just "Ant Tunnel"). */
    private static final Set<String> NO_AREA = Set.of("dungeon", "hidden street", "victoria road", "warning street");

    /** What players call a leveling-path map: the street name without its numbering ("Ant Tunnel II" -> "Ant Tunnel"). */
    static Spot spotOf(LevelingPath.Entry e) {
        String full = e.name() == null ? "" : e.name();
        int colon = full.indexOf(':');
        String area = colon < 0 ? null : full.substring(0, colon).trim();
        String place = (colon < 0 ? full : full.substring(colon + 1)).replaceAll("<[^>]*>", " ")
                .replaceAll("\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
        place = place.replaceAll("(?i)\\s+(?:[IVX]+|\\d+)$", "").replaceAll("(?i)^the\\s+", "").trim();
        if (area != null && area.equalsIgnoreCase("Kerning City Subway")) {
            return new Spot(e.mapId(), "Kerning subway", null);
        }
        if (area != null && (NO_AREA.contains(area.toLowerCase(Locale.ROOT))
                || place.toLowerCase(Locale.ROOT).contains(area.toLowerCase(Locale.ROOT)))) {
            area = null;
        }
        return new Spot(e.mapId(), place.isEmpty() ? full : place, area);
    }

    /**
     * The real training spots for a bot of this level and job, best first (the path's weights), one per place name
     * ("Ant Tunnel II/III/IV" is one spot, its best map), at most {@code n}. Party-only spots are left out.
     */
    public static List<Spot> spotsFor(int level, int jobId, int n) {
        LevelingPath.Path path = LevelingPath.path();
        List<LevelingPath.Entry> entries = new ArrayList<>(path.candidates(path.bandIndex(level), jobId, 0.5));
        entries.removeIf(LevelingPath.Entry::party);
        entries.sort(Comparator.comparingDouble(LevelingPath.Entry::weight).reversed());
        Map<String, Spot> byName = new LinkedHashMap<>();
        for (LevelingPath.Entry e : entries) {
            Spot s = spotOf(e);
            byName.putIfAbsent(s.name().toLowerCase(Locale.ROOT), s);
            if (byName.size() >= n) {
                break;
            }
        }
        return List.copyOf(byName.values());
    }

    /** Whether this map is one the leveling path trains a bot of this level and job on. */
    public static boolean realSpot(int mapId, int level, int jobId) {
        LevelingPath.Path path = LevelingPath.path();
        for (LevelingPath.Entry e : path.candidates(path.bandIndex(level), jobId, 0.5)) {
            if (e.mapId() == mapId) {
                return true;
            }
        }
        return false;
    }

    /** Words of a spot name that a line must carry to name it ("the", "of" and short words do not count). */
    static List<String> keyWords(String name) {
        List<String> out = new ArrayList<>();
        for (String w : norm(name).split(" ")) {
            if (w.length() >= 4 || w.matches("[a-z]{3}") && !Set.of("the", "and", "for").contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * 1.6.0 (TEST trace 2026-10-27): what players type for a spot instead of its name ("subway", "golems", "mu lung"),
     * by the spot's normalised name. A line with one of these names that spot.
     */
    static final Map<String, List<String>> SAID_AS = Map.ofEntries(
            Map.entry("kerning subway", List.of("subway", "the subway", "kerning sub")),
            Map.entry("land of wild boar", List.of("wild boar", "wild boars", "boars", "boar land")),
            Map.entry("forest of golem", List.of("golem", "golems", "golem forest", "golem temple")),
            Map.entry("forest of dead trees", List.of("dead trees", "dead tree", "dead forest")),
            Map.entry("cave of evil eye", List.of("evil eye", "evil eyes")),
            Map.entry("drake hunting ground", List.of("drake", "drakes")),
            Map.entry("henesys hunting ground", List.of("hhg", "henesys hunting")),
            Map.entry("practice field beginner", List.of("mu lung", "mulung", "practice field")),
            Map.entry("eos tower 73rd floor", List.of("eos", "eos tower")),
            Map.entry("lost time", List.of("clocktower", "clock tower")),
            Map.entry("watch out for icy path", List.of("icy path")),
            Map.entry("pig beach", List.of("pigs", "pig beach")),
            Map.entry("ghost ship", List.of("ghost ship", "ghostship")),
            Map.entry("ant tunnel", List.of("ants", "ant tunnels")));

    /** Whether a line names this spot: its whole name, what players call it, or (for 2+ key words) all but one. */
    static boolean names(String line, Spot spot) {
        String text = " " + norm(line) + " ";
        String name = norm(spot.name());
        if (!name.isEmpty() && text.contains(" " + name + " ")) {
            return true;
        }
        for (String said : SAID_AS.getOrDefault(name, List.of())) {
            if (text.contains(" " + said + " ")) {
                return true;
            }
        }
        List<String> keys = keyWords(spot.name());
        if (keys.size() < 2) {
            return false;
        }
        int hit = 0;
        for (String k : keys) {
            if (text.contains(" " + k + " ") || text.contains(" " + k + "s ")) {
                hit++;
            }
        }
        return hit >= Math.max(2, keys.size() - 1);
    }

    /**
     * The spot a TRAINING thread suggested to {@code asker}: the first of {@code spots} a line of the other speaker
     * names, else one any line names; null when none.
     */
    public static Spot suggested(List<String[]> lines, String asker, List<Spot> spots) {
        for (boolean others : new boolean[]{true, false}) {
            for (String[] l : lines) {
                if (others && l[0].equalsIgnoreCase(asker)) {
                    continue;
                }
                for (Spot s : spots) {
                    if (names(l[1], s)) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    // ── checking the places a line names (the social trace's SENSE) ──

    /** What players call places the path names differently, and the party quests (with their own levels). */
    static final Map<String, String> NICKNAMES = Map.ofEntries(
            Map.entry("fire boar", "burnt land"), Map.entry("fire boars", "burnt land"),
            Map.entry("wild boar", "land of wild boar"), Map.entry("wild boars", "land of wild boar"),
            Map.entry("subway", "kerning subway"), Map.entry("golems", "forest of golem"),
            Map.entry("golem temple", "forest of golem"), Map.entry("drakes", "drake area"),
            Map.entry("clocktower", "lost time"), Map.entry("clock tower", "lost time"),
            Map.entry("ghost ship", "ghost ship"), Map.entry("ant tunnel", "ant tunnel"));

    /** Party quests by what players type, and their entry levels (LevelingPath.yaml party_quests). */
    static final Map<String, int[]> PQ_LEVELS = Map.of("hpq", new int[]{10, 20}, "kpq", new int[]{21, 30},
            "cpq", new int[]{30, 50}, "lpq", new int[]{35, 50}, "opq", new int[]{51, 70}, "ppq", new int[]{55, 90});

    /** Where each party quest and each boar lives, by the town word players use. */
    static final Map<String, String> HOME = Map.of("hpq", "henesys", "kpq", "kerning", "lpq", "ludi", "opq", "orbis",
            "wild boar", "perion", "wild boars", "perion", "fire boar", "perion", "fire boars", "perion");

    /** Town words a line may pair a PQ or a mob with, normalised to one key each. */
    static final Map<String, String> TOWNS = Map.ofEntries(Map.entry("henesys", "henesys"),
            Map.entry("kerning", "kerning"), Map.entry("subway", "kerning"), Map.entry("ludi", "ludi"),
            Map.entry("ludibrium", "ludi"), Map.entry("orbis", "orbis"), Map.entry("perion", "perion"),
            Map.entry("ellinia", "ellinia"), Map.entry("sleepywood", "sleepywood"), Map.entry("leafre", "leafre"));

    private record Range(int lo, int hi) {
    }

    private record Index(LevelingPath.Path of, Map<String, Range> byName) {
    }

    private static volatile Index index;

    /** Every spot the path trains at, by lower-case name, with the levels of the bands that hold it. */
    static Map<String, Range> ranges() {
        LevelingPath.Path path = LevelingPath.path();
        Index i = index;
        if (i == null || i.of() != path) {
            Map<String, Range> m = new LinkedHashMap<>();
            for (LevelingPath.Band b : path.bands()) {
                for (List<LevelingPath.Entry> es : b.groups().values()) {
                    for (LevelingPath.Entry e : es) {
                        String key = norm(spotOf(e).name());
                        if (key.length() < 4) {
                            continue;
                        }
                        Range r = m.get(key);
                        m.put(key, r == null ? new Range(b.lo(), b.hi())
                                : new Range(Math.min(r.lo(), b.lo()), Math.max(r.hi(), b.hi())));
                    }
                }
            }
            i = new Index(path, Map.copyOf(m));
            index = i;
        }
        return i.byName();
    }

    /** How far outside a place's levels a bot may still be sent or brag about it. */
    static final int SLACK = 5;

    /**
     * What is wrong with the places a TRAINING line names, for bots of these levels (the asker's and the
     * speaker's): a place the path never trains either level at ("burnt land is for levels 30-40, not 63"), a PQ
     * outside its levels, a PQ or boar put in the wrong town ("kpq is in kerning, not ludi"). Empty: fine.
     */
    public static List<String> wrongPlaces(String line, int... levels) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isBlank()) {
            return out;
        }
        String text = " " + norm(line) + " ";
        Map<String, Range> ranges = ranges();
        java.util.Set<String> checked = new java.util.HashSet<>();
        for (Map.Entry<String, String> nick : NICKNAMES.entrySet()) {
            if (text.contains(" " + nick.getKey() + " ")) {
                checkLevel(nick.getValue(), nick.getKey(), ranges.get(nick.getValue()), levels, checked, out);
            }
        }
        List<String> named = new ArrayList<>();
        for (String spot : ranges.keySet()) {
            if (spot.indexOf(' ') > 0 && text.contains(" " + spot + " ")) { // one word ("study") is just a word
                named.add(spot);
            }
        }
        for (String spot : named) {
            boolean part = false; // "path of time" inside "forgotten path of time" is that other place
            for (String other : named) {
                part |= other.length() > spot.length() && other.contains(spot);
            }
            if (!part) {
                checkLevel(spot, spot, ranges.get(spot), levels, checked, out);
            }
        }
        for (Map.Entry<String, int[]> pq : PQ_LEVELS.entrySet()) {
            if (text.contains(" " + pq.getKey() + " ")) {
                checkLevel(pq.getKey(), pq.getKey(), new Range(pq.getValue()[0], pq.getValue()[1]), levels, checked,
                        out);
            }
        }
        for (Map.Entry<String, String> home : HOME.entrySet()) {
            if (!text.contains(" " + home.getKey() + " ")) {
                continue;
            }
            for (Map.Entry<String, String> town : TOWNS.entrySet()) {
                if (text.contains(" " + town.getKey() + " ") && !town.getValue().equals(home.getValue())) {
                    out.add(home.getKey() + " is in " + home.getValue() + ", not " + town.getKey());
                    break;
                }
            }
        }
        return out;
    }

    private static void checkLevel(String key, String said, Range r, int[] levels, java.util.Set<String> checked,
                                   List<String> out) {
        if (r == null || !checked.add(key)) {
            return;
        }
        boolean fits = false;
        for (int lv : levels) {
            if (lv > 0 && lv >= r.lo() - SLACK && lv <= r.hi() + SLACK) {
                fits = true;
                break;
            }
        }
        boolean known = false;
        for (int lv : levels) {
            known |= lv > 0;
        }
        if (known && !fits) {
            out.add(said + " is for levels " + r.lo() + "-" + r.hi() + ", not " + levelsText(levels));
        }
    }

    private static String levelsText(int[] levels) {
        StringBuilder s = new StringBuilder();
        for (int lv : levels) {
            if (lv > 0 && s.indexOf(String.valueOf(lv)) < 0) {
                s.append(s.length() == 0 ? "" : "/").append(lv);
            }
        }
        return s.toString();
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    // ── following a tip ──

    /** How long a tip stands before the bot forgets it (the trace's ACT window). */
    static final long TIP_MS = TimeUnit.MINUTES.toMillis(15);

    record Tip(int mapId, long until) {
    }

    private static final Map<Integer, Tip> tips = new ConcurrentHashMap<>();

    /** The bot will train at {@code mapId} on its next decision (within {@link #TIP_MS}). */
    public static void offer(int botId, int mapId, long now) {
        tips.put(botId, new Tip(mapId, now + TIP_MS));
        if (tips.size() > 4096) { // a bot that never decides again (logged out): drop the stale ones
            tips.values().removeIf(t -> t.until() < now);
        }
    }

    /** The tip a deciding training bot follows now, or -1 (none, or it lapsed); taken once. */
    public static int take(int botId, long now) {
        Tip t = tips.remove(botId);
        return t == null || now > t.until() ? -1 : t.mapId();
    }

    /** The map this bot has a standing tip for, or -1 (not taken). */
    public static int pending(int botId) {
        Tip t = tips.get(botId);
        return t == null ? -1 : t.mapId();
    }

    static void clearForTest() {
        tips.clear();
    }
}
