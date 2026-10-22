package soloMapling.ArtificialPlayer.BotGuildSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The bot guild rules that need no game state (1.5.0 bot guilds, owner grill 2026-10-21). Every number is a
 * {@code solomapling.guilds.*} system property (config.yaml is strict, so none there).
 */
public final class GuildRules {

    private GuildRules() {
    }

    /** What a guild is like: what its members say, who it takes, how it recruits. */
    public enum Vibe {
        CASUAL, HARDCORE, MERCHANT, SOCIAL, NEWBIE, PVM;

        public static Vibe parse(String raw) {
            if (raw == null) {
                return CASUAL;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return CASUAL;
            }
        }

        public String key() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static double prop(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty("solomapling.guilds." + key, Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.guilds.enabled", "true"));
    }

    /** Whether the server founds the bot guilds itself when none exist yet. */
    public static boolean seedEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.guilds.seed", "true"));
    }

    static long minutes(String key, double fallback) {
        return (long) (prop(key, fallback) * TimeUnit.MINUTES.toMillis(1));
    }

    static long hours(String key, double fallback) {
        return (long) (prop(key, fallback) * TimeUnit.HOURS.toMillis(1));
    }

    // ── a player invites a bot into their guild ──

    /**
     * The chance a bot says yes to a player's guild invite. Only friends say yes (owner: "friend bots accept a
     * player's guild invite"); a neutral bot or a rival never does. Past half bots the guild is "full of bots" and a
     * friend gets pickier, so the guild stays player-led (a soft limit: never a hard no, but 5% at three quarters).
     *
     * @param botsAfter the guild's bot members if this one joined
     * @param sizeAfter the guild's members if this one joined
     */
    public static double inviteAcceptChance(AffinityState state, int botsAfter, int sizeAfter) {
        if (state != AffinityState.FRIEND || sizeAfter <= 0) {
            return 0;
        }
        double base = prop("inviteAccept", 0.95);
        double share = (double) botsAfter / sizeAfter;
        double soft = prop("softShare", 0.5);
        if (share <= soft) {
            return base;
        }
        return base * Math.max(0.05, 1 - (share - soft) * 4);
    }

    // ── a player asks to join a bot guild ──

    /** Why a bot guild's leader says no, or null when it says yes. */
    public enum Refusal {
        HOSTILE, LEVEL, FULL, IN_GUILD, NOT_NOW
    }

    /**
     * The leader's answer to a player asking to join: no to a rival, a full guild or a player already in one; a
     * friend is always welcome; anyone else needs the guild's level floor (a hardcore guild a higher one) and a roll.
     * Null = yes.
     */
    public static Refusal application(AffinityState leaderFeels, int playerLevel, int playerGuildId, int minLevel,
                                      Vibe vibe, int members, int capacity, double roll) {
        if (playerGuildId > 0) {
            return Refusal.IN_GUILD;
        }
        if (members >= capacity) {
            return Refusal.FULL;
        }
        if (leaderFeels.hostile()) {
            return Refusal.HOSTILE;
        }
        if (leaderFeels == AffinityState.FRIEND) {
            return null;
        }
        int floor = vibe == Vibe.HARDCORE || vibe == Vibe.PVM ? Math.max(minLevel, 50) : minLevel;
        if (playerLevel < floor) {
            return Refusal.LEVEL;
        }
        return roll < prop("applyAccept", 0.8) ? null : Refusal.NOT_NOW;
    }

    // ── a bot quits ──

    /** Why a bot member leaves its guild, or null when it stays. */
    public enum QuitReason {
        RIVALS, IGNORED
    }

    /**
     * A bot in a player-led guild quits when the guild's players became its rivals (the player leader, or at least
     * half of the guild's players it has feelings about), or when nobody of the guild did anything with it for
     * {@code ignoreDays} (21) - and it has been in the guild at least that long. A bot in a bot-run guild never quits:
     * a grudge against a player member is the leader's to settle ({@link #kick}), and bot guilds would only shrink.
     *
     * @param hostileToLeader the guild's leader is a real player this bot is a rival or nemesis of
     * @param hostile         guild players (online, feelings loaded) it is hostile to
     * @param known           guild players (online, feelings loaded) it feels anything about, hostile included
     */
    public static QuitReason quit(boolean playerLed, boolean hostileToLeader, int hostile, int known, long joinedAt,
                                  long attentionAt, long now) {
        if (!playerLed) {
            return null; // a bot-run guild keeps its bots, whatever they think of its players
        }
        if (hostileToLeader || (hostile > 0 && hostile * 2 >= known)) {
            return QuitReason.RIVALS;
        }
        long ignore = (long) (prop("ignoreDays", 21) * TimeUnit.DAYS.toMillis(1));
        long since = Math.max(joinedAt, attentionAt);
        return now - joinedAt >= ignore && now - since >= ignore ? QuitReason.IGNORED : null;
    }

    // ── a bot guild's leader kicks a rival player ──

    /**
     * A bot-run guild's leader expels a player member who is its nemesis, or whom the guild's bots hold a real grudge
     * against: at least {@code kickMinKnown} (3) bot members have feelings about them and more than half of those are
     * rivals or nemeses (owner 2026-10-21; 1.5.0 review: one grudge must never get a player kicked). Bots never kick a
     * player they feel nothing about.
     *
     * @param leaderNemesis the leader is the player's nemesis
     * @param hostile       bot members hostile to the player
     * @param known         bot members with any feelings (hostile included) about the player
     */
    public static boolean kick(boolean leaderNemesis, int hostile, int known) {
        return leaderNemesis || (known >= (int) prop("kickMinKnown", 3) && hostile * 2 > known);
    }

