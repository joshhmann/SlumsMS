// Temple of Time -> Arcane River (Vanishing Journey). Owner 2026-10-29: Arcane River opens at the 2nd rebirth, any level
// (was 3); the rule and its message live in server.maps.AreaGates, so the Hyper Rock, the teleporter and this agree.
function enter(pi) {
    const gate = Java.type('server.maps.AreaGates').denyReason(pi.getPlayer(), 450001003);

    if (gate == null) {
        pi.playPortalSound();
        pi.warp(450001003, 0);
        return true;
    }
    pi.playerMessage(5, gate);
    return false;
}
