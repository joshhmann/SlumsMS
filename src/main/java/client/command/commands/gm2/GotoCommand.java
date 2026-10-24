/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import constants.game.GameConstants;
import constants.id.NpcId;
import server.maps.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class GotoCommand extends Command {

    {
        setDescription("Warp to a predefined map.");

        List<Entry<String, Integer>> towns = new ArrayList<>(GameConstants.GOTO_TOWNS.entrySet());
        sortGotoEntries(towns);

        try {
            // thanks shavit for noticing goto areas getting loaded from wz needlessly only for the name retrieval

            for (Map.Entry<String, Integer> e : towns) {
                GOTO_TOWNS_INFO += ("'" + e.getKey() + "' - #b" + (MapFactory.loadPlaceName(e.getValue())) + "#k\r\n");
            }

            List<Entry<String, Integer>> areas = new ArrayList<>(GameConstants.GOTO_AREAS.entrySet());
            sortGotoEntries(areas);
            for (Map.Entry<String, Integer> e : areas) {
                GOTO_AREAS_INFO += ("'" + e.getKey() + "' - #b" + (MapFactory.loadPlaceName(e.getValue())) + "#k\r\n");
            }
        } catch (Exception e) {
            e.printStackTrace();

            GOTO_TOWNS_INFO = "(none)";
            GOTO_AREAS_INFO = "(none)";
        }

    }

    public static String GOTO_TOWNS_INFO = "";
    public static String GOTO_AREAS_INFO = "";

    private static void sortGotoEntries(List<Entry<String, Integer>> listEntries) {
        listEntries.sort((e1, e2) -> e1.getValue().compareTo(e2.getValue()));
    }

    /** Where a player may @goto: GMs every town and area, players only the towns (1.5.0). */
    public static Map<String, Integer> destinations(boolean gm) {
        Map<String, Integer> out = new HashMap<>(GameConstants.GOTO_TOWNS);
        if (gm) {
            out.putAll(GameConstants.GOTO_AREAS);
        }
        return out;
    }

    /** Why the player can't @goto from where they stand, or null when they can. */
    public static String blockedHere(Character player) {
        return blockedReason(player.isGM(), player.isAlive(), player.getEventInstance() != null, player.getMapId(),
                player.getMap() == null ? 0 : player.getMap().getFieldLimit());
    }

    /** The @goto rules: no dead players; players not from events, mini-dungeons or maps that block migration. */
    public static String blockedReason(boolean gm, boolean alive, boolean inEvent, int mapId, int fieldLimit) {
        if (!alive) {
            return "This command cannot be used when you're dead.";
        }
        if (!gm && (inEvent || MiniDungeonInfo.isDungeonMap(mapId) || FieldLimit.CANNOTMIGRATE.check(fieldLimit))) {
            return "This command can not be used in this map.";
        }
        return null;
    }

    /**
     * Warps the player to {@code key} under the @goto rules (the {@code @guide}'s "take me there" uses it too).
     *
     * @return null when the player went, else why not
     */
    public static String warp(Character player, String key) {
        String blocked = blockedHere(player);
        if (blocked != null) {
            return blocked;
        }
        Integer mapId = destinations(player.isGM()).get(key);
        if (mapId == null) {
            return "'" + key + "' is not a town you can @goto.";
        }
        MapleMap target = player.getClient().getChannelServer().getMapFactory().getMap(mapId);
        Portal targetPortal = target.getRandomPlayerSpawnpoint();
        player.saveLocationOnWarp();
        player.changeMap(target, targetPortal);
        return null;
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            String sendStr = "Syntax: #b@goto <map name>#k. Available areas:\r\n\r\n#rTowns:#k\r\n" + GOTO_TOWNS_INFO;
            if (player.isGM()) {
                sendStr += ("\r\n#rAreas:#k\r\n" + GOTO_AREAS_INFO);
            }

            player.getAbstractPlayerInteraction().npcTalk(NpcId.SPINEL, sendStr);
            return;
        }

        String blocked = blockedHere(player);
        if (blocked != null) {
            player.dropMessage(1, blocked);
            return;
        }

        // 1.5.0: players get the towns only (the GM areas include boss maps and the GM map)
        if (destinations(player.isGM()).containsKey(params[0])) {
            warp(player, params[0]);
        } else {
            // detailed info on goto available areas suggested thanks to Vcoc
            String sendStr = "Area '#r" + params[0] + "#k' is not available. Available areas:\r\n\r\n#rTowns:#k" + GOTO_TOWNS_INFO;
            if (player.isGM()) {
                sendStr += ("\r\n#rAreas:#k\r\n" + GOTO_AREAS_INFO);
            }

            player.getAbstractPlayerInteraction().npcTalk(NpcId.SPINEL, sendStr);
        }
    }
}
