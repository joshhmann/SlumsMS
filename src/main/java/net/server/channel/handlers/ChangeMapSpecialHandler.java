/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.Trade;
import server.Trade.TradeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.GenericPortal;
import server.maps.Portal;
import tools.PacketCreator;

public final class ChangeMapSpecialHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChangeMapSpecialHandler.class);
    @Override
    public final void handlePacket(InPacket p, Client c) {
        p.readByte();
        String startwp = p.readString();
        p.readShort();
        Portal portal = c.getPlayer().getMap().getPortal(startwp);
        String refused = refusal(portal, c.getPlayer().portalDelay() > currentServerTime(),
                portal != null && c.getPlayer().getBlockedPortals().contains(portal.getScriptName()),
                c.getPlayer().isChangingMaps(), c.getPlayer().isBanned());
        if (refused != null) {
            // 1.6.1: every refusal is logged (the Rose Garden walk-in did nothing and left no trace)
            log.info(GenericPortal.scriptLogLine(portal != null && portal.getScriptName() != null ? portal.getScriptName()
                    : "(portal " + startwp + ")", c.getPlayer().getName(), c.getPlayer().getMapId(), refused));
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (c.getPlayer().getTrade() != null) {
            Trade.cancelTrade(c.getPlayer(), TradeResult.UNSUCCESSFUL_ANOTHER_MAP);
        }
        portal.enterPortal(c);
    }

    /** Why the server drops this scripted-portal request before the script runs, or null. */
    static String refusal(Portal portal, boolean portalDelay, boolean blocked, boolean changingMaps, boolean banned) {
        if (portal == null) {
            return "refused: no such portal on this map";
        }
        if (portalDelay) {
            return "refused: portal delay";
        }
        if (blocked) {
            return "refused: portal blocked for this character";
        }
        if (changingMaps) {
            return "refused: already changing maps";
        }
        if (banned) {
            return "refused: banned";
        }
        return null;
    }
}
