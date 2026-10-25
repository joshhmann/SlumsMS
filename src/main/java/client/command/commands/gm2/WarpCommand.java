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
import server.maps.FieldLimit;
import server.maps.MapleMap;
import server.maps.MiniDungeonInfo;

public class WarpCommand extends Command {
    private static final int MAX_LISTED = 10;

    {
        setDescription("Warp to a map by id or name.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage("Syntax: !warp <mapid|map name>");
            return;
        }

        MapleMap target = null;
        String query = joinStringFrom(params, 0);
        try {
            target = c.getChannelServer().getMapFactory().getMap(Integer.parseInt(params[0]));
            if (target == null) {
                player.yellowMessage("Map ID " + params[0] + " is invalid.");
                return;
            }
        } catch (NumberFormatException notAnId) {
            java.util.List<MapSearchHelper.MapMatch> found = MapSearchHelper.resolve(MapSearchHelper.findMaps(query), query);
            if (found.isEmpty()) {
                player.yellowMessage("No map matches '" + query + "'.");
                return;
            }
            if (found.size() > 1) {
                player.yellowMessage(found.size() + " maps match '" + query + "', use the id (!warp <mapid>):");
                for (int i = 0; i < Math.min(found.size(), MAX_LISTED); i++) {
                    MapSearchHelper.MapMatch m = found.get(i);
                    player.yellowMessage(m.mapId() + " - " + m.streetName() + " - " + m.mapName());
                }
                if (found.size() > MAX_LISTED) {
                    player.yellowMessage("... and " + (found.size() - MAX_LISTED) + " more.");
                }
                return;
            }
            target = c.getChannelServer().getMapFactory().getMap(found.get(0).mapId());
            if (target == null) {
                player.yellowMessage("Map ID " + found.get(0).mapId() + " is invalid.");
                return;
            }
        } catch (Exception ex) {
            player.yellowMessage("Map ID " + params[0] + " is invalid.");
            return;
        }

        if (!player.isAlive()) {
            player.dropMessage(1, "This command cannot be used when you're dead.");
            return;
        }

        if (!player.isGM()) {
            if (player.getEventInstance() != null || MiniDungeonInfo.isDungeonMap(player.getMapId()) || FieldLimit.CANNOTMIGRATE.check(player.getMap().getFieldLimit())) {
                player.dropMessage(1, "This command cannot be used in this map.");
                return;
            }
        }

        // expedition issue with this command detected thanks to Masterrulax
        player.saveLocationOnWarp();
        player.changeMap(target, target.getRandomPlayerSpawnpoint());
    }
}
