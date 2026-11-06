/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    Copyleft (L) 2016 - 2019 RonanLana (HeavenMS)

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
package client.processor.npc;

import client.Character;
import client.Client;
import client.autoban.AutobanFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.inventory.manipulator.InventoryManipulator;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.maps.HiredMerchant;
import server.maps.PlayerShopItem;
import server.transactions.JdbcJournalStore;
import server.transactions.JournalStore;
import server.transactions.OperationJournal;
import service.NoteService;
import tools.DatabaseConnection;
import tools.FilePrinter;
import tools.PacketCreator;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import static java.util.concurrent.TimeUnit.DAYS;

/**
 * @author RonanLana - synchronization of Fredrick modules and operation results
 */
public class FredrickProcessor {
    private static final Logger log = LoggerFactory.getLogger(FredrickProcessor.class);
    private static final int[] dailyReminders = new int[]{2, 5, 10, 15, 30, 60, 90, Integer.MAX_VALUE};

    private final NoteService noteService;

    public FredrickProcessor(NoteService noteService) {
        this.noteService = noteService;
    }

    private static byte canRetrieveFromFredrick(Character chr, List<Pair<Item, InventoryType>> items) {
        if (!Inventory.checkSpotsAndOwnership(chr, items)) {
            List<Integer> itemids = new LinkedList<>();
            for (Pair<Item, InventoryType> it : items) {
                itemids.add(it.getLeft().getItemId());
            }

            if (chr.canHoldUniques(itemids)) {
                return 0x22;
            } else {
                return 0x20;
            }
        }

        int netMeso = chr.getMerchantNetMeso();
        if (netMeso > 0) {
            if (!chr.canHoldMeso(netMeso)) {
                return 0x1F;
            }
        } else {
            if (chr.getMeso() < -1 * netMeso) {
                return 0x21;
            }
        }

        return 0x0;
    }

    public static int timestampElapsedDays(Timestamp then, long timeNow) {
        return (int) ((timeNow - then.getTime()) / DAYS.toMillis(1));
    }

    private static String fredrickReminderMessage(int daynotes) {
        String msg;

        if (daynotes < 4) {
            msg = "Hi customer! I am Fredrick, the Union Chief of the Hired Merchant Union. A reminder that " + dailyReminders[daynotes] + " days have passed since you used our service. Please reclaim your stored goods at FM Entrance.";
        } else {
            msg = "Hi customer! I am Fredrick, the Union Chief of the Hired Merchant Union. " + dailyReminders[daynotes] + " days have passed since you used our service. Consider claiming back the items before we move them away for refund.";
        }

        return msg;
    }

    /** Removes the log row. False when the database refused it (the row is then still there). */
    public static boolean removeFredrickLog(int cid) {
        try (Connection con = DatabaseConnection.getConnection()) {
            removeFredrickLog(con, cid);
            return true;
        } catch (SQLException sqle) {
            log.error("Could not remove the Fredrick log of chr {}", cid, sqle);
            return false;
        }
    }

