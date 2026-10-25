/*
    Quest 21728 - The Puppeteer's Cave (Sleepywood), end.
    SlumsMS 1.5.2: the server's Quest.wz flags this quest as script-ended (endscript q21728e), where stock
    Cosmic's data checks the quest progress instead (infoex "0"). With no script the hand-in fell back
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

    if (qm.getQuestProgress(21728, 21761) != "0") {   // checked on every step, not only the first
        qm.sendOk("Did you find the #bPuppeteer's Headquarters#k? Go up to the Evil Eye Cave and look for it.");
        qm.dispose();
        return;
    }

    if (status == 0) {
        qm.sendNext("You found the Puppeteer's cave! I knew that kid was up to no good.");
    } else {
        qm.gainExp(200);
        qm.forceCompleteQuest();
        qm.dispose();
    }
}
