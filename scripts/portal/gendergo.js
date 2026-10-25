// Amoria PQ stage 1 has one room per gender, but the only way to clear the stage is to kill every mob on the map.
// A party of one gender (ticket 75: 2 to 6 friends, no marriage) could never reach the other room, so both doors are
// open to everyone now. The rooms and their portals keep their names.
function enter(pi) {
    var map = pi.getPlayer().getMap();

    pi.playPortalSound();
    if (pi.getPortal().getName() == "female00") {
        pi.warp(map.getId(), "female01");
    } else {
        pi.warp(map.getId(), "male01");
    }

    return true;
}
