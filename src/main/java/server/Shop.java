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
package server;

import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * @author Matze
 */
public class Shop {
    private static final Logger log = LoggerFactory.getLogger(Shop.class);
    private static final Set<Integer> rechargeableItems = new LinkedHashSet<>();

    /** What every NPC shop offers to recharge (test seam, 1.6.6 b-20). */
    static Set<Integer> rechargeables() {
        return java.util.Collections.unmodifiableSet(rechargeableItems);
    }

    private final int id;
    private final int npcId;
    private final List<ShopItem> items;
    private final int tokenvalue = 1000000000;
    private final int token = ItemId.GOLDEN_MAPLE_LEAF;

    static {
        for (int throwingStarId : ItemId.allThrowingStarIds()) {
            rechargeableItems.add(throwingStarId);
        }
        rechargeableItems.add(ItemId.BLAZE_CAPSULE);
        rechargeableItems.add(ItemId.GLAZE_CAPSULE);
        rechargeableItems.add(ItemId.BALANCED_FURY);
        rechargeableItems.remove(ItemId.DEVIL_RAIN_THROWING_STAR); // doesn't exist
        for (int bulletId : ItemId.allBulletIds()) {
            rechargeableItems.add(bulletId);
        }
    }

    private Shop(int id, int npcId) {
        this.id = id;
        this.npcId = npcId;
        items = new ArrayList<>();
    }

    private void addItem(ShopItem item) {
        items.add(item);
    }

    /** 1.5.0 rc3 (economy lane): a shop built in code, not from the shops table (the cosmetics merchant). */
    public static Shop custom(int id, int npcId, List<ShopItem> stock) {
        Shop shop = new Shop(id, npcId);
        for (ShopItem item : stock) {
            shop.addItem(item);
        }
        return shop;
    }

    public void sendShop(Client c) {
        c.getPlayer().setShop(this);
        c.sendPacket(PacketCreator.getNPCShop(c, getNpcId(), items));
    }

