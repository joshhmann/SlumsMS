/*
	NPC Name: 		The Forgotten Temple Manager
	Map(s): 		Deep in the Shrine - Twilight of the gods
	Description: 		Pink Bean
 */

var status;

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

        var eim = cm.getEventInstance();
        if (eim === null) {
            // 1.6.0: no event instance (walked in some other way, or the room is gone) used to throw here.
            if (status == 0) {
                cm.sendYesNo("Do you want to get out now?");
            } else if (status == 1) {
                cm.warp(270050000, 0);
                cm.dispose();
            }
        } else if (!eim.isEventCleared()) {
            if (status == 0) {
                cm.sendYesNo("Do you want to get out now?");
            } else if (status == 1) {
                cm.warp(270050000, 0);
                cm.dispose();
            }

        } else {
            if (status == 0) {
                cm.sendYesNo("Pink Bean has been defeated! You guys sure are true heroes of this land! In no time, Temple of Time will shine again as bright as ever, all thanks to your efforts! Hooray to our heroes!! Are you ready to go now?");
            } else if (status == 1) {
                // 1.6.0: the extras are paid only for a cleared run, once per player, and only when everything fits.
                var claimKey = "pinkBeanPaid_" + cm.getPlayer().getId();
                if (eim.getProperty(claimKey) == "true") {
                    cm.sendOk("You have already received your prize for this run.");
                } else if (!cm.canHold(4021010, 1) || !cm.canHold(2022282, 2) || !cm.canHold(2022179, 6) || !cm.canHold(2000005, 1000)) {
                    cm.sendOk("You cannot receive an instance prize without having an empty room in your EQUIP, USE, SET-UP and ETC inventory.");
                } else if (eim.giveEventReward(cm.getPlayer(), 1)) {
                    eim.setProperty(claimKey, "true");
                    cm.gainItem(4021010, 1);
                    cm.gainItem(2022282, 2);
                    cm.gainItem(2022179, 6);
                    cm.gainItem(2000005, 1000);

                    cm.warp(270050000);
                } else {
                    cm.sendOk("You cannot receive an instance prize without having an empty room in your EQUIP, USE, SET-UP and ETC inventory.");
                }

                cm.dispose();
            }
        }
    }
}