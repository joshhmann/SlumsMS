/*
 * Quest 2234 - "Enjoy the Entitlement!" - Captain Al (NPC 1002103, Lith Harbor 104000000).
 *
 * Quest.wz/Check.img.xml block "1" declares an endscript for this quest, and 2234 is the last link of the chain:
 * its block "0" only lets it start once 2233 is COMPLETED. The condition - a total family Rep of at least 2,000 with
 * the current Rep spent down below 500 - is written only into the QuestInfo.img description; Check.img carries no
 * rep requirement node, so this script is the only plane the server can enforce it on.
 *
 * Quest.wz/Act.img.xml blocks "0" and "1" for 2234 are empty: this quest pays no exp, no meso and no items. That is
 * deliberate, and no reward may be added here without changing the WZ data as well.
 *
 * Both entry points run the same check: either one must be able to finish the quest.
 */
var REQUIRED_TOTAL_REP = 2000;
var MAX_CURRENT_REP = 500;

function checkAndComplete() {
    var familyEntry = qm.getPlayer().getFamilyEntry();
    if (familyEntry == null) {
        qm.sendNext("You don't have a Family yet. Captain Al's tasks are for Family members - join a Family, or ask him how to lead one.");
    } else {
        var total = familyEntry.getTotalReputation();
        var current = familyEntry.getReputation();
        if (total < REQUIRED_TOTAL_REP) {
            qm.sendNext("Not yet. Captain Al wants #r2,000#k Rep earned in total, and your Family has stacked #b" + total + "#k so far. Keep supporting your Juniors.");
        } else if (current >= MAX_CURRENT_REP) {
            qm.sendNext("Your total Rep is there, but you still hold #b" + current + "#k of it. This task is to actually enjoy the Entitlements - spend Rep in the Family window until it is under #r500#k.");
        } else {
            qm.forceCompleteQuest();
            qm.sendNext("Total Rep #b" + total + "#k earned and only #b" + current + "#k left unspent. That's the mission done - Captain Al will want to hear it.");
        }
    }
    qm.dispose();
}

function start(mode, type, selection) {
    checkAndComplete();
}

function end(mode, type, selection) {
    checkAndComplete();
}
