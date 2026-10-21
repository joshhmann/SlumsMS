package server.setitem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.packet.Packet;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.SoloMaplingConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server side of the SetEquip addon (ticket 18): what a character wears, and the two client packets. */
public final class SetItems {

    /** A client may ask for the ~100 KB catalog this often at most (one entry per character id, never removed: tiny). */
    static final long CATALOG_COOLDOWN_MS = 3_000;
    private static final Map<Integer, Long> lastCatalog = new ConcurrentHashMap<>();

    private SetItems() {
    }

    /** Item ids in the character's equipped inventory. */
    public static List<Integer> equippedItemIds(Character chr) {
        List<Integer> ids = new ArrayList<>();
        for (Item item : chr.getInventory(InventoryType.EQUIPPED)) {
            ids.add(item.getItemId());
        }
        return ids;
    }

    /** Only real players have the client half; bots and the console character get nothing sent. */
    static boolean hasClient(Character chr) {
        return chr != null && chr.getClient() != null && !BotHelpers.isBot(chr)
                && chr.getId() < SoloMaplingConstants.GameConstants.BOT_BASE_ID
                && chr.getId() != SoloMaplingConstants.GameConstants.CONSOLE_BOT_ID;
    }

    public static void sendCatalog(Character chr) {
        if (!hasClient(chr)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastCatalog.get(chr.getId());
        if (last != null && now - last < CATALOG_COOLDOWN_MS) {
            return;
        }
        lastCatalog.put(chr.getId(), now);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Packet p : SetItemPackets.catalog(SetItemCatalog.getInstance(), chr.getJob().getId(), equippedItemIds(chr), ii::getName)) {
            chr.sendPacket(p);
        }
    }

    public static void sendPieces(Character chr) {
        if (!hasClient(chr)) {
            return;
        }
        SetItemCatalog catalog = SetItemCatalog.getInstance();
        List<Integer> equipped = equippedItemIds(chr);
        chr.sendPacket(SetItemPackets.pieceCounts(catalog.wornPieces(equipped), catalog.bonusFor(equipped)));
    }
}
