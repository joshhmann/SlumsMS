
/* Tana
 * 
 * @Author Rulax, Seeker1437
 * Helps players leave the map and logs weekly bosses
 * Tana
 */

const ExpeditionBossLog = Java.type("server.expeditions.ExpeditionBossLog");
const ItemConstants = Java.type("constants.inventory.ItemConstants");
const InventoryManipulator = Java.type("client.inventory.manipulator.InventoryManipulator");
const InventoryType = Java.type("client.inventory.InventoryType");
// Inventory tab names, indexed like InventoryType.getType() - 1 (EQUIP 1 ... CASH 5).
const TAB_NAMES = ["Equip", "Use", "Set-up", "Etc", "Cash"];
const TAB_TYPES = [InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH];

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

        if (warpToMapId !== 0) {
            if (eim === null) {
                cm.sendYesNo("How did you even get in here without starting the expedition? Do you want to leave?");
            } else if (!eim.isEventCleared()) {
                cm.sendYesNo("If you leave now, you'll have to start over. \r\n\r\n" +
                    cm.getEventInstance().sendDmgDealt(cm.getPlayer().getWorld()) +
                    "Are you sure you want to leave?");
            } else {
                cm.sendYesNo("You guys finally overthrew such darkness! What a superb feat! Congratulations! \r\n\r\n" +
                    cm.getEventInstance().sendDmgDealt(cm.getPlayer().getWorld()) +
                    "Are you sure you want to leave now?");
            }
        } else {
            cm.sendYesNo("If you leave now, you'll have to start over. Are you sure you want to leave?");
        }

        if (status == 1) {
            // 1.5.2: a null instance threw here (the "how did you get in here?" way out), and a cleared run never
            // disposed the talk, so the player could talk to no NPC until they relogged.
            if (eim !== null && eim.isEventCleared()) {
                // 1.6.7 bagrewards: a clear reward that does not land is a reward lost. cm.gainItem answers a full
                // tab with "Your inventory is full" and returns null, and the old latch then stopped the player from
                // ever asking again. Each player's list is measured before anything is taken or granted; a player
                // whose bag cannot take it stays in the room, unlatched, and is told which tab to free.
                if (!giveClearRewards(eim)) {
                    cm.dispose();
                    return;
                }
            }
            cm.warp(warpToMapId !== 0 ? warpToMapId : cm.getPlayer().getMap().getReturnMapId());
            cm.dispose();
        }
    }
}

// Hands this room's clear rewards to every player in the instance who has not had them yet and whose bag can take
// them, then says whether the talker may leave. Each player's [itemid, quantity] list is built once, random pick
// included, and that same list is both measured for space and granted, so the check and the grant cannot disagree.
// The first talker still rewards everybody present, but nobody whose bag is short is latched: they can make room and
// talk again. Rewards are the same ids, quantities and odds as before.
function giveClearRewards(eim) {
    const reward = clearRewards(cm.getMapId());
    if (reward === null) return true; // no reward table for this room: leave exactly as it was

    const talkerId = cm.getPlayer().getId();
    let talkerBlocked = false;
    let everyoneDone = true;

    eim.getPlayers().forEach((chr) => {
        if (chr.isBot()) return; // 1.6.1: a bot was handed its boss item at the clear (RebirthBossRuns.onClear); it has no client to give the rest to
        const latch = "rewarded_" + chr.getId();
        if (eim.getProperty(latch) == "true") return; // this player already has this room's reward

        const items = reward.roll();
        const short = tabsToFree(chr, items);
        if (short.length > 0) {
            everyoneDone = false;
            const message = "Your bag can't take the boss rewards. Free " + slotsText(short) + ", then talk to me again.";
            if (chr.getId() === talkerId) {
                cm.sendOk(message);
                talkerBlocked = true;
            } else {
                chr.dropMessage(message);
            }
            return;
        }

        chr.getClient().getWorldServer().removeUnclaimed(reward.log, chr.getId());
        const playerInteraction = chr.getAbstractPlayerInteraction();
        for (let i = 0; i < items.length; i++) {
            playerInteraction.gainItem(items[i][0], items[i][1]);
        }
        eim.setProperty(latch, "true");
    });

    // "rewarded" keeps its old meaning, "everyone present is done", for anything that reads it; nothing gates on it
    // any more, so a player who was short is still served on their next talk.
    if (everyoneDone) eim.setProperty("rewarded", "true");
    return !talkerBlocked;
}

