package soloMapling.ArtificialPlayer.BotGearSystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;

/*
 * Ticket 162 part 1: the cheap pass for offscreen bots. An offscreen bot loots in bursts (the offscreen accrual credits
 * several drops at once) and every equip it takes asks for a gear review. When nothing the review reads has changed
 * since the last review that left the bot as it was - its level, job, base stats, fame and every equip it wears or
 * carries, by identity and stats - the review is skipped.
 *
 * The signature is read straight off the inventories slot by slot (no list copy) and the table is a primitive
 * open-addressing map, so a skipped review allocates nothing.
 */
final class GearMemo {

    private GearMemo() {}

    private static final int CAPACITY = 1 << 14;   // 16,384 slots: room for every population bot at a load under 1/4
    private static final int[] keys = new int[CAPACITY];
    private static final long[] values = new long[CAPACITY];
    private static final boolean[] used = new boolean[CAPACITY];
    private static int size;

    /* What a gear review reads from the bot, as one number. */
    static long signature(Character bot) {
        long h = 1125899906842597L;
        h = mix(h, bot.getLevel());
        h = mix(h, bot.getJob().getId());
        h = mix(h, bot.getStr());
        h = mix(h, bot.getDex());
        h = mix(h, bot.getInt());
        h = mix(h, bot.getLuk());
        h = mix(h, bot.getFame());
        Inventory body = bot.getInventory(InventoryType.EQUIPPED);
        for (short slot : BotGearPlanner.SLOTS) {
            h = mixItem(h, slot, body.getItem(slot));
        }
        Inventory bag = bot.getInventory(InventoryType.EQUIP);
        int limit = Byte.toUnsignedInt(bag.getSlotLimit());
        for (short slot = 1; slot <= limit; slot++) {
            Item item = bag.getItem(slot);
            if (item != null) {
                h = mixItem(h, slot, item);
            }
        }
        return h;
    }

    private static long mixItem(long h, short slot, Item item) {
        if (item == null) {
            return mix(h, slot);
        }
        h = mix(h, slot);
        h = mix(h, System.identityHashCode(item));
        h = mix(h, item.getItemId());
        if (item instanceof Equip e) {
            h = mix(h, e.getStr() | e.getDex() << 16);
            h = mix(h, e.getInt() | e.getLuk() << 16);
            h = mix(h, e.getWatk() | e.getMatk() << 16);
            h = mix(h, e.getAcc() | e.getUpgradeSlots() << 16);
        }
        return h;
    }

    private static long mix(long h, int v) {
        h ^= v;
        h *= 0x100000001B3L;
        return h ^ (h >>> 29);
    }

    /* True when the bot's last review saw exactly this signature. */
    static synchronized boolean unchanged(int botId, long signature) {
        int i = find(botId);
        return used[i] && values[i] == signature;
    }

    static synchronized void remember(int botId, long signature) {
        int i = find(botId);
        if (!used[i]) {
            if (size >= CAPACITY / 2) {
                clear();   // far more bots than ever log in at once: start over rather than grow
                i = find(botId);
            }
            used[i] = true;
            keys[i] = botId;
            size++;
        }
        values[i] = signature;
    }

    static synchronized void forget(int botId) {
        int i = find(botId);
        if (used[i]) {
            // Re-insert the rest of the run so lookups past the hole still find their key.
            used[i] = false;
            size--;
            int j = (i + 1) & (CAPACITY - 1);
            while (used[j]) {
                int k = keys[j];
                long v = values[j];
                used[j] = false;
                size--;
                remember(k, v);
                j = (j + 1) & (CAPACITY - 1);
            }
        }
    }

    static synchronized void clear() {
        java.util.Arrays.fill(used, false);
        size = 0;
        BotGear.clearPlanCache();
    }

    private static int find(int botId) {
        int i = (Integer.hashCode(botId) * 0x9E3779B9) >>> 18 & (CAPACITY - 1);
        while (used[i] && keys[i] != botId) {
            i = (i + 1) & (CAPACITY - 1);
        }
        return i;
    }
}
