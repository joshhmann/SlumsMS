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
package server.maps;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.inventory.manipulator.InventoryManipulator;
import client.inventory.manipulator.KarmaManipulator;
import client.processor.npc.FredrickProcessor;
import config.YamlConfig;
import net.packet.Packet;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.Trade;
import server.transactions.JdbcJournalStore;
import server.transactions.JournalStore;
import server.transactions.OperationJournal;
import soloMapling.ArtificialPlayer.BotHoldings;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author XoticStory
 * @author Ronan - concurrency protection
 */
public class HiredMerchant extends AbstractMapObject {
    private static final Logger log = LoggerFactory.getLogger(HiredMerchant.class);
    private static final int VISITOR_HISTORY_LIMIT = 10;
    private static final int BLACKLIST_LIMIT = 20;
    protected int ownerId;
    protected int ownerAccountId;
    private final int itemId;
    private final int mesos = 0;
    private final int channel;
    private final int world;
    private final long start;
    protected String ownerName = "";
    private String description = "";
    private final List<PlayerShopItem> items = new LinkedList<>();
    private final List<Pair<String, Byte>> messages = new LinkedList<>();
    private final List<SoldItem> sold = new LinkedList<>();
    private final AtomicBoolean open = new AtomicBoolean();
    private boolean published = false;
    private volatile boolean closePersisted;  // P0-2: a close already committed (hand-over or Fredrick), so forceClose must not save again
    private JournalStore journal = new JdbcJournalStore();
    private boolean forceClosed;    // 1.5.2: forceClose runs once; a second run would save an empty list over Fredrick's items
    private MapleMap map;
    private final Visitor[] visitors = new Visitor[3];
    private final LinkedList<PastVisitor> visitorHistory = new LinkedList<>();
    private final LinkedHashSet<String> blacklist = new LinkedHashSet<>(); // case-sensitive character names
    private final Lock visitorLock = new ReentrantLock(true);

    private record Visitor(Character chr, Instant enteredAt) {}

    public record PastVisitor(String chrName, Duration visitDuration) {}

    public HiredMerchant(final Character owner, String desc, int itemId) {
        this.setPosition(owner.getPosition());
        this.start = System.currentTimeMillis();
        this.ownerId = owner.getId();
        this.ownerAccountId = owner.getAccountID();
        this.channel = owner.getClient().getChannel();
        this.world = owner.getWorld();
        this.itemId = itemId;
        this.ownerName = owner.getName();
        this.description = desc;
        this.map = owner.getMap();
    }

    /** The owner's side of a sale for the price board: its account, and its address when it is online. */
    private soloMapling.Economy.PriceBook.Party ownerParty() {
        try {
            Character owner = Server.getInstance().getWorld(world).getPlayerStorage().getCharacterById(ownerId);
            if (owner != null) {
                return soloMapling.Economy.PriceBook.Party.of(owner);
            }
        } catch (RuntimeException e) {
            // no world here: the account alone
        }
        return new soloMapling.Economy.PriceBook.Party(ownerAccountId, null, null);
    }

    public void broadcastToVisitorsThreadsafe(Packet packet) {
        visitorLock.lock();
        try {
            broadcastToVisitors(packet);
        } finally {
            visitorLock.unlock();
        }
    }

    /**
     * 1.5.2: a merchant can outlive its map (the channel disposed it at shutdown, or a close already ran and nulled it).
     * There is nobody on that map to tell, and the throw used to abort the caller halfway through a close.
     */
    private void broadcastIfOnMap(Packet packet) {
        MapleMap map = this.map;
        if (map != null) {
            map.broadcastMessage(packet);
        }
    }

    private void broadcastToVisitors(Packet packet) {
        purgeDefunctVisitors();
        for (Visitor visitor : visitors) {
            if (visitor != null) {
                visitor.chr.sendPacket(packet);
            }
        }
    }

    /**
     * 1.5.2 (nutnnut/Cosmic 0a9f08bf3): a visitor whose client is gone logged out mid-visit and was disposed without
     * leaving the shop. Free its slot: broadcasting to it crashed every later join/leave, and the slot stayed taken
     * for good. Called under visitorLock.
     */
    private void purgeDefunctVisitors() {
        for (int i = 0; i < visitors.length; i++) {
            Visitor visitor = visitors[i];
            if (visitor != null && visitor.chr.getClient() == null) {
                visitors[i] = null;
            }
        }
    }

    public byte[] getShopRoomInfo() {
        visitorLock.lock();
        try {
            byte count = 0;
            if (this.isOpen()) {
                for (Visitor visitor : visitors) {
                    if (visitor != null) {
                        count++;
                    }
                }
            } else {
                count = (byte) (visitors.length + 1);
            }

            return new byte[]{count, (byte) (visitors.length + 1)};
        } finally {
            visitorLock.unlock();
        }
    }

    public boolean addVisitor(Character visitor) {
        visitorLock.lock();
        try {
            purgeDefunctVisitors();     // 1.5.2: or three ghosts keep the shop full for good
            int i = this.getFreeSlot();
            if (i > -1) {
                visitors[i] = new Visitor(visitor, Instant.now());
                broadcastToVisitors(PacketCreator.hiredMerchantVisitorAdd(visitor, i + 1));
                broadcastIfOnMap(PacketCreator.updateHiredMerchantBox(this));

                return true;
            }

            return false;
        } finally {
            visitorLock.unlock();
        }
    }

    public void removeVisitor(Character chr) {
        visitorLock.lock();
        try {
            int slot = getVisitorSlot(chr);
            if (slot < 0) { //Not found
                return;
            }

            Visitor visitor = visitors[slot];
            if (visitor != null && visitor.chr.getId() == chr.getId()) {
                visitors[slot] = null;
                addVisitorToHistory(visitor);
                broadcastToVisitors(PacketCreator.hiredMerchantVisitorLeave(slot + 1));
                broadcastIfOnMap(PacketCreator.updateHiredMerchantBox(this));
            }
        } finally {
            visitorLock.unlock();
        }
    }

