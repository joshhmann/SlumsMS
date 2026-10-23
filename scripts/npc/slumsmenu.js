/*
    SlumsMS 1.5.0: a Java-driven menu in the NPC window (@settings, !qa).
    The menus live in soloMapling.menus; this script only shows the page and hands each click back.
    A click either redraws the window with the next page or closes it.
*/
var MenuWindow = Java.type('soloMapling.menus.MenuWindow');

function show() {
    var text = MenuWindow.text(cm.getPlayer());
    if (text == null) {
        cm.dispose();
        return;
    }
    cm.sendSimple(text);
}

function start() {
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
