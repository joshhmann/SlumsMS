function enter(pi) {
    if (pi.isQuestStarted(31149)) {
	    pi.forceCompleteQuest(31149);
	    pi.getPlayer().dropMessage(5, "I found the Cygnus Garden, I should report back to Chief Alex.");
	    return true;
    }
    if (pi.haveItem(4032923)) {
       pi.playPortalSound();
       pi.warp(271040000, "out00");
       pi.gainItem(4032923, -1);
       return true;
    }
    // 1.6.8: the Dream Key has no source in game (its Thunder Stone 2430200 drops nowhere), so a player who
    // already holds the four stones the key is made of may pay them here instead. Nothing is taken unless all
    // four stacks are there.
    if (pi.haveItem(4000660, 5) && pi.haveItem(4000661, 5) && pi.haveItem(4000662, 5) && pi.haveItem(4000663, 5)) {
       pi.playPortalSound();
       pi.gainItem(4000660, -5);
       pi.gainItem(4000661, -5);
       pi.gainItem(4000662, -5);
       pi.gainItem(4000663, -5);
       pi.warp(271040000, "out00");
       pi.getPlayer().dropMessage(5, "The gap in the vines took 5 Dawn, 5 Blaze, 5 Wind and 5 Night stones from you.");
       return true;
    }
    pi.getPlayer().dropMessage(5, "I need a Dream Key, or 5 of each stone, in order to enter.");
    return false;
}
