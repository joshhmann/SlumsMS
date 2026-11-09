/*  1.6.8: named by two portals, neither of which had a script.
    Cygnus Garden 271040000 portal in00 (x 177): the door into the chamber. A walk-in must not start a fight -
    only the expedition (CygnusBattle.playerEntry) puts anyone inside the instance, so it turns the player away.
    Cygnus's Chamber 271040100 portal out00 (x -909): the way back out to the garden, at the garden's out00 point,
    the same spot the garden gate drops players on.
*/
function enter(pi) {
    if (pi.getMapId() == 271040100) {
        pi.playPortalSound();
        pi.warp(271040000, "out00");
        return true;
    }
    pi.getPlayer().dropMessage(5, "The way in is through the expedition leader.");
    return false;
}
