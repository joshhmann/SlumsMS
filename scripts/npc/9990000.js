/*
    Slums-chan (SlumsMS 1.6.1): NPC 9990000, a friendly guide in Base of Roots (993910000) and Henesys (100000000),
    placed by migration 042-slums-chan-npc. A short welcome, then the guide or the settings menu in the same window.
    The pages live in soloMapling.menus.SlumsChanMenu; this script only drives the window like slumsmenu.js.
*/
var MenuWindow = Java.type('soloMapling.menus.MenuWindow');
var SlumsChanMenu = Java.type('soloMapling.menus.SlumsChanMenu');

function show() {
    var text = MenuWindow.text(cm.getPlayer());
    if (text == null) {
        cm.dispose();
        return;
    }
    cm.sendSimple(text);
}

function start() {
    SlumsChanMenu.talk(cm.getPlayer());
    show();
}

function action(mode, type, selection) {
    if (mode == 1 && selection >= 0) {
        if (MenuWindow.select(cm.getPlayer(), selection)) {
            show();
            return;
        }
    } else {
        MenuWindow.closed(cm.getPlayer());
    }
    cm.dispose();
}
