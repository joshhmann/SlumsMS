package net.server.channel.handlers;

import constants.id.MapId;

/** Lets tests outside this package ask HyperTPHandler's refusal rule (a plain hop from Henesys). */
public final class HyperTPRefusalTestAccess {

    private HyperTPRefusalTestAccess() {
    }

    public static String refusal(int toMapId, int reborns, int level) {
        return HyperTPHandler.refusal(100000000, false, true, toMapId, 0, MapId.NONE, reborns, level);
    }
}
