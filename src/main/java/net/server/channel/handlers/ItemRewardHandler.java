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
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import server.ItemInformationProvider;
import server.ItemInformationProvider.RewardItem;
import tools.PacketCreator;
import tools.Pair;
import tools.Randomizer;

import java.util.List;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * @author Jay Estrella
 * @author kevintjuh93
 */
public final class ItemRewardHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        byte slot = (byte) p.readShort();
        int itemId = p.readInt(); // will load from xml I don't care.

        Item it = c.getPlayer().getInventory(InventoryType.USE).getItem(slot);   // null check here thanks to Thora
        if (it == null || it.getItemId() != itemId || c.getPlayer().getInventory(InventoryType.USE).countById(itemId) < 1) {
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Pair<Integer, List<RewardItem>> rewards = ii.getItemReward(itemId);
        for (RewardItem reward : rewards.getRight()) {
            if (!InventoryManipulator.checkSpace(c, reward.itemid, reward.quantity, "")) {
                c.sendPacket(PacketCreator.getShowInventoryFull());
                break;
            }
            if (Randomizer.nextInt(rewards.getLeft()) < reward.prob) {//Is it even possible to get an item with prob 1?
                if (ItemConstants.getInventoryType(reward.itemid) == InventoryType.EQUIP) {
                    final Item item = ii.getEquipById(reward.itemid);
                    long expiration = rewardExpiration(currentServerTime(), reward.period);
                    if (expiration != -1) {
                        item.setExpiration(expiration);
                    }
                    InventoryManipulator.addFromDrop(c, item, false);
                } else {
                    InventoryManipulator.addById(c, reward.itemid, reward.quantity, "", -1);
                }
                InventoryManipulator.removeById(c, InventoryType.USE, itemId, 1, false, false);
                if (reward.worldmsg != null) {
                    String msg = rewardMessage(reward.worldmsg, c.getPlayer().getName(), ii.getName(reward.itemid));
                    Server.getInstance().broadcastMessage(c.getWorld(), PacketCreator.serverNotice(6, msg));
                }
                break;
            }
        }
        c.sendPacket(PacketCreator.enableActions());
    }

    /**
     * Expiration stamp (epoch millis) for an equip granted by a reward box, or -1 when the reward entry carries no
     * {@code period} node (WZ default -1).
     * <p>
     * The WZ {@code reward/period} unit is HOURS. The one entry in the shipped client data that this method can
     * actually reach is {@code Item.wz/Consume/0202.img.xml} items 02022503-02022524/02022550: their reward 0 grants
     * equip 1132010 with period 7200, i.e. 300 days. 1132010 / 1000000 = 1, so it is an EQUIP and the gate above is
     * what sends it here. Unreachable, but corroborating the same "whole days times 24" shape, is
     * {@code Item.wz/Cash/0553.img.xml} item 05530000 (4170012, period 21600): 4170012 / 1000000 = 4, an ETC item,
     * so it takes the addById branch and its period is never consulted - it is not evidence for this method's unit,
     * only for how the field is written. Reading the field as days would make the pig's egg cape expire in 7200 days
     * (about 19.7 years), which is indistinguishable from permanent and would make the number meaningless.
     */
    static long rewardExpiration(long now, int period) {
        if (period == -1) {
            return -1;
        }
        return now + HOURS.toMillis(period);
    }

    /**
     * Substitutes the two tokens the WZ {@code reward/worldMsg} template uses: {@code /name} becomes the winner's name
     * and {@code /item} the granted item's name. {@link String#replace} is literal on purpose - the template is plain
     * WZ text, and a player name is player-supplied data that must never be read as a regular expression.
     */
    static String rewardMessage(String template, String playerName, String itemName) {
        return template.replace("/name", playerName).replace("/item", itemName);
    }
}
