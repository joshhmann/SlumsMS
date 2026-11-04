
var status;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        im.dispose();
    } else {
        if (mode == 0 && type > 0) {
            im.dispose();
            return;
        }
        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        if (status == 0) {
            // 1.6.4: the map is enough. The old 310 px check against portal east00 read the server-side position, which is
            // stale after a warp or channel change until the player moves, so the crystal silently did nothing.
            if (im.getMapId() == 211060400) {
                im.gainItem(2430159, -1);
                im.completeQuest(3182);
            }
            im.dispose();
        }
    }
}
