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
/* Adobis
 * 
 * El Nath - The Door to Zakum (211042300)
 * 
 * Vs Zakum Recruiter NPC
 * 
 * Custom Quest 100200 = Whether you can start Zakum PQ
 * Custom Quest 100201 = Whether you have done the trials
*/

const ItemConstants = Java.type("constants.inventory.ItemConstants");
const InventoryManipulator = Java.type("client.inventory.manipulator.InventoryManipulator");
const InventoryType = Java.type("client.inventory.InventoryType");
const ArrayList = Java.type("java.util.ArrayList");
const JavaInteger = Java.type("java.lang.Integer");
// Inventory tab names, indexed like InventoryType.getType() - 1 (EQUIP 1 ... CASH 5).
const TAB_NAMES = ["Equip", "Use", "Set-up", "Etc", "Cash"];

var status;
var em;
var selectedType;
var gotAllDocs;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
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

        if (cm.haveItem(4001109, 1)) {
            cm.warp(921100000, "out00");
            cm.dispose();
            return;
        }

        if (!(cm.isQuestStarted(100200) || cm.isQuestCompleted(100200))) {   // thanks Vcoc for finding out a need of reapproval from the masters for Zakum expeditions
            if (cm.getPlayer().getLevel() >= 50) {  // thanks Z1peR for noticing not-so-clear unmet requirements message here.
                cm.sendOk("Beware, for the power of olde has not been forgotten... If you seek to defeat #rZakum#k someday, earn the #bChief's Residence Council#k approval foremost and then #bface the trials#k, only then you will become eligible to fight.");
            } else {
                cm.sendOk("Beware, for the power of olde has not been forgotten...");
            }

            cm.dispose();
            return;
        }

        em = cm.getEventManager("ZakumPQ");
        if (em == null) {
            cm.sendOk("The Zakum PQ has encountered an error.");
            cm.dispose();
            return;
        }

        if (status == 0) {
            cm.sendSimple("#e#b<Party Quest: Zakum Campaign>\r\n#k#n" + em.getProperty("party") + "\r\n\r\nBeware, for the power of olde has not been forgotten... #b\r\n#L0#Enter the Unknown Dead Mine (Stage 1)#l\r\n#L1#Face the Breath of Lava (Stage 2)#l\r\n#L2#Forging the Eyes of Fire (Stage 3)#l");
        } else if (status == 1) {
            if (selection == 0) {
                if (cm.getParty() == null) {
                    cm.sendOk("You can participate in the party quest only if you are in a party.");
                    cm.dispose();
                } else if (!cm.isLeader()) {
                    cm.sendOk("Your party leader must talk to me to start this party quest.");
                    cm.dispose();
                } else {
                    var eli = em.getEligibleParty(cm.getParty());
                    if (eli.size() > 0) {
                        if (!em.startInstance(cm.getParty(), cm.getPlayer().getMap(), 1)) {
                            cm.sendOk("Another party has already entered the #rParty Quest#k in this channel. Please try another channel, or wait for the current party to finish.");
                        }
                    } else {
                        cm.sendOk("You cannot start this party quest yet, because either your party is not in the range size, some of your party members are not eligible to attempt it or they are not in this map. If you're having trouble finding party members, try Party Search.");
                    }

                    cm.dispose();
                }
            } else if (selection == 1) {
                if (cm.haveItem(4031061) && !cm.haveItem(4031062)) {
                    cm.sendYesNo("Would you like to attempt the #bBreath of Lava#k?  If you fail, there is a very real chance you will die.");
                } else {
                    if (cm.haveItem(4031062)) {
                        cm.sendNext("You've already got the #bBreath of Lava#k, you don't need to do this stage.");
                    } else {
                        cm.sendNext("Please complete the earlier trials first.");
                    }

                    cm.dispose();
                }
            } else {
                if (cm.haveItem(4031061) && cm.haveItem(4031062)) {
                    if (!cm.haveItem(4000082, 30)) {
                        cm.sendOk("You have completed the trials, however there's still the need of #b30 #t4000082##k to forge 5 #t4001017#.");
                    } else {
                        // 1.6.7 bagrewards: the three materials were taken and then the Eyes handed out with no
                        // space check, so a full Etc tab cost the player the Eyes (about 1500 kills of teeth) after
                        // completeQuest had already been recorded. Nothing is taken unless the Eyes fit, measured
                        // against the bag as the takes will leave it: a stack the takes empty frees its slot.
                        const short = eyesSlotsShort();
                        if (short > 0) {
                            cm.sendOk(`Free ${short === 1 ? "1 slot" : short + " slots"} in your ${eyesTabName()} tab, then come back, nothing was taken.`);
                            cm.dispose();
                            return;
                        }

                        cm.completeQuest(100201);
                        cm.gainItem(4031061, -1);
                        cm.gainItem(4031062, -1);
                        cm.gainItem(4000082, -30);

                        cm.gainItem(4001017, 5);
                        cm.sendNext("You #rhave completed the trials#k, from now on having my approval to challenge Zakum.");
                    }

                    cm.dispose();
                } else {
                    cm.sendOk("You lack some of the required items to forge the #b#t4001017##k.");
                    cm.dispose();
                }
            }
        } else if (status == 2) {
            cm.warp(280020000, 0);
            cm.dispose();
        }
    }
}

// The Eyes of Fire this NPC hands out, measured against the bag as the same talk's takes will leave it. An Etc
// stack that a take empties frees its slot and pays for the Eyes; a stack with something left frees nothing.
// InventoryManipulator.checkSpaceProgressively is the same counter cm.gainItem consults, so a bag this says fits
// is a bag the grant delivers to.
function eyesSlotsShort() {
    const chr = cm.getPlayer();
    const addType = ItemConstants.getInventoryType(4001017);
    const removed = [[4031061, 1], [4031062, 1], [4000082, 30]];

    let freedInGrantTab = 0;
    for (let i = 0; i < removed.length; i++) {
        const removeType = ItemConstants.getInventoryType(removed[i][0]);
        if (removeType !== addType) continue; // only a slot in the grant's own tab pays for the grant

        const stacks = chr.getInventory(removeType).listById(removed[i][0]);
        let remaining = removed[i][1];
        for (let s = 0; s < stacks.size() && remaining > 0; s++) {
            const quantity = stacks.get(s).getQuantity();
            if (quantity <= remaining) freedInGrantTab++;
            remaining -= quantity;
        }
    }

    const needed = InventoryManipulator.checkSpaceProgressively(chr, 4001017, 5, "", 0, false) >> 1;
    return needed - chr.getInventory(addType).getNumFreeSlot() - freedInGrantTab;
}

function eyesTabName() {
    return TAB_NAMES[ItemConstants.getInventoryType(4001017).getType() - 1];
}
