/*
    Quest 3927 - The Existence of the Secret Organization (Ariant), end.
    SlumsMS 1.5.2: the server's Quest.wz flags this quest as script-ended (endscript q3927e), where stock
    Cosmic's data checks the quest progress instead (infoex "1"). With no script the hand-in fell back
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

    if (qm.getQuestProgress(3927) != "1") {   // checked on every step, not only the first
        qm.sendOk("Find #bthe wall that has something written about the Sand Bandits#k first, then come back to me.");
        qm.dispose();
        return;
    }

    if (status == 0) {
        qm.sendNext("So you found the wall... The #bSand Bandits#k are real. Keep that to yourself, all right?");
    } else {
        qm.forceCompleteQuest();
        qm.dispose();
    }
}
