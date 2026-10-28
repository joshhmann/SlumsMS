/*
    Lazy Penguin (Sunny), NPC 2988 (SlumsMS 1.6.1): decorative, stands in Perion; placed by migration 046-lazy-penguins.
    One lazy line and that's it.
*/
function start() {
    cm.sendOk("*yawns* I'd greet you properly, but standing up is a whole thing.");
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
