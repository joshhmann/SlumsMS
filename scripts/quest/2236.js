/*
    Quest 2236 - How to Shoo Away the Evil (Sleepywood), end.
    SlumsMS 1.5.2: the server's Quest.wz flags this quest as script-ended (endscript q2236e), where stock
    Cosmic's data checks the quest progress instead (infoex "111111"). With no script the hand-in fell back
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

    if (qm.getQuestProgress(2236) != "111111") {   // checked on every step, not only the first
        qm.sendOk("There are still spots where the evil gathers. Place a #b#t4032263##k at each of the six of them, then come back.");
        qm.dispose();
        return;
    }

    if (status == 0) {
        qm.sendNext("You placed all six #t4032263# on the evil spots? Thank you, the forest feels calmer already.");
    } else {
        qm.gainExp(60000);
        qm.forceCompleteQuest();
        qm.dispose();
    }
}
