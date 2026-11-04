/*
    Holiday PQ exit portal inside the snowman fields (889100001 / 011 / 021): walks the party to the
    clear/exit map one map up (889100002 / 012 / 022), the same map HolidayPQ_1..3 use as clearMap/exitMap.
*/

function enter(pi) {
    pi.playPortalSound();
    pi.warp(pi.getMapId() + 1, 0);
    return true;
}
