var fid = "glpq_s";

function action(eim) {
    rm.mapMessage(6, "All stirges have disappeared.");
    rm.getMap().killAllMonsters();
    eim.setIntProperty(fid, 777);
}

function touch() {
    var eim = rm.getEventInstance();

    if (eim.getIntProperty(fid) == 5) {
        action(eim);
    }
    eim.setIntProperty(fid, eim.getIntProperty(fid) + 1);
}

function untouch() {
    var eim = rm.getEventInstance();

    if (eim.getIntProperty(fid) == 5) {
        action(eim);
    }
    eim.setIntProperty(fid, eim.getIntProperty(fid) - 1);
}