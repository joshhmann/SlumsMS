/*
    Lazy Penguin (Christmas), NPC 2985 (SlumsMS 1.6.1): decorative, stands in Happyville; placed by migration 046-lazy-penguins.
    One lazy line and that's it.
*/
function start() {
    cm.sendOk("*yawns* ...it's cold and I'm comfy. Come back after the holidays.");
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
