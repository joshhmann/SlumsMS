package server;

import client.Client;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.npc.NPCConversationManager;
import soloMapling.Casino.CasinoChipConfig;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Ticket 164: the restored {@code scripts/npc/9000055.js} only calls {@code cm.openShopNPC(9999001)}, and
 * {@code NPCConversationManager.openShopNPC} has no missing-shop branch of its own: with no {@code shops} row it logs
 * "Shop ID: 9999001 is missing from database." and opens GM shop 11000 instead. So the script without migration 059
 * is worse than the mute NPC it replaces - a player gets the GM stock - and 059 without the script leaves the NPC mute.
 * These two cases drive the real {@code openShopNPC} through ShopFactory's loader seam, no database.
 */
class CasinoNpcShopFallbackTest {

    private static ShopFactory factory(Shop chipShop, Shop gmShop) {
        return new ShopFactory((id, byId) -> {
            if (!byId) {
                return null;
            }
            if (id == CasinoChipConfig.CASINO_SHOP_ID) {
                return chipShop;
            }
            return id == 11000 ? gmShop : null;
        });
    }

    @Test
    void withoutTheShopRowTheScriptOpensGmShop11000() {
        Shop gmShop = mock(Shop.class);
        Client c = mock(Client.class);
        try (MockedStatic<ShopFactory> factories = mockStatic(ShopFactory.class)) {
            factories.when(ShopFactory::getInstance).thenReturn(factory(null, gmShop));
            new NPCConversationManager(c, CasinoChipConfig.CASINO_NPC_ID, "9000055")
                    .openShopNPC(CasinoChipConfig.CASINO_SHOP_ID);
        }
        verify(gmShop).sendShop(c);
    }

    @Test
    void withMigration059sRowTheScriptOpensTheChipShop() {
        Shop chipShop = mock(Shop.class);
        Shop gmShop = mock(Shop.class);
        Client c = mock(Client.class);
        try (MockedStatic<ShopFactory> factories = mockStatic(ShopFactory.class)) {
            factories.when(ShopFactory::getInstance).thenReturn(factory(chipShop, gmShop));
            new NPCConversationManager(c, CasinoChipConfig.CASINO_NPC_ID, "9000055")
                    .openShopNPC(CasinoChipConfig.CASINO_SHOP_ID);
        }
        verify(chipShop).sendShop(c);
        verifyNoInteractions(gmShop);
    }
}