    public void buy(Client c, short slot, int itemId, short quantity) {
        ShopItem item = findBySlot(slot);
        if (item != null) {
            if (item.getItemId() != itemId) {
                log.warn("Wrong slot number in shop {}", id);
                return;
            }
        } else {
            return;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (item.getPrice() > 0) {
            // 1.6.4 b289: a rechargeable is granted as one whole slotMax stack, so what it costs is the same
            // WZ price/unitPrice expression that selling the stack pays out (getPrice, also used by sell and
            // recharge). Charging the counter price once for the stack minted mesos on every buy/sell pair.
            boolean rechargeable = ItemConstants.isRechargeable(itemId);
            if (rechargeable) {
                quantity = ii.getSlotMax(c.getPlayer(), itemId);
            }
            // 1.5.0 rc3 review: exact long math; a total over the most mesos a character holds is refused
            long total = rechargeable ? ii.getPrice(itemId, quantity) : ShopMath.total(item.getPrice(), quantity);
            if (total < 0 || quantity < 1) {
                c.sendPacket(PacketCreator.shopTransaction((byte) 2));
                return;
            }
            int amount = (int) total;
            if (c.getPlayer().getMeso() >= amount) {
                if (InventoryManipulator.checkSpace(c, itemId, quantity, "")) {
                    if (!rechargeable) { //Pets can't be bought from shops
                        InventoryManipulator.addById(c, itemId, quantity, "", -1);
                        c.getPlayer().gainMeso(-amount, false);
                        soloMapling.Economy.stats.EconomyCounters.recordNpcBuy(amount);
                        if (soloMapling.Economy.MesoSinks.isCosmeticNpc(npcId)) {   // 1.5.0 rc3: a meso sink
                            soloMapling.Economy.MesoSinks.onSinkPurchase(c.getPlayer(), itemId, quantity, amount);
                        }
                    } else {
                        InventoryManipulator.addById(c, itemId, quantity, "", -1);
                        c.getPlayer().gainMeso(-amount, false);
                        soloMapling.Economy.stats.EconomyCounters.recordNpcBuy(amount);
                    }
                    c.sendPacket(PacketCreator.shopTransaction((byte) 0));
                    server.reports.PlayerTrail.event(c.getPlayer(), "shop", itemId, "npc " + npcId); // ticket 131: @bug trail
                } else {
                    c.sendPacket(PacketCreator.shopTransaction((byte) 3));
                }

            } else {
                c.sendPacket(PacketCreator.shopTransaction((byte) 2));
            }

        } else if (item.getPitch() > 0) {
            long pitchTotal = ShopMath.total(item.getPitch(), quantity);
            if (pitchTotal < 0 || quantity < 1) {
                c.sendPacket(PacketCreator.shopTransaction((byte) 2));
                return;
            }
            int amount = (int) pitchTotal;

            if (c.getPlayer().getInventory(InventoryType.ETC).countById(ItemId.PERFECT_PITCH) >= amount) {
                if (InventoryManipulator.checkSpace(c, itemId, quantity, "")) {
                    if (!ItemConstants.isRechargeable(itemId)) {
                        InventoryManipulator.addById(c, itemId, quantity, "", -1);
                        InventoryManipulator.removeById(c, InventoryType.ETC, ItemId.PERFECT_PITCH, amount, false, false);
                    } else {
                        short slotMax = ii.getSlotMax(c.getPlayer(), item.getItemId());
                        quantity = slotMax;
                        InventoryManipulator.addById(c, itemId, quantity, "", -1);
                        InventoryManipulator.removeById(c, InventoryType.ETC, ItemId.PERFECT_PITCH, amount, false, false);
                    }
                    c.sendPacket(PacketCreator.shopTransaction((byte) 0));
                    server.reports.PlayerTrail.event(c.getPlayer(), "shop", itemId, "npc " + npcId); // ticket 131: @bug trail
                } else {
                    c.sendPacket(PacketCreator.shopTransaction((byte) 3));
                }
            }

        } else if (c.getPlayer().getInventory(InventoryType.CASH).countById(token) != 0) {
            int amount = c.getPlayer().getInventory(InventoryType.CASH).countById(token);
            long value = (long) amount * tokenvalue;
            long cost = (long) item.getPrice() * quantity;
            if (quantity < 1 || cost < 0) {
                c.sendPacket(PacketCreator.shopTransaction((byte) 2));
                return;
            }
            if (c.getPlayer().getMeso() + value >= cost) {
                long cardreduce = value - cost;
                int diff = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, cardreduce + c.getPlayer().getMeso()));
                if (InventoryManipulator.checkSpace(c, itemId, quantity, "")) {
                    if (ItemConstants.isPet(itemId)) {
                        int petid = Pet.createPet(itemId);
                        InventoryManipulator.addById(c.getPlayer(), itemId, quantity, "", petid, -1);
                    } else {
                        InventoryManipulator.addById(c.getPlayer(), itemId, quantity, "", -1, -1);
                    }
                    c.getPlayer().gainMeso(diff, false);
                } else {
                    c.sendPacket(PacketCreator.shopTransaction((byte) 3));
                }
                c.sendPacket(PacketCreator.shopTransaction((byte) 0));
            } else {
                c.sendPacket(PacketCreator.shopTransaction((byte) 2));
            }
        }
    }

    private static boolean canSell(Item item, short quantity) {
        if (item == null) { //Basic check
            return false;
        }

        short iQuant = item.getQuantity();
        if (iQuant == 0xFFFF) {
            iQuant = 1;
        } else if (iQuant < 0) {
            return false;
        }

        if (!ItemConstants.isRechargeable(item.getItemId())) {
            return iQuant != 0 && quantity <= iQuant;
        }

        return true;
    }

    private static short getSellingQuantity(Item item, short quantity) {
        if (ItemConstants.isRechargeable(item.getItemId())) {
            quantity = item.getQuantity();
            if (quantity == 0xFFFF) {
                quantity = 1;
            }
        }

        return quantity;
    }

    public void sell(Client c, InventoryType type, short slot, short quantity) {
        if (quantity == 0xFFFF || quantity == 0) {
            quantity = 1;
        } else if (quantity < 0) {
            return;
        }

        Item item = c.getPlayer().getInventory(type).getItem(slot);
        if (canSell(item, quantity)) {
            quantity = getSellingQuantity(item, quantity);
            InventoryManipulator.removeFromSlot(c, type, (byte) slot, quantity, false);
            soloMapling.Economy.exchange.volatility.MarketFlows.consumed(item.getItemId(), quantity); // 1.6.1 fair value: a shop sink

            int recvMesos = sellPayout(item.getItemId(), quantity);
            if (recvMesos > 0) {
                c.getPlayer().gainMeso(recvMesos, false);
                soloMapling.Economy.stats.EconomyCounters.recordNpcSell(recvMesos);
            }
            c.sendPacket(PacketCreator.shopTransaction((byte) 0x8));
        } else {
            c.sendPacket(PacketCreator.shopTransaction((byte) 0x5));
        }
    }

    /**
     * What a sale of {@code quantity} of an item pays. Casino chips pay their face value
     * ({@link soloMapling.Casino.CasinoChipConfig#sellValue}), not the stock chip WZ {@code info/price} (250/350/400/350
     * for the four stamps), so the chip shop's exchange stays lossless; everything else pays the WZ price as before.
     *
     * <p>This is the one answer to that question: both the NPC shop counter ({@link #sell}) and the quick-sell paths
     * ({@code Character.sellAllItemsFromPosition} / {@code sellAllPosLast}, i.e. quicksell.js and the donation box) go
     * through it. The {@code @sell} GM command ({@code client.commands.SellitemsCommand}) still reads the WZ price.
     */
    public static int sellPayout(int itemId, int quantity) {
        int face = soloMapling.Casino.CasinoChipConfig.sellValue(itemId, quantity);
        return face >= 0 ? face : ItemInformationProvider.getInstance().getPrice(itemId, quantity);
    }

    public void recharge(Client c, short slot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item item = c.getPlayer().getInventory(InventoryType.USE).getItem(slot);
        if (item == null || !ItemConstants.isRechargeable(item.getItemId())) {
            return;
        }
        short slotMax = ii.getSlotMax(c.getPlayer(), item.getItemId());
        if (item.getQuantity() < 0) {
            return;
        }
        if (item.getQuantity() < slotMax) {
            // 1.6.4 b289: the same WZ price/unitPrice expression as buying and selling a stack, so a refill
            // from empty costs exactly a fresh purchase and the refilled stack sells for what it cost.
            int price = ii.getRechargePrice(item.getItemId(), slotMax - item.getQuantity());
            if (price < 0) {    // no WZ price data for this item: there is no price to charge
                return;
            }
            if (c.getPlayer().getMeso() >= price) {
                item.setQuantity(slotMax);
                c.getPlayer().forceUpdateItem(item);
                c.getPlayer().gainMeso(-price, false, true, false);
                soloMapling.Economy.stats.EconomyCounters.recordNpcBuy(price);
                c.sendPacket(PacketCreator.shopTransaction((byte) 0x8));
            } else {
                c.sendPacket(PacketCreator.shopTransaction((byte) 0x2));
            }
        }
    }

    private ShopItem findBySlot(short slot) {
        return items.get(slot);
    }

    /** An NPC's own shop: never a GM shop (ShopFactory.isGmShop), and the one numbered after the NPC first. */
    static final String NPC_SHOP_QUERY = "SELECT * FROM shops WHERE npcid = ? AND shopid <> 1337"
            + " AND shopid NOT BETWEEN 9999992 AND 9999999 ORDER BY shopid = npcid DESC, shopid";

    static Shop forTest(int id, int npcId) {
        return new Shop(id, npcId);
    }

    public static Shop createFromDB(int id, boolean isShopId) {
        Shop ret = null;
        int shopId;
        try (Connection con = DatabaseConnection.getConnection()) {
            final String query;
            if (isShopId) {
                query = "SELECT * FROM shops WHERE shopid = ?";
            } else {
                query = NPC_SHOP_QUERY;
            }

            try (PreparedStatement ps = con.prepareStatement(query)) {
                ps.setInt(1, id);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        shopId = rs.getInt("shopid");
                        ret = new Shop(shopId, rs.getInt("npcid"));
                    } else {
                        return null;
                    }
                }
            }

            try (PreparedStatement ps = con.prepareStatement("SELECT itemid, price, pitch FROM shopitems WHERE shopid = ? ORDER BY position DESC")) {
                ps.setInt(1, shopId);

                try (ResultSet rs = ps.executeQuery()) {
                    List<Integer> recharges = new ArrayList<>(rechargeableItems);
                    while (rs.next()) {
                        if (ItemConstants.isRechargeable(rs.getInt("itemid"))) {
                            ShopItem starItem = new ShopItem((short) 1, rs.getInt("itemid"), rs.getInt("price"), rs.getInt("pitch"));
                            ret.addItem(starItem);
                            if (rechargeableItems.contains(starItem.getItemId())) {
                                recharges.remove(Integer.valueOf(starItem.getItemId()));
                            }
                        } else {
                            // 1.5.0 rc3: economy.yaml's sinks.shopPrices bumps an item's price in every NPC shop
                            ret.addItem(new ShopItem((short) 1000, rs.getInt("itemid"),
                                    soloMapling.Economy.MesoSinks.npcPrice(rs.getInt("itemid"), rs.getInt("price")),
                                    rs.getInt("pitch")));
                        }
                    }
                    for (Integer recharge : recharges) {
                        ret.addItem(new ShopItem((short) 1000, recharge, 0, 0));
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return ret;
    }

    /**
     * 1.6.4: every shop the database holds, shopid -> npcid, for the GM shop editor's pick list
     * (scripts/npc/ShopEditor.js). One query for the pairs, not a Shop apiece: the editor only lists them.
     */
    public static Map<Integer, Integer> loadAllShopIds() {
        Map<Integer, Integer> ret = new LinkedHashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT shopid, npcid FROM shops ORDER BY npcid, shopid");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ret.put(rs.getInt("shopid"), rs.getInt("npcid"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return ret;
    }

    public int getNpcId() {
        return npcId;
    }

    public int getId() {
        return id;
    }

    public List<ShopItem> getItems() {
        return items;
    }
}
