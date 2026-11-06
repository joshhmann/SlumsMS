package server.transactions;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;

/** {@link BuybackPlayer} on a real character. */
final class CharacterBuybackPlayer implements BuybackPlayer {
    private final Character player;

    CharacterBuybackPlayer(Character player) {
        this.player = player;
    }

    @Override
    public int getMeso() {
        return player.getMeso();
    }

    @Override
    public void gainMeso(int amount) {
        player.gainMeso(amount, true);
    }

    @Override
    public int addItem(Item item) {
        short before = item.getQuantity();
        boolean ok = InventoryManipulator.add(player.getClient(), item, false);
        // A failed stack add leaves the unplaced remainder in item's quantity.
        return ok ? before : before - item.getQuantity();
    }

    @Override
    public void undoAdd(Item item, int quantity) {
        if (quantity <= 0) {
            return;
        }
        InventoryType type = item.getInventoryType();
        if (type == InventoryType.EQUIP) {
            // add() put this very object in its slot
            InventoryManipulator.removeFromSlot(player.getClient(), type, item.getPosition(), (short) 1, false, false);
        } else {
            InventoryManipulator.removeById(player.getClient(), type, item.getItemId(), quantity, false, false);
        }
    }

    @Override
    public boolean save(long journalId) {
        return player.saveCharToDB(true, con -> OperationJournal.markApplied(con, journalId));
    }

    @Override
    public Object saveLock() {
        return player;
    }
}
