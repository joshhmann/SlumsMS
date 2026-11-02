package server;

import client.BotClient;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import soloMapling.Casino.CasinoChipConfig;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket 164: the casino chip shop is buyable and sellable at the same price. {@code Shop.buy} charges the shopitems
 * price the migration inserts; {@code Shop.sell} used to pay {@code ItemInformationProvider.getPrice}, i.e. the stock
 * chip WZ {@code info/price} (250/350/400/350 for the four stamps), so a 1m chip sold back returned 350 mesos. These
 * check the payout seam {@code Shop.sell} now calls, without a database and without WZ.
 *
 * <p>The quick-sell paths ({@code Character.sellAllItemsFromPosition}, reached from quicksell.js and the donation box)
 * paid the WZ price too; they now go through the same {@code Shop.sellPayout}. The WZ side is mocked at the stock
 * prices so a regression reads as a number, not as a missing-database stack trace.
 */
class CasinoChipSellTest {

    @Test
    void chipsSellBackAtTheirFaceValue() {
        assertEquals(10_000, Shop.sellPayout(4002000, 1));
        assertEquals(50_000, Shop.sellPayout(4002001, 1));
        assertEquals(250_000, Shop.sellPayout(4002002, 1));
        assertEquals(1_000_000, Shop.sellPayout(4002003, 1));
        assertEquals(3_000_000, Shop.sellPayout(4002003, 3), "whole stacks pay per chip");
        assertEquals(200_000, Shop.sellPayout(4002000, 20));
    }

    @Test
    void buyAndSellPayTheSameMesosForEveryChip() {
        for (Map.Entry<Integer, Integer> chip : CasinoChipConfig.getChips().entrySet()) {
            int face = chip.getValue();
            assertEquals(face, CasinoChipConfig.faceValue(chip.getKey()));
            assertEquals(face, CasinoChipConfig.sellValue(chip.getKey(), 1));
            assertEquals(face, Shop.sellPayout(chip.getKey(), 1), "chip " + chip.getKey());
        }
    }

    @Test
    void onlyChipsGetTheFaceValue() {
        assertEquals(-1, CasinoChipConfig.sellValue(2000000, 1), "a Red Potion is not a chip");
        assertFalse(CasinoChipConfig.isCasinoChip(2000000));
        assertEquals(-1, CasinoChipConfig.sellValue(4002000, 0), "selling nothing pays nothing");
        assertEquals(-1, CasinoChipConfig.sellValue(4002000, -1));
    }

    @Test
    void hugeQuantitiesSaturateInsteadOfWrapping() {
        assertEquals(Integer.MAX_VALUE, CasinoChipConfig.sellValue(4002003, 3000));
        assertTrue(CasinoChipConfig.sellValue(4002003, 200) > 0);
    }

    @Test
    void quickSellPaysChipsAtTheirFaceValue() {
        BotClient c = new BotClient(1, 1);
        Character chr = Character.getDefault(c);
        c.setPlayer(chr);   // InventoryManipulator.removeFromSlot resolves the player through the client
        Inventory etc = chr.getInventory(InventoryType.ETC);
        assertEquals(1, etc.addItem(new Item(4002000, (short) 1, (short) 1)));

        assertEquals(10_000, chr.sellAllItemsFromPosition(InventoryType.ETC, (short) 1),
                "quicksell/donation-box sales pay the chip's face value, not the WZ 250");
        assertEquals(10_000, chr.getMeso());
        assertNull(etc.getItem((short) 1), "the chip left the inventory");

        etc.addItem(new Item(4002003, (short) 1, (short) 2));
        assertEquals(2_000_000, chr.sellAllItemsFromPosition(InventoryType.ETC, (short) 1), "two 1m chips");
        assertEquals(2_010_000, chr.getMeso());
    }

    /**
     * The non-chip branch must stay byte-equivalent. It cannot be sold for real here: {@code ItemInformationProvider}
     * cannot initialise without a database and WZ files ("connection pool is uninitialized"), and Mockito cannot even
     * instrument it. So pin the source instead: quick-sell asks the shared seam, and the seam's fallback is the very
     * expression the quick-sell path used before this change.
     */
    @Test
    void quickSellPaysNonChipsExactlyTheWzPrice() throws IOException {
        String character = Files.readString(Path.of("src/main/java/client/Character.java"));
        String quickSell = methodBody(character, "private int standaloneSell(");
        assertTrue(quickSell.contains("Shop.sellPayout(itemid, quantity)"), "quick-sell goes through the one seam");
        assertFalse(quickSell.contains("getPrice("), "it no longer reads the WZ price itself");

        String shop = Files.readString(Path.of("src/main/java/server/Shop.java"));
        String payout = methodBody(shop, "public static int sellPayout(");
        assertTrue(payout.contains("ItemInformationProvider.getInstance().getPrice(itemId, quantity)"),
                "non-chip sales keep exactly the pre-fix call, same arguments");
        assertTrue(payout.contains("CasinoChipConfig.sellValue(itemId, quantity)"), "chips are answered first");
    }

    private static String methodBody(String source, String signature) {
        int from = source.indexOf(signature);
        assertTrue(from >= 0, signature + " is gone");
        int open = source.indexOf('{', from);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return source.substring(from, i + 1);
            }
        }
        fail("unbalanced braces after " + signature);
        return null;
    }

    @Test
    void theNpcQueryKeepsTheChipShop() {
        assertFalse(ShopFactory.isGmShop(CasinoChipConfig.CASINO_SHOP_ID));
        assertFalse(Shop.NPC_SHOP_QUERY.contains(String.valueOf(CasinoChipConfig.CASINO_SHOP_ID)),
                "the NPC query only excludes 1337 and 9999992-9999999, so 9999001 is returned");
    }
}
