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
import client.inventory.manipulator.KarmaManipulator;
import config.YamlConfig;
import constants.id.ItemId;
import constants.id.NpcId;
import constants.inventory.ItemConstants;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.DueyPackage;
import server.ItemInformationProvider;
import server.Trade;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Pair;

import java.sql.*;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author RonanLana - synchronization of Duey modules
 */
public class DueyProcessor {
    private static final Logger log = LoggerFactory.getLogger(DueyProcessor.class);

    public enum Actions {
        TOSERVER_RECV_ITEM(0x00),
        TOSERVER_SEND_ITEM(0x02),
        TOSERVER_CLAIM_PACKAGE(0x04),
        TOSERVER_REMOVE_PACKAGE(0x05),
        TOSERVER_CLOSE_DUEY(0x07),
        TOCLIENT_OPEN_DUEY(0x08),
        TOCLIENT_SEND_ENABLE_ACTIONS(0x09),
        TOCLIENT_SEND_NOT_ENOUGH_MESOS(0x0A),
        TOCLIENT_SEND_INCORRECT_REQUEST(0x0B),
        TOCLIENT_SEND_NAME_DOES_NOT_EXIST(0x0C),
        TOCLIENT_SEND_SAMEACC_ERROR(0x0D),
        TOCLIENT_SEND_RECEIVER_STORAGE_FULL(0x0E),
        TOCLIENT_SEND_RECEIVER_UNABLE_TO_RECV(0x0F),
        TOCLIENT_SEND_RECEIVER_STORAGE_WITH_UNIQUE(0x10),
        TOCLIENT_SEND_MESO_LIMIT(0x11),
        TOCLIENT_SEND_SUCCESSFULLY_SENT(0x12),
        TOCLIENT_RECV_UNKNOWN_ERROR(0x13),
        TOCLIENT_RECV_ENABLE_ACTIONS(0x14),
        TOCLIENT_RECV_NO_FREE_SLOTS(0x15),
        TOCLIENT_RECV_RECEIVER_WITH_UNIQUE(0x16),
        TOCLIENT_RECV_SUCCESSFUL_MSG(0x17),
        TOCLIENT_RECV_PACKAGE_MSG(0x1B);
        final byte code;

        Actions(int code) {
            this.code = (byte) code;
        }

        public byte getCode() {
            return code;
        }
    }

    public static final String CLOSED_MESSAGE = "Duey's delivery service is closed for now. Sorry about that!";

    /**
     * When Duey is turned off (USE_DUEY: false) tells the player so instead of opening a window whose buttons do
     * nothing (ticket 55).
     *
     * @param fromNpc true when the player clicked Duey (answered in an NPC dialog), false for the Quick Delivery ticket
     *                or a Duey window packet (a popup)
     * @return true when Duey is open and the caller may go on
     */
    public static boolean openOrExplain(Client c, boolean fromNpc) {
        if (c instanceof client.BotClient) {
            return false; // bots never use Duey (1.5.2: Duey is on for players only)
        }
        if (YamlConfig.config.server.USE_DUEY) {
            return true;
        }
        if (fromNpc) {
            c.sendPacket(PacketCreator.getNPCTalk(NpcId.DUEY, (byte) 0, CLOSED_MESSAGE, "00 00", (byte) 0));
        } else {
            c.sendPacket(PacketCreator.serverNotice(1, CLOSED_MESSAGE));
        }
        c.sendPacket(PacketCreator.enableActions());
        return false;
    }

    static boolean isBotSender(Client c) {
        return c instanceof client.BotClient || c.getPlayer() == null
                || soloMapling.ArtificialPlayer.BotHelpers.isArtificial(c.getPlayer().getId());
    }

    private static Pair<Integer, Integer> getAccountCharacterIdFromCNAME(String name) {
        Pair<Integer, Integer> ids = new Pair<>(-1, -1);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id,accountid FROM characters WHERE name = ?")) {
            ps.setString(1, name);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ids.left = rs.getInt("accountid");
                    ids.right = rs.getInt("id");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return ids;
    }

    private static void showDueyNotification(Client c, Character player) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT SenderName, Type FROM dueypackages WHERE ReceiverId = ? AND Checked = 1 ORDER BY Type DESC")) {

