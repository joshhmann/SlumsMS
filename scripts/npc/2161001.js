// Ifia, on the Roof of the Fifth Tower (211061001). During the Von Leon quests (3173-3176) she sends you to the
// Audience Room corridor, as before. 1.6.1 (owner 2026-10-29): she also walks you into the Rose Garden, the same warp
// as the roof's next00 portal (pt_rosegarden.js), because pressing Up on that portal did nothing in a real client.
var status = 0;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
        return;
    }
    if (mode == 0 && status == 0) {
        cm.dispose();
        return;
    }
    if (mode == 1) {
        status++;
    } else {
        status--;
    }

    if (status == 0) {
        if (cm.isQuestStarted(3173)
            || cm.isQuestStarted(3174)
            || cm.isQuestStarted(3175)
            || cm.isQuestStarted(3176)) {
            cm.warp(211070000, "west00");
            cm.dispose();
            return;
        }
        cm.sendSimple("Please help me...\r\n#b#L0#Take me to the Rose Garden.#l");
    } else if (status == 1) {
        if (selection == 0) {
            cm.warp(211080000, "west00");
        }
        cm.dispose();
    } else {
        cm.dispose();
    }
}
