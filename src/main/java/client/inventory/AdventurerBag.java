package client.inventory;

import client.Character;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The Adventurer's Bag (@bp, NPC 3004039, table {@code infinitystorage}): a per-character count of USE/ETC/SETUP
 * items, keyed by item id only.
 *
 * <p>1.6.1 hardening. The bag keeps no flags, expiry, owner tag or pet id, so only "plain" stacks may go in: before
 * this a bound, karma'd, GM-made (untradeable/account/sandbox), owner-tagged or expiring stack went in through the
 * script ({@code gainItem(id, -n)} took any stack) or auto-pickup and came back out as a fresh, permanent, tradeable
 * item. Quest, party-quest and cash items are refused too (a PQ pass in the bag escaped the event's clean-up).
 *
 * <p>Deposit and withdraw move the bag count and the inventory together under the bag's lock (and the tab's), and
 * the character save writes the inventory and the bag from inside that same lock and the same transaction, so a
 * crash can't persist one side of a move without the other. The old code set absolute amounts from a number the
 * script read a dialog step earlier, credited the bag even when {@code gainItem} refused to hand the item out, and
 * flushed on its own connection then cleared the cache, losing (or, for a withdraw racing the flush, duplicating)
 * whatever changed mid-flush.
 */
public final class AdventurerBag {

    /** Per item id; far above anything a player can gather, and keeps every sum inside an int. */
    public static final int MAX_PER_ITEM = 100_000_000;

    public enum Result {
        OK, BAD_AMOUNT, NOT_ALLOWED, NOT_ENOUGH, BAG_FULL, NO_SPACE
    }

    /** The WZ facts the bag needs; tests pass their own. */
    public interface Rules {
        short slotMax(Character chr, int itemId);

        /** Quest, party-quest and cash items never go in. */
        boolean refused(int itemId);

        /** WZ "only": at most one in the inventory. */
        boolean oneOfAKind(int itemId);

        /**
         * Whether every stack of this item id carries ACCOUNT_SHARING by itself (WZ account-bound ids). Only for those
         * is the flag put back on withdraw, so only those stacks are plain; for any other id it is real information
         * the bag cannot give back.
         */
        default boolean accountBound(int itemId) {
            return false;
        }

        /** Gives a freshly made withdrawn stack the flags its item id always carries (account-bound ids). */
        void stampNew(Item fresh);

        /** Tells the client about a changed slot (mode 0 add, 1 quantity, 3 remove). */
        void announce(Character chr, int mode, Item item);
    }

    public static Rules wzRules() {
        return new Rules() {
            @Override
            public short slotMax(Character chr, int itemId) {
                return ItemInformationProvider.getInstance().getSlotMax(chr, itemId);
            }

            @Override
            public boolean refused(int itemId) {
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                return ii.isQuestItem(itemId) || ii.isPartyQuestItem(itemId) || ii.isCash(itemId);
            }

            @Override
            public boolean oneOfAKind(int itemId) {
                return ItemInformationProvider.getInstance().isPickupRestricted(itemId);
            }

            @Override
            public boolean accountBound(int itemId) {
                return ItemInformationProvider.getInstance().isAccountRestricted(itemId);
            }

            @Override
            public void stampNew(Item fresh) {
                fresh.setFlag((short) 0); // setFlag adds ACCOUNT_SHARING for account-bound ids, as a drop gets
            }

            @Override
            public void announce(Character chr, int mode, Item item) {
                chr.sendPacket(PacketCreator.modifyInventory(true, Collections.singletonList(new ModifyInventory(mode, item))));
            }
        };
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Supplier<Map<Integer, Integer>> loader;
    private final Map<Integer, Integer> amounts = new HashMap<>();
    private boolean loaded;

    /** {@code loader} reads the saved rows; it must throw rather than return a partial or empty map on failure. */
    public AdventurerBag(Supplier<Map<Integer, Integer>> loader) {
        this.loader = loader;
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }

    private void ensureLoaded() {
        if (!loaded) {
            Map<Integer, Integer> rows = loader.get();
            if (rows == null) {
                throw new IllegalStateException("Adventurer's Bag could not be loaded");
            }
            amounts.clear();
            amounts.putAll(rows);
            loaded = true;
        }
    }

    public static boolean isBagType(int itemId) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        return (type == InventoryType.USE || type == InventoryType.ETC || type == InventoryType.SETUP)
                && !ItemConstants.isRechargeable(itemId);
    }

    /**
     * A stack the bag can hold without losing anything: no flag, no expiry, no owner tag, not a pet.
     * ACCOUNT_SHARING alone counts only for an id that is account-bound anyway ({@code Item.setFlag} stamps it on every
     * stack of such an id, some ores and crystals, and {@link Rules#stampNew} puts it back on withdraw). On any other
     * id it is a real account-share mark the bag could not give back, so that stack stays in the inventory.
     */
    public static boolean isPlain(Item item, Rules rules) {
        int flag = item.getFlag();
        if ((flag & ~ItemConstants.ACCOUNT_SHARING) != 0 || item.getExpiration() > 0 || item.getPetId() >= 0
                || (item.getOwner() != null && !item.getOwner().isEmpty())) {
            return false;
        }
        return (flag & ItemConstants.ACCOUNT_SHARING) == 0 || rules.accountBound(item.getItemId());
    }

