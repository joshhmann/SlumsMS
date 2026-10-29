// The Gate of Time (Temple of Time -> the Future). Ticket 81 round 3: level 120 or 2 rebirths (server.maps.AreaGates).
function enter(pi) {
    const player = pi.getPlayer();
    const gate = Java.type('server.maps.AreaGates').denyReason(player, 271000000);

    if (gate == null) {
        pi.playPortalSound();
        pi.warp(271000000, 0);
        return true;
    }
    pi.playerMessage(5, gate);
    return false;
}
