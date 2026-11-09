/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as
    published by the Free Software Foundation. You may not use, modify or distribute
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

const ItemConstants = Java.type("constants.inventory.ItemConstants");
const InventoryManipulator = Java.type("client.inventory.manipulator.InventoryManipulator");
const InventoryType = Java.type("client.inventory.InventoryType");
// Inventory tab names, indexed like InventoryType.getType() - 1 (EQUIP 1 ... CASH 5).
const TAB_NAMES = ["Equip", "Use", "Set-up", "Etc", "Cash"];
const TAB_TYPES = [InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH];
const ELIXIRS = 2000005; // Power Elixirs, once per Papulatus run
const ELIXIRS_GIVEN = 100;

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
            let canLeave = true;
            if (eim !== null && eim.isEventCleared() && mapId == 220080001) {
                // 1.6.7 bagrewards: the run's elixirs were handed out with no space check after the latch was set,
                // so a full Use tab lost them for everybody and nobody could ask again. Each player is measured
                // before anything is granted; a player whose bag is short stays here, unlatched, and is told.
                canLeave = givePapulatusRewards(eim);
            }
            if (!canLeave) {
                cm.dispose();
                return;
            }
            cm.warp(warpToMapId !== 0 ? warpToMapId : 220080000);
            cm.dispose();
        } else {
            cm.dispose();
        }
    }
}

// Hands the once-per-run elixirs to every player in the instance who has not had them yet and whose bag can take
// them, then says whether the talker may leave. The latch is per player and set only after that player's grant, so
// a player who was short can make room and talk again, and nobody is handed the reward twice.
function givePapulatusRewards(eim) {
    const talkerId = cm.getPlayer().getId();
    let talkerBlocked = false;
    let everyoneDone = true;

    eim.getPlayers().forEach((chr) => {
        const latch = "rewarded_" + chr.getId();
        if (eim.getProperty(latch) == "true") return;

        const short = tabsToFree(chr, [[ELIXIRS, ELIXIRS_GIVEN]]);
        if (short.length > 0) {
            everyoneDone = false;
            const message = "Your bag can't take the reward. Free " + slotsText(short) + ", then talk to me again.";
            if (chr.getId() === talkerId) {
                cm.sendOk(message);
                talkerBlocked = true;
            } else {
                chr.dropMessage(message);
            }
            return;
        }

        chr.getAbstractPlayerInteraction().gainItem(ELIXIRS, ELIXIRS_GIVEN);
        eim.setProperty(latch, "true");
    });

    // "rewarded" keeps its old meaning, "everyone present is done", for anything that reads it; nothing gates on it
    // any more, so a player who was short is still served on their next talk.
    if (everyoneDone) eim.setProperty("rewarded", "true");
    return !talkerBlocked;
}

// Slots the reward list needs in each inventory tab, counting a stack with room left as needing no new slot: this is
// the counter Inventory.checkSpots and InventoryManipulator.addFromDrop (what cm.gainItem adds with) both use.
// Quantities are summed per item id first, the way Inventory.checkSpots sums them.
function slotsNeeded(chr, items) {
    const ids = [];
    const quantities = [];
    for (let i = 0; i < items.length; i++) {
        const id = items[i][0];
        const index = ids.indexOf(id);
        if (index < 0) {
            ids.push(id);
            quantities.push(items[i][1]);
        } else {
            quantities[index] += items[i][1];
        }
    }

    const used = [0, 0, 0, 0, 0];
    const refused = [false, false, false, false, false];
    for (let i = 0; i < ids.length; i++) {
        const type = ItemConstants.getInventoryType(ids[i]);
        if (chr.getInventory(type) == null) {
            // In game every character carries all five bags; this only keeps a stubbed character (tests, probes)
            // from being refused over a bag it does not have.
            return null;
        }
        const tab = type.getType() - 1;
        const result = InventoryManipulator.checkSpaceProgressively(chr, ids[i], quantities[i], "", used[tab], false);
        if (result % 2 === 0) {
            refused[tab] = true;
        }
        used[tab] = result >> 1;
    }

    return {used: used, refused: refused};
}

// [tab name, slots to free] for every tab that cannot take the whole list, empty when all of it fits.
function tabsToFree(chr, items) {
    const needed = slotsNeeded(chr, items);
    if (needed === null) return [];

    const short = [];
    for (let tab = 0; tab < needed.used.length; tab++) {
        let missing = needed.used[tab] - chr.getInventory(TAB_TYPES[tab]).getNumFreeSlot();
        if (missing <= 0 && needed.refused[tab]) missing = 1;
        if (missing > 0) short.push([TAB_NAMES[tab], missing]);
    }

    return short;
}

function slotsText(short) {
    let out = "";
    for (let i = 0; i < short.length; i++) {
        if (i > 0) out += i === short.length - 1 ? " and " : ", ";
        out += `${short[i][1] === 1 ? "1 slot" : short[i][1] + " slots"} in your ${short[i][0]} tab`;
    }
    return out;
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
