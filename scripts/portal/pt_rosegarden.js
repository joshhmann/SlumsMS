// Roof of the Fifth Tower (211061001, portal next00) -> the Rose Garden. The WZ portal named this script but it was
// missing, so the walk in did nothing (owner 2026-10-29). The Rose Garden is open lv 128-134 training: no gate here.
function enter(pi) {
    pi.playPortalSound();
    pi.warp(211080000, "west00");
    return true;
}
