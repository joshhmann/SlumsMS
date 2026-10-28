package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.Character;
import client.Character.SkillEntry;
import client.Job;
import client.Skill;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 1.6.1 ticket 30 (owner 2026-10-28: "Earned, slowly"): a regular that reaches the rebirth level goes through the
 * rebirth like a player, key item included, at a believable pace.
 *
 * <p>The player path is {@code scripts/npc/rebirth.js} at Wyatt the Reborn (NPC 1300009, Base of Roots 993910000):
 * level 200 (the class's max), fully advanced, fewer than 3 rebirths, and one key item that the rebirth takes
 * (4033446, 4033442, 4033450 for the 1st, 2nd, 3rd). A player makes the key at the crafter from two boss drops
 * ({@code craft.js}: Zakum's 4032133 + Horntail's 4001094; 4021010 + Von Leon's 4001693; Cygnus' 4000659 + 4032922).
 * The rebirth sets the character back to level 1 as a Beginner, Noblesse or Legend with fresh base stats, keeps its
 * skills, records the past job in {@code characters_rebirth} and sets the EXP rate to REBIRTH_FIRST/SECOND/THIRD_RATE.
 *
 * <p>A bot does the same, in steps ({@link #stage}):
 * <ol>
 *   <li><b>Bothers?</b> Not every max-level bot wants to start over: its Bartle blend decides ({@link #botherChance},
 *       Achievers most, then Killers), re-rolled once a week, so the rest may decide later.</li>
 *   <li><b>Gather</b> the two boss drops: an offscreen boss run is rolled at most every {@link #gatherEveryMs} with
 *       {@link #gatherChance} (ticket 30's "roll a clear chance"); a win puts that one drop in its bag through
 *       {@link BotHoldings#grantItem} (the ledger, saved with its holdings). Never more than one of each.</li>
 *   <li><b>Craft</b> the key from them, as craft.js does: both drops taken, one key given.</li>
 *   <li><b>Trip</b>: walk (or ride) to Wyatt; only while the server-wide pace has a slot left today
 *       ({@code BOT_REBIRTH_PER_DAY}, UTC days).</li>
 *   <li><b>Rebirth</b> at Wyatt ({@link #rebirthHere}): every check again, the pace slot taken, the key taken, then the
 *       rebirth itself ({@link #perform}).</li>
 * </ol>
 * Nothing here costs or grants real currency (no NX, no cash items, no Slums Stamps); the only items made are the
 * boss drops and the key the player path also makes, and the gear the bot wears is left as it is (as a player's is),
 * its normal gear rules ({@link BotGear}) still choosing what it can wear.
 *
 * <p>Switches: {@code BOT_REBIRTH_ENABLED} (needs {@code USE_REBIRTH_SYSTEM}), {@code BOT_REBIRTH_PER_DAY} (default 2).
 * Knobs: {@code -Dslums.botRebirth.gatherHours} (6), {@code -Dslums.botRebirth.gatherChance} (0.35). The day's pace
 * count is memory-only: a restart starts the day's count again.
 */
public final class BotRebirth {

    private static final Logger log = LoggerFactory.getLogger(BotRebirth.class);

    /** Wyatt the Reborn stands in the Base of Roots. */
    public static final int NPC_MAP = 993910000;
    public static final int MAX_REBIRTHS = 3;
    /** The class's max level before the third rebirth (Character.getMaxClassLevel). */
    public static final int REBIRTH_LEVEL = 200;
    private static final long DAY_MS = Duration.ofDays(1).toMillis();
    private static final long WEEK_MS = Duration.ofDays(7).toMillis();
    /** A trip that failed (no way there, stuck) waits this long before the next one. */
    static final long TRIP_RETRY_MS = Duration.ofHours(1).toMillis();

    /** rebirth.js refuses these: not fully advanced (and the three beginners). */
    static final Set<Integer> NOT_ADVANCED = Set.of(0, 100, 110, 111, 120, 121, 130, 131, 200, 210, 211, 220, 221, 230,
            231, 300, 310, 311, 320, 321, 400, 410, 411, 420, 421, 500, 510, 511, 520, 521, 1000, 1100, 1110, 1200,
            1210, 1300, 1310, 1400, 1410, 1500, 1510, 2000, 2100, 2110, 2111);

    public enum Stage { NOT_READY, MAXED, NOT_BOTHERED, GATHER, CRAFT, TRIP }

    /** Per bot: when its next boss-run roll may happen, and when a failed trip may be tried again. */
    private static final Map<Integer, Long> nextRoll = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> tripRetry = new ConcurrentHashMap<>();
    private static final Pace pace = new Pace();
    /** Bot id -> rebirths, read from characters_rebirth once per boot (null until then). */
    private static volatile Map<Integer, Integer> stored;
    /** Test seam: off in unit tests (no database). */
    private static volatile boolean dbWrites = true;
    /** QA: when set, bothering and the boss-run roll always say yes. */
    private static volatile long certainUntil;

    private BotRebirth() {
    }

    // ---------------------------------------------------------------- switches

    public static boolean enabled() {
        var server = YamlConfig.config.server;
        return server.BOT_REBIRTH_ENABLED && server.USE_REBIRTH_SYSTEM;
    }

    static int perDay() {
        return Math.max(0, YamlConfig.config.server.BOT_REBIRTH_PER_DAY);
    }

    static long gatherEveryMs() {
        return (long) (Math.max(0.01, doubleProp("slums.botRebirth.gatherHours", 6)) * 3_600_000L);
    }

    static double gatherChance() {
        return Math.max(0, Math.min(1, doubleProp("slums.botRebirth.gatherChance", .35)));
    }

    private static double doubleProp(String key, double fallback) {
        try {
            String v = System.getProperty(key);
            return v == null ? fallback : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- the rules (pure)

    /** The key item the next rebirth takes (rebirth.js keyItemFor), or -1 when there is none. */
    public static int keyItemFor(int reborns) {
        return switch (reborns) {
            case 0 -> 4033446;
            case 1 -> 4033442;
            case 2 -> 4033450;
            default -> -1;
        };
    }

    /** The two boss drops craft.js makes that key from, or an empty array. */
    public static int[] ingredientsFor(int reborns) {
        return switch (reborns) {
            case 0 -> new int[]{4032133, 4001094};
            case 1 -> new int[]{4021010, 4001693};
            case 2 -> new int[]{4000659, 4032922};
            default -> new int[0];
        };
    }

    /** Items a bot keeps out of its junk sales: the keys and their boss drops. */
    public static boolean keeps(int itemId) {
        for (int r = 0; r < MAX_REBIRTHS; r++) {
            if (keyItemFor(r) == itemId) return true;
            for (int i : ingredientsFor(r)) if (i == itemId) return true;
        }
        return false;
    }

    public static boolean fullyAdvanced(int jobId) {
        return !NOT_ADVANCED.contains(jobId);
    }

    /**
     * How likely this bot is to bother starting over, from its Bartle blend: Achievers chase it, Killers want the
     * flex, Explorers like the new areas, Socializers mostly don't care. Null blend: a middling chance.
     */
    public static double botherChance(SocialProfile social) {
        if (social == null) return .35;
        double c = .05 + .75 * social.share(SocialProfile.Type.ACHIEVER) + .5 * social.share(SocialProfile.Type.KILLER)
                + .3 * social.share(SocialProfile.Type.EXPLORER) + .1 * social.share(SocialProfile.Type.SOCIALIZER);
        return Math.max(.05, Math.min(.9, c));
    }

    /** This week's answer for this bot (stable within a week, re-rolled the next). */
    public static boolean bothers(int botId, int reborns, double chance, long nowMs) {
        long week = Math.floorDiv(nowMs, WEEK_MS);
        long h = (botId * 0x9E3779B97F4A7C15L) ^ (week * 0xC2B2AE3D27D4EB4FL) ^ (reborns * 0x165667B19E3779F9L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        double unit = (h >>> 11) / (double) (1L << 53);
        return unit < chance;
    }

    /** Where this bot stands on its way to its next rebirth. */
    public static Stage stage(int level, int reborns, int jobId, boolean bothers, int keyHeld, int[] ingredientsHeld) {
        if (reborns >= MAX_REBIRTHS) return Stage.MAXED;
        if (level < REBIRTH_LEVEL || !fullyAdvanced(jobId)) return Stage.NOT_READY;
        if (keyHeld > 0) return Stage.TRIP;
        if (!bothers) return Stage.NOT_BOTHERED;
        for (int held : ingredientsHeld) if (held <= 0) return Stage.GATHER;
        return Stage.CRAFT;
    }

    /** The path a bot is reborn on: mostly Explorer, some Cygnus, a few Aran (stable per bot and rebirth). */
    public static int pathFor(int botId, int reborns) {
        int roll = Math.floorMod(botId * 31 + reborns * 7, 10);
        int job = roll < 6 ? 0 : roll < 9 ? 1000 : 2000;
        return BotBuild.isBuildJob(job) ? job : 0;
    }

    /** The EXP rate after {@code reborns} rebirths at this level (as rebirth.js, World and the login handler set it). */
    public static int rateFor(int reborns, int level) {
        var s = YamlConfig.config.server;
        return switch (reborns) {
            case 1 -> s.REBIRTH_FIRST_RATE;
            case 2 -> s.REBIRTH_SECOND_RATE;
            case 3 -> level >= REBIRTH_LEVEL ? s.REBIRTH_FINAL_RATE : s.REBIRTH_THIRD_RATE;
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- the server-wide pace

    /** At most {@link #perDay()} bot rebirths a UTC day. */
    static final class Pace {
        private long day = Long.MIN_VALUE;
        private int used;

        synchronized int left(long nowMs, int perDay) {
            roll(nowMs);
            return Math.max(0, perDay - used);
        }

        synchronized boolean take(long nowMs, int perDay) {
            roll(nowMs);
            if (used >= perDay) return false;
            used++;
            return true;
        }

        synchronized void giveBack(long nowMs) {
            roll(nowMs);
            if (used > 0) used--;
        }

        synchronized void reset() {
            day = Long.MIN_VALUE;
            used = 0;
        }

        private void roll(long nowMs) {
            long d = Math.floorDiv(nowMs, DAY_MS);
            if (d != day) {
                day = d;
                used = 0;
            }
        }
    }

    /** Rebirths left today, server-wide. */
    public static int paceLeft(long nowMs) {
        return pace.left(nowMs, perDay());
    }

    // ---------------------------------------------------------------- the game side

    /** The bot's stage now (reads its level, job, rebirths and bag). */
    public static Stage stageOf(Character bot, long nowMs) {
        int reborns = bot.getReborns();
        int[] ing = ingredientsFor(reborns);
        int[] held = new int[ing.length];
        for (int i = 0; i < ing.length; i++) held[i] = count(bot, ing[i]);
        int key = keyItemFor(reborns);
        boolean certain = nowMs < certainUntil;
        boolean wants = certain || bothers(bot.getId(), reborns,
                botherChance(SocialProfile.forCharacter(bot.getId())), nowMs);
        return stage(bot.getLevel(), reborns, bot.getJob().getId(), wants, key < 0 ? 0 : count(bot, key), held);
    }

    /**
     * Called when the bot decides where to go next. Moves it one step along (a boss-run roll, crafting the key) and
     * returns Wyatt's map when it should go and be reborn now, else -1. Never throws.
     */
    public static int tripMap(Character bot, long nowMs) {
        if (!enabled() || bot == null || !bot.isBot()) return -1;
        try {
            Stage stage = stageOf(bot, nowMs);
            switch (stage) {
                case GATHER -> gather(bot, nowMs);
                case CRAFT -> craft(bot);
                default -> {
                }
            }
            if (stageOf(bot, nowMs) != Stage.TRIP || paceLeft(nowMs) <= 0) return -1;
            if (nowMs < tripRetry.getOrDefault(bot.getId(), 0L)) return -1;
            return NPC_MAP;
        } catch (RuntimeException e) {
            log.warn("Rebirth step of bot {} ({}) failed", bot.getId(), bot.getName(), e);
            return -1;
        }
    }

    /** The trip to Wyatt did not get there: try again later, not on the next decision. */
    public static void tripFailed(int botId, long nowMs) {
        tripRetry.put(botId, nowMs + TRIP_RETRY_MS);
    }

    /** One offscreen boss run: at most one roll per {@link #gatherEveryMs}; a win adds the first missing drop. */
    static boolean gather(Character bot, long nowMs) {
        boolean certain = nowMs < certainUntil;
        Long next = nextRoll.get(bot.getId());
        if (!certain && next != null && nowMs < next) return false;
        nextRoll.put(bot.getId(), nowMs + gatherEveryMs());
        if (next == null && !certain) return false; // the first look only starts the clock: no run on arrival at 200
        if (!certain && ThreadLocalRandom.current().nextDouble() >= gatherChance()) return false;
        for (int id : ingredientsFor(bot.getReborns())) {
            if (count(bot, id) > 0) continue;
            boolean got = BotHoldings.grantItem(bot, new Item(id, (short) 0, (short) 1));
            if (got) log.info("[Rebirth] {} (lv {}) got boss drop {} toward rebirth {}", bot.getName(), bot.getLevel(),
                    id, bot.getReborns() + 1);
            return got;
        }
        return false;
    }

    /** craft.js: one of each boss drop for one key. */
    static boolean craft(Character bot) {
        int reborns = bot.getReborns();
        int[] ing = ingredientsFor(reborns);
        int key = keyItemFor(reborns);
        if (key < 0 || ing.length == 0) return false;
        synchronized (bot) {
            for (int id : ing) if (count(bot, id) <= 0) return false;
            Item keyItem = new Item(key, (short) 0, (short) 1);
            if (!BotHoldings.canHold(bot, keyItem)) return false;
            for (int id : ing) {
                Item held = bot.getInventory(InventoryType.ETC).findById(id);
                if (held == null || BotHoldings.consumeHeld(bot, held, 1) != 1) return false;
            }
            if (!BotHoldings.grantItem(bot, keyItem)) return false;
        }
        log.info("[Rebirth] {} (lv {}) crafted rebirth key {}", bot.getName(), bot.getLevel(), key);
        return true;
    }

    /** What a rebirth attempt did. */
    public record Result(boolean done, String text) {
    }

    /**
     * At Wyatt: rebirth.js's checks again (level, advanced, fewer than 3, the key held), a slot of today's pace, then
     * the key is taken and the bot reborn. {@code announce} posts it to the website feed (QA runs pass false).
     */
    public static Result rebirthHere(Character bot, long nowMs, boolean announce) {
        if (!enabled()) return new Result(false, "bot rebirths are off");
        synchronized (bot) {
            int reborns = bot.getReborns();
            int key = keyItemFor(reborns);
            if (reborns >= MAX_REBIRTHS || key < 0) return new Result(false, "already at " + reborns + " rebirths");
            if (bot.getLevel() != bot.getMaxClassLevel() || !fullyAdvanced(bot.getJob().getId())) {
                return new Result(false, "lv " + bot.getLevel() + " job " + bot.getJob().getId() + " not ready");
            }
            Item held = bot.getInventory(InventoryType.ETC).findById(key);
            if (held == null) return new Result(false, "no key item " + key);
            if (!pace.take(nowMs, perDay())) return new Result(false, "today's bot rebirths are used up");
            if (BotHoldings.consumeHeld(bot, held, 1) != 1) {
                pace.giveBack(nowMs);
                return new Result(false, "the key item could not be taken");
            }
            int pastJob = bot.getJob().getId();
            perform(bot, Job.getById(pathFor(bot.getId(), reborns)));
            tripRetry.remove(bot.getId());
            nextRoll.remove(bot.getId());
            record(bot, pastJob);
            if (announce) soloMapling.site.SiteFeed.rebirth(bot, bot.getReborns());
            String text = bot.getName() + " was reborn as " + bot.getJob().name() + " (rebirth " + bot.getReborns()
                    + ", was job " + pastJob + "; EXP rate " + bot.getExpRate() + ")";
            log.info("[Rebirth] {}", text);
            return new Result(true, text);
        }
    }

    /**
     * The rebirth itself, as rebirth.js does it for a player: one more rebirth, the new path's job, level 1 and no
     * EXP, base stats fresh (the build of a level-1 beginner), the skills it had kept, and the rebirth EXP rate.
     */
    static void perform(Character bot, Job job) {
        Map<Skill, SkillEntry> kept = new LinkedHashMap<>(bot.getSkills());
        bot.setBotReborns(bot.getReborns() + 1);
        bot.setJob(job);
        bot.setLevel(1);
        bot.setExp(0);
        BotProgression.forget(bot.getId());
        BotProgression.buildNew(bot);
        Map<Skill, SkillEntry> merged = new LinkedHashMap<>(kept);
        merged.putAll(bot.getSkills());
        bot.replaceBotSkills(merged);
        bot.refreshStatsAfterProgression();
        applyRates(bot);
        BotGear.afterProgress(bot);
    }

    /** The rebirth EXP rate for a bot with rebirths (bots without keep theirs). */
    public static void applyRates(Character bot) {
        int r = bot.getReborns();
        int rate = rateFor(r, bot.getLevel());
        if (r > 0 && rate > 0) bot.setPlayerExpRatesCerezeth(rate);
    }

    /** The past job in characters_rebirth, as rebirth.js's updateRebornTableCerezeth does for a player. */
    static void record(Character bot, int pastJob) {
        Map<Integer, Integer> cache = stored;
        if (cache != null) cache.put(bot.getId(), bot.getReborns());
        if (!dbWrites) return;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO characters_rebirth (accountid, job, characterid,"
                     + " name, reborns) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE reborns = VALUES(reborns)")) {
            ps.setInt(1, bot.getAccountID());
            ps.setInt(2, pastJob);
            ps.setInt(3, bot.getId());
            ps.setString(4, bot.getName());
            ps.setInt(5, bot.getReborns());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Could not record the rebirth of bot {} ({})", bot.getId(), bot.getName(), e);
        }
    }

    /** On a bot's load: its rebirth count from characters_rebirth (0 without a row) and the rate that goes with it. */
    public static void restore(Character bot) {
        if (!YamlConfig.config.server.USE_REBIRTH_SYSTEM || bot == null) return;
        bot.setBotReborns(storedRebirths().getOrDefault(bot.getId(), 0));
        applyRates(bot);
    }

    private static Map<Integer, Integer> storedRebirths() {
        Map<Integer, Integer> cache = stored;
        if (cache != null) return cache;
        synchronized (BotRebirth.class) {
            if (stored != null) return stored;
            Map<Integer, Integer> read = new ConcurrentHashMap<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT characterid, MAX(reborns) AS r FROM"
                         + " characters_rebirth WHERE characterid >= ? GROUP BY characterid")) {
                ps.setInt(1, soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) read.put(rs.getInt(1), rs.getInt(2));
                }
                stored = read;
            } catch (Exception e) {
                log.warn("Could not read bot rebirths; bots load with none this time", e);
                return new HashMap<>();
            }
            return read;
        }
    }

    private static int count(Character bot, int itemId) {
        return bot.getInventory(InventoryType.ETC).countById(itemId);
    }

    // ---------------------------------------------------------------- QA and tests

    /** QA: for this long, every max-level bot bothers and every boss run drops. */
    public static void certainFor(long ms) {
        certainUntil = ms <= 0 ? 0 : System.currentTimeMillis() + ms;
    }

    /** QA: undo a staged rebirth's pace slot. */
    public static void givePaceBack(long nowMs) {
        pace.giveBack(nowMs);
    }

    /** QA: forget a staged rebirth's row and cache entry (restoring the bot's count to {@code reborns}). */
    public static void unrecord(Character bot, int reborns) {
        Map<Integer, Integer> cache = stored;
        if (cache != null) {
            if (reborns > 0) cache.put(bot.getId(), reborns);
            else cache.remove(bot.getId());
        }
        if (!dbWrites) return;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM characters_rebirth WHERE characterid = ?"
                     + " AND reborns > ?")) {
            ps.setInt(1, bot.getId());
            ps.setInt(2, reborns);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Could not undo the QA rebirth row of bot {}", bot.getId(), e);
        }
    }

    static void resetForTest() {
        pace.reset();
        nextRoll.clear();
        tripRetry.clear();
        certainUntil = 0;
        stored = new ConcurrentHashMap<>();
        dbWrites = false;
    }
}
