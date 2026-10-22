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
import client.autoban.AutobanFactory;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import scripting.npc.NPCScriptManager;

/**
 * @author Generic
 */
public final class RemoteGachaponHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        int ticket = p.readInt();
        int gacha = p.readInt();
        if (ticket != ItemId.REMOTE_GACHAPON_TICKET) {
            AutobanFactory.GENERAL.alert(c.getPlayer(), " Tried to use RemoteGachaponHandler with item id: " + ticket);
            c.disconnect(false, false);
            return;
        } else if (gacha < 0 || gacha > 11) {
            AutobanFactory.GENERAL.alert(c.getPlayer(), " Tried to use RemoteGachaponHandler with mode: " + gacha);
            c.disconnect(false, false);
            return;
        } else if (c.getPlayer().getInventory(ItemConstants.getInventoryType(ticket)).countById(ticket) < 1) {
            AutobanFactory.GENERAL.alert(c.getPlayer(), " Tried to use RemoteGachaponHandler without a ticket.");
            c.disconnect(false, false);
            return;
        }
        // 1.5.0: the selection names a pool town (server.gachapon.Gachapon.REMOTE_ORDER); the script runs as its NPC
        soloMapling.Gachapon.GachaPools.Town town = server.gachapon.Gachapon.remote(gacha);
        if (town == null) {
            c.getPlayer().dropMessage(1, "That Gachapon is not available.");
            c.sendPacket(tools.PacketCreator.enableActions());
            return;
        }
        int npcId = town.npcId();
        NPCScriptManager.getInstance().start(c, npcId, "gachaponRemote", null);
    }
}
