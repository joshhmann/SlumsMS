package net.server.channel.handlers;

import server.maps.Portal;

/** Lets tests outside this package ask ChangeMapSpecialHandler's refusal rule. */
public final class ChangeMapSpecialRefusalAccess {

    private ChangeMapSpecialRefusalAccess() {
    }

    public static String refusal(Portal portal, boolean delay, boolean blocked, boolean changing, boolean banned) {
        return ChangeMapSpecialHandler.refusal(portal, delay, blocked, changing, banned);
    }
}
