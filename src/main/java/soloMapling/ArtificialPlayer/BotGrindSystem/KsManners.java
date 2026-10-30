package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.5.0 rc3 (owner decision 4, KS manners): a neutral bot treats a real player's platform as taken. It does not
 * pick a spot there, does not take a mob a player is fighting or standing by, and when every spot left on the map
 * is a player's it moves on (TrainingBot, sometimes with a "cc?"). Rivals and nemeses are left out on purpose:
 * they KS through the affinity system's sprees. A party member's platform is shared, and a hidden GM is not seen.
 * <p>
 * Cheap: {@link #respectedPlayers} walks the map's characters once and is empty on almost every map, and every
 * other check loops over that short list.
 */
public final class KsManners {

    private KsManners() {
    }

    /** How far past a spot's radius a player's position still counts as "on that platform". */
    static final int PLATFORM_MARGIN_PX = 200;
    /** A mob this close (x) to a respected player on its ledge is that player's. */
    static final int PLAYER_MOB_PX = 260;
    /** Vertical slack for "standing on that ledge" when the nav graph cannot tell. */
    static final int SAME_LEDGE_DY = 90;

    /** The real players on the bot's map whose platform this bot respects (empty when manners are off). */
    public static List<Character> respectedPlayers(Character bot) {
        if (bot == null || !BotCombatTuning.ksManners()) {
            return List.of();
        }
        return realPlayersOn(bot.getMap(), c -> c != bot && c.getId() != bot.getId() && respects(bot, c));
    }

    /**
     * The visible real players on this map (no bots, no hidden GM) that {@code counts} keeps: who the manners rules
     * look at. Shared by the population bots ({@link #respectedPlayers}) and owned alts (1.6.2: the players outside
     * the alt's party), so both read "a player is here" the same way. Empty on almost every map.
     */
    public static List<Character> realPlayersOn(MapleMap map, java.util.function.Predicate<Character> counts) {
        if (map == null) {
            return List.of();
        }
        List<Character> out = null;
        for (Character c : map.getCharacters()) {
            if (c == null || BotHelpers.isBot(c) || c.isHidden() || !counts.test(c)) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(2);
            }
            out.add(c);
        }
        return out == null ? List.of() : out;
    }

    /**
     * A bot respects a player's platform unless it is the player's rival or nemesis, they share a party, or it is on a
     * KS spree against the player (a KS war's called helper).
     */
    static boolean respects(Character bot, Character player) {
        Integer summoner = showcaseSummoner.get(bot.getId());
        if (summoner != null && summoner == player.getId()) {
            return false; // owner 2026-10-30: a !botshowcase bot fights by the GM who summoned it
        }
        if (bot.getParty() != null && player.getParty() != null
                && bot.getParty().getId() == player.getParty().getId()) {
            return false; // partied: grinding together is the point
        }
        if (soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.onKsSpreeAgainst(bot.getId(),
                player.getId())) {
            return false; // a KS war's helper is here to steal this player's mobs
        }
        try {
            return !BotAffinity.hostile(bot.getId(), player.getId());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The first of {@code players} standing on the platform around this anchor, or null. */
    static Character playerOn(Character bot, List<Character> players, Point anchor, int radius) {
        MapleMap map = bot.getMap();
        for (Character p : players) {
            Point pp = p.getPosition();
            if (pp != null && Math.abs(pp.x - anchor.x) <= radius + PLATFORM_MARGIN_PX
                    && sameLedge(map, anchor, pp)) {
                return p;
            }
        }
        return null;
    }

    /** The respected player this mob belongs to (fighting it, or standing by it on its ledge), or null. */
    static Character ownerOf(Character bot, List<Character> players, Monster m) {
        return ownerOf(bot.getMap(), players, m);
    }

    /**
     * Audit 163 (B21, KS ordering): how long after an attacker's last hit a mob is still theirs. Before 1.6.2 any hit,
     * ever, made a mob the hitter's for the rest of its life (Monster.isAttackedBy): one player passing through an AoE
     * map fenced off mobs from every bot. Now a claim lapses once its holder stops hitting for this long (a player who
     * leaves the map is not asked at all).
     */
    public static final long CLAIM_MS = Monster.HIT_STREAK_GAP_MS;

    /** {@code who} has hit this mob within {@link #CLAIM_MS}: it is theirs while they keep at it. */
    public static boolean claims(Monster m, int who, long now) {
        long at = m.lastHitBy(who);
        return at > 0 && now - at <= CLAIM_MS;
    }

    /**
     * When {@code who}'s live claim on this mob began (the start of their current run of hits), or 0 when they hold
     * none. The ordering rule shared by population bots and owned alts: the earlier claim wins.
     */
    public static long claimSince(Monster m, int who, long now) {
        if (!claims(m, who, now)) {
            return 0;
        }
        long since = m.hitStreakStart(who);
        return since > 0 ? since : m.lastHitBy(who);
    }

    /*
     * Audit 163 (B20): manners at the moment of the hit, not only when a target is picked. A population bot's attack
     * (BotAttackDriver.mayTarget: the swing's target, every mob an area attack catches, and the "2 or more in reach"
     * AoE count) leaves a respected player's mob alone even when the player walked up after the bot chose it. The
     * respected players are re-read at most every PLAYERS_TTL_MS per bot (a hit lands many mob checks at once).
     */
    static final long PLAYERS_TTL_MS = 500;
    private record Seen(long at, List<Character> players) {
    }
    private static final java.util.Map<Integer, Seen> seen = new java.util.concurrent.ConcurrentHashMap<>();

    /** A population bot may swing at this mob: no respected player is fighting it or standing by it. */
    public static boolean mayHit(Character bot, Monster m, long now) {
        if (bot == null || m == null || bot.isOwnedAlt() || !BotCombatTuning.ksManners()) {
            return true; // an owned alt has its own, stricter rule (OwnedAltCombat)
        }
        Seen s = seen.get(bot.getId());
        if (s == null || now - s.at() >= PLAYERS_TTL_MS || now < s.at()) {
            s = new Seen(now, respectedPlayers(bot));
            seen.put(bot.getId(), s);
        }
        return s.players().isEmpty() || ownerOf(bot.getMap(), s.players(), m, now) == null;
    }

    /** Release a bot that left (its cached player list, its showcase summoner). */
    public static void forget(int botId) {
        seen.remove(botId);
        showcaseSummoner.remove(botId);
    }

    /*
     * Owner decision 2026-10-30: a !botshowcase bot is spawned to swing at the mobs right by the GM who called it, so it
     * does not keep KS manners toward that GM (a visible GM standing by the mobs would otherwise stop the showcase).
     * Only showcase bots, only toward their own summoner: every other player is still respected, and every other bot
     * still respects that GM. Cleared when the bot is despawned (BotGeneration.removeBotFromServer -> forget).
     */
    private static final java.util.Map<Integer, Integer> showcaseSummoner = new java.util.concurrent.ConcurrentHashMap<>();

    /** BotShowcaseCommand: this showcase bot was summoned by {@code gmId}. */
    public static void showcaseSummonedBy(int botId, int gmId) {
        showcaseSummoner.put(botId, gmId);
        seen.remove(botId);
    }

    /**
     * Which of {@code players} this mob belongs to: one who has hit it lately ({@link #CLAIM_MS}), or one standing by it ({@link #PLAYER_MOB_PX}
     * across, on its ledge), or null. The one KS-manners rule: population bots ask it about the players they respect,
     * owned alts (OwnedAltCombat) about the players outside their party.
     */
    public static Character ownerOf(MapleMap map, List<Character> players, Monster m) {
        return ownerOf(map, players, m, System.currentTimeMillis());
    }

    /** ownerOf at a given time: a player's hit claims the mob for {@link #CLAIM_MS} after it lands. */
    public static Character ownerOf(MapleMap map, List<Character> players, Monster m, long now) {
        Point mp = m.getPosition();
        if (mp == null) {
            return null;
        }
        for (Character p : players) {
            Point pp = p.getPosition();
            if (pp == null) {
                continue;
            }
            if (claims(m, p.getId(), now) || standsBy(map, pp, mp)) {
                return p;
            }
        }
        return null;
    }

    /** A player at {@code player} is standing by a mob at {@code mob}: close across and on its ledge. */
    public static boolean standsBy(MapleMap map, Point player, Point mob) {
        return Math.abs(player.x - mob.x) <= PLAYER_MOB_PX && sameLedge(map, player, mob);
    }

    // Same ledge: the nav graph does not put them on different ledges, and they are within a sloped ledge's height.
    private static boolean sameLedge(MapleMap map, Point a, Point b) {
        if (Math.abs(a.y - b.y) > SAME_LEDGE_DY) {
            return false;
        }
        return map == null || !GCMovement.onDifferentLedge(map, a.x, a.y, b.x, b.y);
    }

    static void log(Character bot, String line) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.MANNERS)) {
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.MANNERS, bot.getMapId(), line);
        }
    }
}
