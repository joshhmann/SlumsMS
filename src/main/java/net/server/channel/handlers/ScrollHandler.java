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

import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.Equip.ScrollResult;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ModifyInventory;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.ItemInformationProvider;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Matze
 * @author Frz
 */
public final class ScrollHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {
        if (c.tryacquireClient()) {
            try {
                p.readInt(); // whatever...
                short scrollSlot = p.readShort();
                short equipSlot = p.readShort();
                byte ws = (byte) p.readShort();
                boolean whiteScroll = false; // white scroll being used?
                boolean legendarySpirit = false; // legendary spirit skill
                if ((ws & 2) == 2) {
                    whiteScroll = true;
                }

                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                Character chr = c.getPlayer();
                Equip toScroll = (Equip) chr.getInventory(InventoryType.EQUIPPED).getItem(equipSlot);
                Skill LegendarySpirit = SkillFactory.getSkill(1003);
                if (chr.getSkillLevel(LegendarySpirit) > 0 && equipSlot >= 0) {
                    legendarySpirit = true;
                    toScroll = (Equip) chr.getInventory(InventoryType.EQUIP).getItem(equipSlot);
                }
                // SlumsMS 1.4.0: the MapleRoot repack shipped this handler with its body removed (scrolling did nothing);
                // restored from Cosmic. A stale or forged slot must not throw: refuse it like any other bad scroll.
                if (toScroll == null || useInventoryItemMissing(chr, scrollSlot)) {
                    announceCannotScroll(c, legendarySpirit);
                    return;
                }
                byte oldLevel = toScroll.getLevel();
                byte oldSlots = toScroll.getUpgradeSlots();
                Inventory useInventory = chr.getInventory(InventoryType.USE);
                Item scroll = useInventory.getItem(scrollSlot);
                Item wscroll = null;

                if (ItemConstants.isCleanSlate(scroll.getItemId()) && !ii.canUseCleanSlate(toScroll)) {
                    announceCannotScroll(c, legendarySpirit);
                    return;
                } else if (!ItemConstants.isModifierScroll(scroll.getItemId()) && toScroll.getUpgradeSlots() < 1) {
                    announceCannotScroll(c, legendarySpirit);   // thanks onechord for noticing zero upgrade slots freezing Legendary Scroll UI
                    return;
                }

                List<Integer> scrollReqs = ii.getScrollReqs(scroll.getItemId());
                if (!scrollReqs.isEmpty()) {
                    if (!scrollReqs.contains(toScroll.getItemId())) {
                        announceCannotScroll(c, legendarySpirit);
                        return;
                    }
                } else if (!ItemConstants.isChaosScroll(scroll.getItemId()) && !ItemConstants.isCleanSlate(scroll.getItemId())) {
                    if (!canScroll(scroll.getItemId(), toScroll.getItemId())) {
                        announceCannotScroll(c, legendarySpirit);
                        return;
                    }
                }
                if (whiteScroll) {
                    wscroll = useInventory.findById(ItemId.WHITE_SCROLL);
                    if (wscroll == null || wscroll.getQuantity() < 1) {
                        whiteScroll = false;
                        wscroll = null;
                    }
                }

                useInventory.lockInventory();
                try {
                    if (scroll.getQuantity() < 1) {
                        announceCannotScroll(c, legendarySpirit);
                        return;
                    }

                    if (whiteScroll && !ItemConstants.isCleanSlate(scroll.getItemId())) {
                        if (wscroll == null || wscroll.getQuantity() < 1) {
                            announceCannotScroll(c, legendarySpirit);
                            return;
                        }

                        InventoryManipulator.removeFromSlot(c, InventoryType.USE, wscroll.getPosition(), (short) 1, false, false);
                    }

                    InventoryManipulator.removeFromSlot(c, InventoryType.USE, scroll.getPosition(), (short) 1, false);
                } finally {
                    useInventory.unlockInventory();
                }

                Equip scrolled = (Equip) ii.scrollEquipWithId(toScroll, scroll.getItemId(), whiteScroll, 0, chr.isGM());
                ScrollResult scrollSuccess = Equip.ScrollResult.FAIL; // fail
                if (scrolled == null) {
                    scrollSuccess = Equip.ScrollResult.CURSE;
                } else if (scrolled.getLevel() > oldLevel || (ItemConstants.isCleanSlate(scroll.getItemId()) && scrolled.getUpgradeSlots() == oldSlots + 1) || ItemConstants.isFlagModifier(scroll.getItemId(), scrolled.getFlag())) {
                    scrollSuccess = Equip.ScrollResult.SUCCESS;
                }

                final List<ModifyInventory> mods = new ArrayList<>();
                if (scrollSuccess == Equip.ScrollResult.CURSE) {
                    if (!ItemId.isWeddingRing(toScroll.getItemId())) {
                        mods.add(new ModifyInventory(3, toScroll));
                        if (equipSlot < 0) {
                            Inventory inv = chr.getInventory(InventoryType.EQUIPPED);

                            inv.lockInventory();
                            try {
                                chr.unequippedItem(toScroll);
                                inv.removeItem(toScroll.getPosition());
                            } finally {
                                inv.unlockInventory();
                            }
                        } else {
                            Inventory inv = chr.getInventory(InventoryType.EQUIP);

                            inv.lockInventory();
                            try {
                                inv.removeItem(toScroll.getPosition());
                            } finally {
                                inv.unlockInventory();
                            }
                        }
                    } else {
                        scrolled = toScroll;
                        scrollSuccess = Equip.ScrollResult.FAIL;

                        mods.add(new ModifyInventory(3, scrolled));
                        mods.add(new ModifyInventory(0, scrolled));
                    }
                } else {
                    mods.add(new ModifyInventory(3, scrolled));
                    mods.add(new ModifyInventory(0, scrolled));
                }
                c.sendPacket(PacketCreator.modifyInventory(true, mods));
                chr.getMap().broadcastMessage(PacketCreator.getScrollEffect(chr.getId(), scrollSuccess, legendarySpirit, whiteScroll));
                if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.SCROLL)) { // !qa gn-2
                    soloMapling.qa.QaLog.owner(soloMapling.qa.QaLog.Topic.SCROLL, chr.getId(), "scroll " + scroll.getItemId()
                            + " on " + toScroll.getItemId() + ": " + scrollSuccess + (scrolled == null ? ""
                            : ", watk " + scrolled.getWatk() + ", matk " + scrolled.getMatk() + ", slots left "
                            + scrolled.getUpgradeSlots() + ", level " + scrolled.getLevel()));
                }
                if (scrollSuccess == Equip.ScrollResult.CURSE) {
                    int boomed = toScroll.getItemId();
                    soloMapling.server.MapleRootSolo.runHook("beef boom", () -> // 1.6.0 S7: a bot nearby may tease
                            soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.onPlayerBoom(chr, boomed));
                }
                if (equipSlot < 0 && (scrollSuccess == Equip.ScrollResult.SUCCESS || scrollSuccess == Equip.ScrollResult.CURSE)) {
                    chr.equipChanged();
                }
            } finally {
                c.releaseClient();
            }
        }
    }

    private static boolean useInventoryItemMissing(Character chr, short scrollSlot) {
        Item scroll = chr.getInventory(InventoryType.USE).getItem(scrollSlot);
        return scroll == null || scroll.getItemId() / 10000 != 204 || scroll.getQuantity() < 1;
    }

    private static void announceCannotScroll(Client c, boolean legendarySpirit) {
        if (legendarySpirit) {
            c.sendPacket(PacketCreator.getScrollEffect(c.getPlayer().getId(), Equip.ScrollResult.FAIL, false, false));
        } else {
            c.sendPacket(PacketCreator.getInventoryFull());
        }
    }

    /** Whether a scroll's category matches the equip's. Public for the bots' scrolling (ticket 11): same rules. */
    public static boolean canScroll(int scrollid, int itemid) {
        int sid = scrollid / 100;

        switch (sid) {
            case 20492: //scroll for accessory (pendant, belt, ring)
                return canScroll(ItemId.RING_STR_100_SCROLL, itemid) || canScroll(ItemId.DRAGON_STONE_SCROLL, itemid) ||
                        canScroll(ItemId.BELT_STR_100_SCROLL, itemid);

            default:
                return (scrollid / 100) % 100 == (itemid / 10000) % 100;
        }
    }
}
