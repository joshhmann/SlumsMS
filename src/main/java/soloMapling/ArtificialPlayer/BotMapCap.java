package soloMapling.ArtificialPlayer;

import client.Character;
import config.ServerConfig;
import config.YamlConfig;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1.5.0 (3k bots): the per-map bot cap. At most {@code BOT_MAP_CAP} bots (default 50; per-map
 * {@code BOT_MAP_CAP_OVERRIDES}) are put on one map instance - one map on one channel - by the systems that choose
 * where a bot goes on its own:
 * <ul>
 *   <li>the population director's login placement ({@link BotPopulationDirector}: the home map on the reserved
 *       channel, else the same map on another channel, else the map's return town);</li>
 *   <li>grind picks ({@code TrainingMapChooser}, including the leveling path, region moves and the first-trip warp),
 *       a world-event answer and a walk back after a death ({@code TrainingBot});</li>
 *   <li>town wandering ({@code TownWandererBot}, {@code HenesysBot}) and FM browsing ({@code FMBot});</li>
 *   <li>a bot opening a shop in an FM room ({@code Economy.BotShops}).</li>
 * </ul>
 * A full map makes the bot pick another map (or another channel's instance at login). Bots already on a map are
 * never moved: the cap only stops additions, so a map can sit above its cap (exempt arrivals, a lowered cap) and
 * drains as bots leave on their own.
 *
 * <p>Exempt but counted: staff and environment set pieces (merchants, dealers, casino tables, gacha, the boot town
 * dressing - they are placed where they stand on purpose and do not roam), bots following or partied with a real
 * player, chat-asked walks, story scenes (weddings, drama, guild boss runs), a grinder's short errands to its home
 * town or the sauna, and GM commands. They still count toward a map's load, so roaming bots fill only what is left.
 *
 * <p>Cheap: every {@link MapleMap} keeps its bot count (updated in addPlayer/removePlayer), and the instances at or
 * over their cap are kept in a small set, so a pick reads a counter or that set, never a player list.
 */
public final class BotMapCap {

    private static final Logger log = LoggerFactory.getLogger(BotMapCap.class);

    public static final int DEFAULT_CAP = 50;

    /** Map instances at or over their cap: channel << 32 | mapId. Usually empty or a handful. */
    private static final Set<Long> FULL = ConcurrentHashMap.newKeySet();
    /** Picks the cap turned away (a map skipped or a login moved), for the GM read-out. */
    private static final AtomicLong TURNED_AWAY = new AtomicLong();

    private record Parsed(int defaultCap, String spec, Map<Integer, Integer> overrides) {
    }

    private static volatile Parsed parsed;

    private BotMapCap() {
    }

    // ── pure ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Parses {@code "mapId:cap,mapId:cap"}. Blank parts are skipped; a malformed or negative entry is skipped (and
     * reported in {@code bad} when given), so a typo never turns the cap off elsewhere.
     */
    public static Map<Integer, Integer> parseOverrides(String spec, List<String> bad) {
        Map<Integer, Integer> out = new HashMap<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String[] kv = p.split(":");
            try {
                if (kv.length != 2) {
                    throw new NumberFormatException();
                }
                int mapId = Integer.parseInt(kv[0].trim());
                int cap = Integer.parseInt(kv[1].trim());
                if (mapId <= 0 || cap < 0) {
                    throw new NumberFormatException();
                }
                out.put(mapId, cap);
            } catch (NumberFormatException e) {
                if (bad != null) {
                    bad.add(p);
                }
            }
        }
        return out;
    }

    /** The cap of a map: its override, else the default; 0 (or less) = no cap. */
    public static int capFor(int mapId, int defaultCap, Map<Integer, Integer> overrides) {
        Integer o = overrides.get(mapId);
        return Math.max(0, o != null ? o : defaultCap);
    }

    /** Whether one more bot may be put on a map holding {@code bots} with this cap. */
    public static boolean hasRoom(int bots, int cap) {
        return cap <= 0 || bots < cap;
    }

    /**
     * The channel (1-based) whose instance of a map takes a bot: {@code preferred} while it has room, else the
     * channel with room holding the fewest bots on that map (ties to the lower channel); -1 when every instance is
     * full. {@code botsOnMap[i]} is channel i+1's count.
     */
    public static int pickChannel(int preferred, int[] botsOnMap, int cap) {
        int pi = preferred - 1;
        if (pi >= 0 && pi < botsOnMap.length && hasRoom(botsOnMap[pi], cap)) {
            return preferred;
        }
        int best = -1;
        for (int i = 0; i < botsOnMap.length; i++) {
            if (hasRoom(botsOnMap[i], cap) && (best < 0 || botsOnMap[i] < botsOnMap[best])) {
                best = i;
            }
        }
        return best < 0 ? -1 : best + 1;
    }

    static long key(int channel, int mapId) {
        return ((long) channel << 32) | (mapId & 0xFFFFFFFFL);
    }

    // ── config ───────────────────────────────────────────────────────────────────────────────────

    private static Parsed config() {
        ServerConfig server = YamlConfig.config == null ? null : YamlConfig.config.server;
        int def = server == null ? DEFAULT_CAP : server.BOT_MAP_CAP;
        String spec = server == null ? "" : server.BOT_MAP_CAP_OVERRIDES;
        Parsed p = parsed;
        if (p == null || p.defaultCap() != def || !java.util.Objects.equals(p.spec(), spec)) {
            List<String> bad = new ArrayList<>();
            p = new Parsed(def, spec, Collections.unmodifiableMap(parseOverrides(spec, bad)));
            if (!bad.isEmpty()) {
                log.warn("BOT_MAP_CAP_OVERRIDES: skipped {} (want mapId:cap,mapId:cap)", bad);
            }
            parsed = p;
        }
        return p;
    }

    /**
     * This map's cap from config.yaml; 0 = no cap. 1.6.0 towns: while the town pull is on (BOT_TOWN_PULL > 0) a town
     * map ({@link soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns}) without an override of its own takes
     * BOT_TOWN_MAP_MAX when that is higher, so BOT_MAP_CAP never cuts a town's busyness target.
     */
    public static int cap(int mapId) {
        Parsed p = config();
        int cap = capFor(mapId, p.defaultCap(), p.overrides());
        if (cap <= 0 || p.overrides().containsKey(mapId)) return cap;
        ServerConfig server = YamlConfig.config == null ? null : YamlConfig.config.server;
        if (server == null || !(server.BOT_TOWN_PULL > 0) || server.BOT_TOWN_MAP_MAX <= cap) return cap;
        return soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns.isTownMap(mapId) ? server.BOT_TOWN_MAP_MAX : cap;
    }

    /**
     * 1.6.0 madness (owner 2026-10-27, after a real-client density test: one map held 620 bots at ~1.5 GB and only
     * got laggy at ~1,000): a running invasion's map takes up to {@code WORLD_EVENT_MAP_MAX} bots (default
     * {@value #DEFAULT_WORLD_EVENT_MAP_MAX}) instead of its usual cap, only while the event runs (WorldEvents raises
     * it at the start and lowers it at the end).
     */
    public static final int DEFAULT_WORLD_EVENT_MAP_MAX = 200;
    private static final Map<Long, Integer> RAISED = new ConcurrentHashMap<>();

    /** The configured ceiling for a running world event's map. */
    public static int worldEventMapMax() {
        ServerConfig server = YamlConfig.config == null ? null : YamlConfig.config.server;
        return server == null ? DEFAULT_WORLD_EVENT_MAP_MAX : Math.max(0, server.WORLD_EVENT_MAP_MAX);
    }

    /** A world event runs on this map instance: its cap is {@link #worldEventMapMax()} until {@link #lower}. */
    public static void raise(int channel, int mapId) {
        RAISED.put(key(channel, mapId), raisedCap(cap(mapId), worldEventMapMax()));
    }

    public static void lower(int channel, int mapId) {
        if (RAISED.remove(key(channel, mapId)) != null) {
            FULL.add(key(channel, mapId)); // re-checked against its live count on the next pick (hasRoomOn)
        }
    }

    /** This map instance's cap now: a running world event's ceiling, else {@link #cap(int)}. */
    public static int capOn(int channel, int mapId) {
        Integer raised = RAISED.isEmpty() ? null : RAISED.get(key(channel, mapId));
        return raised != null ? raised : cap(mapId);
    }

    /** Pure (tested): an event's ceiling never lowers the map's own cap; 0 (no cap) stays no cap. */
    static int raisedCap(int own, int eventMax) {
        return own <= 0 ? 0 : Math.max(own, eventMax);
    }

    /** The overrides in force, for the GM read-out. */
    public static Map<Integer, Integer> overrides() {
        return config().overrides();
    }

    // ── live ─────────────────────────────────────────────────────────────────────────────────────

    /** Called by {@link MapleMap} after a bot entered or left it (outside the map's lock). Never throws. */
    public static void onBotCountChanged(MapleMap map) {
        try {
            long k = key(map.getChannelId(), map.getId());
            if (hasRoom(map.getBotCount(), capOn(map.getChannelId(), map.getId()))) {
                FULL.remove(k);
            } else {
                FULL.add(k);
            }
        } catch (RuntimeException e) {
            // the cap is advisory; a map's entry must never fail on it
        }
    }

    /** Whether one more bot may be put on this map instance. */
    public static boolean hasRoom(MapleMap map) {
        return map == null || hasRoom(map.getBotCount(), capOn(map.getChannelId(), map.getId()));
    }

    /** Whether one more bot may be put on this map of this channel. A map not loaded holds no bots. */
    public static boolean hasRoomOn(int channel, int mapId) {
        if (!FULL.contains(key(channel, mapId))) {
            return true; // not full (FULL is updated on every bot entry and exit)
        }
        Channel ch = channelOrNull(channel);
        if (ch == null) {
            return false; // no server (tests): trust the full set
        }
        if (!ch.getMapFactory().isMapLoaded(mapId)) {
            return true;
        }
        return hasRoom(ch.getMapFactory().getMap(mapId));
    }

    /**
     * Whether this bot may go to {@code mapId} on its own channel: true when it is already there (it is not an
     * addition) or the instance has room.
     */
    public static boolean mayEnter(Character bot, int mapId) {
        if (bot == null) {
            return true;
        }
        MapleMap here = bot.getMap();
        if (here != null && here.getId() == mapId) {
            return true;
        }
        return hasRoomOn(channelOf(bot), mapId);
    }

    /** {@link #mayEnter} that also counts a refusal for the GM read-out. */
    public static boolean admit(Character bot, int mapId) {
        if (mayEnter(bot, mapId)) {
            return true;
        }
        TURNED_AWAY.incrementAndGet();
        return false;
    }

    /** The map ids full on this channel (from the small full set; each re-checked against its live count). */
    public static Set<Integer> fullMaps(int channel) {
        Set<Integer> out = new HashSet<>(); // mutable: callers may drop their own map from it
        for (long k : FULL) {
            if ((int) (k >>> 32) == channel && !hasRoomOn(channel, (int) k)) {
                out.add((int) k);
            }
        }
        return out;
    }

    /**
     * {@code excluded} plus the maps full on the bot's channel, except the one it stands on. Returns
     * {@code excluded} itself when nothing is full (the usual case: no copy).
     */
    public static Set<Integer> excludeFull(Character bot, Set<Integer> excluded) {
        if (bot == null || FULL.isEmpty()) {
            return excluded;
        }
        Set<Integer> full = fullMaps(channelOf(bot));
        full.remove(bot.getMapId());
        if (full.isEmpty()) {
            return excluded;
        }
        Set<Integer> out = new HashSet<>(excluded == null ? Set.of() : excluded);
        out.addAll(full);
        return out;
    }

    private static Channel channelOrNull(int channel) {
        try {
            return BotChannels.channel(channel);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int channelOf(Character bot) {
        MapleMap map = bot.getMap();
        if (map != null) {
            return map.getChannelId();
        }
        return bot.getClient() == null ? 1 : bot.getClient().getChannel();
    }

    /** Counts one pick the cap turned away (a caller that handled the refusal itself). */
    public static void noteTurnedAway() {
        TURNED_AWAY.incrementAndGet();
    }

    public static long turnedAway() {
        return TURNED_AWAY.get();
    }

    // ── read-outs (GM command, health sweep) ─────────────────────────────────────────────────────

    /** One map instance's bot load. */
    public record Load(int channel, int mapId, int bots, int cap) {
        public boolean over() {
            return cap > 0 && bots > cap;
        }

        public boolean full() {
            return !hasRoom(bots, cap);
        }
    }

    /** Every loaded map instance holding bots, busiest first. Walks the loaded maps: for a GM or a sweep only. */
    public static List<Load> loads() {
        List<Load> out = new ArrayList<>();
        for (Channel ch : BotChannels.channels()) {
            for (MapleMap map : ch.getMapFactory().getMaps().values()) {
                int n = map.getBotCount();
                if (n > 0) {
                    out.add(new Load(ch.getId(), map.getId(), n, capOn(ch.getId(), map.getId())));
                }
            }
        }
        out.sort(BUSIEST);
        return out;
    }

    static final Comparator<Load> BUSIEST = Comparator.comparingInt(Load::bots).reversed()
            .thenComparingInt(Load::channel).thenComparingInt(Load::mapId);

    /** The busiest {@code n} instances of each channel, channel by channel. */
    public static Map<Integer, List<Load>> topPerChannel(List<Load> loads, int n) {
        Map<Integer, List<Load>> out = new java.util.TreeMap<>();
        for (Load l : loads) {
            List<Load> list = out.computeIfAbsent(l.channel(), k -> new ArrayList<>());
            if (list.size() < n) {
                list.add(l);
            }
        }
        return out;
    }

    /** The !botmapcap read-out: the cap, its overrides and the busiest maps of each channel. */
    public static List<String> describe(List<Load> loads, int perChannel, Map<Integer, Integer> overrides,
                                        int defaultCap, long turnedAway) {
        List<String> lines = new ArrayList<>();
        lines.add("Bot map cap: " + (defaultCap > 0 ? defaultCap + " per map instance" : "off")
                + (overrides.isEmpty() ? "" : ", overrides " + new java.util.TreeMap<>(overrides))
                + "; " + turnedAway + " pick(s) turned away since boot");
        long over = loads.stream().filter(Load::over).count();
        long full = loads.stream().filter(Load::full).count();
        lines.add(loads.size() + " map instance(s) hold bots; " + full + " at or over the cap, " + over + " over it");
        for (Map.Entry<Integer, List<Load>> e : topPerChannel(loads, perChannel).entrySet()) {
            StringBuilder sb = new StringBuilder("ch" + e.getKey() + ":");
            for (Load l : e.getValue()) {
                sb.append(' ').append(l.mapId()).append(' ').append(l.bots()).append('/')
                        .append(l.cap() > 0 ? String.valueOf(l.cap()) : "-");
                if (l.over()) {
                    sb.append('!');
                }
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    /** Test hook: forget the full set and the counter. */
    static void resetForTest() {
        FULL.clear();
        TURNED_AWAY.set(0);
        parsed = null;
    }

    /** Test hook: mark an instance full or not, as MapleMap would. */
    static void markForTest(int channel, int mapId, boolean full) {
        if (full) {
            FULL.add(key(channel, mapId));
        } else {
            FULL.remove(key(channel, mapId));
        }
    }
}
