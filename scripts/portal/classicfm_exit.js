function enter(pi) {
    var destination = pi.getPlayer().peekSavedLocation("CLASSIC_FREE_MARKET");
    if (destination < 0 || (destination >= 910000000 && destination <= 910000022)) destination = 993910000;
    pi.playPortalSound();
    pi.warp(destination, pi.getMarketPortalId(destination));
    pi.getPlayer().getSavedLocation("CLASSIC_FREE_MARKET");
    return true;
}
