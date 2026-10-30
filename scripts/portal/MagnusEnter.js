// Magnus's entrance: 3 rebirths and quest 31166. 1.6.2 b090: a character short of rebirths was told to finish the quest;
// each block now gets its own message (owner-approved wording for the rebirths).
function enter(pi) {
    const player = pi.getPlayer();
    if (player.getReborns() < 3) {
        pi.playerMessage(5, "You need 3 rebirths");
        return false;
    }
    if (!pi.isQuestCompleted(31166)) {
        pi.playerMessage(5, "You need to complete New Beginnings quest first!.");
        return false;
    }
    pi.playPortalSound();
    pi.warp(401053002);
    return true;
}
