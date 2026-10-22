/*
    SlumsMS 1.5.0: a Remote Gachapon Ticket (5451000) used from the item. RemoteGachaponHandler starts this as the
    chosen town's machine NPC; one ticket = one roll of that town's pool. The ticket is taken only when the prize fits.
*/
var REMOTE_TICKET = 5451000;

function start() {
    cm.doGachapon(REMOTE_TICKET);
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
