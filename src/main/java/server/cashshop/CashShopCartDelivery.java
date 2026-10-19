package server.cashshop;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ModifyInventory;
import tools.DatabaseConnection;
import tools.PacketCreator;
import org.slf4j.LoggerFactory;
import java.util.*;

/** Locks inventory during a cart and restores original objects/quantities on failed delivery.
 * Call while holding the character monitor, as saveCharToDB(boolean) does. */
final class CashShopCartDelivery implements AutoCloseable {
    private record Original(Item item, short position, short quantity) {}
    private final Client client;
    private final Map<Inventory, List<Original>> originals = new LinkedHashMap<>();
    private final List<Integer> pets = new ArrayList<>();
    private boolean dirty, committed;

    CashShopCartDelivery(Client client, Character chr) {
        this.client = client;
        try {
            for (InventoryType type : List.of(InventoryType.EQUIPPED, InventoryType.EQUIP,
                    InventoryType.USE, InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH)) {
                Inventory inventory = chr.getInventory(type);
                inventory.lockInventory();
                originals.put(inventory, new ArrayList<>());
                for (Item item : inventory.list()) {
                    originals.get(inventory).add(new Original(item, item.getPosition(), item.getQuantity()));
                }
            }
        } catch (RuntimeException failure) {
            unlock();
            throw failure;
        }
    }

    void createdPet(int id) { pets.add(id); }
    void deliveryStarted() { dirty = true; }
    void commit() { committed = true; }

    private void unlock() {
        var inventories = new ArrayList<>(originals.keySet());
        Collections.reverse(inventories);
        for (Inventory inventory : inventories) inventory.unlockInventory();
    }

    @Override public void close() {
        try {
            if (!committed) {
                if (dirty) restore();
                removeUnclaimedPets();
            }
        } finally {
            unlock();
        }
    }

    private void restore() {
        var packets = new ArrayList<net.packet.Packet>();
        for (var entry : originals.entrySet()) {
            Inventory inventory = entry.getKey();
            if (inventory.getType() == InventoryType.EQUIPPED) continue;
            var changes = new ArrayList<ModifyInventory>();
            for (Item current : new ArrayList<>(inventory.list())) {
                changes.add(new ModifyInventory(3, current));
                inventory.removeSlot(current.getPosition());
            }
            for (Original original : entry.getValue()) {
                original.item().setPosition(original.position());
                original.item().setQuantity(original.quantity());
                inventory.addItemFromDB(original.item());
                changes.add(new ModifyInventory(0, original.item()));
            }
            // Inventory-operation counts are one byte. Bound each correction packet.
            for (int i = 0; i < changes.size(); i += 100) {
                packets.add(PacketCreator.modifyInventory(true, changes.subList(i, Math.min(i + 100, changes.size()))));
            }
        }
        for (var packet : packets) {
            try { client.sendPacket(packet); }
            catch (RuntimeException disconnected) {
                LoggerFactory.getLogger(getClass()).warn("Cash shop rollback restored inventory; client refresh failed", disconnected);
                break;
            }
        }
    }

    private void removeUnclaimedPets() {
        if (pets.isEmpty()) return;
        try (var connection = DatabaseConnection.getConnection();
             var statement = connection.prepareStatement("DELETE FROM pets WHERE petid=?")) {
            for (int id : pets) { statement.setInt(1, id); statement.addBatch(); }
            statement.executeBatch();
        } catch (java.sql.SQLException failure) {
            LoggerFactory.getLogger(getClass()).error("Failed to remove unused cash-shop pet rows after rollback", failure);
        }
    }
}