    private void addVisitorToHistory(Visitor visitor) {
        Duration visitDuration = Duration.between(visitor.enteredAt, Instant.now());
        visitorHistory.addFirst(new PastVisitor(visitor.chr.getName(), visitDuration));
        while (visitorHistory.size() > VISITOR_HISTORY_LIMIT) {
            visitorHistory.removeLast();
        }
    }

    public int getVisitorSlotThreadsafe(Character visitor) {
        visitorLock.lock();
        try {
            return getVisitorSlot(visitor);
        } finally {
            visitorLock.unlock();
        }
    }

    private int getVisitorSlot(Character visitor) {
        for (int i = 0; i < 3; i++) {
            if (visitors[i] != null && visitors[i].chr.getId() == visitor.getId()) {
                return i;
            }
        }
        return -1; //Actually 0 because of the +1's.
    }

    private void removeAllVisitors() {
        visitorLock.lock();
        try {
            for (int i = 0; i < 3; i++) {
                Visitor visitor = visitors[i];

                if (visitor != null) {
                    final Character visitorChr = visitor.chr;
                    visitorChr.setHiredMerchant(null);
                    visitorChr.sendPacket(PacketCreator.leaveHiredMerchant(i + 1, 0x11));
                    visitorChr.sendPacket(PacketCreator.hiredMerchantMaintenanceMessage());
                    visitors[i] = null;
                    addVisitorToHistory(visitor);
                }
            }

            broadcastIfOnMap(PacketCreator.updateHiredMerchantBox(this));
        } finally {
            visitorLock.unlock();
        }
    }

    private void removeOwner(Character owner) {
        if (owner.getHiredMerchant() == this) {
            owner.sendPacket(PacketCreator.hiredMerchantOwnerLeave());
            owner.sendPacket(PacketCreator.leaveHiredMerchant(0x00, 0x03));
            owner.setHiredMerchant(null);
        }
    }

    public void withdrawMesos(Character chr) {
        if (isOwner(chr)) {
            synchronized (items) {
                if (!chr.withdrawMerchantMesos()) {
                    chr.dropMessage(1, "Your merchant mesos could not be collected right now. Nothing was taken. Please try again.");
                    chr.sendPacket(PacketCreator.enableActions());
                }
            }
        }
    }

    public void takeItemBack(int slot, Character chr) {
        synchronized (items) {
            PlayerShopItem shopItem = itemAt(slot);   // P0-2b: a bad or stale slot is refused before anything changes
            if (shopItem == null) {
                chr.sendPacket(PacketCreator.enableActions());
                return;
            }
            if (shopItem.isExist()) {
                if (shopItem.getBundles() > 0) {
                    Item iitem = shopItem.getItem().copy();
                    iitem.setQuantity((short) (shopItem.getItem().getQuantity() * shopItem.getBundles()));

                    if (!Inventory.checkSpot(chr, iitem)) {
                        chr.sendPacket(PacketCreator.serverNotice(1, "Have a slot available on your inventory to claim back the item."));
                        chr.sendPacket(PacketCreator.enableActions());
                        return;
                    }

                    if (isPersistent()) {
                        if (!takeBackPersistent(chr, slot, shopItem, iitem)) {
                            chr.sendPacket(PacketCreator.serverNotice(1, "The item could not be taken back right now. It is still in the shop. Please try again."));
                            chr.sendPacket(PacketCreator.enableActions());
                            return;
                        }
                    } else {
                        InventoryManipulator.addFromDrop(chr.getClient(), iitem, true);
                        removeFromSlot(slot);
                    }
                } else {
                    removeFromSlot(slot);
                }
                chr.sendPacket(PacketCreator.updateHiredMerchant(this, chr));
            }

            if (YamlConfig.config.server.USE_ENFORCE_MERCHANT_SAVE) {
                chr.saveCharToDB(false);
            }
        }
    }

    /**
     * P0-2b: the owner takes a line back all-or-nothing. A STARTED MERCHANT_TAKEBACK row is claimed, the item goes to
     * the owner's bag and off the shop in memory under the owner's save lock, and one owner save carries the stock
     * write and the APPLIED mark. If it does not commit the bag and the line are put back, so the item can never be
     * in the bag and in the stored stock at once. Caller holds the {@code items} lock.
     */
    private boolean takeBackPersistent(Character chr, int slot, PlayerShopItem shopItem, Item iitem) {
        long journalId;
        try {
            journalId = journal.startRow(OperationJournal.MERCHANT_TAKEBACK, chr.getId(), ownerId,
                    "{\"item\":" + iitem.getItemId() + ",\"qty\":" + iitem.getQuantity() + "}");
        } catch (SQLException | RuntimeException e) {
            log.error("Merchant take-back: could not start the journal row for owner {}, nothing changed", ownerId, e);
            return false;
        }
        boolean saved = false;
        boolean added = false, removed = false;
        synchronized (chr) {   // Character.saveCharToDB is synchronized on it: no autosave can slip in between
            try {
                if (!InventoryManipulator.addFromDrop(chr.getClient(), iitem, true)) {
                    throw new IllegalStateException("could not add item " + iitem.getItemId());
                }
                added = true;
                items.remove(slot);
                removed = true;
                final List<PlayerShopItem> snapshot = new ArrayList<>(items);
                saved = chr.saveCharToDB(true, con -> {
                    writeItems(con, snapshot);
                    OperationJournal.markApplied(con, journalId);
                });
            } catch (RuntimeException e) {
                log.error("Merchant take-back: owner {} failed, undoing", ownerId, e);
            }
            if (!saved) {
                log.error("Merchant take-back: the save of owner {} did not commit, undoing", ownerId);
                if (removed) {
                    items.add(Math.min(slot, items.size()), shopItem);
                }
                if (added) {
                    try {
                        takeBack(chr.getClient(), iitem);
                    } catch (RuntimeException e) {
                        log.error("Merchant take-back: could not take item {} back from owner {}", iitem.getItemId(), ownerId, e);
                    }
                }
                try {
                    journal.rollBackRow(journalId, OperationJournal.MERCHANT_TAKEBACK, chr.getId(), "save failed");
                } catch (SQLException | RuntimeException e) {
                    log.error("Merchant take-back: could not release journal row {}; boot reconcile will", journalId, e);
                }
            }
        }
        return saved;
    }

