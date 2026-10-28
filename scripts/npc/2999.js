/*
    Lazy Penguin (Classic), NPC 2999 (SlumsMS 1.6.1): decorative, stands in New Leaf City; placed by migration 046-lazy-penguins.
    One lazy line and that's it.
*/
function start() {
    cm.sendOk("The classic lazy penguin. Nobody has ever been lazier. I checked. Then I took a nap.");
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
