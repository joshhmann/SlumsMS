package soloMapling.ArtificialPlayer;

import client.Character;
import config.YamlConfig;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import server.maps.MapleMap;
import soloMapling.server.SoloMaplingConstants.GameConstants;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ticket 57: which channel a bot lives on. Bots used to live on channel 1 only; now they spread over the world's
 * channels like a real server's players - channel 1 busiest, falling toward the last ({@code BOT_CHANNEL_WEIGHTS}).
 *
 * <p>A bot's channel is its client's ({@link BotClientHandler#getBotClient(int)}), so the channel's player storage,
 * map factory and every {@code changeMap} follow it. Who chooses:
 * <ul>
 *   <li>The population director picks the channel of each login by deficit against the target split
 *       ({@link #reserve()}), counting every bot online, so the split holds as bots come and go.</li>
 *   <li>The boot environment's town dressing (the wave tasks it marks with {@link #spreadTask}) is scaled by
 *       {@code BOT_ENVIRONMENT_SCALE} ({@link #envCount}) and split by the same weights among itself, bot by bot
 *       ({@link #reserveDressing()}), so every channel's towns get their share. Set pieces - the tutorial bot,
 *       casino tables, drop game, OPQ lobby, gacha - stay together on channel 1; the director's logins even out
 *       the total around them.</li>
 *   <li>Everything else (GM spawns, a player's companion) spawns on the channel of the map it is given.</li>
 *   <li>A companion follows its player to another channel ({@link #moveToChannel}).</li>
 * </ul>
 * Real players are untouched: nothing here moves or counts them.
 */
public final class BotChannels {

    /** The shipped split when the config is blank or unreadable. */
    // Owner 2026-10-21 (1.4.1 QA): five channels whose bars read 100/85/50/30/20 % at the peak, "like it was before";
    // channel 5 is the quiet one. With 1,200 bots at 0.25 each, CHANNEL_LOAD 105 puts channel 1 at 100 %.
    static final double[] DEFAULT_WEIGHTS = {100, 55, 35, 20, 15};

    /** Wave tasks of the boot plan whose bots are spread over the channels (and scaled): wave * 1024 + task. */
    private static final Set<Integer> SPREAD_TASKS = ConcurrentHashMap.newKeySet();
    /** The spread tasks that dress the FM entrance (FM bots, merchants): scaled by BOT_FM_ENVIRONMENT_SCALE instead. */
    private static final Set<Integer> MARKET_TASKS = ConcurrentHashMap.newKeySet();
    /** 1.6.0 towns: the spread tasks that dress Henesys (HenesysBots, fillers, the pet park): BOT_TOWN_DRESSING_SCALE. */
    private static final Set<Integer> TOWN_TASKS = ConcurrentHashMap.newKeySet();

    /** 1.6.0 t-tw-1: the Henesys town dressing at boot: bots asked for, bots spawned, cohorts (see {@link #envCount}). */
    private static final java.util.concurrent.atomic.AtomicInteger townAsked = new java.util.concurrent.atomic.AtomicInteger(),
            townGiven = new java.util.concurrent.atomic.AtomicInteger(),
            townCohorts = new java.util.concurrent.atomic.AtomicInteger();

    /** Bots chosen for a channel but not yet in its player storage, so parallel logins do not all pick one channel. */
    private static final int[] inFlight = new int[64];
    /** The same for the environment's town dressing, which is split among itself (see {@link #reserveDressing()}). */
    private static final int[] dressingInFlight = new int[64];
    /** Serialises companions' channel changes. */
    private static final Object HOP_LOCK = new Object();

    private BotChannels() {
    }

    // ── pure ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The target share of each channel (index 0 = channel 1), summing to 1. Weights come as a comma list; channels
     * past the list take its last weight, extra weights are ignored, a blank or broken list falls back to the
     * shipped one, and a list of all zeros means every channel alike.
     */
    public static double[] shares(String spec, int channels) {
        if (channels <= 0) {
            return new double[0];
        }
        double[] weights = parse(spec);
        double[] out = new double[channels];
        double sum = 0;
        for (int i = 0; i < channels; i++) {
            out[i] = weights.length == 0 ? 1.0 : weights[Math.min(i, weights.length - 1)];
            sum += out[i];
        }
        for (int i = 0; i < channels; i++) {
            out[i] = sum > 0 ? out[i] / sum : 1.0 / channels;
        }
        return out;
    }

    private static double[] parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return DEFAULT_WEIGHTS;
        }
        List<Double> list = new ArrayList<>();
        for (String part : spec.split(",")) {
            try {
                double w = Double.parseDouble(part.trim());
                if (!Double.isFinite(w) || w < 0) {
                    return DEFAULT_WEIGHTS;
                }
                list.add(w);
            } catch (NumberFormatException e) {
                return DEFAULT_WEIGHTS;
            }
        }
        return list.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * The channel (0-based) the next bot goes to: the one furthest below its share of the population once that bot
     * is counted. Ties go to the lower channel. Applied one login at a time this tracks the shares within a bot.
     */
    public static int pickByDeficit(int[] counts, double[] shares) {
        int n = Math.min(counts.length, shares.length);
        if (n == 0) {
            return 0;
        }
        long total = 0;
        for (int i = 0; i < n; i++) {
            total += counts[i];
        }
        int best = 0;
        double bestDeficit = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double deficit = shares[i] * (total + 1) - counts[i];
            if (deficit > bestDeficit + 1e-9) {
                bestDeficit = deficit;
                best = i;
            }
        }
        return best;
    }

    /**
     * A cohort's headcount after the environment scale: {@code n * scale}, rounded up or down at random in
     * proportion ({@code u} in [0,1)), so small cohorts still show up sometimes and the total keeps its expectation.
     */
    public static int scaledCount(int n, double scale, double u) {
        if (n <= 0) {
            return 0;
        }
        if (!(scale < 1.0)) {
            return n;
        }
        double exact = n * Math.max(0.0, scale);
        return Math.min(n, (int) Math.floor(exact + u));
    }

    /** The director's peak after the population cap: what is left of it once the environment is counted. */
    public static int directorPeak(int rosterPeak, int populationPeak, int environmentOnline) {
        int peak = Math.max(0, rosterPeak);
        if (populationPeak <= 0) {
            return peak;
        }
        return Math.max(0, Math.min(peak, populationPeak - Math.max(0, environmentOnline)));
    }

    static int taskKey(int wave, int task) {
        return wave * 1024 + task;
    }

    // ── environment spread ───────────────────────────────────────────────────────────────────────

    /** Marks one boot-wave task as town dressing: its bots are scaled and spread over the channels. */
    public static void spreadTask(int wave, int task) {
        spreadTask(wave, task, false);
    }

    /**
     * 1.5.2 hotfix: marks one boot-wave task as town dressing; {@code market} tasks (the FM entrance's FM bots and
     * merchants) are scaled by {@code BOT_FM_ENVIRONMENT_SCALE} rather than {@code BOT_ENVIRONMENT_SCALE}.
     */
    public static void spreadTask(int wave, int task, boolean market) {
        spreadTask(wave, task, market, false);
    }

    /**
     * 1.6.0 towns: as {@link #spreadTask(int, int, boolean)}; {@code town} tasks (the Henesys town dressing) are scaled
     * by {@code BOT_TOWN_DRESSING_SCALE} when it is set (0..1), else by {@code BOT_ENVIRONMENT_SCALE} as before.
     */
    public static void spreadTask(int wave, int task, boolean market, boolean town) {
        SPREAD_TASKS.add(taskKey(wave, task));
        if (market) {
            MARKET_TASKS.add(taskKey(wave, task));
        } else {
            MARKET_TASKS.remove(taskKey(wave, task));
        }
        if (town && !market) {
            TOWN_TASKS.add(taskKey(wave, task));
        } else {
            TOWN_TASKS.remove(taskKey(wave, task));
        }
    }

    /** Whether a bot of this slot dresses Henesys (a town spread task). */
    static boolean isTownSlot(int slot) {
        int wave = BotStore.waveOf(slot);
        return wave > 0 && TOWN_TASKS.contains(taskKey(wave, BotStore.taskOf(slot)));
    }

    /** Whether a bot of this slot dresses the FM entrance (a market spread task). */
    static boolean isMarketSlot(int slot) {
        int wave = BotStore.waveOf(slot);
        return wave > 0 && MARKET_TASKS.contains(taskKey(wave, BotStore.taskOf(slot)));
    }

    /** Whether a bot of this slot belongs to a spread environment task. */
    public static boolean isSpreadSlot(int slot) {
        int wave = BotStore.waveOf(slot);
        return wave > 0 && SPREAD_TASKS.contains(taskKey(wave, BotStore.taskOf(slot)));
    }

    /** A cohort's headcount: scaled by {@code BOT_ENVIRONMENT_SCALE} when its slots belong to a spread task. */
    public static int envCount(int slotBase, int n) {
        if (!isSpreadSlot(slotBase)) {
            return n;
        }
        int given = scaledCount(n, environmentScale(slotBase), ThreadLocalRandom.current().nextDouble());
        if (isTownSlot(slotBase)) {
            townAsked.addAndGet(Math.max(0, n));
            townGiven.addAndGet(given);
            townCohorts.incrementAndGet();
        }
        return given;
    }

    /** 1.6.0 t-tw-1: the Henesys town dressing since boot: {bots asked for, bots spawned, cohorts}. */
    public static int[] townDressingCounts() {
        return new int[]{townAsked.get(), townGiven.get(), townCohorts.get()};
    }

    /** 1.6.0 t-tw-1: the share of the Henesys town dressing that spawns (BOT_TOWN_DRESSING_SCALE, else the environment's). */
    public static double effectiveTownDressingScale() {
        return townDressingScale(environmentScale());
    }

    /** The environment scale; a bot-shop chance or similar odds scale by it too. */
    public static double environmentScale(int slot) {
        if (!isSpreadSlot(slot)) {
            return 1.0;
        }
        if (isMarketSlot(slot)) {
            return marketScale();
        }
        return isTownSlot(slot) ? townDressingScale(environmentScale()) : environmentScale();
    }

    /** 1.6.0 towns: BOT_TOWN_DRESSING_SCALE clamped to 0..1; a negative (the shipped -1) follows {@code fallback}. */
    static double townDressingScale(double fallback) {
        double scale = YamlConfig.config.server == null ? -1 : YamlConfig.config.server.BOT_TOWN_DRESSING_SCALE;
        return !Double.isFinite(scale) || scale < 0 ? fallback : Math.min(1.0, scale);
    }

    /*
     * 1.5.2 hotfix: the FM entrance spawned BOT_ENVIRONMENT_SCALE (0.5) of its 90 FM bots and merchants and split
     * them over five channels, so channel 1's entrance held about ten merchants where 1.4.x had all 90 on channel 1.
     */
    private static double marketScale() {
        return YamlConfig.config.server == null ? 1.0 : YamlConfig.config.server.BOT_FM_ENVIRONMENT_SCALE;
    }

    private static double environmentScale() {
        return YamlConfig.config.server == null ? 1.0 : YamlConfig.config.server.BOT_ENVIRONMENT_SCALE;
    }

    // ── live ─────────────────────────────────────────────────────────────────────────────────────

    private static World world() {
        return Server.getInstance().getWorld(GameConstants.homeWorld());
    }

    /** The home world's channels, in order (channel 1 first). */
    public static List<Channel> channels() {
        World w = world();
        return w == null ? List.of() : w.getChannels();
    }

    public static Channel channel(int ch) {
        World w = world();
        return w == null ? null : w.getChannel(ch);
    }

    /** One map of one channel; null when the channel does not exist. */
    public static MapleMap map(int ch, int mapId) {
        Channel c = channel(ch);
        return c == null ? null : c.getMapFactory().getMap(mapId);
    }

    /** This map id on every channel where it is already loaded (never loads a map). */
    public static List<MapleMap> loadedMaps(int mapId) {
        List<MapleMap> out = new ArrayList<>();
        for (Channel c : channels()) {
            if (c.getMapFactory().isMapLoaded(mapId)) {
                MapleMap m = c.getMapFactory().getMap(mapId);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** The configured shares for this world's channel count. */
    public static double[] currentShares() {
        String spec = YamlConfig.config.server == null ? null : YamlConfig.config.server.BOT_CHANNEL_WEIGHTS;
        return shares(spec, Math.max(1, channels().size()));
    }

    /** Bots in each channel's player storage (index 0 = channel 1). */
    public static int[] botsPerChannel() {
        return botsPerChannel(false);
    }

    private static int[] botsPerChannel(boolean dressingOnly) {
        List<Channel> chs = channels();
        int[] counts = new int[Math.max(1, chs.size())];
        for (int i = 0; i < chs.size(); i++) {
            int n = 0;
            for (Character chr : chs.get(i).getPlayerStorage().getAllCharacters()) {
                if (BotHelpers.isBot(chr) && (!dressingOnly || isDressing(chr.getId() - BotStore.BOT_ID_BASE))) {
                    n++;
                }
            }
            counts[i] = n;
        }
        return counts;
    }

    /** A bot of the environment's town dressing (a spread wave task), not a roster member. */
    static boolean isDressing(int slot) {
        return isSpreadSlot(slot) && !BotRoster.isMember(slot);
    }

    /** Bots online that are not roster members: the environment (and GM spawns). */
    public static int environmentOnline() {
        int n = 0;
        for (Channel c : channels()) {
            for (Character chr : c.getPlayerStorage().getAllCharacters()) {
                if (BotHelpers.isBot(chr) && !BotRoster.isMember(chr.getId() - BotStore.BOT_ID_BASE)) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Picks the channel for one bot about to log in and holds its place until {@link #release(int)}, so logins in
     * parallel see each other. Returns a channel id (1-based).
     */
    public static int reserve() {
        return withReservationLock(() -> reserveIn(botsPerChannel(false), inFlight));
    }

    /**
     * {@link #reserve()} for one bot of the environment's town dressing. The dressing is split among itself by the
     * channel weights, not against every bot: the set pieces already stand on channel 1, and counted against them
     * channel 1's towns would get almost no dressing. The director's logins, counted against everyone, even the
     * total out. Release with {@link #releaseDressing(int)}.
     */
    public static int reserveDressing() {
        return withReservationLock(() -> reserveIn(botsPerChannel(true), dressingInFlight));
    }

    // Java 21 virtual threads must be able to unmount while player storage is locked. An intrinsic
    // monitor here can pin every carrier during a boot fill, preventing the storage owner from resuming.
    private static final java.util.concurrent.locks.ReentrantLock RESERVATION_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    static <T> T withReservationLock(java.util.function.Supplier<T> work) {
        RESERVATION_LOCK.lock();
        try { return work.get(); }
        finally { RESERVATION_LOCK.unlock(); }
    }

    private static int reserveIn(int[] counts, int[] flight) {
        for (int i = 0; i < counts.length && i < flight.length; i++) {
            counts[i] += flight[i];
        }
        int pick = pickByDeficit(counts, currentShares());
        if (pick < flight.length) {
            flight[pick]++;
        }
        return pick + 1;
    }

    /** Releases a {@link #reserve()} once the bot is in its channel's storage (or failed to log in). */
    public static void release(int ch) {
        withReservationLock(() -> { releaseIn(ch, inFlight); return null; });
    }

    /**
     * 1.5.0 (per-map bot cap): moves a {@link #reserve()} to another channel, for a login whose map is full on the
     * reserved one. Release the new channel afterwards.
     */
    public static void transfer(int from, int to) {
        withReservationLock(() -> {
            releaseIn(from, inFlight);
            int i = to - 1;
            if (i >= 0 && i < inFlight.length) inFlight[i]++;
            return null;
        });
    }

    public static void releaseDressing(int ch) {
        withReservationLock(() -> { releaseIn(ch, dressingInFlight); return null; });
    }

    private static void releaseIn(int ch, int[] flight) {
        int i = ch - 1;
        if (i >= 0 && i < flight.length && flight[i] > 0) {
            flight[i]--;
        }
    }

    /**
     * Moves a bot to another channel of the world, onto the same map and spot - what a player's channel change
     * looks like. For a companion following its player. Not for a bot inside an event instance (those maps belong
     * to one channel). Returns whether it moved.
     */
    public static boolean moveToChannel(Character bot, int ch) {
        if (bot == null || bot.getMap() == null || bot.getClient() == null || bot.getEventInstance() != null) {
            return false;
        }
        if (soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths.isDead(bot.getId())) {
            return false; // 1.5.0 rc3: lying dead - it respawns on its own channel first
        }
        synchronized (HOP_LOCK) {
            Channel from = bot.getClient().getChannelServer();
            Channel to = channel(ch);
            if (to == null || from == null || from.getId() == ch) {
                return false;
            }
            MapleMap dest = to.getMapFactory().getMap(bot.getMapId());
            if (dest == null) {
                return false;
            }
            // 1.5.0 rc3: a grinder (or one resting on a grind break) leaves the grind cleanly first: spot claim,
            // train slot, grinding nav, break sign, death eligibility. It re-decides on the new channel.
            if (soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(bot.getId())
                    instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot trainer) {
                trainer.leaveForChannelMove();
            }
            // The bot's own changeMap (travel, portals, respawn) takes this lock too, so none can run while it is in
            // neither channel's storage (that warp would find no storage and disconnect the bot's client).
            // Movement and claims are stopped before the warp lock is taken (no other lock is taken under it here).
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(bot); // follow/travel sessions hold the old map
            BotSpotClaims.releaseAllFor(bot.getId());
            java.util.concurrent.locks.Lock warp = bot.getWarpLock();
            warp.lock();
            try {
                if (bot.getClient().getChannelServer() != from || bot.getMap() == null) {
                    return false; // changed under us before the lock
                }
                MapleMap here = to.getMapFactory().getMap(bot.getMapId()); // a warp that just ran may have moved it
                if (here != null) {
                    dest = here;
                }
                Point pos = new Point(bot.getPosition());
                from.removePlayer(bot);
                bot.setClient(BotClientHandler.getBotClient(ch));
                to.addPlayer(bot);
                if (bot.getParty() != null && bot.getMPC() != null) {
                    bot.getMPC().setChannel(ch);
                }
                bot.changeMap(dest, pos); // resolves through the new client: leaves the old channel's map, joins this one
                return true;
            } finally {
                warp.unlock();
            }
        }
    }

    /** For the GM read-out: bots per channel against the target split. */
    public static String describe() {
        int[] counts = botsPerChannel();
        double[] shares = currentShares();
        int total = Arrays.stream(counts).sum();
        StringBuilder sb = new StringBuilder("bots per channel (now/target):");
        for (int i = 0; i < counts.length; i++) {
            sb.append(" ch").append(i + 1).append(' ').append(counts[i]).append('/')
                    .append(Math.round(shares[i] * total));
        }
        return sb.toString();
    }
}
