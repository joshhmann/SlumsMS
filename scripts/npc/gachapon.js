/*
    SlumsMS 1.5.0: the town Gachapon machines (NPC 9100100-9100117).

    One Gachapon Ticket (5220000) = one roll of this town's pool (soloMapling/Gachapon/gachapon-pools.yaml).
    The ticket is taken only when the prize fits. Rare and jackpot wins get a server-wide notice.
    Until 1.5.0 this script was MapleRoot's donor-point chair machine; its chairs are in the shared pool now.
*/
var status;
var TICKET = 5220000;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1 || (mode == 0 && status <= 0)) {
        cm.dispose();
        return;
    }
    status++;
    if (status == 0) {
        var text = "Welcome to the #b" + cm.gachaponTownName() + " Gachapon#k!\r\n\r\n";
        if (cm.haveItem(TICKET)) {
            text += "#L0#Use a #t" + TICKET + "##l\r\n";
        }
        text += "#L1#What can I win, and how likely is it?#l\r\n#L2#Where do I get tickets?#l";
        cm.sendSimple(text);
    } else if (status == 1) {
        if (selection == 0) {
            cm.doGachapon(TICKET);
            cm.dispose();
        } else if (selection == 1) {
            cm.sendOk("Every ticket gives one prize from this town's machine:\r\n\r\n#b" + cm.gachaponOdds()
                + "#k\r\nRare and jackpot prizes are announced to the whole server. Type #r@gacha " + cm.gachaponTownName()
                + "#k to see every prize.");
            cm.dispose();
        } else {
            cm.sendOk("#bGachapon Tickets#k are sold in the #rCash Shop#k, and the daily check-in gives some too. A #bRemote Gachapon Ticket#k plays any town's machine from wherever you are.");
            cm.dispose();
        }
    } else {
        cm.dispose();
    }
}
