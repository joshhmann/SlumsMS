/*
 * Quest 2233 - "Raise the Rep!" - Captain Al (NPC 1002103, Lith Harbor 104000000).
 *
 * Quest.wz/Check.img.xml block "1" declares an endscript for this quest, so QuestScriptManager.end() lands here and
 * this file is the only thing that can complete it. The condition - a current family Rep of at least 1,000 - is
 * written only into the QuestInfo.img description ("Achieve 1,000 Rep"); Check.img carries no rep requirement node,
 * so the script is the only plane the server can enforce it on.
 *
 * Quest.wz/Act.img.xml blocks "0" and "1" for 2233 are empty: this quest pays no exp, no meso and no items. That is
 * deliberate, and no reward may be added here without changing the WZ data as well.
 *
 * Both entry points run the same check: a player can reach the quest window through start (2232 completed) or
 * through the NPC dialog's end path, and either must be able to finish the quest.
 */
var REQUIRED_REP = 1000;

function checkAndComplete() {
    var familyEntry = qm.getPlayer().getFamilyEntry();
    if (familyEntry == null) {
        qm.sendNext("You don't have a Family yet. Captain Al's tasks are for Family members - join a Family, or ask him how to lead one.");
    } else if (familyEntry.getReputation() < REQUIRED_REP) {
        qm.sendNext("Not yet. Your Family's Rep is #b" + familyEntry.getReputation() + "#k, and Captain Al asked for #r1,000#k. Help your Juniors level up by hunting with them and the Rep will follow.");
    } else {
        qm.forceCompleteQuest();
        qm.sendNext("Your Family's Rep reached #b" + familyEntry.getReputation() + "#k. That's the 1,000 Captain Al wanted - I should go tell him.");
    }
    qm.dispose();
}

function start(mode, type, selection) {
    checkAndComplete();
}

function end(mode, type, selection) {
    checkAndComplete();
}
