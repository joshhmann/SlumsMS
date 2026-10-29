// The Gate of Time (Temple of Time, portal1). Ticket 81 round 3: the Future opens at level 120 (or the 1st rebirth since 2026-10-29).
// 1.6.1 b040: the player notes say the Gate of Time also reaches lower Grandis, so the portal now asks where to go
// (scripts/npc/gateOfTime.js); both destinations are checked by server.maps.AreaGates there.
function enter(pi) {
    pi.openNpc(2140000, "gateOfTime");
    return false;
}
