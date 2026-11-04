// Murt, a ghost in the Second Tower of the Lion King's Castle (211060400). His quests (3181 Murt's Letter, 3182
// Alcaster's Crystal) are started and finished from the quest window; this is only his talk when none is open.
function start() {
    cm.sendOk("The Lion King's curse has left me a ghost. But if you bring me the treasure I seek, I will give you something helpful...");
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