    public static void removeFredrickLog(Connection con, int cid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM `fredstorage` WHERE `cid` = ?")) {
            ps.setInt(1, cid);
            ps.executeUpdate();
        }
    }

    /**
     * Replaces the owner's Fredrick log row on the caller's connection and transaction (P0-2): the caller commits, so
     * the log can never disagree with the merchant rows saved next to it. SQL errors are thrown, not swallowed.
     */
    public static void insertFredrickLog(Connection con, int cid) throws SQLException {
        removeFredrickLog(con, cid);
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO `fredstorage` (`cid`, `daynotes`, `timestamp`) VALUES (?, 0, ?)")) {
            ps.setInt(1, cid);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    private static void removeFredrickReminders(List<Pair<Integer, Integer>> expiredCids) {
        List<String> expiredCnames = new LinkedList<>();
        for (Pair<Integer, Integer> id : expiredCids) {
            String name = Character.getNameById(id.getLeft());
            if (name != null) {
                expiredCnames.add(name);
            }
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM `notes` WHERE `from` LIKE ? AND `to` LIKE ?")) {
            ps.setString(1, "FREDRICK");

            for (String cname : expiredCnames) {
                ps.setString(2, cname);
                ps.executeBatch();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void runFredrickSchedule() {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<Pair<Integer, Integer>> expiredCids = new LinkedList<>();
            List<Pair<Pair<Integer, String>, Integer>> notifCids = new LinkedList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM fredstorage f LEFT JOIN (SELECT id, name, world, lastLogoutTime FROM characters) AS c ON c.id = f.cid");
                 ResultSet rs = ps.executeQuery()) {
                long curTime = System.currentTimeMillis();

                while (rs.next()) {
                    int cid = rs.getInt("cid");
                    int world = rs.getInt("world");
                    Timestamp ts = rs.getTimestamp("timestamp");
                    int daynotes = Math.min(dailyReminders.length - 1, rs.getInt("daynotes"));

                    int elapsedDays = timestampElapsedDays(ts, curTime);
                    if (elapsedDays > 100) {
                        expiredCids.add(new Pair<>(cid, world));
                    } else {
                        int notifDay = dailyReminders[daynotes];

                        if (elapsedDays >= notifDay) {
                            do {
                                daynotes++;
                                notifDay = dailyReminders[daynotes];
                            } while (elapsedDays >= notifDay);

                            Timestamp logoutTs = rs.getTimestamp("lastLogoutTime");
                            int inactivityDays = timestampElapsedDays(logoutTs, curTime);

                            if (inactivityDays < 7 || daynotes >= dailyReminders.length - 1) {  // don't spam inactive players
                                String name = rs.getString("name");
                                notifCids.add(new Pair<>(new Pair<>(cid, name), daynotes));
                            }
                        }
                    }
                }

            }

            if (!expiredCids.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM `inventoryitems` WHERE `type` = ? AND `characterid` = ?")) {
                    ps.setInt(1, ItemFactory.MERCHANT.getValue());

                    for (Pair<Integer, Integer> cid : expiredCids) {
                        ps.setInt(2, cid.getLeft());
                        ps.addBatch();
                    }

                    ps.executeBatch();
                }

                try (PreparedStatement ps = con.prepareStatement("UPDATE `characters` SET `MerchantMesos` = 0 WHERE `id` = ?")) {
                    List<Character> online = new LinkedList<>();
                    for (Pair<Integer, Integer> cid : expiredCids) {
                        ps.setInt(1, cid.getLeft());
                        ps.addBatch();

                        World wserv = Server.getInstance().getWorld(cid.getRight());
                        if (wserv != null) {
                            Character chr = wserv.getPlayerStorage().getCharacterById(cid.getLeft());
                            if (chr != null) {
                                online.add(chr);
                            }
                        }
                    }

                    ps.executeBatch();
                    for (Character chr : online) {   // memory follows the database, only once the batch went through
                        chr.mirrorMerchantMeso(0);
                    }
                }

                removeFredrickReminders(expiredCids);

                try (PreparedStatement ps = con.prepareStatement("DELETE FROM `fredstorage` WHERE `cid` = ?")) {
                    for (Pair<Integer, Integer> cid : expiredCids) {
                        ps.setInt(1, cid.getLeft());
                        ps.addBatch();
                    }

                    ps.executeBatch();
                }
            }

            if (!notifCids.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement("UPDATE `fredstorage` SET `daynotes` = ? WHERE `cid` = ?")) {
                    for (Pair<Pair<Integer, String>, Integer> cid : notifCids) {
                        ps.setInt(1, cid.getRight());
                        ps.setInt(2, cid.getLeft().getLeft());
                        ps.addBatch();

                        String msg = fredrickReminderMessage(cid.getRight() - 1);
                        // The reminder day has already been booked below; a refused note is the store-and-forward
                        // player's one channel, so the loss has to be visible somewhere.
                        NoteService.warnIfNotDelivered("Fredrick reminder", "FREDRICK", cid.getLeft().getRight(),
                                noteService.sendNormal(msg, "FREDRICK", cid.getLeft().getRight()));
                    }

                    ps.executeBatch();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void deleteFredrickItems(Connection con, int cid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM `inventoryitems` WHERE `type` = ? AND `characterid` = ?")) {
            ps.setInt(1, ItemFactory.MERCHANT.getValue());
            ps.setInt(2, cid);
            ps.executeUpdate();
        }
    }

    private static void writeMerchantMesos(Connection con, int cid, int value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE `characters` SET `MerchantMesos` = ? WHERE `id` = ?")) {
            ps.setInt(1, value);
            ps.setInt(2, cid);
            ps.executeUpdate();
        }
    }

    public void fredrickRetrieveItems(Client c) {     // thanks Gustav for pointing out the dupe on Fredrick handling
        if (c.tryacquireClient()) {
            try {
                Character chr = c.getPlayer();

                List<Pair<Item, InventoryType>> items;
                try {
                    items = ItemFactory.MERCHANT.loadItems(chr.getId(), false);
                } catch (SQLException ex) {
                    log.error("Fredrick retrieve: could not load the stored goods of chr {}", chr.getId(), ex);
                    chr.message("An unknown error has occured.");
                    return;
                }
                retrieve(chr, items, new JdbcJournalStore());
            } finally {
                c.releaseClient();
            }
        }
    }

    /**
     * P0-2b, all or nothing (RFC 2.9). Items and mesos used to move in separate steps (mesos first, then the stored
     * rows deleted, then the items added in memory with no save), so a failed add, a failed delete or a crash
     * before the next autosave lost goods or mesos, and a swallowed MerchantMesos write let the mesos be taken twice.
     * Now: claim a STARTED FREDRICK_RETRIEVE row, add wallet and items in memory under the character's save lock,
     * and let one character save carry the stored-rows delete, the new MerchantMesos, the log removal and the
     * APPLIED mark. If anything fails the memory is undone, the row released, and Fredrick still holds everything.
     */
    static void retrieve(Character chr, List<Pair<Item, InventoryType>> items, JournalStore journal) {
        byte response = canRetrieveFromFredrick(chr, items);
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.MERCHANT)) { // !qa np-2, np-3
            soloMapling.qa.QaLog.owner(soloMapling.qa.QaLog.Topic.MERCHANT, chr.getId(), "Fredrick retrieve: "
                    + items.size() + " item(s) stored, merchant mesos " + chr.getMerchantMeso()
                    + ", response " + response + (response == 0 ? " (handing them over)" : " (refused)"));
        }
        if (response != 0) {
            chr.sendPacket(PacketCreator.fredrickMessage(response));
            return;
        }

        final int cid = chr.getId();
        final Character.MerchantWithdrawal plan = chr.planMerchantWithdrawal();
        StringBuilder deltas = new StringBuilder("{\"mesos\":" + plan.gain() + ",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i).getLeft();
            deltas.append(i == 0 ? "" : ",").append("{\"id\":").append(it.getItemId()).append(",\"qty\":").append(it.getQuantity()).append('}');
        }
        deltas.append("]}");

        long journalId;
        try {
            journalId = journal.startRow(OperationJournal.FREDRICK_RETRIEVE, cid, cid, deltas.toString());
        } catch (SQLException | RuntimeException e) {
            log.error("Fredrick retrieve: could not start the journal row for chr {}, nothing changed", cid, e);
            chr.sendPacket(PacketCreator.serverNotice(1, "Fredrick could not hand over your goods right now. Nothing was taken. Please try again."));
            return;
        }

        Client c = chr.getClient();
        boolean saved = false;
        boolean gained = false;
        List<Item> added = new LinkedList<>();
        synchronized (chr) {   // Character.saveCharToDB is synchronized on it: no autosave can slip in between
            try {
                if (plan.gain() != 0) {
                    chr.gainMeso(plan.gain(), false);
                    gained = true;
                }
                for (Pair<Item, InventoryType> it : items) {
                    if (!InventoryManipulator.addFromDrop(c, it.getLeft(), false)) {
                        throw new IllegalStateException("could not add item " + it.getLeft().getItemId());
                    }
                    added.add(it.getLeft());
                }
                saved = chr.saveCharToDB(true, con -> {
                    deleteFredrickItems(con, cid);
                    writeMerchantMesos(con, cid, plan.newMerchantMeso());
                    removeFredrickLog(con, cid);
                    OperationJournal.markApplied(con, journalId);
                });
            } catch (RuntimeException e) {
                log.error("Fredrick retrieve: chr {} failed, undoing", cid, e);
            }
            if (!saved) {
                log.error("Fredrick retrieve: the save of chr {} did not commit, undoing the hand-over", cid);
                for (Item it : added) {
                    try {
                        InventoryType type = it.getInventoryType();
                        if (type == InventoryType.EQUIP) {
                            InventoryManipulator.removeFromSlot(c, type, it.getPosition(), (short) 1, false, false);
                        } else {
                            InventoryManipulator.removeById(c, type, it.getItemId(), it.getQuantity(), false, false);
                        }
                    } catch (RuntimeException e) {
                        log.error("Fredrick retrieve: could not take item {} back from chr {}", it.getItemId(), cid, e);
                    }
                }
                if (gained) {
                    chr.gainMeso(-plan.gain(), false);
                }
                try {
                    journal.rollBackRow(journalId, OperationJournal.FREDRICK_RETRIEVE, cid, "save failed");
                } catch (SQLException | RuntimeException e) {
                    log.error("Fredrick retrieve: could not release journal row {}; boot reconcile will", journalId, e);
                }
            }
        }
        if (!saved) {
            chr.sendPacket(PacketCreator.serverNotice(1, "Fredrick could not hand over your goods right now. Nothing was taken and everything is still stored. Please try again."));
            chr.sendPacket(PacketCreator.enableActions());
            return;
        }

        // Committed: only memory-side bookkeeping is left.
        chr.mirrorMerchantMeso(plan.newMerchantMeso());
        try {
            // Cosmic: drop the in-memory copy too, so a merchant still loaded for this owner cannot
            // hand the same items out again after Fredrick has (1.4.1, restored from Cosmic).
            HiredMerchant merchant = chr.getHiredMerchant();
            if (merchant != null) {
                merchant.clearItems();
            }
            for (Pair<Item, InventoryType> it : items) {
                Item item = it.getLeft();
                String itemName = ItemInformationProvider.getInstance().getName(item.getItemId());
                FilePrinter.print(FilePrinter.FREDRICK + chr.getName() + ".txt", chr.getName() + " put into Fredrick " + item.getQuantity() + " " + itemName + " (" + item.getItemId() + ")");
                FilePrinter.print(FilePrinter.FREDRICK + chr.getName() + ".txt", chr.getName() + " gained " + item.getQuantity() + " " + itemName + " (" + item.getItemId() + ")");
                log.debug("Chr {} gained {}x {} ({})", chr.getName(), item.getQuantity(), itemName, item.getItemId());
            }
        } catch (RuntimeException e) {
            log.error("Fredrick retrieve: bookkeeping after the commit failed for chr {}", cid, e);
        }
        chr.sendPacket(PacketCreator.fredrickMessage((byte) 0x1E));
    }
}