// The clear reward of one boss room: the boss-log entry to clear and a roll() that builds one player's exact
// [itemid, quantity] list, random pick included. The lists are the ones this script has always given, in the same
// order, duplicates kept (two entries of 2022282 stay two entries). null for a room that has no reward table.
function clearRewards(mapId) {
    switch (mapId) {
        case 280030000: //ZAKUM
            return {
                log: ExpeditionBossLog.BossLogEntry.ZAKUM,
                roll: () => [[4032133, 1], [2000005, 100]]
            };
        case 240060200: //HORNTAIL
            return {
                log: ExpeditionBossLog.BossLogEntry.HORNTAIL,
                roll: () => [[4001094, 1], [2022179, 1], [2000005, 200]]
            };
        case 211070100: //VON LEON
            return {
                log: ExpeditionBossLog.BossLogEntry.VONLEON,
                roll: () => {
                    const list = [[4001693, 1]]; // Von Leon's Seal: the rebirth-2 stone needs it
                    const randomChance = Math.random();

                    if (randomChance < 0.2) { // 20% chance to get 2000005
                        list.push([2000005, 400]);
                    } else if (randomChance < 0.4) { // 20% chance to get 4000999
                        list.push([4000999, 200]);
                    } else if (randomChance < 0.6) { // 20% chance to get 2022282
                        list.push([2022282, 1]);
                    } else if (randomChance < 0.8) { // 20% chance to get 2022179
                        list.push([2022179, 2]);
                    } else if (randomChance < 0.84) { // 4% chance to get 4036544
                        list.push([4036544, 1]);
                    } else if (randomChance < 0.88) { // 4% chance to get 4036545
                        list.push([4036545, 1]);
                    } else if (randomChance < 0.92) { // 4% chance to get 4036546
                        list.push([4036546, 1]);
                    } else if (randomChance < 0.96) { // 4% chance to get 4036547
                        list.push([4036547, 1]);
                    } else { // 4% chance to get 4036548
                        list.push([4036548, 1]);
                    }

                    return list;
                }
            };
        case 271040100: //CYGNUS
            return {
                log: ExpeditionBossLog.BossLogEntry.CYGNUS,
                roll: () => [[4000659, 1], [4000999, 500], [2000005, 500], [2022282, 3], [2022179, 6], [2022283, 3],
                    anyClassCore(1)]
            };
        case 450007440: //WILL HARD
            return {
                log: ExpeditionBossLog.BossLogEntry.WILLSPIDER,
                roll: () => [[4000999, 2000], [4036536, 1], [4310000, 8], [4001890, 2], [2022282, 10],
                    [2022179, 10], [2000005, 1000], [2022282, 10], anyClassCore(5)]
            };
        case 450010100: //VERUS HARD
            return {
                log: ExpeditionBossLog.BossLogEntry.VERUS,
                roll: () => [[4000999, 2000], [4036538, 1], [4310000, 8], [4001893, 2], [2022282, 10],
                    [2022282, 10], [2022179, 10], [2000005, 1000], anyClassCore(5)]
            };
        case 450012210: //DARKNELL HARD
            return {
                log: ExpeditionBossLog.BossLogEntry.DARKNELL,
                roll: () => [[4000999, 2000], [4036537, 1], [4310000, 8], [4001894, 2], [2022282, 10],
                    [2022179, 10], [2000005, 1000], anyClassCore(5)]
            };
        case 450004750: //LUCID HARD
            return {
                log: ExpeditionBossLog.BossLogEntry.LUCID,
                roll: () => [[4000999, 2000], [4036535, 1], [4310000, 8], [4033732, 2], [4001879, 2], // Non-quest forge trophy; fixed clear reward.
                    [2022282, 10], [2022179, 10], [2000005, 1000], anyClassCore(5)]
            };
        case 401060100: //MAGNUS
            return {
                log: ExpeditionBossLog.BossLogEntry.MAGNUS,
                roll: () => [[4000999, 4000], [4036540, 1], [4310000, 8], [4036543, 1], [2022282, 10],
                    [2022179, 10], [2000005, 1000], anyClassCore(7)]
            };

        //NORMAL BOSSES

        case 450007441: //WILL NORMAL
            return {
                log: ExpeditionBossLog.BossLogEntry.NWILLSPIDER,
                roll: () => [[4000999, 800], [4001890, 1], [2022282, 6], [2022283, 6], [2022179, 7], [2000005, 1000],
                    anyClassCore(3)]
            };
        case 450010101: //VERUS NORMAL
            return {
                log: ExpeditionBossLog.BossLogEntry.NVERUS,
                roll: () => [[4000999, 800], [4001893, 1], [2022282, 6], [2022283, 6], [2022179, 7], [2000005, 1000],
                    anyClassCore(3)]
            };
        case 450012211: //DARKNELL NORMAL
            return {
                log: ExpeditionBossLog.BossLogEntry.NDARKNELL,
                roll: () => [[4000999, 800], [4001894, 1], [2022282, 6], [2022283, 6], [2022179, 7], [2000005, 1000],
                    anyClassCore(3)]
            };
        case 450004751: //LUCID NORMAL
            return {
                log: ExpeditionBossLog.BossLogEntry.NLUCID,
                roll: () => [[4000999, 800], [4033732, 1], [4001879, 1], // Non-quest forge trophy; fixed clear reward.
                    [2022282, 6], [2022283, 6], [2022179, 7], [2000005, 1000], anyClassCore(3)]
            };
    }

    return null;
}

