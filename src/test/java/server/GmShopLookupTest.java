package server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** GM shops (1337, 9999992-9999999) used to come back for NPCs players and bots can talk to. */
class GmShopLookupTest {
    private static final Map<Integer, Shop> BY_ID = Map.of(
            1337, Shop.forTest(1337, 11000), 11000, Shop.forTest(11000, 11000),
            9999992, Shop.forTest(9999992, 9001002));

    /** Mimics the old DB lookup by NPC: the first row with that npcid, in shop id order. */
    private static Shop oldDbOrder(int id, boolean isShopId) {
        if (isShopId) {
            return BY_ID.get(id);
        }
        return BY_ID.values().stream().filter(s -> s.getNpcId() == id)
                .min((a, b) -> Integer.compare(a.getId(), b.getId())).orElse(null);
    }

    @Test void gmShopsAreKnown() {
        assertTrue(ShopFactory.isGmShop(1337));
        assertTrue(ShopFactory.isGmShop(9999992));
        assertTrue(ShopFactory.isGmShop(9999999));
        assertFalse(ShopFactory.isGmShop(1338)); // Miu Miu's travel store is for players
        assertFalse(ShopFactory.isGmShop(11000));
        assertFalse(ShopFactory.isGmShop(9201060)); // @shop
    }

    @Test void polarBearPochOpensNoGmShop() {
        assertNull(new ShopFactory(GmShopLookupTest::oldDbOrder).getShopForNPC(9001002));
    }

    @Test void mapleIslandsNpcKeepsItsOwnShopEvenAfterAGmOpensTheirs() {
        ShopFactory f = new ShopFactory(GmShopLookupTest::oldDbOrder);
        assertEquals(1337, f.getShop(1337).getId());
        assertNull(f.getShopForNPC(11000), "the old DB order gives 1337 first: skipped, not cached as 11000's");
        ShopFactory g = new ShopFactory((id, byId) -> byId ? BY_ID.get(id) : (id == 11000 ? BY_ID.get(11000) : null));
        g.getShop(1337);
        assertEquals(11000, g.getShopForNPC(11000).getId());
    }

    @Test void theNpcQuerySkipsGmShopsAndPrefersTheNpcsOwnShop() {
        assertTrue(Shop.NPC_SHOP_QUERY.contains("shopid <> 1337"));
        assertTrue(Shop.NPC_SHOP_QUERY.contains("NOT BETWEEN 9999992 AND 9999999"));
        assertTrue(Shop.NPC_SHOP_QUERY.contains("ORDER BY shopid = npcid DESC"));
    }
}
