// Aerial Prison (211070101), the way back to the Von Leon fight. Its Prison Key 4032860 has no source anywhere (no
// drop row, no mob in the prison; BotStory and nutnnut have none either), and since 1.6.4 the Hyper Teleport Rock and
// @goto reach the prison, so a player looking for Von Leon could land here with no way on (live bug B-26, 9 Nov 2026).
// 1.6.7 (aerial-prison-dead-end): with the key, as before; without it, a character on or past "Defeat the Lion King"
// (3171) goes to the Audience Room corridor, where the recruiter starts the fight, and anyone else is let out to the
// tower roof with the way to Von Leon. Nobody stays stuck here.
function enter(pi) {
    if (pi.getPlayer().haveItem(4032860)) {
        pi.gainItem(4032860, -1);
        pi.playPortalSound();
        pi.warp(211070100, 0);
        return true;
    }
    if (pi.isQuestStarted(3171) || pi.isQuestCompleted(3171)) {
        pi.playerMessage(5, "The prison door opens onto the Audience Room corridor. Talk to the recruiter there to face Von Leon.");
        pi.playPortalSound();
        pi.warp(211070000, "west00");
        return true;
    }
    pi.playerMessage(5, "You slip out of the prison. To face Von Leon, finish the Knight's quests up to Defeat the Lion King; then Ifia on the Roof of the Fifth Tower can take you to the Audience Room corridor.");
    pi.playPortalSound();
    pi.warp(211060801, 0);
    return true;
}