    private static boolean canBuy(Client c, Item newItem) {    // thanks xiaokelvin (Conrad) for noticing a leaked test code here
        return InventoryManipulator.checkSpace(c, newItem.getItemId(), newItem.getQuantity(), newItem.getOwner()) && InventoryManipulator.addFromDrop(c, newItem, false);
    }

    private int getQuantityLeft(int itemid) {
        synchronized (items) {
            int count = 0;

            for (PlayerShopItem mpsi : items) {
                if (mpsi.getItem().getItemId() == itemid) {
                    count += (mpsi.getBundles() * mpsi.getItem().getQuantity());
                }
            }

            return count;
        }
    }

    /** The shop line at {@code index}, or null when the index is out of range (a bad or stale client packet). */
    private PlayerShopItem itemAt(int index) {
        synchronized (items) {
            return index < 0 || index >= items.size() ? null : items.get(index);
        }
    }

    void setJournalStore(JournalStore store) {   // test seam
        this.journal = store;
    }

    public void buy(Client c, int item, short quantity) {
        synchronized (items) {
            PlayerShopItem pItem = itemAt(item);   // P0-2: a bad or stale index is refused before anything changes
            if (pItem == null || quantity < 1 || !pItem.isExist() || pItem.getBundles() < 1 || pItem.getBundles() < quantity) {
                MarketLog.refused(marketKind(), c.getPlayer(), ownerKind(), "bad-line");
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            Item newItem = pItem.getItem().copy();

            newItem.setQuantity((short) ((pItem.getItem().getQuantity() * quantity)));
            if (newItem.getInventoryType().equals(InventoryType.EQUIP) && newItem.getQuantity() > 1) {
                MarketLog.refused(marketKind(), c.getPlayer(), ownerKind(), "bad-line");
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            if (!server.ShopMath.isSaleablePrice(pItem.getPrice())) {   // 1.5.0 rc6: never free, never paid to buy
                MarketLog.refused(marketKind(), c.getPlayer(), ownerKind(), "price");
                c.getPlayer().dropMessage(1, "That item isn't for sale right now.");
                c.sendPacket(PacketCreator.enableActions());
                return;
            }

            KarmaManipulator.toggleKarmaFlagToUntradeable(newItem);

            long total = server.ShopMath.total(pItem.getPrice(), quantity);   // 1.5.0 rc3 review: exact
            if (total < 0) {
                MarketLog.refused(marketKind(), c.getPlayer(), ownerKind(), "price");
                c.getPlayer().dropMessage(1, "That costs more mesos than anyone can carry at once.");
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            int price = (int) total;
            if (c.getPlayer().getMeso() < price) {
                MarketLog.refused(marketKind(), c.getPlayer(), ownerKind(), "funds");
                c.getPlayer().dropMessage(1, "You don't have enough mesos to purchase this item.");
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            if (isPersistent()) {
                buyPersistent(c, pItem, newItem, quantity, price);
                return;
            }

            // A bot shop (not persistent): nothing of it is stored, so the sale only touches memory.
            if (canBuy(c, newItem)) {
                c.getPlayer().gainMeso(-price, false);
                int paid = price;
                int fee = soloMapling.Economy.MesoSinks.shopFee(price);   // 1.5.0 rc3: FM shop tax
                price -= fee;
                soloMapling.Economy.MesoSinks.onShopSale(newItem, paid, fee, false, true, true,
                        null, soloMapling.Economy.PriceBook.Party.of(c.getPlayer()));
                soloMapling.achievements.Achievements.onMarketBuy(c.getPlayer()); // 1.6.1 ticket 93

                synchronized (sold) {
                    sold.add(new SoldItem(c.getPlayer().getName(), pItem.getItem().getItemId(), newItem.getQuantity(), price));
                }

                pItem.setBundles((short) (pItem.getBundles() - quantity));
                if (pItem.getBundles() < 1) {
                    pItem.setDoesExist(false);
                }
                // Ticket 11: an equip a bot put on sale in a bot shop - the bot is paid what the player paid
                soloMapling.ArtificialPlayer.BotScrollSystem.BotConsignment.onSold(pItem, price);
                soloMapling.Economy.BotShops.onSold(pItem, price);   // 1.5.0 rc3: a bot's own FM shop

                MarketLog.sale(marketKind(), c.getPlayer(), ownerKind());
                if (YamlConfig.config.server.USE_ANNOUNCE_SHOPITEMSOLD) {   // idea thanks to Vcoc
                    announceItemSold(newItem, price, getQuantityLeft(pItem.getItem().getItemId()));
                }
            } else {
                MarketLog.refused(marketKind(), c.getPlayer(), ownerKind(), "inventory-full");
                c.getPlayer().dropMessage(1, "Your inventory is full. Please clear a slot before buying this item.");
                c.sendPacket(PacketCreator.enableActions());
            }
        }
    }

    private String marketKind() {
        return "merchant";
    }

    /** real or bot, for the one-line sale log; an offline owner of a stored shop is a real player. */
    private String ownerKind() {
        Character o = onlineOwner();
        return o != null ? MarketLog.who(o) : (isPersistent() ? "real" : "bot");
    }

    private Character onlineOwner() {
        try {
            World w = Server.getInstance().getWorld(world);
            return w == null ? null : w.getPlayerStorage().getCharacterById(ownerId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * P0-2: a purchase from a real hired merchant is all-or-nothing (RFC 2.9). Claim a STARTED MERCHANT_BUY row, take
     * the item and mesos in memory under the buyer's save lock, then persist the buyer with the shop stock, the
     * owner's MerchantMesos and the journal's APPLIED mark in that one commit. Any failure undoes the memory and
     * releases the row; a crash in between leaves a STARTED row that boot reconcile rolls back (nothing committed).
     * Caller holds the {@code items} lock.
     */
    private void buyPersistent(Client c, PlayerShopItem pItem, Item newItem, short quantity, int price) {
        Character buyer = c.getPlayer();
        if (!InventoryManipulator.checkSpace(c, newItem.getItemId(), newItem.getQuantity(), newItem.getOwner())) {
            MarketLog.refused(marketKind(), buyer, ownerKind(), "inventory-full");
            buyer.dropMessage(1, "Your inventory is full. Please clear a slot before buying this item.");
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        final int paid = price;
        final int fee = soloMapling.Economy.MesoSinks.shopFee(price);   // 1.5.0 rc3: FM shop tax
        final int net = price - fee;
        final short bundlesBefore = pItem.getBundles();
        final boolean existedBefore = pItem.isExist();

        long journalId;
        try {
            journalId = journal.startRow(OperationJournal.MERCHANT_BUY, buyer.getId(), ownerId,
                    "{\"mesos\":" + (-paid) + ",\"ownerMesos\":" + net + ",\"item\":" + newItem.getItemId()
                            + ",\"qty\":" + newItem.getQuantity() + ",\"bundles\":" + quantity + "}");
        } catch (SQLException | RuntimeException e) {
            log.error("Merchant buy: could not start the journal row for buyer {} at owner {}'s shop, nothing changed",
                    buyer.getId(), ownerId, e);
            MarketLog.refused(marketKind(), buyer, ownerKind(), "journal-start");
            refuseBuy(c, "The purchase could not be started. You were not charged. Please try again.");
            return;
        }

        boolean saved = false;
        boolean added = false, took = false;
        synchronized (buyer) {   // Character.saveCharToDB is synchronized on it: no autosave can slip in between
            try {
                if (!InventoryManipulator.addFromDrop(c, newItem, false)) {
                    throw new IllegalStateException("could not add item " + newItem.getItemId());
                }
                added = true;
                buyer.gainMeso(-paid, false);
                took = true;
                pItem.setBundles((short) (bundlesBefore - quantity));
                if (pItem.getBundles() < 1) {
                    pItem.setDoesExist(false);
                }
                final List<PlayerShopItem> snapshot = new ArrayList<>(items);
                saved = buyer.saveCharToDB(true, con -> {
                    writeItems(con, snapshot);
                    creditOwnerMesos(con, net);
                    OperationJournal.markApplied(con, journalId);
                });
            } catch (RuntimeException e) {
                log.error("Merchant buy: buyer {} at owner {}'s shop failed, undoing", buyer.getId(), ownerId, e);
            }
            if (!saved) {
                log.error("Merchant buy: the save of buyer {} did not commit, undoing the sale at owner {}'s shop",
                        buyer.getId(), ownerId);
                pItem.setBundles(bundlesBefore);
                pItem.setDoesExist(existedBefore);
                if (took) {
                    buyer.gainMeso(paid, false);
                }
                if (added) {
                    try {
                        takeBack(c, newItem);
                    } catch (RuntimeException e) {
                        log.error("Merchant buy: could not take item {} back from buyer {}", newItem.getItemId(), buyer.getId(), e);
                    }
                }
                try {
                    journal.rollBackRow(journalId, OperationJournal.MERCHANT_BUY, buyer.getId(), "save failed");
                } catch (SQLException | RuntimeException e) {
                    log.error("Merchant buy: could not release journal row {}; boot reconcile will", journalId, e);
                }
            }
        }
        if (!saved) {
            MarketLog.refused(marketKind(), buyer, ownerKind(), "save-failed");
            refuseBuy(c, "The purchase could not be completed. You were not charged and the item is still for sale.");
            return;
        }

        // Committed: only memory-side bookkeeping is left, and none of it can undo the sale.
        MarketLog.sale(marketKind(), buyer, ownerKind());
        Character owner = onlineOwner();
        if (owner != null) {
            owner.mirrorMerchantMesosCredit(net);
        }
        try {
            soloMapling.Economy.MesoSinks.onShopSale(newItem, paid, fee, true, true, false,
                    ownerParty(), soloMapling.Economy.PriceBook.Party.of(buyer));
            soloMapling.achievements.Achievements.onMarketBuy(buyer); // 1.6.1 ticket 93
        } catch (RuntimeException e) {
            log.error("Merchant buy: post-sale bookkeeping failed for buyer {}", buyer.getId(), e);
        }
        synchronized (sold) {
            sold.add(new SoldItem(buyer.getName(), pItem.getItem().getItemId(), newItem.getQuantity(), net));
        }
        if (YamlConfig.config.server.USE_ANNOUNCE_SHOPITEMSOLD) {   // idea thanks to Vcoc
            announceItemSold(newItem, net, getQuantityLeft(pItem.getItem().getItemId()));
        }
    }

    private static void refuseBuy(Client c, String message) {
        c.getPlayer().dropMessage(1, message);
        c.sendPacket(PacketCreator.enableActions());
    }

    /** Takes back what addFromDrop gave the buyer or owner. */
    private static void takeBack(Client c, Item item) {
        InventoryType type = item.getInventoryType();
        if (type == InventoryType.EQUIP) {
            InventoryManipulator.removeFromSlot(c, type, item.getPosition(), (short) 1, false, false);
        } else {
            InventoryManipulator.removeById(c, type, item.getItemId(), item.getQuantity(), false, false);
        }
    }

    /** Adds {@code net} to the owner's MerchantMesos (capped at the int limit) on the caller's transaction. */
    private void creditOwnerMesos(Connection con, int net) throws SQLException {
        if (net <= 0) {
            return;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE characters SET MerchantMesos = LEAST(MerchantMesos + ?, 2147483647) WHERE id = ?")) {
            ps.setInt(1, net);
            ps.setInt(2, ownerId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("owner " + ownerId + " has no characters row to credit");
            }
        }
    }

    private void announceItemSold(Item item, int mesos, int inStore) {
        String qtyStr = (item.getQuantity() > 1) ? " x " + item.getQuantity() : "";

        Character player = onlineOwner();
        if (player != null && player.isLoggedinWorld()) {
            player.dropMessage(6, "[Hired Merchant] Item '" + ItemInformationProvider.getInstance().getName(item.getItemId()) + "'" + qtyStr + " has been sold for " + mesos + " mesos. (" + inStore + " left)");
        }
    }

    public void forceClose() {
        // 1.5.2: a second close (channel shutdown after the owner closed it, or two shutdown steps) would reach saveItems()
        // with the already-cleared list, and saveItemsMerchant deletes first: that would wipe the owner's Fredrick items.
        if (forceClosed) {
            return;
        }
        forceClosed = true;
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.MERCHANT)) { // !qa np-3
            soloMapling.qa.QaLog.owner(soloMapling.qa.QaLog.Topic.MERCHANT, ownerId, "hired merchant of " + ownerId
                    + " force-closed with " + items.size() + " item line(s), map " + (map == null ? "?" : map.getId()));
        }
        //Server.getInstance().getChannel(world, channel).removeHiredMerchant(ownerId);
        MapleMap map = this.map;    // 1.5.2: already off the map (channel shutdown, or a close that ran before this one)
        if (map != null) {
            map.broadcastMessage(PacketCreator.removeHiredMerchantBox(getOwnerId()));
            map.removeMapObject(this);
        }

        World wserv = Server.getInstance().getWorld(world);
        Character owner = (wserv == null) ? null : wserv.getPlayerStorage().getCharacterById(ownerId);

        visitorLock.lock();
        try {
            setOpen(false);
            removeAllVisitors();

            if (owner != null && owner.isLoggedinWorld() && this == owner.getHiredMerchant()) {
                closeOwnerMerchant(owner);
            }
        } finally {
            visitorLock.unlock();
        }

        if (wserv != null) {
            wserv.unregisterHiredMerchant(this);
        }

        if (!closePersisted) {
            try {
                persistFinalStock();
                synchronized (items) {
                    items.clear();
                }
            } catch (SQLException ex) {
                // Nothing was written (one transaction, rolled back): the goods stay where the last save put them, in
                // Fredrick's tables. The flag stays set until boot clears it; a second forceClose may try again.
                log.error("Hired merchant of {} could not be saved at close; its goods stay as last saved", ownerId, ex);
                forceClosed = false;
                return;
            }
        }

        Character player = (wserv == null) ? null : wserv.getPlayerStorage().getCharacterById(ownerId);
        if (player != null) {
            player.setHasMerchant(false);
        }

        this.map = null;
    }

    public void closeOwnerMerchant(Character chr) {
        if (this.isOwner(chr)) {
            if (this.closeShop(chr.getClient(), false)) {
                chr.setHasMerchant(false);
            }
        }
    }

    /**
     * Closes the shop. The durable step comes first and is one commit ({@link #persistClose}); only after it
     * succeeded does the shop leave the map, so a failed close changes nothing and can simply be tried again.
     * False when it failed.
     */
    private boolean closeShop(Client c, boolean timeout) {
        List<PlayerShopItem> copyItems;
        synchronized (items) {
            copyItems = new ArrayList<>(items);
        }
        boolean toOwner = !timeout && check(c.getPlayer(), copyItems);
        if (!persistClose(c, toOwner, copyItems)) {
            c.getPlayer().dropMessage(1, "The shop could not be closed right now. Your goods are safe. Please try again.");
            c.sendPacket(PacketCreator.enableActions());
            return false;
        }
        closePersisted = true;

        try {
            MapleMap shopMap = this.map;
            if (shopMap != null) {
                shopMap.removeMapObject(this);
                shopMap.broadcastMessage(PacketCreator.removeHiredMerchantBox(ownerId));
            }
            c.getChannelServer().removeHiredMerchant(ownerId);

            this.removeAllVisitors();
            this.removeOwner(c.getPlayer());

            synchronized (items) {
                items.clear();
            }

            // thanks Rohenn for noticing a possible dupe scenario on closing shop
            Character player = c.getWorldServer().getPlayerStorage().getCharacterById(ownerId);
            if (player != null) {
                player.setHasMerchant(false);
            }

            if (YamlConfig.config.server.USE_ENFORCE_MERCHANT_SAVE) {
                c.getPlayer().saveCharToDB(false);
            }
        } catch (RuntimeException e) {
            // The close is already committed; this is only the in-memory tidy-up.
            log.error("Hired merchant of {} closed in the database, but leaving the map failed", ownerId, e);
        }

        World w = Server.getInstance().getWorld(world);
        if (w != null) {
            w.unregisterHiredMerchant(this);
        }
        return true;
    }

    /**
     * The one durable step of a close (P0-2). For an owner who can take the goods (not timed out, space checked) the
     * goods go to the owner's inventory under the journal pattern of RFC 2.9: a STARTED MERCHANT_CLOSE row, the items
     * added in memory under the owner's save lock, then the owner's save carries the emptied merchant rows, the
     * Fredrick log, HasMerchant=0 and the APPLIED mark in one commit. If any of that fails the memory is undone and
     * the goods are stored with Fredrick instead, in one transaction ({@link #commitStock}). If that fails too,
     * nothing changed and false comes back. A bot shop is never stored, so it only hands over in memory.
     */
    private boolean persistClose(Client c, boolean toOwner, List<PlayerShopItem> copyItems) {
        if (!isPersistent()) {
            if (toOwner) {
                handOverInMemory(c, copyItems);
            }
            return true;
        }
        if (toOwner) {
            if (handOverToOwner(c, copyItems)) {
                return true;
            }
            c.getPlayer().dropMessage(1, "Your inventory could not take the goods back. Fredrick keeps them for you.");
        }
        try {
            commitStock(true, copyItems);
            return true;
        } catch (SQLException | RuntimeException e) {
            log.error("Hired merchant of {} could not be stored with Fredrick at close; nothing was changed", ownerId, e);
            return false;
        }
    }

    private void handOverInMemory(Client c, List<PlayerShopItem> copyItems) {
        for (PlayerShopItem mpsi : copyItems) {
            if (mpsi.isExist()) {
                if (mpsi.getItem().getInventoryType().equals(InventoryType.EQUIP)) {
                    InventoryManipulator.addFromDrop(c, mpsi.getItem(), false);
                } else {
                    InventoryManipulator.addById(c.getPlayer(), mpsi.getItem().getItemId(), (short) (mpsi.getBundles() * mpsi.getItem().getQuantity()), mpsi.getItem().getOwner(), -1, mpsi.getItem().getFlag(), mpsi.getItem().getExpiration());
                }
            }
        }
    }

    private boolean handOverToOwner(Client c, List<PlayerShopItem> copyItems) {
        Character owner = c.getPlayer();
        StringBuilder deltas = new StringBuilder("{\"toOwner\":[");
        boolean first = true;
        for (PlayerShopItem mpsi : copyItems) {
            if (mpsi.isExist()) {
                deltas.append(first ? "" : ",").append("{\"id\":").append(mpsi.getItem().getItemId())
                        .append(",\"qty\":").append(mpsi.getBundles() * mpsi.getItem().getQuantity()).append('}');
                first = false;
            }
        }
        deltas.append("]}");

        long journalId;
        try {
            journalId = journal.startRow(OperationJournal.MERCHANT_CLOSE, ownerId, ownerId, deltas.toString());
        } catch (SQLException | RuntimeException e) {
            log.error("Merchant close: could not start the journal row for owner {}", ownerId, e);
            return false;
        }

        synchronized (owner) {   // Character.saveCharToDB is synchronized on it: no autosave can slip in between
            List<Item> added = new ArrayList<>();
            List<Integer> addedQty = new ArrayList<>();
            boolean saved = false;
            try {
                for (PlayerShopItem mpsi : copyItems) {
                    if (!mpsi.isExist()) {
                        continue;
                    }
                    Item it = mpsi.getItem();
                    if (it.getInventoryType().equals(InventoryType.EQUIP)) {
                        if (!InventoryManipulator.addFromDrop(c, it, false)) {
                            throw new IllegalStateException("could not hand over equip " + it.getItemId());
                        }
                        added.add(it);
                        addedQty.add(1);
                    } else {
                        int qty = mpsi.getBundles() * it.getQuantity();
                        if (!InventoryManipulator.addById(owner, it.getItemId(), (short) qty, it.getOwner(), -1, it.getFlag(), it.getExpiration())) {
                            throw new IllegalStateException("could not hand over item " + it.getItemId());
                        }
                        added.add(it);
                        addedQty.add(qty);
                    }
                }
                saved = owner.saveCharToDB(true, con -> {
                    writeItems(con, List.of());
                    clearHasMerchant(con);
                    OperationJournal.markApplied(con, journalId);
                });
            } catch (RuntimeException e) {
                log.error("Merchant close: handing over to owner {} failed, undoing", ownerId, e);
            }
            if (!saved) {
                log.error("Merchant close: the save of owner {} did not commit, undoing the hand-over", ownerId);
                for (int i = added.size() - 1; i >= 0; i--) {
                    try {
                        Item it = added.get(i);
                        InventoryType type = it.getInventoryType();
                        if (type == InventoryType.EQUIP) {
                            InventoryManipulator.removeFromSlot(c, type, it.getPosition(), (short) 1, false, false);
                        } else {
                            InventoryManipulator.removeById(c, type, it.getItemId(), addedQty.get(i), false, false);
                        }
                    } catch (RuntimeException e) {
                        log.error("Merchant close: could not take item {} back from owner {}", added.get(i).getItemId(), ownerId, e);
                    }
                }
                try {
                    journal.rollBackRow(journalId, OperationJournal.MERCHANT_CLOSE, ownerId, "save failed");
                } catch (SQLException | RuntimeException e) {
                    log.error("Merchant close: could not release journal row {}; boot reconcile will", journalId, e);
                }
            }
            return saved;
        }
    }

    public synchronized void visitShop(Character chr) {
        visitorLock.lock();
        try {
            if (this.isOwner(chr)) {
                this.setOpen(false);
                this.removeAllVisitors();

                List<PlayerShopItem> existingItems = new ArrayList<>();
                for (PlayerShopItem item : getItems()) {
                    if (item.isExist()) existingItems.add(item);
                }

                chr.setHiredMerchant(this);
                chr.sendPacket(PacketCreator.getHiredMerchant(chr, this, false));
            } else if (!this.isOpen()) {
                chr.sendPacket(PacketCreator.getMiniRoomError(18));
                return;
            } else if (isBlacklisted(chr.getName())) {
                chr.sendPacket(PacketCreator.getMiniRoomError(17));
                return;
            } else if (!this.addVisitor(chr)) {
                chr.sendPacket(PacketCreator.getMiniRoomError(2));
                return;
            } else {
                chr.sendPacket(PacketCreator.getHiredMerchant(chr, this, false));
            }
            chr.setHiredMerchant(this);
        } finally {
            visitorLock.unlock();
        }
    }

    public String getOwner() {
        return ownerName;
    }

    public void clearItems() {
        synchronized (items) {
            items.clear();
        }
    }

    public int getOwnerId() {
        return ownerId;
    }

    public String getDescription() {
        return description;
    }

    public Character[] getVisitorCharacters() {
        visitorLock.lock();
        try {
            Character[] copy = new Character[visitors.length];
            for (int i = 0; i < visitors.length; i++) {
                Visitor visitor = visitors[i];
                if (visitor != null) {
                    copy[i] = visitor.chr;
                }
            }
            return copy;
        } finally {
            visitorLock.unlock();
        }
    }

    public List<PlayerShopItem> getItems() {
        synchronized (items) {
            return Collections.unmodifiableList(items);
        }
    }

    public boolean hasItem(int itemid) {
        for (PlayerShopItem mpsi : getItems()) {
            if (mpsi.getItem().getItemId() == itemid && mpsi.isExist() && mpsi.getBundles() > 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Ticket 11: takes one listed bundle off sale under the shop's lock (a bot's unsold consignment going back to
     * it), so a buyer in the same moment cannot also get it. False when it was already sold or gone.
     */
    public boolean withdrawListing(PlayerShopItem item) {
        synchronized (items) {
            if (!items.contains(item) || !item.isExist() || item.getBundles() < 1) {
                return false;
            }
            item.setBundles((short) 0);
            item.setDoesExist(false);
            return true;
        }
    }

    public boolean addItem(PlayerShopItem item) {
        synchronized (items) {
            if (items.size() >= 16) {
                return false;
            }

            items.add(item);
            return true;
        }
    }

    public void clearNonexistentItems() {
        synchronized (items) {
            for (int i = items.size() - 1; i >= 0; i--) {
                if (!items.get(i).isExist()) {
                    items.remove(i);
                }
            }

            try {
                this.saveItems();
            } catch (SQLException ex) {
                log.error("Hired merchant of {}: could not save after clearing sold-out lines", ownerId, ex);
            }
        }
    }

    private void removeFromSlot(int slot) {
        synchronized (items) {
            items.remove(slot);

            try {
                this.saveItems();
            } catch (SQLException ex) {
                log.error("Hired merchant of {}: could not save after removing a line", ownerId, ex);
            }
        }
    }

    private int getFreeSlot() {
        for (int i = 0; i < 3; i++) {
            if (visitors[i] == null) {
                return i;
            }
        }
        return -1;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isPublished() {
        return published;
    }

    public boolean isOpen() {
        return open.get();
    }

    public void setOpen(boolean set) {
        open.getAndSet(set);
        published = true;
    }

    public int getItemId() {
        return itemId;
    }

    public boolean isOwner(Character chr) {
        return chr.getId() == ownerId;
    }

    public void sendMessage(Character chr, String msg) {
        String message = chr.getName() + " : " + msg;
        byte slot = (byte) (getVisitorSlot(chr) + 1);

        synchronized (messages) {
            messages.add(new Pair<>(message, slot));
        }
        broadcastToVisitorsThreadsafe(PacketCreator.hiredMerchantChat(message, slot));
    }

    public List<PlayerShopItem> sendAvailableBundles(int itemId) {
        List<PlayerShopItem> list = new LinkedList<>();
        List<PlayerShopItem> all = new ArrayList<>();

        if (!open.get()) {
            return list;
        }

        synchronized (items) {
            all.addAll(items);
        }

        for (PlayerShopItem mpsi : all) {
            if (mpsi.getItem().getItemId() == itemId && mpsi.getBundles() > 0 && mpsi.isExist()) {
                list.add(mpsi);
            }
        }
        return list;
    }

    /** Persists the stock (and the Fredrick log) in one transaction; throws, with nothing written, on any SQL error. */
    public void saveItems() throws SQLException {
        List<PlayerShopItem> snapshot;
        synchronized (items) {
            snapshot = new ArrayList<>(items);
        }
        commitStock(false, snapshot);
    }

    /** Final save at a force close: the stock and HasMerchant=0 together. A bot shop (not persistent) saves nothing. */
    private void persistFinalStock() throws SQLException {
        if (!isPersistent()) {
            saveItems();
            return;
        }
        List<PlayerShopItem> snapshot;
        synchronized (items) {
            snapshot = new ArrayList<>(items);
        }
        commitStock(true, snapshot);
    }

    /** One connection, one transaction: merchant rows, Fredrick log and optionally HasMerchant=0, all or nothing. */
    private void commitStock(boolean clearFlag, List<PlayerShopItem> snapshot) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                writeItems(con, snapshot);
                if (clearFlag) {
                    clearHasMerchant(con);
                }
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    /** The merchant rows and the Fredrick log on the caller's connection; the caller owns the transaction. */
    void writeItems(Connection con, List<PlayerShopItem> snapshot) throws SQLException {
        List<Pair<Item, InventoryType>> itemsWithType = new ArrayList<>();
        List<Short> bundles = new ArrayList<>();

        for (PlayerShopItem pItems : snapshot) {
            Item newItem = pItems.getItem();
            short newBundle = pItems.getBundles();

            if (newBundle > 0) {
                itemsWithType.add(new Pair<>(newItem, newItem.getInventoryType()));
                bundles.add(newBundle);
            }
        }

        ItemFactory.MERCHANT.saveItems(itemsWithType, bundles, this.ownerId, con);
        FredrickProcessor.insertFredrickLog(con, this.ownerId);
    }

    private void clearHasMerchant(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET HasMerchant = 0 WHERE id = ?")) {
            ps.setInt(1, ownerId);
            ps.executeUpdate();
        }
    }

    private static boolean check(Character chr, List<PlayerShopItem> items) {
        List<Pair<Item, InventoryType>> li = new ArrayList<>();
        for (PlayerShopItem item : items) {
            Item it = item.getItem().copy();
            it.setQuantity((short) (it.getQuantity() * item.getBundles()));

            li.add(new Pair<>(it, it.getInventoryType()));
        }

        return Inventory.checkSpotsAndOwnership(chr, li);
    }

    public int getChannel() {
        return channel;
    }

    public int getTimeOpen() {
        double openTime = (System.currentTimeMillis() - start) / 60000;
        openTime /= 1440;   // heuristics since engineered method to count time here is unknown
        openTime *= 1318;

        return (int) Math.ceil(openTime);
    }

    public void clearMessages() {
        synchronized (messages) {
            messages.clear();
        }
    }

    public List<Pair<String, Byte>> getMessages() {
        synchronized (messages) {
            List<Pair<String, Byte>> msgList = new LinkedList<>();
            msgList.addAll(messages);

            return msgList;
        }
    }

    public List<PastVisitor> getVisitorHistory() {
        return Collections.unmodifiableList(visitorHistory);
    }

    public void addToBlacklist(String chrName) {
        visitorLock.lock();
        try {
            if (blacklist.size() >= BLACKLIST_LIMIT) {
                return;
            }
            blacklist.add(chrName);
        } finally {
            visitorLock.unlock();
        }
    }

    public void removeFromBlacklist(String chrName) {
        visitorLock.lock();
        try {
            blacklist.remove(chrName);
        } finally {
            visitorLock.unlock();
        }
    }

    public Set<String> getBlacklist() {
        return Collections.unmodifiableSet(blacklist);
    }

    private boolean isBlacklisted(String chrName) {
        visitorLock.lock();
        try {
            return blacklist.contains(chrName);
        } finally {
            visitorLock.unlock();
        }
    }

    public int getMapId() {
        return map.getId();
    }

    public MapleMap getMap() {
        return map;
    }

    public List<SoldItem> getSold() {
        synchronized (sold) {
            return Collections.unmodifiableList(sold);
        }
    }

    public int getMesos() {
        return mesos;
    }

    @Override
    public MapObjectType getType() {
        return MapObjectType.HIRED_MERCHANT;
    }

    @Override
    public void sendDestroyData(Client client) {}

    @Override
    public void sendSpawnData(Client client) {
        client.sendPacket(PacketCreator.spawnHiredMerchantBox(this));
    }

    public class SoldItem {

        int itemId, mesos;
        short quantity;
        String buyer;

        public SoldItem(String buyer, int itemId, short quantity, int mesos) {
            this.buyer = buyer;
            this.itemId = itemId;
            this.quantity = quantity;
            this.mesos = mesos;
        }

        public String getBuyer() {
            return buyer;
        }

        public int getItemId() {
            return itemId;
        }

        public short getQuantity() {
            return quantity;
        }

        public int getMesos() {
            return mesos;
        }
    }

    // Additive SoloMapling APIs; MapleRoot player paths remain in place.

    protected boolean isPersistent() { return true; }

    /*
    SoloMapling allows bots to buy
     */
    public boolean botBuy(Character fakechar, PlayerShopItem pItem, short quantity) {
        synchronized (items) {
            // P0-2: the line must still be one of this shop's live lines (a stale or foreign line is refused).
            if (quantity < 1 || !items.contains(pItem) || !pItem.isExist() || pItem.getBundles() < quantity) {
                return false;
            }
            Item newItem = pItem.getItem().copy();
            newItem.setQuantity((short) (pItem.getItem().getQuantity() * quantity));
            if (!server.ShopMath.isSaleablePrice(pItem.getPrice())) {   // 1.5.0 rc6: never free, never paid to buy
                return false;
            }
            long total = server.ShopMath.total(pItem.getPrice(), quantity);   // 1.5.0 rc3 review: exact
            if (total < 0) {
                return false;
            }
            int price = (int) total;
            int paid = price;
            int fee = soloMapling.Economy.MesoSinks.shopFee(price);   // 1.5.0 rc3: FM shop tax
            price -= fee;

            if (isPersistent()) {
                // Commit the shop side first (stock and the owner's MerchantMesos, one transaction). The bot's wallet
                // and bag are memory only, so check them without changing anything, commit, then apply.
                if (fakechar.getMeso() < paid) {
                    fakechar.dropMessage(1, "You don't have enough mesos to purchase this item.");
                    return false;
                }
                if (!BotHoldings.canHold(fakechar, newItem)) {
                    fakechar.dropMessage(1, "Your inventory is full. Please clear a slot before buying this item.");
                    return false;
                }
                final short before = pItem.getBundles();
                final boolean existed = pItem.isExist();
                final int net = price;
                pItem.setBundles((short) (before - quantity));
                if (pItem.getBundles() < 1) {
                    pItem.setDoesExist(false);
                }
                try {
                    List<PlayerShopItem> snapshot = new ArrayList<>(items);
                    try (Connection con = DatabaseConnection.getConnection()) {
                        con.setAutoCommit(false);
                        try {
                            writeItems(con, snapshot);
                            creditOwnerMesos(con, net);
                            con.commit();
                        } catch (SQLException | RuntimeException e) {
                            con.rollback();
                            throw e;
                        } finally {
                            con.setAutoCommit(true);
                        }
                    }
                } catch (SQLException | RuntimeException e) {
                    log.error("Merchant botBuy: saving the sale at owner {}'s shop failed, sale refused", ownerId, e);
                    pItem.setBundles(before);
                    pItem.setDoesExist(existed);
                    MarketLog.refused(marketKind(), fakechar, ownerKind(), "save-failed");
                    return false;
                }
                if (!BotHoldings.spendMeso(fakechar, paid) || !BotHoldings.grantItem(fakechar, newItem)) {
                    // Checked above and the bot acts on its own thread, so this is not expected; the sale is committed.
                    log.error("Merchant botBuy: bot {} could not take a committed sale (item {} x{}, {} mesos)",
                            fakechar.getName(), newItem.getItemId(), newItem.getQuantity(), paid);
                }
                Character owner = onlineOwner();
                if (owner != null && !isBot(owner)) {
                    owner.mirrorMerchantMesosCredit(net);
                }
            } else {
                if (!BotHoldings.spendMeso(fakechar, paid)) {
                    fakechar.dropMessage(1, "You don't have enough mesos to purchase this item.");
                    return false;
                }

                if (!BotHoldings.grantItem(fakechar, newItem)) {
                    BotHoldings.grantMeso(fakechar, paid);
                    fakechar.dropMessage(1, "Your inventory is full. Please clear a slot before buying this item.");
                    return false;
                }
                pItem.setBundles((short) (pItem.getBundles() - quantity));
                if (pItem.getBundles() < 1) {
                    pItem.setDoesExist(false);
                }
            }

            MarketLog.sale(marketKind(), fakechar, ownerKind());
            soloMapling.Economy.MesoSinks.onShopSale(newItem, paid, fee, isPersistent(), false, !isPersistent());
            soloMapling.achievements.Achievements.onMarketBuy(fakechar); // 1.6.1 ticket 93: a bot buying

            synchronized (sold) {
                sold.add(new SoldItem(fakechar.getName(), pItem.getItem().getItemId(), quantity, price));
            }

            if (YamlConfig.config.server.USE_ANNOUNCE_SHOPITEMSOLD) {   // idea thanks to Vcoc
                announceItemSold(newItem, price, getQuantityLeft(pItem.getItem().getItemId()));
            }
            return true;
        }
    }
}
