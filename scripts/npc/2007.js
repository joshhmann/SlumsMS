/*
    NPC: Maple Administrator (2007), Maple Island (map 10000)
    Skip-the-tutorial NPC, also opened by the tutoChatNPC portal for accounts with a level 30 character.

    Stock Cosmic offers a warp to Lith Harbor (104000000). SlumsMS does not route new characters through Maple
    Island at all: every creator (BeginnerCreator, NoblesseCreator, LegendCreator) spawns them at the hub
    (MapId.MAPLEROOT_HUB, 993910000). So skipping the island sends the player where a new character starts, and
    every path ends with dispose() (MapleRoot shipped this file empty, which threw and left the dialog open).
*/

var HUB = 993910000;
var status;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
        return;
    }
    if (mode == 0) {
        cm.sendOk("Enjoy your trip.");
        cm.dispose();
        return;
    }

    status++;
    if (status == 0) {
        cm.sendYesNo("Would you like to skip the tutorials and head straight to #b#m" + HUB + "##k, where new adventurers start?");
    } else {
        cm.warp(HUB, 0);
        cm.dispose();
    }
}
