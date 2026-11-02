package server;

import org.junit.jupiter.api.Test;
import soloMapling.Casino.CasinoChipConfig;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket 164: the casino chip shop is buyable and sellable at the same price. {@code Shop.buy} charges the shopitems
 * price the migration inserts; {@code Shop.sell} used to pay {@code ItemInformationProvider.getPrice}, i.e. the stock
 * chip WZ {@code info/price} (250/350/400/350 for the four stamps), so a 1m chip sold back returned 350 mesos. These
 * check the payout seam {@code Shop.sell} now calls, without a database and without WZ.
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
    void theNpcQueryKeepsTheChipShop() {
        assertFalse(ShopFactory.isGmShop(CasinoChipConfig.CASINO_SHOP_ID));
        assertFalse(Shop.NPC_SHOP_QUERY.contains(String.valueOf(CasinoChipConfig.CASINO_SHOP_ID)),
                "the NPC query only excludes 1337 and 9999992-9999999, so 9999001 is returned");
    }
}
