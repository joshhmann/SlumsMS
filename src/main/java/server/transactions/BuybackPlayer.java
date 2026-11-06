package server.transactions;

import client.inventory.Item;

/** What the buyback needs from the character; the seam for fault injection. {@link CharacterBuybackPlayer} is the real one. */
interface BuybackPlayer {
    int getMeso();

    void gainMeso(int amount);

    /** Adds the item to the inventory and returns the quantity actually added (less than item's quantity = failed). */
    int addItem(Item item);

    /** Takes back {@code quantity} of an earlier {@link #addItem}. */
    void undoAdd(Item item, int quantity);

    /** Persists the character and marks the journal row APPLIED in the same commit. True only if it committed. */
    boolean save(long journalId);

    /** Monitor that excludes every other save of this character (Character.saveCharToDB is synchronized on it). */
    Object saveLock();
}
