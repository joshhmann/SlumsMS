package soloMapling.ArtificialPlayer.BotTravelSystem;

import client.Character;
import config.YamlConfig;
import net.server.channel.handlers.HyperTPHandler;

// MapleRoot's rebirth gates, as bots must respect them (ticket 03). HyperTPHandler only stops the Hyper
// Teleport Rock, but a bot treats the same id ranges as a hard rule even where a portal would let it walk in:
//   Lion King's Castle      rebirth 1
//   Twilight Perion range   rebirth 2 (also covers Gate to the Future, Dark Ereve, Leafre of Past)
//   Grandis / Arcane        rebirth 3
//   HyperTP-restricted      never (Knight Stronghold, 401053002)
// Plus one hole the research found (docs/real-bots/leveling-path-research.md, finding 4): Warrior Grounds
// 273060300 sits just past the Twilight Perion range but is the same area, so it needs rebirth 2 too.
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
            return 3;
        }
        if (HyperTPHandler.isTwilightPerion(mapId) || mapId == WARRIOR_GROUNDS) {
            return 2;
        }
        if (HyperTPHandler.isLionKingMap(mapId)) {
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

    public static boolean mayEnter(int mapId, int rebirths) {
        return rebirths >= requiredRebirths(mapId);
    }
}
