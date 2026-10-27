package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import config.YamlConfig;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntUnaryOperator;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

/**
 * 1.6.0 towns (owner 2026-10-27: "towns should just be bustling"): a busyness target for every town on every channel,
 * and a pull on free bots toward towns below it. Nothing spawns: bots fill a town only through their own HANG_OUT and
 * SHOW_OFF choices, with this pull as one more bias, at normal decision points (never mid-grind or mid-errand), inside
 * the town's level band ({@link LifeTowns}), on their own continent, under a per-instance budget a minute, and never
 * past the map cap ({@link LifeTownSlots#reserve}).
 *
 * <p>The index ({@link #refresh}, each director tick): per channel, per town map: target and bots there. A town's
 * target is its peak ({@code BOT_TOWN_BUSY_TARGET} over {@link LifeTowns#TOWNS}) x the director's curve share (online
 * / peak) x the channel's weight ({@code BOT_TOWN_CHANNEL_WEIGHTS}; 1.0 where a real player is online), floored at
 * the trough (4 for the big eight, 2 for the rest), x {@code BOT_TOWN_PLAYER_BOOST} where a visible real player
 * stands in the town; all targets together stay under {@code BOT_TOWN_SHARE_MAX} of the director's bots online
 * (scaled down alike); a town's target is split over its map family, each instance at most {@code BOT_TOWN_MAP_MAX}
 * (which is also a town map's bot cap while the pull is on, {@link soloMapling.ArtificialPlayer.BotMapCap#cap}).
 * Cost: ~19 town maps x channels, {@code getBotCount()} each; real players are looked for only on a channel with one
 * connected. Off ({@code BOT_TOWN_PULL=0}): no index, no pull, nothing registered.
 */
