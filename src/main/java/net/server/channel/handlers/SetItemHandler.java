package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.setitem.SetItems;

/**
 * CP {@code SET_ITEM_ACTION} 0x3726 (ticket 18, SetEquip addon): {@code byte action} 1 = send the set catalog,
 * 2 = send my worn pieces and set bonus. The client asks for the catalog once after login and polls the pieces;
 * equipping or taking off gear also pushes them (see {@link Character#equipChanged}).
 */
public final class SetItemHandler extends AbstractPacketHandler {

    private static final int REQ_CATALOG = 1;
    private static final int REQ_PIECES = 2;

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        if (chr == null || p.available() < 1) {
            return;
        }
        switch (p.readByte()) {
            case REQ_CATALOG -> SetItems.sendCatalog(chr);
            case REQ_PIECES -> SetItems.sendPieces(chr);
            default -> {
            }
        }
    }
}
