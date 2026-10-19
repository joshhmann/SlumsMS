package net.server.channel.handlers;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.checkin.DailyCheckin;

/** CP 0x3732: the Daily Check-In window. {@code byte action} 0 = open, 1 = claim followed by {@code byte day}. */
public final class DailyCheckinHandler extends AbstractPacketHandler {

    private static final int REQ_OPEN = 0;
    private static final int REQ_CLAIM = 1;

    @Override
    public void handlePacket(InPacket p, Client c) {
        if (c.getPlayer() == null) {
            return;
        }
        int action = p.readByte();
        int day = action == REQ_CLAIM && p.available() > 0 ? p.readByte() : 0;
        if (action == REQ_OPEN || action == REQ_CLAIM) {
            DailyCheckin.handleRequest(c, day);
        }
    }
}
