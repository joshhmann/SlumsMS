// Roof of the Fourth Tower -> the Fourth Tower. 1.6.1 b040: no rebirth gate (it could strand a player on the roof
// now that Lion King's Castle is open without rebirths).
function enter(pi) {
    pi.playPortalSound();
    pi.warp(211060800, "up00");
    return true;
}
