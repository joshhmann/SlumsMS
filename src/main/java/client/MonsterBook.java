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
package client;

import tools.DatabaseConnection;
import tools.PacketCreator;

//Custom for monsterbook
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class MonsterBook {
    private int specialCard = 0;
    private int normalCard = 0;
    private int bookLevel = 1;
    private final Map<Integer, Integer> cards = new LinkedHashMap<>();
    private final Lock lock = new ReentrantLock();

    public Set<Entry<Integer, Integer>> getCardSet() {
        lock.lock();
        try {
            return new HashSet<>(cards.entrySet());
        } finally {
            lock.unlock();
        }
    }

    public void addCard(final Client c, final int cardid) {
        c.getPlayer().getMap().broadcastMessage(c.getPlayer(), PacketCreator.showForeignCardEffect(c.getPlayer().getId()), false);
        int MobItem = 1113232;
        Inventory equipped = c.getPlayer().getInventory(InventoryType.EQUIPPED);
        Equip eq = (Equip) equipped.findById(MobItem);

        if (eq == null) {
            // If not found in EQUIPPED, search in EQUIP inventory
            Inventory equipInventory = c.getPlayer().getInventory(InventoryType.EQUIP);
            eq = (Equip) equipInventory.findById(MobItem);
        }

        Integer qty;
        lock.lock();
        try {
            qty = cards.get(cardid);

            if (qty != null) {
                if (qty < 5) {
                    cards.put(cardid, qty + 1);
                }
            } else {
                cards.put(cardid, 1);
                qty = 0;

                if (cardid / 1000 >= 2388) {
                    specialCard++;
                } else {
                    normalCard++;
                }
            }
        } finally {
            lock.unlock();
        }

        if (qty < 5) {
            if (qty == 0) {     // leveling system only accounts unique cards
                calculateLevel();
            }

            c.sendPacket(PacketCreator.addCard(false, cardid, qty + 1));
            c.sendPacket(PacketCreator.showGainCard());
            // Cards stop at five copies, so a card reaching five is the one moment its tier can advance.
            int completedTier = cards.get(cardid) == 5 ? tierOf(cardid) : 0;
            if (completedTier != 0) {
                if (c.getPlayer().saveTierProgress(completedTier, 1)) {
                    c.getPlayer().forceUpdateItem(eq);
                    c.getPlayer().applyLinkStatsBoost();
                    c.getPlayer().dropMessage("Tier " + completedTier + " card completed");
                } else {
                    c.getPlayer().dropMessage("Tier " + completedTier
                            + " card completed, but the progress could not be saved.");
                }
            }
        } else {
            c.sendPacket(PacketCreator.addCard(true, cardid, 5));
        }
    }

    private void calculateLevel() {
        lock.lock();
        try {
            int collectionExp = (normalCard + specialCard);

            int level = 0, expToNextlevel = 1;
            do {
                level++;
                expToNextlevel += level * 10;
            } while (collectionExp >= expToNextlevel);

            bookLevel = level;  // thanks IxianMace for noticing book level differing between book UI and character info UI
        } finally {
            lock.unlock();
        }
    }

    public int getBookLevel() {
        lock.lock();
        try {
            return bookLevel;
        } finally {
            lock.unlock();
        }
    }

    public Map<Integer, Integer> getCards() {
        lock.lock();
        try {
            return Collections.unmodifiableMap(cards);
        } finally {
            lock.unlock();
        }
    }

    public int getTotalCards() {
        lock.lock();
        try {
            return specialCard + normalCard;
        } finally {
            lock.unlock();
        }
    }

    public int getNormalCard() {
        lock.lock();
        try {
            return normalCard;
        } finally {
            lock.unlock();
        }
    }

    public int getSpecialCard() {
        lock.lock();
        try {
            return specialCard;
        } finally {
            lock.unlock();
        }
    }

    public void loadCards(final int accId) throws SQLException {
        lock.lock();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT cardid, level FROM monsterbook WHERE accountid = ? ORDER BY cardid ASC")) {
            ps.setInt(1, accId);

            try (ResultSet rs = ps.executeQuery()) {
                int cardid;
                int level;
                while (rs.next()) {
                    cardid = rs.getInt("cardid");
                    level = rs.getInt("level");
                    if (cardid / 1000 >= 2388) {
                        specialCard++;
                    } else {
                        normalCard++;
                    }
                    cards.put(cardid, level);
                }
            }
        } finally {
            lock.unlock();
        }

        calculateLevel();
    }

    public void saveCards(Connection con, int accId) throws SQLException {
        final String query = """
                INSERT INTO monsterbook (accountid, cardid, level)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE level = VALUES(level);
                """;
        try (final PreparedStatement ps = con.prepareStatement(query)) {
            for (Map.Entry<Integer, Integer> cardAndLevel : cards.entrySet()) {
                final int card = cardAndLevel.getKey();
                final int level = cardAndLevel.getValue();
                // insert
                ps.setInt(1, accId);
                ps.setInt(2, card);
                ps.setInt(3, level);

                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public static int[] getCardTierSize() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM monstercarddata GROUP BY floor(cardid / 1000);", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = ps.executeQuery()) {
            rs.last();
            int[] tierSizes = new int[rs.getRow()];
            rs.beforeFirst();

            while (rs.next()) {
                tierSizes[rs.getRow() - 1] = rs.getInt(1);
            }

            return tierSizes;
        } catch (SQLException e) {
            e.printStackTrace();
            return new int[0];
        }
    }

    //Tiers Boolean so you could actually remove the cards you dont need to be counted...
    public static boolean isTier1(int CardID) {
        return CardID >= 2380000 && CardID <= 2380019;
    }
    public static boolean isTier2(int CardID) {
        return CardID >= 2381000 && CardID <= 2381083;
    }
         
    public static boolean isTier3(int CardID) {
        return CardID >= 2382000 && CardID <= 2382096;
    }
    public static boolean isTier4(int CardID) {
        return CardID >= 2383000 && CardID <= 2383059;
    }
    public static boolean isTier5(int CardID) {
        return CardID >= 2384000 && CardID <= 2384040;
    }
    
    public static boolean isTier6(int CardID) {
        return CardID >= 2385000 && CardID <= 2385025;
    }
    public static boolean isTier7(int CardID) {
        return CardID >= 2386000 && CardID <= 2386524;
    }
    public static boolean isTier8(int CardID) {
        return CardID >= 2387000 && CardID <= 2387600;
    }
    public static boolean isTier9(int CardID) {
        return CardID >= 2388000 && CardID <= 2388600;
    }

    /** The tier a card belongs to, 1..9, or 0 when it belongs to none. */
    public static int tierOf(int cardId) {
        if (isTier1(cardId)) {
            return 1;
        }
        if (isTier2(cardId)) {
            return 2;
        }
        if (isTier3(cardId)) {
            return 3;
        }
        if (isTier4(cardId)) {
            return 4;
        }
        if (isTier5(cardId)) {
            return 5;
        }
        if (isTier6(cardId)) {
            return 6;
        }
        if (isTier7(cardId)) {
            return 7;
        }
        if (isTier8(cardId)) {
            return 8;
        }
        if (isTier9(cardId)) {
            return 9;
        }
        return 0;
    }
}
