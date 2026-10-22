package soloMapling.ArtificialPlayer.BotCoupleSystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotCustomization;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.5.0: clothes a bot wears for a while over its own - the wedding tuxedo or dress, the partner's hat on a matching
 * day. The bot's own item is kept (the object itself, stats and all) and goes back on with {@link #giveBack}; until
 * then the bot's store write sees the bot's own item in that slot, never the loan ({@link #persisted}), so a loan can
 * never become the bot's for good - not across a logout, a restart or a crash.
 *
 * <p>A loan belongs to one live character object: a bot that logged out and in again is a new object, its loans are
 * stale and ignored (its row already holds its own clothes).
 */
public final class OutfitLoans {

    /** One borrowed slot: the live bot wearing it, what it wore before (null = nothing), and the loaned item id. */
    record Loan(Character holder, Item own, int lentId) {
    }

    private static final Map<Long, Loan> loans = new ConcurrentHashMap<>();

    /** How an item goes on (a seam: tests have no item data to build a clean equip from). */
    interface Equipper {
        void equip(Character bot, int itemId, short slot);
    }

    private static final Equipper STOCK = BotCustomization::EquipItem;
    private static volatile Equipper equipper = STOCK;

    /** Puts a fresh copy of the item on in that slot, replacing what is there. */
    static void equip(Character bot, int itemId, short slot) {
        equipper.equip(bot, itemId, slot);
    }

    static void useEquipperForTest(Equipper test) {
        equipper = test != null ? test : STOCK;
    }

    private OutfitLoans() {
    }

    private static long key(int charId, short slot) {
        return ((long) charId << 16) ^ (slot & 0xffff);
    }

    /**
     * The bot wears {@code itemId} in {@code slot} over its own. Its own item is remembered only the first time (a
     * second loan on the same slot keeps the first one's original); an item listed in {@code neverOwn} is never taken
     * for its own (a wedding outfit left on by an older build is dropped, not kept).
     */
    public static synchronized void lend(Character bot, short slot, int itemId, int... neverOwn) {
        long k = key(bot.getId(), slot);
        Loan loan = loans.get(k);
        if (loan != null && loan.holder() != bot) {
            loans.remove(k); // stale: a session that ended
            loan = null;
        }
        if (loan == null) {
            Item worn = bot.getInventory(InventoryType.EQUIPPED).getItem(slot);
            if (worn != null) {
                for (int id : neverOwn) {
                    if (worn.getItemId() == id) {
                        worn = null;
                        break;
                    }
                }
            }
            loans.put(k, new Loan(bot, worn, itemId));
        } else {
            loans.put(k, new Loan(bot, loan.own(), itemId));
        }
        equip(bot, itemId, slot);
    }

    /** Puts the bot's own item back (or leaves the slot empty when it had none). False when nothing was lent. */
    public static synchronized boolean giveBack(Character bot, short slot) {
        long k = key(bot.getId(), slot);
        Loan loan = loans.get(k);
        if (loan == null || loan.holder() != bot) {
            if (loan != null) {
                loans.remove(k);
            }
            return false;
        }
        loans.remove(k);
        bot.getInventory(InventoryType.EQUIPPED).removeSlot(slot);
        if (loan.own() != null) {
            loan.own().setPosition(slot);
            bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(loan.own());
        }
        bot.equipChanged();
        return true;
    }

    /** Whether this live bot wears a loan in that slot. */
    public static boolean lent(Character bot, short slot) {
        Loan loan = loans.get(key(bot.getId(), slot));
        return loan != null && loan.holder() == bot;
    }

    /**
     * What the bot store writes for this equipped item: the item itself, unless it is a loan - then the bot's own item
     * (null: the slot is written empty).
     */
    public static Item persisted(Character bot, Item equipped) {
        if (loans.isEmpty() || equipped == null) {
            return equipped;
        }
        Loan loan = loans.get(key(bot.getId(), equipped.getPosition()));
        if (loan == null || loan.holder() != bot || loan.lentId() != equipped.getItemId()) {
            return equipped;
        }
        return loan.own();
    }

    /** Drops the loans of characters that are no longer the live bot (a session that ended). */
    static void forgetStale(java.util.function.IntFunction<Character> live) {
        loans.values().removeIf(l -> live.apply(l.holder().getId()) != l.holder());
    }

    static void clearForTest() {
        loans.clear();
    }
}
