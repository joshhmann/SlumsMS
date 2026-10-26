/*
    Buy/Sell Board (SlumsMS 1.6.0, owner 2026-10-26): NPC 1092017 ("Anonymous Merchant", a merchant's stall with no
    name tag) at the Free Market Entrance (910000000), placed by migration 034-site-board-npc. Read-only: it shows the
    open posts from the website's board, newest first, a page at a time. The text comes from
    soloMapling.Economy.board.SiteBoard.npcPage; posting happens on the website.
*/
var SiteBoard = Java.type('soloMapling.Economy.board.SiteBoard');
var page = 0;
var current = null;

function show() {
    current = SiteBoard.npcPage(page);
    page = current.page();
    if (current.hasNext() || current.hasPrev()) {
        cm.sendSimple(current.text());
    } else {
        cm.sendOk(current.text());
    }
}

function start() {
    page = 0;
    show();
}

function action(mode, type, selection) {
    if (mode != 1 || current == null || !(current.hasNext() || current.hasPrev())) {
        cm.dispose();
        return;
    }
    if (selection == 0) {
        page++;
    } else if (selection == 1) {
        page--;
    } else {
        cm.dispose();
        return;
    }
    show();
}
