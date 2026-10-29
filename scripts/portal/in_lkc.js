// Desolate Moor -> Lion King's Castle. 1.6.1 b040: the castle is open training for lv 117-134 (the player notes), so
// the old 1 rebirth gate is gone; the towers past Walls 1 keep their key items / quests, and Von Leon's wing keeps
// its own entry.
function enter(pi) {
    pi.playPortalSound();
    pi.warp(211060010, "west00");
    return true;
}