            ps.setInt(1, player.getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement ps2 = con.prepareStatement("UPDATE dueypackages SET Checked = 0 where ReceiverId = ?")) {
                        ps2.setInt(1, player.getId());
                        ps2.executeUpdate();

                        c.sendPacket(PacketCreator.sendDueyParcelReceived(rs.getString("SenderName"), rs.getInt("Type") == 1));
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void deletePackageFromInventoryDB(Connection con, int packageId) throws SQLException {
        ItemFactory.DUEY.saveItems(new LinkedList<>(), packageId, con);
    }

    private static void removePackageFromDB(int packageId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM dueypackages WHERE PackageId = ?")) {
            ps.setInt(1, packageId);
            ps.executeUpdate();

            deletePackageFromInventoryDB(con, packageId);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /** Deletes a package only when it is addressed to this character (Cosmic deleted any package id it was sent). */
    private static boolean removeOwnPackageFromDB(int packageId, int receiverId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM dueypackages WHERE PackageId = ? AND ReceiverId = ?")) {
            ps.setInt(1, packageId);
            ps.setInt(2, receiverId);
            if (ps.executeUpdate() < 1) {
                return false;
            }

            deletePackageFromInventoryDB(con, packageId);
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static DueyPackage getPackageFromDB(ResultSet rs) {
        try {
            int packageId = rs.getInt("PackageId");

            List<Pair<Item, InventoryType>> dueyItems = ItemFactory.DUEY.loadItems(packageId, false);
            DueyPackage dueypack;

            if (!dueyItems.isEmpty()) {     // in a duey package there's only one item
                dueypack = new DueyPackage(packageId, dueyItems.get(0).getLeft());
            } else {
                dueypack = new DueyPackage(packageId);
            }

            dueypack.setSender(rs.getString("SenderName"));
            dueypack.setMesos(rs.getInt("Mesos"));
            dueypack.setSentTime(rs.getTimestamp("TimeStamp"), rs.getBoolean("Type"));
            dueypack.setMessage(rs.getString("Message"));

            return dueypack;
        } catch (SQLException sqle) {
            sqle.printStackTrace();
            return null;
        }
    }

    private static List<DueyPackage> loadPackages(Character chr) {
        List<DueyPackage> packages = new LinkedList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM dueypackages dp WHERE ReceiverId = ?")) {
            ps.setInt(1, chr.getId());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DueyPackage dueypack = getPackageFromDB(rs);
                    if (dueypack == null) {
                        continue;
                    }

                    packages.add(dueypack);
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return packages;
    }

    private static int createPackage(int mesos, String message, String sender, int toCid, boolean quick) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO `dueypackages` (ReceiverId, SenderName, Mesos, TimeStamp, Message, Type, Checked) VALUES (?, ?, ?, ?, ?, ?, 1)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, toCid);
            ps.setString(2, sender);
            ps.setInt(3, mesos);
            ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            ps.setString(5, message);
            ps.setInt(6, quick ? 1 : 0);

            int updateRows = ps.executeUpdate();
            if (updateRows < 1) {
                log.error("Error trying to create package [mesos: {}, sender: {}, quick: {}, receiver chrId: {}]", mesos, sender, quick, toCid);
                return -1;
            }

            final int packageId;
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    packageId = rs.getInt(1);
                } else {
                    log.error("Failed inserting package [mesos: {}, sender: {}, quick: {}, receiver chrId: {}]", mesos, sender, quick, toCid);
                    return -1;
                }
            }

