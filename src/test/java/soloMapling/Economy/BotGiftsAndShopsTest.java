package soloMapling.Economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.PlayerShopItem;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotGiftsAndShopsTest {

    EconomyTestSupport t;

    @BeforeEach
    void setUp() {
        t = new EconomyTestSupport();
        BotShops.resetForTest(t.now::get);
        BotGifts.resetForTest(t.store, t.now::get);
    }

    @AfterEach
    void tearDown() {
        BotShops.resetForTest(null);
        BotGifts.resetForTest(null, null);
        EconomyTestSupport.reset();
    }

    @Test
    void giftsAreSmallAndMostlyPotions() {
        assertArrayEquals(new int[]{2000002, 50}, BotGifts.potionFor(20));
        assertArrayEquals(new int[]{2000004, 20}, BotGifts.potionFor(50));
        assertArrayEquals(new int[]{2000005, 10}, BotGifts.potionFor(120));
        Map<BotGifts.Kind, Integer> n = new EnumMap<>(BotGifts.Kind.class);
        Random rng = new Random(3);
        for (int i = 0; i < 10_000; i++) {
            n.merge(BotGifts.pick(rng), 1, Integer::sum);
        }
        assertEquals(4500, n.get(BotGifts.Kind.POTIONS), 300);
        assertEquals(2500, n.get(BotGifts.Kind.MESOS), 300);
        assertEquals(7 * 86_400_000L, BotGifts.periodMs());
    }

    @Test
    void aboutOneBotInTenIsMerchantMinded() {
        int n = 0;
        for (int id = 1_000_000_000; id < 1_000_010_000; id++) {
            n += BotShops.merchantMinded(id) ? 1 : 0;
        }
        assertEquals(1000, n, 150);
        assertEquals(BotShops.merchantMinded(1_000_000_123), BotShops.merchantMinded(1_000_000_123));
    }

    @Test
    void shopPricesFollowTheBoardWithAResaleMarkup() {
        t.statics.put(2040804, 1_000_000L);
        assertEquals(1_000_000, BotShops.askFor(EconomyTestSupport.item(2040804, 1), BotShops.Kind.LOOT, 0));
        assertEquals(1_150_000, BotShops.askFor(EconomyTestSupport.item(2040804, 1), BotShops.Kind.RESALE, 800_000));
        assertEquals(1_380_000, BotShops.askFor(EconomyTestSupport.item(2040804, 1), BotShops.Kind.RESALE, 1_200_000),
                "never resold under what was paid");
        assertEquals(0, BotShops.askFor(EconomyTestSupport.item(4009999, 1), BotShops.Kind.LOOT, 0), "no price: not listed");
    }

    @Test
    void aSoldListingIsOwedToTheBotAndShoppersSkipIt() {
        PlayerShopItem p = new PlayerShopItem(EconomyTestSupport.item(2040804, 1), (short) 1, 1_000_000);
        BotShops.listForTest(77, p, BotShops.Kind.LOOT);
        assertTrue(BotShops.isListed(p));
        BotShops.onSold(p, 970_000);
        assertEquals(0, BotShops.owedForTest(77), "still a bundle left: not sold yet");
        p.setBundles((short) 0);
        BotShops.onSold(p, 970_000);
        assertEquals(970_000, BotShops.owedForTest(77));
        assertFalse(BotShops.isListed(p));
        assertEquals(970_000, EconomyMeters.get(EconomyMeters.BOT_SHOP_SALES));
    }

    @Test
    void theResalePoolTakesOnlyWhatBotsMaySellAndIsBounded() {
        EconomyConfig.useForTest(Map.of("botShops.resalePoolMax", "2"));
        assertFalse(BotShops.offerResale(EconomyTestSupport.item(2049100, 1), 1), "a chaos scroll");
        assertTrue(BotShops.offerResale(EconomyTestSupport.item(2040804, 1), 1));
        assertTrue(BotShops.offerResale(EconomyTestSupport.item(4004000, 3), 1));
        assertFalse(BotShops.offerResale(EconomyTestSupport.item(4004000, 3), 1), "full");
        assertEquals(2, BotShops.resalePoolSize());
    }
}
