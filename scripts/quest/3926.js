/*
    Quest 3926 - Screwing the Red Scorpions (Ariant), end.
    SlumsMS 1.5.2: the server's Quest.wz flags this quest as script-ended (endscript q3926e), where stock
    Cosmic's data checks the quest progress instead (infoex "3333"). With no script the hand-in fell back
    to medalQuest.js, which completed the quest with no reward and a "<> is not coded" message. This does
    what stock Cosmic did: the same progress check and the reward from Act.img.
*/
var status = -1;

function end(mode, type, selection) {
    status++;
    if (mode != 1) {
        qm.dispose();
        return;
    }

    if (qm.getQuestProgress(3926) != "3333") {   // checked on every step, not only the first
        qm.sendOk("There are still houses that didn't get their share. Leave a #b#t4031579##k at each of the four houses.");
        qm.dispose();
        return;
    }

    if (status == 0) {
        qm.sendNext("You left the treasures at all four houses? Hehe, the people of Ariant will be glad. Nice work.");
    } else {
        qm.gainExp(6500);
        qm.forceCompleteQuest();
        qm.dispose();
    }
}
