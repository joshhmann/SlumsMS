/*
    Lazy Penguin (Lemon), NPC 2987 (SlumsMS 1.6.1): decorative, stands in Ellinia; placed by migration 044-lazy-penguins.
    One lazy line and that's it.
*/
function start() {
    cm.sendOk("Zzz... huh? Oh, it's you. I was resting my eyes. Both of them.");
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