    // ── guild points ──

    /**
     * The GP a bot guild is founded with: a little history, small enough that a players' guild doing real Guild PQs
     * passes it within days (owner 2026-10-22). About 50 a member, 300 to 2,000 ({@code seedGpPerMember},
     * {@code seedGpMin}, {@code seedGpMax}).
     */
    public static int seedGp(int size) {
        int per = (int) prop("seedGpPerMember", 50);
        return Math.max((int) prop("seedGpMin", 300), Math.min((int) prop("seedGpMax", 2000), size * per));
    }

    /** What a staged boss clear gives the guild ({@code bossGp}, 30): a real GPQ gives several hundred. */
    public static int bossGp() {
        return (int) prop("bossGp", 30);
    }

    /** The rc builds founded with {@code size * 120} GP and gave 150 per staged clear. */
    static final int OLD_GP_PER_MEMBER = 120, OLD_BOSS_GP = 150;

    /**
     * A guild founded by an rc build, still holding exactly the GP that build gave it (its old founding GP plus whole
     * old boss clears, nothing earned by players), gets the GP the current rules would have given it; -1 for any
     * other GP (left alone: it may hold GP players earned).
     */
    public static int rescaledGp(int gp, int size) {
        int oldSeed = size * OLD_GP_PER_MEMBER;
        if (size <= 0 || gp < oldSeed || (gp - oldSeed) % OLD_BOSS_GP != 0) {
            return -1;
        }
        int clears = (gp - oldSeed) / OLD_BOSS_GP;
        int now = seedGp(size) + clears * bossGp();
        return now == gp ? -1 : now;
    }

    /** A bot guild below its target size takes in a roster bot now and then ({@code BotGuilds.refill}). */
    public static boolean refillDue(int members, int targetSize) {
        return targetSize > 0 && members < targetSize;
    }

    /** At most one kick per guild per this many minutes. */
    static long kickGapMs() {
        return minutes("kickMinutes", 30);
    }

    // ── a player invites a bot to a party ──

    /**
     * The chance a bot that was not asked first takes a real player's party invite (owner: friends accept, neutral
     * bots sometimes, rivals never). A guildmate counts like a friend unless it holds a grudge.
     */
    public static double partyAcceptChance(AffinityState state, boolean guildmate, boolean inGuildRun) {
        if (state.hostile()) {
            return 0;
        }
        if (state == AffinityState.FRIEND || inGuildRun) {
            return 1;
        }
        if (guildmate) {
            return prop("partyGuildmate", 0.9);
        }
        return prop("partyNeutral", 0.3);
    }

    // ── staged boss runs ──

    /**
     * A boss (or the Guild PQ) a bot guild runs: where it gathers, who can go, and what a member may brag about after.
     * The loot is only named in chat: no item is made (economy-safe; real boss AI and real drops come in 1.5.x).
     */
    public record Boss(String key, String name, int entranceMap, int minLevel, String[] loot) {
    }

    public static final Boss[] BOSSES = {
            new Boss("gpq", "guild pq", 101030104, 30, new String[]{"protector rock", "a stack of gp"}),
            new Boss("zakum", "zakum", 211042400, 50, new String[]{"zhelm", "zhelm (finally)", "a 4 atk zhelm lol"}),
            new Boss("pap", "papulatus", 220080000, 70, new String[]{"pap cert", "a clean pap drop"}),
            new Boss("horntail", "horntail", 240050400, 110, new String[]{"ht pendant", "a horntail necklace"}),
    };

    public static Boss boss(String key) {
        for (Boss b : BOSSES) {
            if (b.key().equalsIgnoreCase(key) || b.name().equalsIgnoreCase(key)) {
                return b;
            }
        }
        return null;
    }

    /**
     * The hardest boss the group can take: its average level must reach the boss's floor and at least
     * {@code minGroup} members must reach it. GPQ is the fallback for a guild of 30+; null below that.
     */
    public static Boss pickBoss(int[] levels, int minGroup) {
        Boss best = null;
        for (Boss b : BOSSES) {
            int able = 0;
            long sum = 0;
            for (int lv : levels) {
                if (lv >= b.minLevel()) {
                    able++;
                    sum += lv;
                }
            }
            if (able >= minGroup && sum / Math.max(1, able) >= b.minLevel()) {
                best = b;
            }
        }
        return best;
    }

    /**
     * Whether a staged run clears: most do; a group just over the floor wipes more often. 0.65..0.9.
     */
    public static boolean runClears(int avgLevel, Boss boss, double roll) {
        double margin = Math.min(1, Math.max(0, (avgLevel - boss.minLevel()) / 30.0));
        return roll < 0.65 + 0.25 * margin;
    }

    // ── rate limits ──

    /** Idle guild chatter: one line per guild per this many minutes at most (and only while a player is online). */
    static long chatterGapMs() {
        return minutes("chatterMinutes", 8);
    }

    /** A guild answers at most one guild chat line per this many seconds unless a bot is addressed by name. */
    static long answerGapMs() {
        return (long) (prop("answerSeconds", 20) * 1000);
    }
}
