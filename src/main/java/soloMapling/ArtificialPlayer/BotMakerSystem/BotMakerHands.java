package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.MakerService;
import constants.inventory.ItemConstants;
import net.packet.Packet;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.util.ArrayList;
import java.util.List;

/*
 * Ticket 112: a population bot's hands for the shared Maker path (MakerService.Hands). Population bots share one
 * headless client with no player on it, so every item and meso a Maker operation takes or gives goes through the bot
 * ledger (BotHoldings), the same accounting path a bot's loot, sales and scrolls use. Packets go nowhere. Owned alts
 * do not use this: each alt has its own client with the alt attached, so it uses the player's hands.
 */
final class BotMakerHands implements MakerService.Hands {

    private final Character bot;

    BotMakerHands(Character bot) {
        this.bot = bot;
    }

    @Override
    public Character chr() {
        return bot;
    }

    @Override
    public int count(int itemId) {
        return bot.getInventory(ItemConstants.getInventoryType(itemId)).countById(itemId);
    }

    @Override
    public boolean canHold(List<Integer> addIds, List<Integer> addQty, List<Integer> removeIds, List<Integer> removeQty) {
        // Checked one item at a time with nothing removed first: stricter than the player's check, never looser.
        for (int i = 0; i < addIds.size(); i++) {
            if (!BotHoldings.canHold(bot, item(addIds.get(i), addQty.get(i)))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void take(int itemId, int quantity) {
        Inventory inv = bot.getInventory(ItemConstants.getInventoryType(itemId));
        List<Item> stacks = new ArrayList<>();
        inv.lockInventory();
        try {
            for (Item it : inv.list()) {
                if (it.getItemId() == itemId) {
                    stacks.add(it);
                }
            }
        } finally {
            inv.unlockInventory();
        }
        int left = quantity;
        for (Item it : stacks) {
            if (left <= 0) {
                break;
            }
            left -= BotHoldings.consumeHeld(bot, it, left);
        }
    }

    @Override
    public void give(int itemId, int quantity) {
        BotHoldings.grantItem(bot, item(itemId, quantity));
    }

    @Override
    public void giveEquip(Item item) {
        BotHoldings.grantItem(bot, item);
    }

    @Override
    public void removeEquipAt(short pos) {
        Item it = bot.getInventory(InventoryType.EQUIP).getItem(pos);
        if (it != null) {
            BotHoldings.destroyHeldItem(bot, it);
        }
    }

    @Override
    public boolean payMeso(int amount) {
        return BotHoldings.spendMeso(bot, amount);
    }

    @Override
    public void send(Packet packet) {
        // a bot has no client to show the Maker window's result to
    }

    private static Item item(int itemId, int quantity) {
        if (ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP) {
            return ItemInformationProvider.getInstance().getEquipById(itemId);
        }
        return new Item(itemId, (short) 0, (short) quantity);
    }
}
