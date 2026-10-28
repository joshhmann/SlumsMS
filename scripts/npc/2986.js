/*
    Lazy Penguin (Cherry), NPC 2986 (SlumsMS 1.6.1): decorative, stands in Henesys; placed by migration 046-lazy-penguins.
    One lazy line and that's it.
*/
function start() {
    cm.sendOk("Mmm... five more minutes. Maybe ten. Okay, twenty.");
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
