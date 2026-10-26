package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotChatPrompt;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.SocialGate;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 slice S7 "Beef", part 1: friendly teasing, open to everyone ({@link SocialGate.Act#TEASE}: still held by
 * {@code @quiet bots} and a busy player, and by the gate's budget - one tease per bot and player per 30 minutes, 40 an
 * hour world-wide). The ribbing is keyed to how close the bot is ({@link Closeness}): a stranger only ribs mildly
 * ("rip"), someone it knows a bit more, a friend the most ("thats ur 3rd death today lol"). A rival never teases: what a
 * rival says is beef and goes through the started-it rule instead.
 *
 * <p>Triggers ({@link Trigger}): a player dies near bots, a player booms a scroll, a player the bot knows is still the
 * same level as a day ago ({@link #onSight}), or wears a weapon 25+ levels under theirs. Bots tease bots too: a bot's
 * friends standing by when it booms a scroll ({@link #onBotBoom}), on a map a real player watches.
 *
 * <p>The line bank first ({@code DramaDialogue.yaml} Tease*, ContentGuard on every line, never the same line to the
 * same player twice); a friend's tease is a model line now and then ({@link #LLM_CHANCE}), only within the gate's
 * unprompted-LLM budget ({@link SocialGate#llmOk}) and only when ContentGuard passes it.
 */
public final class Teasing {

    private static final Logger log = LoggerFactory.getLogger(Teasing.class);

    /** How close the teasing bot is to the one it teases. */
    public enum Closeness { STRANGER, KNOWN, FRIEND }

    public enum Trigger { DEATH, BOOM, SLOW_LEVEL, OLD_WEAPON }

    /** Bots this far away (px) did not see the death or the boom. */
    static final int NEAR_PX = 600;
    /** A sighting tease is rolled at most this often per bot and player. */
    static final long ROLL_GAP_MS = TimeUnit.MINUTES.toMillis(10);
    /** The same level this long after the bot first saw it is "still {LEVEL}?". */
    static final long SLOW_LEVEL_MS = TimeUnit.HOURS.toMillis(24);
    /** A weapon this many levels under the player's level is "outdated" (as RivalRoasts' gear roast). */
    static final int OLD_WEAPON_GAP = 25;
    static final double LLM_CHANCE = 0.25;
    static final int BOT_TEASES_PER_HOUR = 20;
    static final int SEEN_MAX = 20_000;

    /** (bot, player) -> {level first seen at, when}: for "still {LEVEL}?". Least recently used first, bounded. */
    private static final LinkedHashMap<Long, long[]> seenLevel = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, long[]> eldest) {
            return size() > SEEN_MAX;
        }
    };

    private Teasing() {
    }

    // ── the rules (pure) ──

    /** How close a bot is to a real player, or null for a rival (it does not tease, it has beef). */
    static Closeness toPlayer(int botId, int playerId) {
        AffinityState s = BotAffinity.state(botId, playerId);
        if (s.hostile()) {
            return null;
        }
        if (s == AffinityState.FRIEND) {
            return Closeness.FRIEND;
        }
        return BotAffinity.score(botId, playerId) >= 8 ? Closeness.KNOWN : Closeness.STRANGER;
    }

    /** How close a bot is to another bot (its bond, S2), or null for a rival. */
    static Closeness toBot(int botId, int otherId) {
        AffinityState s = BotBonds.state(botId, otherId);
        if (s.hostile()) {
            return null;
        }
        if (s == AffinityState.FRIEND) {
            return Closeness.FRIEND;
        }
        return BotBonds.score(botId, otherId) >= 8 ? Closeness.KNOWN : Closeness.STRANGER;
    }

    /** The line bank node for a tease, or null when this closeness may not tease about it (a stranger and levels). */
    static String node(Trigger t, Closeness c) {
        if (c == null) {
            return null;
        }
        boolean friend = c == Closeness.FRIEND;
        return switch (t) {
            case DEATH -> friend ? "TeaseDeathFriend" : "TeaseDeathMild";
            case BOOM -> friend ? "TeaseBoomFriend" : "TeaseBoomMild";
            case SLOW_LEVEL -> c == Closeness.STRANGER ? null : "TeaseSlowLevel";
            case OLD_WEAPON -> friend ? "TeaseWeaponFriend" : "TeaseWeaponMild";
        };
    }

    /** The chance a bot of this closeness teases about this (strangers rarely, friends most). */
    static double chance(Trigger t, Closeness c) {
        if (node(t, c) == null) {
            return 0;
        }
        return switch (t) {
            case DEATH -> c == Closeness.FRIEND ? 0.35 : c == Closeness.KNOWN ? 0.15 : 0.04;
            case BOOM -> c == Closeness.FRIEND ? 0.5 : c == Closeness.KNOWN ? 0.25 : 0.08;
            case SLOW_LEVEL -> c == Closeness.FRIEND ? 0.25 : 0.1;
            case OLD_WEAPON -> c == Closeness.FRIEND ? 0.15 : c == Closeness.KNOWN ? 0.06 : 0.02;
        };
    }

    /** Whether a weapon of this level is outdated for a player of that level. */
    static boolean outdated(int level, int weaponLevel) {
        return weaponLevel >= 0 && level >= 30 && level - weaponLevel >= OLD_WEAPON_GAP;
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    /**
     * Notes the level this bot sees the player at; true when it is the same level it first saw them at at least
     * {@link #SLOW_LEVEL_MS} ago (the player "levels slowly").
     */
    static boolean stillSameLevel(int botId, int playerId, int level, long now) {
        long k = ((long) botId << 32) | (playerId & 0xffffffffL);
        synchronized (seenLevel) {
            long[] seen = seenLevel.get(k);
            if (seen == null || seen[0] != level) {
                seenLevel.put(k, new long[]{level, now});
                return false;
            }
            return now - seen[1] >= SLOW_LEVEL_MS;
        }
    }

    // ── triggers ──

    /**
     * A roaming or stationed bot noticed a real player (driver thread, memory only): a slow-levelling acquaintance or
     * an outdated weapon may get a tease, rolled at most every {@link #ROLL_GAP_MS} per pair. True when it teased.
     */
    static boolean onSight(BotSM sm, Character player) {
        if (!BeefKit.enabled() || sm == null || sm.getChr() == null || player == null
                || !BotHelpers.isVisibleRealPlayer(player)) {
            return false;
        }
        Character bot = sm.getChr();
        if (bot.getMapId() != player.getMapId()) {
            return false;
        }
        long now = BeefKit.now();
        boolean slow = stillSameLevel(bot.getId(), player.getId(), player.getLevel(), now);
        Closeness c = toPlayer(bot.getId(), player.getId());
        if (c == null || BotConversation.isTalking(bot.getId(), player.getId())
                || !DramaKit.take("tz:roll:" + bot.getId() + ":" + player.getId(), now, ROLL_GAP_MS)) {
            return false;
        }
        Random rng = ThreadLocalRandom.current();
        Map<String, String> t = new HashMap<>();
        t.put("PLAYER", player.getName());
        t.put("LEVEL", Integer.toString(player.getLevel()));
        Trigger trigger = null;
        if (slow && rng.nextDouble() < chance(Trigger.SLOW_LEVEL, c)) {
            trigger = Trigger.SLOW_LEVEL;
        } else {
            RivalRoasts.Facts f = RivalRoasts.facts(player, 0, 0);
            if (f.weapon() != null && outdated(player.getLevel(), f.weaponLevel())
                    && rng.nextDouble() < chance(Trigger.OLD_WEAPON, c)) {
                trigger = Trigger.OLD_WEAPON;
                t.put("WEAPON", f.weapon());
            }
        }
        if (trigger == null || !may(bot, player, now)) {
            return false;
        }
        Trigger chosen = trigger;
        ExecutorServiceManager.runAsync(() -> say(bot, player, chosen, c, t, rng));
        return true;
    }

    /** A real player died (packet thread): one bot that saw it may tease, a friend most likely. */
    static void onPlayerDeath(Character player) {
        aroundPlayer(player, Trigger.DEATH, Map.of("COUNT", ordinal(Math.max(1, BotRivalry.deathsToday(player.getId())))));
    }

    /** A real player boomed a scroll (its item is gone): one bot that saw it may tease. */
    static void onPlayerBoom(Character player, int itemId) {
        aroundPlayer(player, Trigger.BOOM, Map.of("ITEM", itemName(itemId)));
    }

    private static void aroundPlayer(Character player, Trigger trigger, Map<String, String> extra) {
        if (!BeefKit.enabled() || player == null || player.getMap() == null || BotHelpers.isBot(player)
                || !BotHelpers.isVisibleRealPlayer(player)) {
            return;
        }
        long now = BeefKit.now();
        Random rng = ThreadLocalRandom.current();
        List<Object[]> near = new ArrayList<>(); // {bot, closeness}
        for (Character c : nearbyBots(player)) {
            Closeness close = toPlayer(c.getId(), player.getId());
            if (close != null) {
                near.add(new Object[]{c, close});
            }
        }
        near.sort(Comparator.comparingInt((Object[] o) -> -((Closeness) o[1]).ordinal()));
        for (Object[] o : near) {
            Character bot = (Character) o[0];
            Closeness close = (Closeness) o[1];
            if (rng.nextDouble() >= chance(trigger, close)) {
                continue;
            }
            if (!may(bot, player, now)) {
                continue;
            }
            Map<String, String> t = new HashMap<>(extra);
            t.put("PLAYER", player.getName());
            BotTiming.after(1_200 + rng.nextInt(2_000), () -> say(bot, player, trigger, close, t, rng));
            return; // one tease per death or boom
        }
    }

    /**
     * A bot boomed a scroll: one friend of it standing by on a map a real player watches may tease it (bot to bot,
     * free of the gate; at most {@link #BOT_TEASES_PER_HOUR} an hour world-wide).
     */
    static void onBotBoom(Character bot) {
        if (!BeefKit.enabled() || bot == null || bot.getMap() == null || !DramaKit.realPlayerOnMap(bot)) {
            return;
        }
        long now = BeefKit.now();
        Random rng = ThreadLocalRandom.current();
        for (int f : BotBonds.friendsOf(bot.getId())) {
            Character friend = BeefKit.online(f);
            if (friend == null || friend.getMap() != bot.getMap() || DramaKit.inScene(f)) {
                continue;
            }
            if (rng.nextDouble() >= chance(Trigger.BOOM, Closeness.FRIEND)
                    || !DramaKit.hourly("tz-bot", BOT_TEASES_PER_HOUR, now)) {
                return;
            }
            BotTiming.after(2_000 + rng.nextInt(2_000), () -> {
                String line = DramaKit.say(friend, "TeaseBotBoom", Map.of("OTHER", bot.getName()));
                BeefKit.qa(friend.getMapId(), "TEASE boom friend " + friend.getName() + " -> " + bot.getName() + ": " + line);
            });
            return;
        }
    }

    // ── delivery ──

    /** The gate (quiet, busy) and its budget (pair cooldown, world cap). A no is logged and spends nothing. */
    static boolean may(Character bot, Character player, long now) {
        SocialGate.Verdict v = SocialGate.check(bot, player, SocialGate.Act.TEASE);
        if (!v.ok()) {
            SocialGate.note(SocialGate.Act.TEASE, bot.getId(), bot.getName(), player, v);
            return false;
        }
        if (!SocialGate.take(SocialGate.Act.TEASE, bot.getId(), player.getId(), now)) {
            return false;
        }
        SocialGate.note(SocialGate.Act.TEASE, bot.getId(), bot.getName(), player, v);
        return true;
    }

    /** Says the tease: a friend's model line now and then (within the LLM budget), else the line bank. */
    static void say(Character bot, Character player, Trigger trigger, Closeness close, Map<String, String> tokens,
                    Random rng) {
        try {
            if (bot.getMap() == null || bot.getMapId() != player.getMapId()) {
                return;
            }
            String node = node(trigger, close);
            BotBrain brain = BotBrains.get();
            if (close == Closeness.FRIEND && brain.isLive() && rng.nextDouble() < LLM_CHANCE && SocialGate.llmOk()) {
                List<BotBrain.Turn> prompt = BotChatPrompt.conversation(prompt(bot.getName(), player.getName(), trigger,
                        tokens), List.of(BotBrain.Turn.user("(" + player.getName() + " is standing near you)")));
                brain.complete(player.getId(), prompt).thenAccept(line -> {
                    String said = line.map(String::trim).filter(l -> ContentGuard.chatProblem(l) == null)
                            .filter(l -> SocialGate.fresh(bot.getId(), player.getId(), l)).orElse(null);
                    if (said != null) {
                        soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak(bot, said);
                        qa(bot, player, trigger, close, "(model) " + said);
                    } else {
                        qa(bot, player, trigger, close, DramaKit.sayTo(bot, player, node, tokens));
                    }
                });
                return;
            }
            qa(bot, player, trigger, close, DramaKit.sayTo(bot, player, node, tokens));
        } catch (RuntimeException e) {
            log.debug("Tease by {} failed", bot.getName(), e);
        }
    }

    /** The model prompt: a friendly jab between friends, about the game, with the facts. */
    static String prompt(String botName, String playerName, Trigger trigger, Map<String, String> t) {
        String what = switch (trigger) {
            case DEATH -> playerName + " just died near you" + (t.containsKey("COUNT") ? " (their " + t.get("COUNT")
                    + " death today)" : "");
            case BOOM -> playerName + " just boomed their " + t.getOrDefault("ITEM", "item") + " with a scroll";
            case SLOW_LEVEL -> playerName + " is still level " + t.getOrDefault("LEVEL", "?") + ", same as yesterday";
            case OLD_WEAPON -> playerName + " is level " + t.getOrDefault("LEVEL", "?") + " and still uses a "
                    + t.getOrDefault("WEAPON", "low level weapon");
        };
        return "You are " + botName + ", a MapleStory player in 2008, and " + playerName + " is your friend in the game. "
                + what + ". Write ONE short friendly teasing line to them, under 60 characters, lowercase, old-school"
                + " game chat, playful not mean, about the game only. Never: real-life insults, looks, family, the real"
                + " world, anything sexual, AI or bots. No emojis, no quotes, no hashtags.";
    }

    private static void qa(Character bot, Character player, Trigger trigger, Closeness close, String line) {
        BeefKit.qa(bot.getMapId(), "TEASE " + trigger.name().toLowerCase(Locale.ROOT) + " "
                + close.name().toLowerCase(Locale.ROOT) + " " + bot.getName() + " -> " + player.getName() + ": " + line);
    }

    /** The bots standing within {@link #NEAR_PX} of the player, not held by a scene, nearest first. */
    static List<Character> nearbyBots(Character player) {
        List<Character> out = new ArrayList<>();
        Point at = player.getPosition();
        try {
            for (Character c : player.getMap().getAllPlayers()) {
                if (c == null || c.getId() == player.getId() || !BotHelpers.isBot(c) || DramaKit.inScene(c.getId())
                        || CharacterStorage.getBotById(c.getId()) == null) {
                    continue;
                }
                Point p = c.getPosition();
                if (at != null && p != null && Math.abs(p.x - at.x) <= NEAR_PX && Math.abs(p.y - at.y) <= NEAR_PX / 2) {
                    out.add(c);
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        if (at != null) {
            out.sort(Comparator.comparingDouble(c -> c.getPosition().distance(at)));
        }
        return out;
    }

    static String itemName(int itemId) {
        try {
            String n = server.ItemInformationProvider.getInstance().getName(itemId);
            return n == null || n.isBlank() ? "item" : n.toLowerCase(Locale.ROOT);
        } catch (RuntimeException | LinkageError e) {
            return "item";
        }
    }

    static void resetForTest() {
        synchronized (seenLevel) {
            seenLevel.clear();
        }
    }
}