public final class TownPulse {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TownPulse.class);
    private TownPulse() {}

    /** One town map instance in the index: its target and the bots on it at the last refresh. */
    public record Slot(int channel, int mapId, int target, int bots) {}

    /** What the index needs from one channel. */
    public record ChannelView(int channel, boolean playerOnline, Set<Integer> playerTowns, IntUnaryOperator botsOn) {}

    /** The town a bot is pulled to and how short it is (0..1]. */
    public record Pull(int mapId, double deficit) {}

    static final int BIG_FLOOR = 4, SMALL_FLOOR = 2;
    private static volatile Map<Integer, List<Slot>> byChannel = Map.of();
    private static volatile int targetSum;
    private static final Map<Long, long[]> budget = new ConcurrentHashMap<>();
    private static final Map<Integer, Pull> offers = new ConcurrentHashMap<>();
    private static final LongAdder offered = new LongAdder(), pulled = new LongAdder(), budgetFull = new LongAdder();
    private static final Map<LifeTowns.Source, LongAdder> sources = new ConcurrentHashMap<>();
    private static volatile boolean registered;

    // ── config ───────────────────────────────────────────────────────────────────────────────────

    private static config.ServerConfig server() { return YamlConfig.config == null ? null : YamlConfig.config.server; }

    /** BOT_TOWN_PULL, 0..{@link LifePolicy#MAX_PLUGIN_BIAS}; 0 = off. */
    public static double pull() {
        var s = server();
        double p = s == null ? 0 : s.BOT_TOWN_PULL;
        return !Double.isFinite(p) || p <= 0 ? 0 : Math.min(LifePolicy.MAX_PLUGIN_BIAS, p);
    }

    public static boolean on() { return pull() > 0; }

    /** Each town's peak target: the table, then {@code spec} ("mapId:n,..."; a bare number sets every town). */
    public static Map<Integer, Integer> peaks(String spec) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (LifeTowns.Town town : LifeTowns.TOWNS) out.put(town.mapId(), town.peak());
        if (spec == null || spec.isBlank()) return out;
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            try {
                if (kv.length == 1) {
                    int all = Math.max(0, Integer.parseInt(kv[0].trim()));
                    out.replaceAll((k, v) -> all);
                } else if (kv.length == 2) {
                    LifeTowns.Town town = LifeTowns.townOf(Integer.parseInt(kv[0].trim()));
                    if (town != null) out.put(town.mapId(), Math.max(0, Integer.parseInt(kv[1].trim())));
                }
            } catch (NumberFormatException ignored) { /* a broken entry is skipped */ }
        }
        return out;
    }

    /** {@code BOT_TOWN_CHANNEL_WEIGHTS}: channel -> weight; a channel past the list takes the smallest weight. */
    public static Map<Integer, Double> channelWeights(String spec) {
        Map<Integer, Double> out = new TreeMap<>();
        if (spec != null) for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) continue;
            try {
                double w = Double.parseDouble(kv[1].trim());
                if (Double.isFinite(w) && w >= 0) out.put(Integer.parseInt(kv[0].trim()), w);
            } catch (NumberFormatException ignored) { /* skipped */ }
        }
        return out;
    }

    static double weightOf(Map<Integer, Double> weights, int channel) {
        Double w = weights.get(channel);
        if (w != null) return w;
        return weights.isEmpty() ? 1.0 : Collections.min(weights.values());
    }

    // ── pure: the targets ────────────────────────────────────────────────────────────────────────

    /**
     * The index: per channel, every town map instance with its target and bots. {@code share} is the curve share
     * (director online / peak, clamped 0..1); {@code directorOnline} bounds the total ({@code shareMax}).
     */
    public static Map<Integer, List<Slot>> plan(List<ChannelView> channels, Map<Integer, Integer> peaks,
                                                Map<Integer, Double> channelWeights, double share, double boost,
                                                int mapMax, double shareMax, int directorOnline) {
        double curve = Double.isFinite(share) ? Math.max(0, Math.min(1, share)) : 1;
        double boostBy = Double.isFinite(boost) && boost > 0 ? boost : 1;
        List<double[]> raw = new ArrayList<>(); // per channel, per town
        double sum = 0;
        for (ChannelView view : channels) {
            double weight = view.playerOnline() ? 1.0 : weightOf(channelWeights, view.channel());
            double[] row = new double[LifeTowns.TOWNS.size()];
            for (int i = 0; i < row.length; i++) {
                LifeTowns.Town town = LifeTowns.TOWNS.get(i);
                int peak = peaks.getOrDefault(town.mapId(), 0);
                if (peak <= 0 || weight <= 0) continue;
                double t = Math.max(peak * curve * weight, town.big() ? BIG_FLOOR : SMALL_FLOOR);
                if (view.playerTowns().contains(town.mapId())) t *= boostBy;
                row[i] = t;
                sum += t;
            }
            raw.add(row);
        }
        double limit = Math.max(0, shareMax) * Math.max(0, directorOnline);
        double scale = sum > limit ? (sum <= 0 ? 0 : limit / sum) : 1;
        Map<Integer, List<Slot>> out = new TreeMap<>();
        for (int c = 0; c < channels.size(); c++) {
            ChannelView view = channels.get(c);
            List<Slot> slots = new ArrayList<>();
            for (int i = 0; i < LifeTowns.TOWNS.size(); i++) {
                double t = raw.get(c)[i] * scale;
                if (t <= 0) continue;
                LifeTowns.Town town = LifeTowns.TOWNS.get(i);
                for (int f = 0; f < town.family().length; f++) {
                    int mapId = town.family()[f];
                    int target = Math.min(Math.max(0, mapMax), (int) Math.round(t * town.shares()[f]));
                    if (target > 0) slots.add(new Slot(view.channel(), mapId, target, view.botsOn().applyAsInt(mapId)));
                }
            }
            out.put(view.channel(), List.copyOf(slots));
        }
        return out;
    }

    /** The sum of the index's targets. */
    static int total(Map<Integer, List<Slot>> index) {
        int n = 0;
        for (List<Slot> slots : index.values()) for (Slot s : slots) n += s.target();
        return n;
    }

    // ── live ─────────────────────────────────────────────────────────────────────────────────────

    /** Each director tick: rebuild the index (off: drop it). Never throws. */
    public static void refresh(int directorOnline, int directorPeak) {
        try {
            if (!on()) {
                byChannel = Map.of();
                targetSum = 0;
                return;
            }
            register();
            var s = server();
            List<ChannelView> views = new ArrayList<>();
            for (var channel : soloMapling.ArtificialPlayer.BotChannels.channels()) {
                var factory = channel.getMapFactory();
                Set<Integer> towns = new HashSet<>();
                boolean player = false;
                if (channel.getPlayerStorage().getConnectedPlayerCount() > 0) {
                    for (Character c : channel.getPlayerStorage().getAllCharacters()) {
                        if (BotHelpers.isBot(c) || !BotHelpers.isVisibleRealPlayer(c)) continue;
                        player = true;
                        LifeTowns.Town town = LifeTowns.townOf(c.getMapId());
                        if (town != null) towns.add(town.mapId());
                    }
                }
                views.add(new ChannelView(channel.getId(), player, towns,
                        id -> factory.isMapLoaded(id) ? factory.getMap(id).getBotCount() : 0));
            }
            var index = plan(views, peaks(s.BOT_TOWN_BUSY_TARGET), channelWeights(s.BOT_TOWN_CHANNEL_WEIGHTS),
                    directorPeak > 0 ? directorOnline / (double) directorPeak : 1, s.BOT_TOWN_PLAYER_BOOST,
                    s.BOT_TOWN_MAP_MAX, s.BOT_TOWN_SHARE_MAX, directorOnline);
            byChannel = index;
            targetSum = total(index);
            long minute = System.currentTimeMillis() / 60_000;
            budget.values().removeIf(b -> b[0] < minute - 1);
        } catch (RuntimeException | LinkageError failure) {
            log.debug("Town pulse unavailable", failure);
        }
    }

    /** Auto-QA t-tw-1: the index as of the last director tick (channel -> town map instances); empty when off. */
    public static Map<Integer, List<Slot>> index() {
        return byChannel;
    }

    /** QA/test seam: the index as given. */
    static void index(Map<Integer, List<Slot>> index) {
        byChannel = Map.copyOf(index);
        targetSum = total(index);
    }

    /**
     * Where a free bot on {@code channel} at {@code level} standing on {@code mapId} is pulled to, or null: a town
     * map instance below its target (counting bots on their way, {@link LifeTownSlots}), in the bot's band and on
     * its continent, with budget left this minute. Weighted by deficit, stable for the bot over 30 s.
     */
    public static Pull pullFor(int botId, int channel, int level, int mapId, long now) {
        List<Slot> slots = byChannel.get(channel);
        if (slots == null || slots.isEmpty()) return null;
        int continent = mapId / 100_000_000;
        int perMinute = Math.max(0, server() == null ? 3 : server().BOT_TOWN_PULL_PER_MIN);
        long minute = now / 60_000;
        double sum = 0;
        double[] deficits = new double[slots.size()];
        boolean budgetBlocked = false;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            LifeTowns.Town town = LifeTowns.townOf(slot.mapId());
            if (town == null || !town.fits(level) || town.continent() != 0 && town.continent() != continent
                    || slot.target() <= 0) continue;
            int here = slot.mapId() == mapId ? 0 : 1; // a bot already there is in the count; it may stay
            double d = Math.max(0, slot.target() - slot.bots() - LifeTownSlots.incoming(channel, slot.mapId())
                    + (1 - here)) / (double) slot.target();
            if (d <= 0) continue;
            if (here == 1 && used(channel, slot.mapId(), minute) >= perMinute) { budgetBlocked = true; continue; }
            deficits[i] = Math.min(1, d);
            sum += deficits[i];
        }
        if (sum <= 0) {
            if (budgetBlocked) budgetFull.increment();
            return null;
        }
        double roll = new SplittableRandom(botId * 131L + now / 30_000).nextDouble() * sum;
        int chosen = -1;
        for (int i = 0; i < slots.size(); i++) {
            if (deficits[i] <= 0) continue;
            chosen = i;
            roll -= deficits[i];
            if (roll < 0) break;
        }
        offered.increment();
        return new Pull(slots.get(chosen).mapId(), deficits[chosen]);
    }

    private static long key(int channel, int mapId) { return ((long) channel << 32) | (mapId & 0xffffffffL); }

    private static long used(int channel, int mapId, long minute) {
        long[] b = budget.get(key(channel, mapId));
        return b == null || b[0] != minute ? 0 : b[1];
    }

    /** A bot went to a pulled town: spends a unit of the instance's budget this minute. */
    public static void consume(int channel, int mapId, long now) {
        long minute = now / 60_000;
        budget.compute(key(channel, mapId), (k, b) -> b == null || b[0] != minute ? new long[]{minute, 1}
                : new long[]{minute, b[1] + 1});
        pulled.increment();
    }

    /** Where an accepted hang-out went (bot-life.json). */
    public static void hangOut(LifeTowns.Source source) {
        if (source != null) sources.computeIfAbsent(source, k -> new LongAdder()).increment();
    }

    /** The pull a decision may add (set before the scorer, cleared after); null clears. */
    static void offer(int botId, Pull pull) {
        if (pull != null && pull.deficit() > 0) offers.put(botId, pull); else offers.remove(botId);
    }

    /**
     * The pull as a {@link LifePolicy.Bias}: {@code BOT_TOWN_PULL} x the deficit, on HANG_OUT and SHOW_OFF (which go
     * to the pulled map), and on FREE_MARKET and SELL too when the Free Market is the one short of visitors.
     */
    static final LifePolicy.Bias BIAS = (activity, context, situation) -> {
        if (situation == null || context == null || context.commitment()) return 0;
        Pull offer = offers.get(situation.botId());
        if (offer == null) return 0;
        boolean market = LifeTowns.townOf(offer.mapId()) != null
                && LifeTowns.townOf(offer.mapId()).mapId() == LifeTowns.FREE_MARKET;
        boolean pulls = activity == Activity.HANG_OUT || activity == Activity.SHOW_OFF
                || market && (activity == Activity.FREE_MARKET || activity == Activity.SELL);
        return pulls ? pull() * offer.deficit() : 0;
    };

    static synchronized void register() {
        if (!registered) {
            LifePolicy.addBias(BIAS);
            registered = true;
        }
    }

    /** bot-life.json's "towns": counters, the index's total and per-town bots/targets over every channel. */
    public static Map<String, Object> report() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pull", pull());
        out.put("offered", offered.sum());
        out.put("pulled", pulled.sum());
        out.put("budgetFull", budgetFull.sum());
        out.put("targetSum", targetSum);
        Map<String, Long> from = new TreeMap<>();
        sources.forEach((k, v) -> from.put(k.name().toLowerCase(Locale.ROOT), v.sum()));
        out.put("hangOutFrom", from);
        Map<String, int[]> towns = new TreeMap<>();
        for (List<Slot> slots : byChannel.values()) for (Slot s : slots) {
            LifeTowns.Town town = LifeTowns.townOf(s.mapId());
            if (town == null) continue;
            int[] t = towns.computeIfAbsent(town.name(), k -> new int[2]);
            t[0] += s.bots();
            t[1] += s.target();
        }
        Map<String, List<Integer>> rows = new TreeMap<>();
        towns.forEach((name, t) -> rows.put(name, List.of(t[0], t[1])));
        out.put("botsVsTarget", rows);
        return out;
    }
}
