package soloMapling.Economy.exchange;

import java.util.function.Supplier;

/**
 * Ticket 113 round 2 (owner 2026-10-28: "placing in game too"): the wallet of a logged-in player, as the game holds it
 * in memory: the live character's mesos and its account's NX credit (the CashShop object). The game saves both as
 * absolute values, so the exchange may only change them like this, and always the same way:
 * <ol>
 *   <li>{@link #locked}: hold the character's save lock. {@code Character.saveCharToDB} is synchronized on the
 *   character, and every save (autosave, channel change, logout) goes through it, so no save can run in between;</li>
 *   <li>{@link #usable}: still the live, logged-in character (not mid channel change or logout, not in the Cash Shop);</li>
 *   <li>{@link #saveNow}: save the character, so the database holds exactly what memory holds;</li>
 *   <li>debit: {@link #take} from memory first (exactly, or nothing), then write the ledger and the wallet's
 *   <b>delta</b> in one transaction; if the transaction fails, {@link #give} it back.
 *   Credit: write the transaction first, then {@link #give} in memory.</li>
 * </ol>
 * Why that is dupe-proof: the database row only ever changes by the same delta as memory, inside the lock, and in the
 * same transaction as the escrow or payout it moved to or from. A crash before the commit loses only memory (the
 * database still has the saved values and no escrow); a crash after it leaves the saved values minus the escrow plus
 * the escrow. A save afterwards writes memory, which already has the change. A channel change or logout that already
 * flipped {@code accounts.loggedin} makes the transaction refuse ({@link ExchangeStore.Failure#MOVED}); one that
 * comes later waits for the lock and then saves memory with the change.
 */
public interface LiveWallet {

    /** Finds the live wallet of an account: its logged-in (non-bot, non-alt) character, or null. */
    interface Finder {
        LiveWallet find(long account);

        Finder NONE = account -> null;
    }

    long account();

    long character();

    /** Runs {@code body} holding the character's save lock. */
    <T> T locked(Supplier<T> body);

    /** Under the lock: still the logged-in character on its channel, not changing channel, not in the Cash Shop. */
    boolean usable();

    /** Under the lock: saves the character (memory to database). False when the save could not run. */
    boolean saveNow();

    /** What memory holds now. For an item asset: the copies the exchange may take (tradeable, not cash, no expiry). */
    long have(Ledger.Asset asset);

    /**
     * Takes exactly {@code amount} from memory, or nothing (false). An item asset takes only tradeable copies
     * (no untradeable or lock flag, no expiry date, not a cash item).
     */
    boolean take(Ledger.Asset asset, long amount);

    /** Adds exactly {@code amount} in memory, or nothing (false: it would pass 2,147,483,647, or the bag is full). */
    boolean give(Ledger.Asset asset, long amount);

    /** How much of {@code want} memory can take now (the wallet cap; for an item, the free bag room). */
    default long room(Ledger.Asset asset, long want) {
        return Math.max(0, Math.min(want, Integer.MAX_VALUE - have(asset)));
    }

    /**
     * Writes the character's inventory as memory holds it, inside the store's transaction (item tickers). Null when
     * this wallet has no database inventory (tests: the memory store models the inventory itself).
     */
    default ExchangeStore.InventoryWriter inventoryWriter() {
        return null;
    }

    /** A line to the player (yellow text in game); nothing in tests. */
    default void tell(String line) {
    }
}
