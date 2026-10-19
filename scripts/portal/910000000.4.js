function enter(pi) {
    const player = pi.getPlayer();

    if (player.gmLevel() >= 2 || player.getReborns() >= 10) {
        pi.playPortalSound();
        pi.warp(993910008, 0);
        return true;
    }
    pi.playerMessage(5, "You need at least 10 rebirths to enter Dkingdom.");
    return false;
}