    public int amount(int itemId) {
        lock.lock();
        try {
            ensureLoaded();
            return amounts.getOrDefault(itemId, 0);
        } finally {
            lock.unlock();
        }
    }

    /** Stored entries with a non-zero amount, by item id. */
    public List<Map.Entry<Integer, Integer>> list() {
        lock.lock();
        try {
            ensureLoaded();
            List<Map.Entry<Integer, Integer>> ret = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : amounts.entrySet()) {
                if (e.getValue() != 0) {
                    ret.add(Map.entry(e.getKey(), e.getValue()));
                }
            }
            ret.sort(Comparator.comparingInt(Map.Entry::getKey));
            return ret;
        } finally {
            lock.unlock();
        }
    }

    /** Bag-only credit (no inventory side); false when the amount is bad or the item's cap would be passed. */
    public boolean add(int itemId, int qty) {
        lock.lock();
        try {
            ensureLoaded();
            int cur = amounts.getOrDefault(itemId, 0);
            if (qty <= 0 || (long) cur + qty > MAX_PER_ITEM) {
                return false;
            }
            amounts.put(itemId, cur + qty);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Bag-only debit (no inventory side); false when the amount is bad or more than is stored. */
    public boolean take(int itemId, int qty) {
        lock.lock();
        try {
            ensureLoaded();
            int cur = amounts.getOrDefault(itemId, 0);
            if (qty <= 0 || qty > cur) {
                return false;
            }
            amounts.put(itemId, cur - qty);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** How many of this item the player could deposit right now: plain stacks only. */
    public static int depositable(Character chr, int itemId) {
        return depositable(chr, itemId, wzRules());
    }

    public static int depositable(Character chr, int itemId, Rules rules) {
        if (!isBagType(itemId)) {
            return 0;
        }
        int n = 0;
        for (Item it : chr.getInventory(ItemConstants.getInventoryType(itemId)).listById(itemId)) {
            if (isPlain(it, rules)) {
                n += it.getQuantity();
            }
        }
        return n;
    }

    /** Moves {@code qty} of the item's plain stacks from the inventory into the bag, all or nothing. */
    public Result deposit(Character chr, int itemId, int qty, Rules rules) {
        if (qty <= 0 || qty > MAX_PER_ITEM) {
            return Result.BAD_AMOUNT;
        }
        if (!isBagType(itemId) || rules.refused(itemId) || rules.oneOfAKind(itemId)) {
            return Result.NOT_ALLOWED;
        }
        Inventory inv = chr.getInventory(ItemConstants.getInventoryType(itemId));
        lock.lock();
        try {
            ensureLoaded();
            inv.lockInventory();
            try {
                List<Item> plain = new ArrayList<>();
                int plainCount = 0;
                int anyCount = 0;
                for (Item it : inv.listById(itemId)) {
                    anyCount += it.getQuantity();
                    if (isPlain(it, rules)) {
                        plain.add(it);
                        plainCount += it.getQuantity();
                    }
                }
                if (qty > plainCount) {
                    return anyCount >= qty ? Result.NOT_ALLOWED : Result.NOT_ENOUGH;
                }
                int cur = amounts.getOrDefault(itemId, 0);
                if ((long) cur + qty > MAX_PER_ITEM) {
                    return Result.BAG_FULL;
                }

                int left = qty;
                for (Item it : plain) {
                    if (left == 0) {
                        break;
                    }
                    short take = (short) Math.min(left, it.getQuantity());
                    inv.removeItem(it.getPosition(), take, false);
                    left -= take;
                    rules.announce(chr, it.getQuantity() == 0 ? 3 : 1, it);
                }
                amounts.put(itemId, cur + qty);
                return Result.OK;
            } finally {
                inv.unlockInventory();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Moves {@code qty} out of the bag as plain stacks (split at the slot max), all or nothing. */
    public Result withdraw(Character chr, int itemId, int qty, Rules rules) {
        if (qty <= 0 || qty > MAX_PER_ITEM) {
            return Result.BAD_AMOUNT;
        }
        if (!isBagType(itemId)) {
            return Result.NOT_ALLOWED;
        }
        Inventory inv = chr.getInventory(ItemConstants.getInventoryType(itemId));
        lock.lock();
        try {
            ensureLoaded();
            int cur = amounts.getOrDefault(itemId, 0);
            if (qty > cur) {
                return Result.NOT_ENOUGH;
            }
            inv.lockInventory();
            try {
                if (rules.oneOfAKind(itemId) && (qty > 1 || !inv.listById(itemId).isEmpty())) {
                    return Result.NOT_ALLOWED;
                }
                if (qty > room(inv, itemId, rules.slotMax(chr, itemId), rules)) {
                    return Result.NO_SPACE;
                }
                amounts.put(itemId, cur - qty);
                int left = addPlain(chr, inv, itemId, qty, rules.slotMax(chr, itemId), rules);
                if (left > 0) { // can't happen under the tab's lock after room(); never lose the rest
                    amounts.put(itemId, amounts.getOrDefault(itemId, 0) + left);
                    return Result.NO_SPACE;
                }
                return Result.OK;
            } finally {
                inv.unlockInventory();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Withdraws as much as fits, up to {@code max}; returns the amount moved. */
    public int withdrawUpTo(Character chr, int itemId, int max, Rules rules) {
        if (!isBagType(itemId) || max <= 0) {
            return 0;
        }
        Inventory inv = chr.getInventory(ItemConstants.getInventoryType(itemId));
        lock.lock();
        try {
            ensureLoaded();
            inv.lockInventory();
            try {
                long fits = room(inv, itemId, rules.slotMax(chr, itemId), rules);
                int qty = (int) Math.min(Math.min(max, amounts.getOrDefault(itemId, 0)), fits);
                if (qty <= 0) {
                    return 0;
                }
                return withdraw(chr, itemId, qty, rules) == Result.OK ? qty : 0;
            } finally {
                inv.unlockInventory();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Auto-pickup ({@code @backpack on}): stores a dropped plain stack. False means it must go to the inventory
     * instead (flagged, expiring, owned, refused, or the bag's cap).
     */
    public boolean storePickup(Item drop, Rules rules) {
        int itemId = drop.getItemId();
        if (!isBagType(itemId) || !isPlain(drop, rules) || drop.getQuantity() <= 0
                || rules.refused(itemId) || rules.oneOfAKind(itemId)) {
            return false;
        }
        return add(itemId, drop.getQuantity());
    }

    private static long room(Inventory inv, int itemId, short slotMax, Rules rules) {
        if (slotMax <= 0) {
            return 0;
        }
        long room = 0;
        for (Item it : inv.listById(itemId)) {
            if (isPlain(it, rules) && it.getQuantity() < slotMax) {
                room += slotMax - it.getQuantity();
            }
        }
        return room + (long) inv.getNumFreeSlot() * slotMax;
    }

    /** Tops up plain stacks, then opens new ones; returns what didn't fit. Caller holds the tab's lock. */
    private static int addPlain(Character chr, Inventory inv, int itemId, int qty, short slotMax, Rules rules) {
        int left = qty;
        for (Item it : inv.listById(itemId)) {
            if (left == 0) {
                break;
            }
            if (isPlain(it, rules) && it.getQuantity() < slotMax) {
                int put = Math.min(left, slotMax - it.getQuantity());
                it.setQuantity((short) (it.getQuantity() + put));
                left -= put;
                rules.announce(chr, 1, it);
            }
        }
        while (left > 0) {
            short n = (short) Math.min(left, slotMax);
            Item nItem = new Item(itemId, (short) 0, n);
            rules.stampNew(nItem);
            if (inv.addItem(nItem) == -1) {
                break;
            }
            left -= n;
            rules.announce(chr, 0, nItem);
        }
        return left;
    }

    /**
     * Writes every loaded amount on {@code con} (the character save's transaction). The caller holds {@link #lock()}
     * across this and the inventory save, so both describe the same moment. Nothing to do if never loaded.
     */
    public void save(Connection con, int charId) throws SQLException {
        lock.lock();
        try {
            if (!loaded || amounts.isEmpty()) {
                return;
            }
            // Look the rows up first: an UPDATE's row count can't be trusted to mean "exists" (useAffectedRows,
            // batch rewriting), and a wrong guess would insert a second row for the same item.
            java.util.Set<Integer> existing = new java.util.HashSet<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT itemid FROM infinitystorage WHERE charid = ?")) {
                ps.setInt(1, charId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existing.add(rs.getInt(1));
                    }
                }
            }
            try (PreparedStatement upd = con.prepareStatement("UPDATE infinitystorage SET amount = ? WHERE charid = ? AND itemid = ?");
                 PreparedStatement ins = con.prepareStatement("INSERT INTO infinitystorage (charid, itemid, amount, type, position) VALUES (?, ?, ?, NULL, NULL)")) {
                for (Map.Entry<Integer, Integer> e : amounts.entrySet()) {
                    if (existing.contains(e.getKey())) {
                        upd.setInt(1, e.getValue());
                        upd.setInt(2, charId);
                        upd.setInt(3, e.getKey());
                        upd.executeUpdate();
                    } else if (e.getValue() != 0) {
                        ins.setInt(1, charId);
                        ins.setInt(2, e.getKey());
                        ins.setInt(3, e.getValue());
                        ins.executeUpdate();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
