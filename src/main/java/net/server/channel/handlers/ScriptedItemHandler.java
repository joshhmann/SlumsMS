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
import client.inventory.Item;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import scripting.item.ItemScriptManager;
import server.ItemInformationProvider;
import server.ItemInformationProvider.ScriptedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

/**
 * @author Jay Estrella
 */
public final class ScriptedItemHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(ScriptedItemHandler.class);

    @Override
    public final void handlePacket(InPacket p, Client c) {
        p.readInt(); // trash stamp, thanks RMZero213
        short itemSlot = p.readShort(); // item slot, thanks RMZero213
        int itemId = p.readInt();

        handle(c, itemSlot, itemId, ItemInformationProvider.getInstance().getScriptedItemInfo(itemId));
    }

    /**
     * The client locks itself after sending 0x4E and only a packet with the exclusive-request flag unlocks it, so
     * every exit sends enableActions (s-crystal); each use logs its outcome on one line.
     */
    static void handle(Client c, short itemSlot, int itemId, ScriptedItem info) {
        String outcome = "script ran";
        try {
            if (info == null) {
                outcome = "no item script in the item data";
                return;
            }
            Item item = c.getPlayer().getInventory(ItemConstants.getInventoryType(itemId)).getItem(itemSlot);
            if (item == null || item.getItemId() != itemId || item.getQuantity() < 1) {
                outcome = "slot " + itemSlot + " does not hold the item";
                return;
            }
            if (!ItemScriptManager.getInstance().runItemScript(c, info)) {
                outcome = "script " + info.getScript() + " (npc " + info.getNpc() + ") did not run: busy or missing/failed";
            }
        } catch (RuntimeException e) {
            outcome = "threw " + e;
            throw e;
        } finally {
            if (outcome.equals("script ran")) {
                log.debug("Scripted item {} slot {}: {}", itemId, itemSlot, outcome);
            } else {
                log.warn("Scripted item {} slot {}: {}", itemId, itemSlot, outcome);
            }
            c.sendPacket(PacketCreator.enableActions());
        }
    }
}
