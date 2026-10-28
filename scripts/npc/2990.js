/*
    Lazy Penguin (Popcorn), NPC 2990 (SlumsMS 1.6.1): decorative, stands in Lith Harbor; placed by migration 046-lazy-penguins.
    One lazy line and that's it.
*/
function start() {
    cm.sendOk("I was gonna do something today. ...Tomorrow, maybe.");
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
