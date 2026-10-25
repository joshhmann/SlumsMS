/*
    Quest 3114 - Eliza's Eternal Sleep (Orbis), end.
    SlumsMS 1.5.2: the server's Quest.wz flags this quest as script-ended (endscript q3114e), where stock
    Cosmic's data checks the quest progress instead (infoex "42"). With no script the hand-in fell back
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

    if (qm.getQuestProgress(3114) != "42") {   // checked on every step, not only the first
        qm.sendOk("I still can't fall asleep... Please play the #binstrument#k for me, the whole melody, just as I taught you.");
        qm.dispose();
        return;
    }

    if (status == 0) {
        qm.sendNext("Mmm... that melody... I can finally rest until my goddess is free. Thank you...");
    } else {
        qm.gainFame(20);
        qm.forceCompleteQuest();
        qm.dispose();
    }
}