// One of the five 4036544-4036548 cores, equally likely, in that quantity: the same pick every case above has
// always made.
function anyClassCore(quantity) {
    const randomChance = Math.random();

    if (randomChance < 0.2) {
        return [4036544, quantity];
    } else if (randomChance < 0.4) {
        return [4036545, quantity];
    } else if (randomChance < 0.6) {
        return [4036546, quantity];
    } else if (randomChance < 0.8) {
        return [4036547, quantity];
    }

    return [4036548, quantity];
}

// Slots the reward list needs in each inventory tab, counting a stack with room left as needing no new slot: this is
// the counter Inventory.checkSpots (what cm.canHoldAll asks) and InventoryManipulator.addFromDrop (what cm.gainItem
// adds with) both use. Quantities are summed per item id first, the way Inventory.checkSpots sums them, so two
// entries of the same item cannot both count the same room left in an existing stack. Every item in these lists
// stacks, so summing is right.
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
            // In game every character carries all five bags (Character builds them in its constructor), so this only
            // keeps a character stubbed without bags (tests, probes) from being refused over a bag it does not have.
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
        case 280030000:
            warpToMapId = 211042400; //ZAKUM
            break;
        case 240060200:
            warpToMapId = 240050600; //HORNTAIL
            break;
        case 450007440:
            warpToMapId = 450007240; //WILL HARD
            break;
        case 450012210:
            warpToMapId = 450012200; //DARKNELL HARD
            break;
        case 450010100:
            warpToMapId = 450011500; //VERUS HARD
            break;
        case 541020800:
            warpToMapId = 541020700; //KREXEL
            break;
        case 211070100:
            warpToMapId = 211070000; //VON LEON
            break;
        case 271040100:
            warpToMapId = 271040000; //CYGNUS
            break;
        case 800040410:
            warpToMapId = 800040401; //CASTELLAN
            break;
        case 450004750:
            warpToMapId = 450003600; //LUCID HARD
            break;

        case 450004751:
            warpToMapId = 450003600; //LUCID NORMAL
            break;

        case 450007441:
            warpToMapId = 450007240; //WILL NORMAL
            break;

        case 450012211:
            warpToMapId = 450012200; //DARKNELL NORMAL
            break;

        case 450010101:
            warpToMapId = 450011500; //VERUS NORMAL
            break;

        
        case 401060100:
            warpToMapId = 401053002; //VERUS NORMAL
            break;

        // Add more cases for other map IDs as needed
        default:
            warpToMapId = 0; // Default warp destination if the map ID is not handled
    }

    return warpToMapId;
}
