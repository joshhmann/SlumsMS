/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

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

/* Tana
 * 
 * @Author Rulax
 * Helps players leave the map
 * Tana
 */

var status;

function start() {
    status = -1;
    action(1, 0, 0)
}

function action(mode, type, selection) {
    if (mode < 1) {
        cm.dispose();
    } else {
        if (mode == 0) {
            cm.dispose();
            return;
        }

        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        let eim = cm.getEventInstance();
        let mapId = cm.getMapId();
        let warpToMapId = getWarpToMapId(mapId);

        if (status == 0) {
            if (eim === null) {
                cm.sendYesNo("How did you even get in here without starting the expedition? Do you want to leave?");
            } else if (!eim.isEventCleared()) {
                cm.sendYesNo("If you leave now, you'll have to start over. Are you sure you want to leave?");
            } else {
                cm.sendYesNo("You guys finally overthrew such darkness!, what a superb feat! Congratulations! Are you sure you want to leave now?");
            }
        } else if (status == 1) {
            // 1.5.2: a null instance (a GM warp, or the run just ended) threw here, and a cleared run never disposed
            // the talk, so the player could talk to no NPC until they relogged. Only the Papulatus room is known.
            if (eim !== null && eim.isEventCleared() && mapId == 220080001 && eim.getProperty("rewarded") != "true") {
                eim.setProperty("rewarded", "true");
                const players = eim.getPlayers();
                players.forEach((chr) => {
                    chr.getAbstractPlayerInteraction().gainItem(2000005, 100);  // Power Elixirs, once per run
                });
            }
            cm.warp(warpToMapId !== 0 ? warpToMapId : 220080000);
            cm.dispose();
        } else {
            cm.dispose();
        }
    }
}

function getWarpToMapId(mapId) {
    let warpToMapId;

    switch (mapId) {
        case 220080001:
            warpToMapId = 220080000; //PAPULATUS
            break;
        // Add more cases for other map IDs as needed
        default:
            warpToMapId = 0; // Default warp destination if the map ID is not handled
    }

    return warpToMapId;
}