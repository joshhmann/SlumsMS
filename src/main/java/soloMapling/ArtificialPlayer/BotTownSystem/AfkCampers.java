package soloMapling.ArtificialPlayer.BotTownSystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.MapId;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.GCMoveSystem.WatchGate;
import soloMapling.qa.QaLog;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

/**
 * 1.6.0 player likeness (audit #3, owner-approved): AFK campers. In 2009 every town had a few people standing still
 * for half an hour - gone to dinner, watching TV, waiting for a friend - some on a chair. Here 5 to 15 bots per
 * channel (one per 40 of that channel's online bots, clamped) park where they already stand in a town or the FM
 * entrance and do nothing: no walking, no fidget, no barks, no reaction to a passer-by. On their own map a player sees
 * someone AFK; when the stretch ends (12-45 min on a chair, 3-8 min standing) the bot sometimes types "back" or
 * emotes, and goes on with its day.
 *
 * <p>Who camps rotates: a bot that camped is not picked again for {@link #COOLDOWN_MS}. Only roster bots of the town
 * types (training bots between grinds, town wanderers, social bots) that hold no commitment are picked: not in any
 * party, not trading, not following anyone (companions and owned alts are {@code FollowerBot}s), not talking to a
 * player, not travelling, and free to log out by the population director's own rule. Any commitment that starts
 * while camping (a trade window, a party, an invite window) ends the camp on the bot's next tick, so the commitment
 * is answered as usual.
 *
 * <p>Cost: one pass over the online bots once a minute (no DB, no map scans, nothing per pair), and a map lookup on
 * each camper's own macro tick, which it otherwise skips - campers are cheaper than the bots they replace.
 *
 * <p>Switch: {@code -Dsolomapling.afkCampers=false} (campers are released on the next pass).
 */
public final class AfkCampers {

    public static final String PROP = "solomapling.afkCampers";

    static final int MIN_PER_CHANNEL = 5;
    static final int MAX_PER_CHANNEL = 15;
    static final int BOTS_PER_CAMPER = 40;
    static final int MAX_PER_MAP = 4;          // per channel: a town with 15 statues would read as a bug
    static final long PASS_MS = 60_000;
    static final long MIN_CAMP_MS = TimeUnit.MINUTES.toMillis(12);
    static final long MAX_CAMP_MS = TimeUnit.MINUTES.toMillis(45);
    /**
     * TEST bot watch 2026-10-26 (ItsBucc, Henesys ch1, a player there): a camper with no chair stood frozen on one pixel
     * for 24 min. People do go AFK standing, but a statue that long in front of a player reads as a bot; the owner
     * wants standing still and not sitting for 10 min to be rare. A camper on a chair keeps the long stretch; one that
     * stands is "brb" length: 3 to 8 min.
     */
    static final long STAND_MIN_CAMP_MS = TimeUnit.MINUTES.toMillis(3);
    static final long STAND_MAX_CAMP_MS = TimeUnit.MINUTES.toMillis(8);
    static final long COOLDOWN_MS = TimeUnit.HOURS.toMillis(3);
    static final double CHAIR_CHANCE = 0.6;    // of the campers that own a chair
    static final double RETURN_LINE_CHANCE = 0.35;
    static final double RETURN_EMOTE_CHANCE = 0.25;

    /** Towns and hangouts people AFK in. */
    static final Set<Integer> CAMP_MAPS = Set.of(MapId.HENESYS, MapId.KERNING_CITY, MapId.ELLINIA, MapId.PERION,
            MapId.LITH_HARBOUR, MapId.FM_ENTRANCE, MapId.SLEEPYWOOD, MapId.ORBIS, MapId.EL_NATH, MapId.LUDIBRIUM);

    static final List<String> RETURN_LINES = List.of("back", "re", "sry was afk", "ok back", "k im back", "back lol",
            "was eating", "brb over", "hi again", "lol i was afk", "mom made me eat", "back from dinner", "oops afk");

    record Camp(int botId, int channel, int mapId, long startedMs, long untilMs, boolean sat) {
    }

    private static final Map<Integer, Camp> CAMPS = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> COOLING = new ConcurrentHashMap<>();
    private static volatile ScheduledFuture<?> task;
    private static volatile long lastPassMs;
    private static volatile String lastPass = "no pass yet";

    /** Test seam: roster membership by slot (QA-staged and on-demand bots never camp). */
    static volatile IntPredicate rosterMember = BotRoster::isMember;
    /** Test seam: the population director's commitment rule. */
    static volatile java.util.function.Predicate<BotSM> free = BotPopulationDirector::mayLogOut;

