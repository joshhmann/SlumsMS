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
        MapleMap map = bot.getMap();
        if (map == null) {
            return List.of();
        }
        List<Character> out = null;
        for (Character c : map.getCharacters()) {
            if (c == null || c == bot || c.getId() == bot.getId() || BotHelpers.isBot(c) || c.isHidden()) {
                continue;
            }
            if (!respects(bot, c)) {
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
        Point mp = m.getPosition();
        if (mp == null) {
            return null;
        }
        MapleMap map = bot.getMap();
        for (Character p : players) {
            Point pp = p.getPosition();
            if (pp == null) {
                continue;
            }
            if (m.isAttackedBy(p) || (Math.abs(pp.x - mp.x) <= PLAYER_MOB_PX && sameLedge(map, pp, mp))) {
                return p;
            }
        }
        return null;
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
