/*
    Lazy Penguin (Clown), NPC 2995 (SlumsMS 1.6.1): decorative, stands in Aquarium; placed by migration 044-lazy-penguins.
    One lazy line and that's it.
*/
function start() {
    cm.sendOk("Time is just a suggestion. Naps are forever.");
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