    private AfkCampers() {
    }

    /** On unless {@code -Dsolomapling.afkCampers=false}. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PROP, "true").trim());
    }

    /** Campers wanted on a channel with this many bots online: one per 40, clamped to 5-15 (0 for an empty channel). */
    static int targetFor(int onlineBots) {
        if (onlineBots <= 0) {
            return 0;
        }
        return Math.max(MIN_PER_CHANNEL, Math.min(MAX_PER_CHANNEL, Math.round(onlineBots / (float) BOTS_PER_CAMPER)));
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Starts the once-a-minute pass (idempotent). Called with the town ambience (SocialHotPotatoManager). */
    public static synchronized void start() {
        if (task != null) {
            return;
        }
        task = ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
            try {
                pass(System.currentTimeMillis());
            } catch (Throwable t) {
                soloMapling.BotLogger.log("[AfkCampers] pass failed: " + t);
            }
        }, PASS_MS, PASS_MS, TimeUnit.MILLISECONDS);
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        for (Camp c : new ArrayList<>(CAMPS.values())) {
            release(c, "stopped", false);
        }
    }

    // ── The per-tick hold (BotSM) ──────────────────────────────────────────────────────────────────────────────

    public static boolean isCamping(int charId) {
        return CAMPS.containsKey(charId);
    }

    /**
     * 1.6.0: something bigger than dinner happened (a world event called the bot, or invaders reached its town): the
     * camp ends now, as if the player came back. No "back" line; the caller gives the bot something to do.
     */
    public static void endCamp(int charId, String why) {
        Camp c = CAMPS.get(charId);
        if (c != null) {
            release(c, why, false);
        }
    }

    /**
     * Called first thing on a bot's macro tick: true = the bot is AFK, skip the tick. A camp whose time is up, whose
     * bot moved map, or whose bot picked up a commitment ends here, and the tick runs as usual.
     */
    public static boolean holds(BotSM bot) {
        Character chr = bot == null ? null : bot.getChr();
        Camp c = chr == null ? null : CAMPS.get(chr.getId());
        if (c == null) {
            return false;
        }
        String why = endReason(bot, chr, c, System.currentTimeMillis());
        if (why == null) {
            return true;
        }
        release(c, why, "time up".equals(why));
        return false;
    }

    static String endReason(BotSM bot, Character chr, Camp c, long now) {
        if (!enabled()) {
            return "switched off";
        }
        if (chr.getMap() == null || chr.getMapId() != c.mapId()) {
            return "left the map";
        }
        if (bot.getState() == BotSM.BotState.TRADING || chr.getTrade() != null) {
            return "trade";
        }
        if (chr.getParty() != null) {
            return "party";
        }
        if (soloMapling.ArtificialPlayer.BotCommitments.inFlight(bot)) {
            return "commitment";
        }
        if (bot instanceof SocialBot social && social.hasActiveRespondant()) {
            return "talking to a player";
        }
        return now >= c.untilMs() ? "time up" : null;
    }

    // ── The pass ───────────────────────────────────────────────────────────────────────────────────────────────

    /** One pass: count bots per channel, end stale camps, top every channel up to its target. */
    static void pass(long now) {
        if (!enabled() || BotShutdown.isQuiesced()) {
            for (Camp c : new ArrayList<>(CAMPS.values())) {
                release(c, "switched off", false);
            }
            lastPass = enabled() ? "quiesced" : "switched off";
            return;
        }
        COOLING.values().removeIf(until -> until <= now);
        Map<Integer, Integer> online = new HashMap<>();
        Map<Integer, List<BotSM>> candidates = new HashMap<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot == null ? null : bot.getChr();
            MapleMap map = chr == null ? null : chr.getMap();
            if (map == null) {
                continue;
            }
            int ch = map.getChannelId();
            online.merge(ch, 1, Integer::sum);
            if (!CAMPS.containsKey(chr.getId()) && CAMP_MAPS.contains(map.getId()) && eligible(bot, now)) {
                candidates.computeIfAbsent(ch, k -> new ArrayList<>()).add(bot);
            }
        }
        // stale camps: the bot logged out (gone from the bot map) or its own tick never ran to end it
        for (Camp c : new ArrayList<>(CAMPS.values())) {
            BotSM bot = CharacterStorage.getBotById(c.botId());
            if (bot == null || bot.getChr() == null) {
                CAMPS.remove(c.botId());
            } else if (now >= c.untilMs() + PASS_MS) {
                release(c, "time up", true);
            }
        }
        Map<Integer, Integer> camping = new HashMap<>();
        Map<Long, Integer> perMap = new HashMap<>();
        for (Camp c : CAMPS.values()) {
            camping.merge(c.channel(), 1, Integer::sum);
            perMap.merge(mapKey(c.channel(), c.mapId()), 1, Integer::sum);
        }
        Random rng = ThreadLocalRandom.current();
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(online).entrySet()) {
            int ch = e.getKey();
            int want = targetFor(e.getValue());
            int have = camping.getOrDefault(ch, 0);
            List<BotSM> pool = candidates.getOrDefault(ch, List.of());
            if (have < want && !pool.isEmpty()) {
                pool = new ArrayList<>(pool);
                Collections.shuffle(pool, rng);
                for (BotSM bot : pool) {
                    if (have >= want) {
                        break;
                    }
                    long key = mapKey(ch, bot.getChr().getMapId());
                    if (perMap.getOrDefault(key, 0) >= MAX_PER_MAP) {
                        continue;
                    }
                    if (camp(bot, ch, now, rng)) {
                        have++;
                        perMap.merge(key, 1, Integer::sum);
                    }
                }
            }
            summary.append(String.format(Locale.ROOT, "ch%d %d/%d (bots %d, free in town %d) ", ch, have, want,
                    e.getValue(), pool.size()));
        }
        lastPassMs = now;
        lastPass = summary.length() == 0 ? "no bots online" : summary.toString().trim();
        if (QaLog.on(QaLog.Topic.LIKENESS)) { // !qa lk-1
            QaLog.any(QaLog.Topic.LIKENESS, "CAMPERS " + lastPass);
        }
    }

    /** May this bot start an AFK stretch now? Roster bot of a town type, no commitment, standing still. */
    static boolean eligible(BotSM bot, long now) {
        Character chr = bot.getChr();
        if (!(bot instanceof TrainingBot || bot instanceof TownWandererBot || bot instanceof SocialBot)) {
            return false; // merchants, dealers, hosts, followers (companions, owned alts), PQ and tutorial bots work
        }
        if (bot.getState() != BotSM.BotState.RUNNING || chr.getParty() != null || chr.getTrade() != null) {
            return false;
        }
        if (COOLING.getOrDefault(chr.getId(), 0L) > now) {
            return false; // rotate: it camped not long ago
        }
        if (soloMapling.worldevents.InvasionTownCrowd.holds(chr.getId())) {
            return false; // 1.6.0: fighting or fleeing an invasion in its town
        }
        if (!rosterMember.test(chr.getId() - BotStore.BOT_ID_BASE)) {
            return false;
        }
        if (GCMovement.isTraveling(chr) || GCMovement.isInTransit(chr) || GCMovement.isFollowing(chr)
                || GCMovement.isMoving(chr) || GCMovement.isClimbing(chr)) {
            return false; // mid-errand or mid-step: only a bot already standing still stops for a long while
        }
        if (bot instanceof TrainingBot tb && tb.isGrinding()) {
            return false;
        }
        if (bot instanceof SocialBot social && social.hasActiveRespondant()) {
            return false;
        }
        return free.test(bot);
    }

    /** How long an AFK stretch lasts: 12-45 min on a chair, 3-8 min standing. */
    static long campLength(boolean sit, Random rng) {
        long min = sit ? MIN_CAMP_MS : STAND_MIN_CAMP_MS, max = sit ? MAX_CAMP_MS : STAND_MAX_CAMP_MS;
        return min + (long) (rng.nextDouble() * (max - min));
    }

    private static boolean camp(BotSM bot, int ch, long now, Random rng) {
        Character chr = bot.getChr();
        int chair = ownedChair(chr);
        boolean sit = chair > 0 && rng.nextDouble() < CHAIR_CHANCE;
        long len = campLength(sit, rng);
        GCMovement.stop(chr);
        GCMovement.setFidget(chr, false); // AFK: no fidget; the town idle beat (TownIdle) turns it back on after
        Camp c = new Camp(chr.getId(), ch, chr.getMapId(), now, now + len, sit);
        CAMPS.put(chr.getId(), c);
        COOLING.put(chr.getId(), now + len + COOLDOWN_MS);
        if (sit && chr.getChair() <= 0) {
            try {
                MovementCommands.botSitChair(chr, chair);
            } catch (RuntimeException ignored) {
                // a packet to a map mid-teardown: it simply stands
            }
        }
        if (QaLog.on(QaLog.Topic.LIKENESS)) { // !qa lk-1
            QaLog.map(QaLog.Topic.LIKENESS, chr.getMapId(), String.format(Locale.ROOT,
                    "CAMP START %s ch%d map %d at (%d,%d) for %d min%s", chr.getName(), ch, chr.getMapId(),
                    chr.getPosition().x, chr.getPosition().y, len / 60_000, sit ? " on chair " + chair : ""));
        }
        return true;
    }

    private static void release(Camp c, String why, boolean natural) {
        if (CAMPS.remove(c.botId()) == null) {
            return;
        }
        BotSM bot = CharacterStorage.getBotById(c.botId());
        Character chr = bot == null ? null : bot.getChr();
        if (chr == null) {
            return;
        }
        if (QaLog.on(QaLog.Topic.LIKENESS)) { // !qa lk-1
            QaLog.map(QaLog.Topic.LIKENESS, chr.getMapId(), "CAMP END " + chr.getName() + " ch" + c.channel()
                    + " after " + (System.currentTimeMillis() - c.startedMs()) / 60_000 + " min: " + why);
        }
        try {
            if (c.sat() && natural) {
                MovementCommands.standUp(chr);
            }
            if (natural && chr.getMap() != null && WatchGate.watched(chr)) { // only someone there would see it
                ThreadLocalRandom r = ThreadLocalRandom.current();
                if (r.nextDouble() < RETURN_LINE_CHANCE) {
                    SocialCommands.BotSpeak(chr, RETURN_LINES.get(r.nextInt(RETURN_LINES.size())));
                } else if (r.nextDouble() < RETURN_EMOTE_CHANCE / (1 - RETURN_LINE_CHANCE)) {
                    SocialCommands.BotEmote(chr, 1 + r.nextInt(7));
                }
            }
        } catch (RuntimeException ignored) {
            // cosmetic only
        }
        if (!"trade".equals(why) && !"party".equals(why) && !"commitment".equals(why)) {
            bot.nudgeSoon(500); // back to its day promptly; a commitment's own tick is already running
        }
    }

    /** A chair the bot really owns (setup items 301xxxx), or 0. */
    static int ownedChair(Character chr) {
        try {
            var inv = chr.getInventory(InventoryType.SETUP);
            if (inv == null) {
                return 0;
            }
            for (Item item : inv.list()) {
                if (item != null && item.getItemId() / 10000 == 301) {
                    return item.getItemId();
                }
            }
        } catch (RuntimeException ignored) {
            // no inventory loaded: no chair
        }
        return 0;
    }

    private static long mapKey(int channel, int mapId) {
        return ((long) channel << 32) | (mapId & 0xffffffffL);
    }

    // ── Read-outs ──────────────────────────────────────────────────────────────────────────────────────────────

    public static int campers() {
        return CAMPS.size();
    }

    public static int campersOn(int channel) {
        int n = 0;
        for (Camp c : CAMPS.values()) {
            if (c.channel() == channel) {
                n++;
            }
        }
        return n;
    }

    /** Lines for {@code !qa lk-1}: the last pass per channel, then every camper. */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        out.add("afkCampers=" + (enabled() ? "on" : "off") + " campers=" + CAMPS.size() + " last pass "
                + (lastPassMs == 0 ? "never" : (now - lastPassMs) / 1000 + "s ago") + ": " + lastPass);
        for (Camp c : CAMPS.values()) {
            BotSM bot = CharacterStorage.getBotById(c.botId());
            String name = bot == null || bot.getChr() == null ? String.valueOf(c.botId()) : bot.getChr().getName();
            out.add(String.format(Locale.ROOT, "camper %s ch%d map %d %d min left%s", name, c.channel(), c.mapId(),
                    Math.max(0, (c.untilMs() - now) / 60_000), c.sat() ? " (chair)" : ""));
        }
        return out;
    }

    /** {@code !qa lk-1 do camp}: run a pass now. */
    public static List<String> passNow() {
        pass(System.currentTimeMillis());
        return describe();
    }

    static void resetForTest() {
        CAMPS.clear();
        COOLING.clear();
        lastPassMs = 0;
        lastPass = "no pass yet";
        rosterMember = BotRoster::isMember;
        free = BotPopulationDirector::mayLogOut;
    }

    static Camp campOf(int botId) {
        return CAMPS.get(botId);
    }

    static void putForTest(Camp c) {
        CAMPS.put(c.botId(), c);
    }
}
