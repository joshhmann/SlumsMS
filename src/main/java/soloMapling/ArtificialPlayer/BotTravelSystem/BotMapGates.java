package soloMapling.ArtificialPlayer.BotTravelSystem;

import client.Character;
import config.YamlConfig;
import net.server.channel.handlers.HyperTPHandler;
import server.maps.AreaGates;

// MapleRoot's rebirth gates, as bots must respect them (ticket 03). HyperTPHandler only stops the Hyper
// Teleport Rock, but a bot treats the same id ranges as a hard rule even where a portal would let it walk in:
//   Von Leon's wing         rebirth 1 (1.6.1 b040: the castle's walls and towers and the Rose Garden are open
//                           training, as for players; HyperTPHandler.isLionKingBossWing)
//   Twilight Perion range   rebirth 1 or level 120 (owner 2026-10-29, was 2; also covers Gate to the Future, Dark
//                           Ereve, Leafre of Past)
//   Grandis / Arcane        owner 2026-10-29: deeper Grandis rebirth 1, Arcane River rebirth 2, lower Grandis 1
//                           (or level 120); server.maps.AreaGates.requiredRebirths
// Ticket 81 (round 4): with the bot's level known (mayEnter(map, rebirths, level)), the players' rule applies
// (server.maps.AreaGates): the Future and lower Grandis also open at level 120.
//   HyperTP-restricted      never (Knight Stronghold, 401053002)
// Plus one hole the research found (docs/real-bots/leveling-path-research.md, finding 4): Warrior Grounds
// 273060300 sits just past the Twilight Perion range but is the same area, so it has the same gate.
// Ticket 30/31 own the rebirth path itself; this is only the "never enter" rule. Our own creation.
public final class BotMapGates {

    private BotMapGates() {
    }

    public static final int NEVER = Integer.MAX_VALUE;

    private static final int WARRIOR_GROUNDS = 273060300;

    // Rebirths a bot needs before it may enter mapId: 0 for open maps, NEVER for maps no bot enters.
    public static int requiredRebirths(int mapId) {
        if (HyperTPHandler.isRestrictedMap(mapId)) {
            return NEVER;
        }
        if (HyperTPHandler.isGrandisandArcane(mapId)) {
            return AreaGates.requiredRebirths(mapId);
        }
        if (HyperTPHandler.isTwilightPerion(mapId) || mapId == WARRIOR_GROUNDS) {
            return AreaGates.FUTURE_REBIRTHS; // owner 2026-10-29: level 120 or the 1st rebirth (was 2)
        }
        if (HyperTPHandler.isLionKingBossWing(mapId)) {
            return 1;
        }
        return 0;
    }

    // A bot's rebirth count. Character.getReborns throws while the rebirth system is off; then nobody has any.
    public static int rebirthsOf(Character chr) {
        if (chr == null || YamlConfig.config == null || YamlConfig.config.server == null
                || !YamlConfig.config.server.USE_REBIRTH_SYSTEM) {
            return 0;
        }
        return chr.getReborns();
    }

    // With no level known: the rebirth-only rule (the strictest; level 0 opens nothing).
    public static boolean mayEnter(int mapId, int rebirths) {
        return mayEnter(mapId, rebirths, 0);
    }

    // Ticket 81 round 3/4: the players' rule (server.maps.AreaGates). The Future and lower Grandis also open at level
    // 120; deeper Grandis opens at rebirth 1 and Arcane River at rebirth 2 (owner 2026-10-29); Von Leon's wing rebirth 1;
    // restricted maps never.
    public static boolean mayEnter(int mapId, int rebirths, int level) {
        if (HyperTPHandler.isRestrictedMap(mapId)) {
            return false;
        }
        if (HyperTPHandler.isLionKingBossWing(mapId) && rebirths < 1) {
            return false;
        }
        return AreaGates.denyReason(mapId, level, rebirths) == null;
    }

    public static boolean mayEnter(int mapId, Character chr) {
        return mayEnter(mapId, rebirthsOf(chr), chr == null ? 0 : chr.getLevel())
                // ticket 158 item 5 (-Dslums.temple.enabled): a Temple of Time map past a lane exit needs that lane's quest
                && soloMapling.ArtificialPlayer.BotQuestSystem.TempleOfTime.mayEnter(mapId, chr);
    }
}
