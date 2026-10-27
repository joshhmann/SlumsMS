package soloMapling.site;

import client.Character;
import client.SyntheticClient;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 1.6.0 Track D, the website's news feed ({@code feed.json}, written by {@link PublicSiteExport}): small in-memory
 * rings, one per kind, fed by hooks in the game's own code paths. A hook only builds a short string and appends to a
 * bounded deque under a tiny lock: no database, no file, no packet, and it never throws into the caller.
 * <p>
 * Nothing is recorded until the exporter is switched on ({@link #setRecording}), so live without
 * {@code -Dslums.site.export=true} pays one volatile read per hook.
 * <ul>
 *   <li><b>event</b>: world events starting and ending (won, lost, ended), with the top damage name when there is one.</li>
 *   <li><b>milestone</b>: any character, bot or player: 2nd/3rd/4th job, level 70/120/200, rebirth, guild founded,
 *   wedding. GMs and the auto-QA pretend players never appear.</li>
 *   <li><b>megaphone</b>: megaphone, super megaphone, item and triple megaphones (players and bots); text cleaned,
 *   lines that fail the PG-13 guard dropped; kept 24 h.</li>
 *   <li><b>drama</b>: bot-only drama. Every party must be a bot (character id above the bot base); an item naming
 *   anyone else is refused here, whatever the caller passed.</li>
 * </ul>
 */
public final class SiteFeed {

    public enum Kind {
        EVENT, MILESTONE, MEGAPHONE, DRAMA;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind parse(Object raw) {
            for (Kind k : values()) {
                if (k.label().equals(raw)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** One feed item. {@code tag} says what kind of event, milestone or drama it is (an extra the site may use). */
    public record Item(String id, long t, Kind kind, String tag, String text, List<String> names, Integer map,
                       Integer channel, List<Top> top) {

        public Item(String id, long t, Kind kind, String tag, String text, List<String> names, Integer map,
                    Integer channel) {
            this(id, t, kind, tag, text, names, map, channel, null);
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("t", java.time.Instant.ofEpochMilli(t).toString());
            m.put("kind", kind.label());
            m.put("tag", tag);
            m.put("text", text);
            m.put("names", names);
            if (map != null) {
                m.put("map", map);
            }
            if (channel != null) {
                m.put("channel", channel);
            }
            if (top != null && !top.isEmpty()) {
                List<Object> rows = new ArrayList<>();
                for (Top x : top) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", x.name());
                    r.put("damage", x.damage());
                    rows.add(r);
                }
                m.put("top", rows);
            }
            return m;
        }
    }

    /** One of an event's top damage dealers (bot or player; never a GM or a pretend player). */
    public record Top(String name, long damage) {
    }

    /** Items in feed.json. */
    public static final int LIMIT = 200;
    /** Items kept per kind in memory (more than {@link #LIMIT}, so one busy kind cannot starve the others). */
    static final int RING = 300;
    /** At most this many megaphones in the file, so a megaphone storm cannot push out everything else. */
    public static final int MEGAPHONE_MAX = 80;
    public static final long KEEP_MS = TimeUnit.DAYS.toMillis(7);
    public static final long MEGAPHONE_KEEP_MS = TimeUnit.HOURS.toMillis(24);
    static final int TEXT_MAX = 160;
    public static final int[] LEVELS = {70, 120, 200};

    private static final Pattern NOT_PLAIN = Pattern.compile(
            "[\\p{Cntrl}\\p{Cf}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE00}-\\x{FE0F}\\x{E000}-\\x{F8FF}]|[\\x{10000}-\\x{10FFFF}]");

    private static final EnumMap<Kind, ArrayDeque<Item>> rings = new EnumMap<>(Kind.class);
    private static final AtomicLong seq = new AtomicLong();
    private static volatile boolean recording;
    private static volatile LongSupplier clock = System::currentTimeMillis;

    static {
        for (Kind k : Kind.values()) {
            rings.put(k, new ArrayDeque<>());
        }
    }

    private SiteFeed() {
    }

    public static void setRecording(boolean on) {
        recording = on;
    }

    public static boolean recording() {
        return recording;
    }

    // ── who may be named ────────────────────────────────────────────────────────────────────────

    /** Whether a character may appear on the public site: not a GM/staff character, not an auto-QA pretend player. */
    public static boolean isPublic(Character chr) {
        if (chr == null) {
            return false;
        }
        try {
            return isPublic(chr.getName(), chr.gmLevel(), chr.getClient() instanceof SyntheticClient);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean isPublic(String name, int gmLevel, boolean synthetic) {
        return name != null && !name.isBlank() && gmLevel <= 0 && !synthetic && !isQaName(name);
    }

    /** The auto-QA accounts' characters (AutoQaOne, AutoQaTwo, AutoQaO11 ...). */
    public static boolean isQaName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).startsWith("autoqa");
    }

    // ── hooks (all safe to call from any thread; none throws) ──────────────────────────────────

    /** A world event began. {@code mapId} 0 hides the map (a treasure hunt names only its region). */
    public static void eventStarted(String eventName, String where, int mapId, int channel) {
        if (!recording) {
            return;
        }
        try {
            String text = eventName + " began" + (blank(where) ? "" : " in " + where) + " (Ch " + channel + ").";
            add(Kind.EVENT, "start", text, List.of(), mapId > 0 ? mapId : null, channel);
        } catch (RuntimeException e) {
            // never into the game
        }
    }

    /**
     * A world event ended. {@code outcome}: {@code won} (beaten: a boss down, the treasure found, the cake eaten),
     * {@code lost} (time ran out on something that could be won) or {@code ended} (a hot spot or a visit is over).
     * {@code topName}: the top damage (or the finder); null when there is none.
     */
    public static void eventEnded(String eventName, String where, int mapId, int channel, String outcome,
                                  String topName) {
        eventEnded(eventName, where, mapId, channel, outcome, topName, null);
    }

    /** As above, with the top damage dealers ({@code top}, at most 3 kept; null or empty: none). */
    public static void eventEnded(String eventName, String where, int mapId, int channel, String outcome,
                                  String topName, List<Top> top) {
        if (!recording) {
            return;
        }
        try {
            String at = blank(where) ? "" : " in " + where;
            String text = switch (outcome) {
                case "won" -> eventName + at + " was won" + (blank(topName) ? "." : ". Top damage: " + topName + ".");
                case "lost" -> eventName + at + " ended with no winner.";
                default -> eventName + at + " is over.";
            };
            add(Kind.EVENT, outcome, text, blank(topName) ? List.of() : List.of(topName), mapId > 0 ? mapId : null,
                    channel, top == null || top.isEmpty() ? null : List.copyOf(top.subList(0, Math.min(3, top.size()))));
        } catch (RuntimeException e) {
            // never into the game
        }
    }

    /** Levels gained from {@code before} to the character's level now: one item per milestone level crossed. */
    public static void levelUps(Character chr, int before, int after) {
        if (!recording || after <= before || after < LEVELS[0] || !isPublic(chr)) {
            return;
        }
        try {
            for (int level : crossed(before, after)) {
                add(Kind.MILESTONE, "level", chr.getName() + " reached level " + level + ".", List.of(chr.getName()),
                        null, null);
            }
        } catch (RuntimeException e) {
            // never into the game
        }
    }

    static List<Integer> crossed(int before, int after) {
        List<Integer> out = new ArrayList<>();
        for (int l : LEVELS) {
            if (before < l && after >= l) {
                out.add(l);
            }
        }
        return out;
    }

    /** A job change: an item only for a real 2nd, 3rd or 4th job advancement along one line. */
    public static void jobChanged(Character chr, int oldJob, int newJob) {
        if (!recording || oldJob == newJob || !isPublic(chr)) {
            return;
        }
        try {
            int adv = advancement(oldJob, newJob);
            if (adv == 0) {
                return;
            }
            String job = jobName(newJob);
            add(Kind.MILESTONE, "job" + adv, chr.getName() + " advanced to " + ordinal(adv) + " job"
                    + (job.isEmpty() ? "." : ": " + job + "."), List.of(chr.getName()), null, null);
        } catch (RuntimeException e) {
            // never into the game
        }
    }

    /** 2, 3 or 4 when {@code newJob} is the next advancement after {@code oldJob}; 0 otherwise. Pure. */
    static int advancement(int oldJob, int newJob) {
        if (newJob / 100 == 22 || oldJob / 100 == 22) {
            return 0; // Evan's ten stages do not map onto 2nd/3rd/4th
        }
        int to = tier(newJob);
        int from = tier(oldJob);
        if (to < 2 || to > 4 || from != to - 1) {
            return 0;
        }
        boolean sameLine = to == 2 ? newJob / 100 == oldJob / 100 : newJob / 10 == oldJob / 10;
        return sameLine ? to : 0;
    }

    /** 0 beginner, 1 first job, 2-4 the later advancements (as {@code GameConstants.getJobBranch}). */
    static int tier(int jobId) {
        if (jobId % 1000 == 0) {
            return 0;
        }
        if (jobId % 100 == 0) {
            return 1;
        }
        return 2 + jobId % 10;
    }

    /** The display name the leaderboards use ("Mage (F/P)", "Chief Bandit"), not the enum name ("Fp_mage"). */
    static String jobName(int jobId) {
        return SiteLeaderboards.jobName(jobId);
    }

    private static String ordinal(int n) {
        return switch (n) {
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> n + "th";
        };
    }

    public static void rebirth(Character chr, int count) {
        if (!recording || !isPublic(chr)) {
            return;
        }
        try {
            add(Kind.MILESTONE, "rebirth", chr.getName() + " was reborn" + (count > 1 ? " (rebirth " + count + ")." : "."),
                    List.of(chr.getName()), null, null);
        } catch (RuntimeException e) {
            // never into the game
        }
    }

    /** A guild was founded. {@code leader} null or not public: the item names the guild only. */
    public static void guildFounded(String guildName, Character leader) {
        if (!recording || blank(guildName) || isQaName(guildName)) {
            return;
        }
        try {
            boolean named = isPublic(leader);
            if (leader != null && leader.gmLevel() > 0) {
                return; // a GM's guild (QA, tests) is not news
            }
            add(Kind.MILESTONE, "guild", named ? leader.getName() + " founded the guild " + guildName + "."
                    : "The guild " + guildName + " was founded.", named ? List.of(leader.getName()) : List.of(), null, null);
        } catch (RuntimeException e) {
            // never into the game
        }
    }

    /** A bot guild the server founded: its name and leader's IGN (bots are always public). */
    public static void guildFounded(String guildName, String leaderName) {
        if (!recording || blank(guildName)) {
            return;
        }
        try {
            add(Kind.MILESTONE, "guild", blank(leaderName) ? "The guild " + guildName + " was founded."
                            : leaderName + " founded the guild " + guildName + ".",
                    blank(leaderName) ? List.of() : List.of(leaderName), null, null);
        } catch (RuntimeException e) {
            // never into the game
        }
    }

    /** Two characters married (players, bots, or one of each). Callers pass only public names. */
    public static void wedding(String a, String b) {
        if (!recording || !validName(a) || !validName(b) || isQaName(a) || isQaName(b)) {
            return;
        }
        try {
            add(Kind.MILESTONE, "wedding", a + " and " + b + " got married.", List.of(a, b), null, null);
        } catch (RuntimeException e) {
            // never into the game
        }
    }

    /** A megaphone of any kind. The text is the message only; the IGN goes in {@code names}. */
    public static void megaphone(Character chr, String message) {
        if (!recording || !isPublic(chr)) {
            return;
        }
        try {
            String text = megaphoneText(message);
            if (text == null) {
                return;
            }
            Integer channel = null;
            try {
                channel = chr.getClient() == null ? null : chr.getClient().getChannel();
            } catch (RuntimeException e) {
                // a bot's shared client may not know: leave it out
            }
            add(Kind.MEGAPHONE, "megaphone", text, List.of(chr.getName()), null,
                    channel != null && channel > 0 ? channel : null);
        } catch (RuntimeException e) {
            // never into the game
        }
    }

    /** A megaphone's text made plain, or null when it is empty or fails the PG-13 guard. Pure. */
    static String megaphoneText(String message) {
        String t = plain(message);
        if (t.isEmpty() || ContentGuard.problem(t) != null) {
            return null;
        }
        return t;
    }

    /**
     * Bot-only drama. {@code ids} and {@code names} are the parties, in order; refused (false) unless every id is a
     * bot's (above the bot base id) and every name is given.
     */
    public static boolean drama(String tag, String text, int[] ids, String[] names) {
        if (!recording) {
            return false;
        }
        try {
            if (!botsOnly(ids, names)) {
                return false;
            }
            return add(Kind.DRAMA, tag, text, List.of(names), null, null) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Drama between two bot guilds (a war declared). {@code botGuilds}: the caller checked that both guilds are run
     * by bots; a war on a player guild is never fed. {@code names}: the guild names.
     */
    public static boolean guildDrama(String tag, String text, String[] names, boolean botGuilds) {
        if (!recording || !botGuilds || names == null || names.length == 0) {
            return false;
        }
        try {
            for (String n : names) {
                if (!validName(n)) {
                    return false;
                }
            }
            return add(Kind.DRAMA, tag, text, List.of(names), null, null) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The drama rule: at least one party, every party a bot with a name. Pure. */
    static boolean botsOnly(int[] ids, String[] names) {
        if (ids == null || names == null || ids.length == 0 || ids.length != names.length) {
            return false;
        }
        for (int i = 0; i < ids.length; i++) {
            if (!soloMapling.ArtificialPlayer.BotHelpers.isArtificial(ids[i]) || !validName(names[i])) {
                return false;
            }
        }
        return true;
    }

    // ── the rings ───────────────────────────────────────────────────────────────────────────────

    static Item add(Kind kind, String tag, String text, List<String> names, Integer map, Integer channel) {
        return add(kind, tag, text, names, map, channel, null);
    }

    static Item add(Kind kind, String tag, String text, List<String> names, Integer map, Integer channel, List<Top> top) {
        String clean = plain(text);
        if (clean.isEmpty()) {
            return null;
        }
        long t = clock.getAsLong();
        Item item = new Item(kind.label().charAt(0) + Long.toString(t, 36) + "-" + Long.toString(seq.incrementAndGet(), 36),
                t, kind, tag, clean, List.copyOf(names), map, channel, top);
        ArrayDeque<Item> ring = rings.get(kind);
        synchronized (ring) {
            ring.addLast(item);
            while (ring.size() > RING) {
                ring.pollFirst();
            }
        }
        return item;
    }

    /** What feed.json shows now: newest first, at most {@link #LIMIT}, megaphones 24 h and at most 80. */
    public static List<Item> snapshot(long now) {
        List<Item> all = new ArrayList<>();
        for (Kind k : Kind.values()) {
            ArrayDeque<Item> ring = rings.get(k);
            long keep = k == Kind.MEGAPHONE ? MEGAPHONE_KEEP_MS : KEEP_MS;
            synchronized (ring) {
                for (Item i : ring) {
                    if (now - i.t() <= keep && i.t() <= now + 60_000) {
                        all.add(i);
                    }
                }
            }
        }
        all.sort(Comparator.comparingLong(Item::t).reversed().thenComparing(Item::id, Comparator.reverseOrder()));
        List<Item> out = new ArrayList<>();
        int megaphones = 0;
        for (Item i : all) {
            if (out.size() >= LIMIT) {
                break;
            }
            if (i.kind() == Kind.MEGAPHONE && ++megaphones > MEGAPHONE_MAX) {
                continue;
            }
            out.add(i);
        }
        return out;
    }

    /** feed.json's document. */
    public static Map<String, Object> file(List<Item> items, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", PublicSiteExport.SCHEMA);
        m.put("generated_at", java.time.Instant.ofEpochMilli(now).toString());
        m.put("count", items.size());
        List<Object> rows = new ArrayList<>();
        for (Item i : items) {
            rows.add(i.toJson());
        }
        m.put("items", rows);
        return m;
    }

    /**
     * After a restart: the items of the last feed.json go back into the rings (the feed covers 7 days, the server
     * may restart any time). Items already in memory are kept; unknown or broken rows are skipped.
     */
    static int seed(Object doc) {
        if (!(doc instanceof Map<?, ?> m) || !(m.get("items") instanceof List<?> rows)) {
            return 0;
        }
        int n = 0;
        List<Item> items = new ArrayList<>();
        for (Object row : rows) {
            try {
                if (!(row instanceof Map<?, ?> r)) {
                    continue;
                }
                Kind kind = Kind.parse(r.get("kind"));
                if (kind == null || !(r.get("id") instanceof String id) || !(r.get("text") instanceof String text)) {
                    continue;
                }
                long t = java.time.Instant.parse(String.valueOf(r.get("t"))).toEpochMilli();
                List<String> names = new ArrayList<>();
                if (r.get("names") instanceof List<?> l) {
                    for (Object o : l) {
                        names.add(String.valueOf(o));
                    }
                }
                List<Top> top = null;
                if (r.get("top") instanceof List<?> tl) {
                    top = new ArrayList<>();
                    for (Object o : tl) {
                        if (o instanceof Map<?, ?> tm && tm.get("name") instanceof String tn && tm.get("damage") instanceof Number d) {
                            top.add(new Top(tn, d.longValue()));
                        }
                    }
                }
                items.add(new Item(id, t, kind, r.get("tag") instanceof String s ? s : "", text, List.copyOf(names),
                        r.get("map") instanceof Number x ? x.intValue() : null,
                        r.get("channel") instanceof Number c ? c.intValue() : null, top));
            } catch (RuntimeException e) {
                // a broken row: skip it
            }
        }
        items.sort(Comparator.comparingLong(Item::t));
        for (Item i : items) {
            ArrayDeque<Item> ring = rings.get(i.kind());
            synchronized (ring) {
                if (ring.stream().anyMatch(x -> x.id().equals(i.id()))) {
                    continue;
                }
                // older than anything recorded since boot: in front, keeping time order
                List<Item> merged = new ArrayList<>(ring);
                merged.add(i);
                merged.sort(Comparator.comparingLong(Item::t));
                ring.clear();
                ring.addAll(merged);
                while (ring.size() > RING) {
                    ring.pollFirst();
                }
            }
            n++;
        }
        return n;
    }

    // ── text ────────────────────────────────────────────────────────────────────────────────────

    /** Plain text: no control or format characters, no emoji or symbols outside the BMP, single spaces, capped. */
    static String plain(String s) {
        if (s == null) {
            return "";
        }
        String t = NOT_PLAIN.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (t.length() > TEXT_MAX) {
            t = t.substring(0, TEXT_MAX - 3).trim() + "...";
        }
        return t;
    }

    /** A shown name: not blank, not an unresolved "#id" placeholder. */
    static boolean validName(String s) {
        return !blank(s) && !s.startsWith("#");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ── test seams ──

    static void resetForTest(LongSupplier testClock) {
        for (ArrayDeque<Item> ring : rings.values()) {
            synchronized (ring) {
                ring.clear();
            }
        }
        clock = testClock != null ? testClock : System::currentTimeMillis;
        recording = testClock != null;
    }
}