            return packageId;
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }

        return -1;
    }

    private static boolean insertPackageItem(int packageId, Item item) {
        Pair<Item, InventoryType> dueyItem = new Pair<>(item, InventoryType.getByType(item.getItemType()));
        try (Connection con = DatabaseConnection.getConnection()) {
            ItemFactory.DUEY.saveItems(Collections.singletonList(dueyItem), packageId, con);
            return true;
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }

        return false;
    }

    private static int addPackageItemFromInventory(int packageId, Client c, byte invTypeId, short itemPos, short amount) {
        if (invTypeId > 0) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();

            InventoryType invType = InventoryType.getByType(invTypeId);
            Inventory inv = c.getPlayer().getInventory(invType);

            Item item;
            inv.lockInventory();
            try {
                item = inv.getItem(itemPos);
                if (item != null && item.getQuantity() >= amount) {
                    if (item.isUntradeable() || ii.isUnmerchable(item.getItemId()) || item.getPetId() > -1) {
                        return -1;
                    }

                    if (ItemConstants.isRechargeable(item.getItemId())) {
                        InventoryManipulator.removeFromSlot(c, invType, itemPos, item.getQuantity(), true);
                    } else {
                        InventoryManipulator.removeFromSlot(c, invType, itemPos, amount, true, false);
                    }

                    item = item.copy();
                } else {
                    return -2;
                }
            } finally {
                inv.unlockInventory();
            }

            KarmaManipulator.toggleKarmaFlagToUntradeable(item);
            item.setQuantity(amount);

            if (!insertPackageItem(packageId, item)) {
                return 1;
            }
        }

        return 0;
    }

    /**
     * Checks a send request's numbers before anything is taken from the sender (Cosmic accepted a negative item amount,
     * which the inventory removal turned into extra items, and negative mesos, which the receiver then lost).
     *
     * @return null when the request is well formed, else what is wrong with it (a packet edit)
     */
    static String invalidSendRequest(byte invTypeId, short amount, int sendMesos) {
        if (sendMesos < 0) {
            return "negative mesos " + sendMesos;
        }
        if (invTypeId > 0) {
            if (invTypeId > InventoryType.CASH.getType()) {
                return "inventory " + invTypeId;
            }
            if (amount < 1) {
                return "item amount " + amount;
            }
        } else if (sendMesos == 0) {
            return "nothing to send";
        }
        return null;
    }

    public static void dueySendItem(Client c, byte invTypeId, short itemPos, short amount, int sendMesos, String sendMessage, String recipient, boolean quick) {
        if (c.tryacquireClient()) {
            try {
                if (!YamlConfig.config.server.USE_DUEY || isBotSender(c)) {
                    // Duey off, or a bot: bots never send parcels (no bot-to-player meso/item channel outside trades)
                    c.sendPacket(PacketCreator.sendDueyMSG(DueyProcessor.Actions.TOCLIENT_SEND_INCORRECT_REQUEST.getCode()));
                    return;
                }
                if (c.getPlayer().isGM() && c.getPlayer().gmLevel() < YamlConfig.config.server.MINIMUM_GM_LEVEL_TO_USE_DUEY) {
                    c.getPlayer().message("You cannot use Duey to send items at your GM level.");
                    log.info(String.format("GM %s tried to send a package to %s", c.getPlayer().getName(), recipient));
                    c.sendPacket(PacketCreator.sendDueyMSG(DueyProcessor.Actions.TOCLIENT_SEND_INCORRECT_REQUEST.getCode()));
                    return;
                }

                String invalid = invalidSendRequest(invTypeId, amount, sendMesos);
                if (invalid != null) {
                    AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit with duey.");
                    log.warn("Chr {} sent a bad duey request: {}", c.getPlayer().getName(), invalid);
                    c.disconnect(true, false);
                    return;
                }

                int fee = Trade.getFee(sendMesos);
                if (sendMessage != null && sendMessage.length() > 100) {
                    AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit with Quick Delivery on duey.");
                    log.warn("Chr {} tried to use duey with too long of a text", c.getPlayer().getName());
                    c.disconnect(true, false);
                    return;
                }
                if (!quick) {
                    fee += 5000;
                } else if (!c.getPlayer().haveItem(ItemId.QUICK_DELIVERY_TICKET)) {
                    AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit with Quick Delivery on duey.");
                    log.warn("Chr {} tried to use duey with Quick Delivery without a ticket, mesos {} and amount {}", c.getPlayer().getName(), sendMesos, amount);
                    c.disconnect(true, false);
                    return;
                }

                long finalcost = (long) sendMesos + fee;
                if (finalcost < 0 || finalcost > Integer.MAX_VALUE || (amount < 1 && sendMesos == 0)) {
                    AutobanFactory.PACKET_EDIT.alert(c.getPlayer(), c.getPlayer().getName() + " tried to packet edit with duey.");
                    log.warn("Chr {} tried to use duey with mesos {} and amount {}", c.getPlayer().getName(), sendMesos, amount);
                    c.disconnect(true, false);
                    return;
                }

                if(c.getPlayer().getMeso() < finalcost) {
                    c.sendPacket(PacketCreator.sendDueyMSG(DueyProcessor.Actions.TOCLIENT_SEND_NOT_ENOUGH_MESOS.getCode()));
                    return;
                }

                var accIdCid = getAccountCharacterIdFromCNAME(recipient);
                var recipientAccId = accIdCid.getLeft();
                var recipientCid = accIdCid.getRight();

                if (recipientAccId == -1 || recipientCid == -1) {
                    c.sendPacket(PacketCreator.sendDueyMSG(DueyProcessor.Actions.TOCLIENT_SEND_NAME_DOES_NOT_EXIST.getCode()));
                    return;
                }

                if (recipientAccId == c.getAccID()) {
                    c.sendPacket(PacketCreator.sendDueyMSG(DueyProcessor.Actions.TOCLIENT_SEND_SAMEACC_ERROR.getCode()));
                    return;
                }

                if (soloMapling.ArtificialPlayer.BotHelpers.isArtificial(recipientCid)) { // bots never open Duey
                    c.sendPacket(PacketCreator.sendDueyMSG(DueyProcessor.Actions.TOCLIENT_SEND_RECEIVER_UNABLE_TO_RECV.getCode()));
                    return;
                }

                int packageId = createPackage(sendMesos, sendMessage, c.getPlayer().getName(), recipientCid, quick);
                if (packageId == -1) {
                    c.sendPacket(PacketCreator.sendDueyMSG(DueyProcessor.Actions.TOCLIENT_SEND_ENABLE_ACTIONS.getCode()));
                    return;
                }

                // take the item first: Cosmic charged the mesos (and the ticket) even when the item step then failed
                int res = addPackageItemFromInventory(packageId, c, invTypeId, itemPos, amount);
                if (res != 0) {
                    removePackageFromDB(packageId);
                    c.sendPacket(PacketCreator.sendDueyMSG(res > 0 ? DueyProcessor.Actions.TOCLIENT_SEND_ENABLE_ACTIONS.getCode()
                            : DueyProcessor.Actions.TOCLIENT_SEND_INCORRECT_REQUEST.getCode()));
                    return;
                }

                if (quick) {
                    InventoryManipulator.removeById(c, InventoryType.CASH, ItemId.QUICK_DELIVERY_TICKET, (short) 1, false, false);
                }
                c.getPlayer().gainMeso((int) -finalcost, false);
                c.sendPacket(PacketCreator.sendDueyMSG(DueyProcessor.Actions.TOCLIENT_SEND_SUCCESSFULLY_SENT.getCode()));
                log.info("Duey: {} sent {} mesos{} to {} (package {}, fee {})", c.getPlayer().getName(), sendMesos,
                        invTypeId > 0 ? " and " + amount + " item(s) from inventory " + invTypeId : "", recipient, packageId, fee);

                Client rClient = null;
                int channel = c.getWorldServer().find(recipient);
                if (channel > -1) {
                    Channel rcserv = c.getWorldServer().getChannel(channel);
                    if (rcserv != null) {
                        Character rChr = rcserv.getPlayerStorage().getCharacterByName(recipient);
                        if (rChr != null) {
                            rClient = rChr.getClient();
                        }
                    }
                }

                if (rClient != null && rClient.isLoggedIn() && !rClient.getPlayer().isAwayFromWorld()) {
                    showDueyNotification(rClient, rClient.getPlayer());
                }
            } finally {
                c.releaseClient();
            }
        }
    }

    public static void dueyRemovePackage(Client c, int packageid, boolean playerRemove) {
        if (c.tryacquireClient()) {
            try {
                if (!removeOwnPackageFromDB(packageid, c.getPlayer().getId())) {
                    log.warn("Chr {} tried to remove duey package {} that is not theirs", c.getPlayer().getName(), packageid);
                    c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_UNKNOWN_ERROR.getCode()));
                    return;
                }
                c.sendPacket(PacketCreator.removeItemFromDuey(playerRemove, packageid));
            } finally {
                c.releaseClient();
            }
        }
    }

    public static void dueyClaimPackage(Client c, int packageId) {
        if (c.tryacquireClient()) {
            try {
                try {
                    DueyPackage dp = null;
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement ps = con.prepareStatement("SELECT * FROM dueypackages dp WHERE PackageId = ? AND ReceiverId = ?")) {
                        ps.setInt(1, packageId);
                        ps.setInt(2, c.getPlayer().getId()); // Cosmic let anyone claim any package id

                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                dp = getPackageFromDB(rs);
                            }
                        }
                    }

                    if (dp == null) {
                        c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_UNKNOWN_ERROR.getCode()));
                        log.warn("Chr {} tried to receive package from duey with id {}", c.getPlayer().getName(), packageId);
                        return;
                    }

                    if (dp.isDeliveringTime()) {
                        c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_UNKNOWN_ERROR.getCode()));
                        return;
                    }

                    if (!c.getPlayer().canHoldMeso(dp.getMesos())) {
                        c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_UNKNOWN_ERROR.getCode()));
                        return;
                    }

                    Item dpItem = dp.getItem();
                    if (dpItem != null && !InventoryManipulator.checkSpace(c, dpItem.getItemId(), dpItem.getQuantity(), dpItem.getOwner())) {
                        int itemid = dpItem.getItemId();
                        if (ItemInformationProvider.getInstance().isPickupRestricted(itemid) && c.getPlayer().getInventory(ItemConstants.getInventoryType(itemid)).findById(itemid) != null) {
                            c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_RECEIVER_WITH_UNIQUE.getCode()));
                        } else {
                            c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_NO_FREE_SLOTS.getCode()));
                        }
                        return;
                    }

                    // delete before handing out: only the call that deletes the row pays out, so a package cannot be
                    // claimed twice (Cosmic paid first and removed after, through a nested lock that can fail)
                    if (!removeOwnPackageFromDB(packageId, c.getPlayer().getId())) {
                        c.sendPacket(PacketCreator.sendDueyMSG(Actions.TOCLIENT_RECV_UNKNOWN_ERROR.getCode()));
                        return;
                    }

                    if (dpItem != null) {
                        InventoryManipulator.addFromDrop(c, dpItem, false);
                    }
                    c.getPlayer().gainMeso(dp.getMesos(), false);
                    c.sendPacket(PacketCreator.removeItemFromDuey(false, packageId));
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            } finally {
                c.releaseClient();
            }
        }
    }

    public static void dueySendTalk(Client c, boolean quickDelivery) {
        if (c.tryacquireClient()) {
            try {
                long timeNow = System.currentTimeMillis();
                if (timeNow - c.getPlayer().getNpcCooldown() < YamlConfig.config.server.BLOCK_NPC_RACE_CONDT) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }
                c.getPlayer().setNpcCooldown(timeNow);

                if (quickDelivery) {
                    c.sendPacket(PacketCreator.sendDuey(0x1A, null));
                } else {
                    c.sendPacket(PacketCreator.sendDuey(0x8, loadPackages(c.getPlayer())));
                }
            } finally {
                c.releaseClient();
            }
        }
    }

    public static void dueyCreatePackage(Item item, int mesos, String sender, int recipientCid) {
        if (soloMapling.ArtificialPlayer.BotHelpers.isArtificial(recipientCid)) {
            return; // bots never receive Duey parcels (e.g. a wedding invitation to a bot guest)
        }
        int packageId = createPackage(mesos, null, sender, recipientCid, false);
        if (packageId != -1) {
            insertPackageItem(packageId, item);
        }
    }

    public static void runDueyExpireSchedule() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DATE, -30);
        final Timestamp ts = new Timestamp(c.getTime().getTime());

        try (Connection con = DatabaseConnection.getConnection()) {
            List<Integer> toRemove = new LinkedList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT `PackageId` FROM dueypackages WHERE `TimeStamp` < ?")) {
                ps.setTimestamp(1, ts);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        toRemove.add(rs.getInt("PackageId"));
                    }
                }
            }

            for (Integer pid : toRemove) {
                removePackageFromDB(pid);
            }

            try (PreparedStatement ps = con.prepareStatement("DELETE FROM dueypackages WHERE `TimeStamp` < ?")) {
                ps.setTimestamp(1, ts);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
