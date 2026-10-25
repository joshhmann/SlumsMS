/*
    Quest 3345 - The Black Magician's Magic Pentagram (El Nath), end.
    SlumsMS 1.5.2: the server's Quest.wz flags this quest as script-ended (endscript q3345e), where stock
    Cosmic's data checks the quest progress instead (infoex "4"). With no script the hand-in fell back
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

    if (qm.getQuestProgress(3345) != "4") {   // checked on every step, not only the first
        qm.sendOk("The Magic Pentagram is still unstable. Place the #b3 Magic Rocks#k in sequence in the Black Magician's Lab and press the center of the Pentagram.");
        qm.dispose();
        return;
    }

    if (status == 0) {
        qm.sendNext("The Magic Pentagram has calmed down. Magatia is safe, for now. Well done.");
    } else {
        qm.gainExp(2200);
        qm.forceCompleteQuest();
        qm.dispose();
    }
}
