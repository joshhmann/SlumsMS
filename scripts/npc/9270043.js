/*
    SlumsMS 1.5.0 rc3 review: the Singapore (CBD) Gachapon machine, NPC 9270043.

    It used to take a Remote Gachapon Ticket (5451000) and then call doGachapon(), which plays a Gachapon Ticket
    (5220000) at a pool this NPC did not have: the remote ticket was eaten and nothing was won. It is now the town
    machine of gachapon.js: one Gachapon Ticket (5220000) = one roll of the Singapore pool in
    soloMapling/Gachapon/gachapon-pools.yaml, and the ticket is taken only after the prize fits (GachaMachine).
    A Remote Gachapon Ticket is used from the item (gachaponRemote.js), never here.
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
