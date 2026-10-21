package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import server.maps.HiredMerchant;
import server.maps.PlayerShop;
import soloMapling.FreeMarket.HiredMerchantAdapter;
import soloMapling.FreeMarket.HiredMerchantArtificial;
import soloMapling.FreeMarket.PlayerShopAdapter;
import soloMapling.FreeMarket.ShopKeeper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 1.4.2: FM bots find real players' shops, go there first, and do not hog the visitor slots.
class FMBotRealShopTest {

    private static PlayerShopAdapter playerShop(int ownerId, Client client) {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(ownerId);
        when(owner.getClient()).thenReturn(client);
        PlayerShop shop = mock(PlayerShop.class);
        when(shop.getOwner()).thenReturn(owner);
        return new PlayerShopAdapter(shop);
    }

    @Test
    void realPlayersShopsAreTold() {
        assertTrue(FMBot.isRealPlayersShop(playerShop(1, mock(Client.class))));
        assertFalse(FMBot.isRealPlayersShop(playerShop(1_000_000_005, mock(BotClient.class))));
        HiredMerchant players = mock(HiredMerchant.class);
        when(players.getOwnerId()).thenReturn(7);
        assertTrue(FMBot.isRealPlayersShop(new HiredMerchantAdapter(players)));
        assertFalse(FMBot.isRealPlayersShop(new HiredMerchantAdapter(mock(HiredMerchantArtificial.class))));
    }

    @Test
    void realShopsGoFirstAndBothGroupsKeepTheirOrder() {
        ShopKeeper b1 = playerShop(1_000_000_001, mock(BotClient.class));
        ShopKeeper r1 = playerShop(1, mock(Client.class));
        ShopKeeper b2 = playerShop(1_000_000_002, mock(BotClient.class));
        ShopKeeper r2 = playerShop(2, mock(Client.class));
        assertEquals(List.of(r1, r2, b1, b2),
                FMBot.realShopsFirst(List.of(b1, r1, b2, r2), FMBot::isRealPlayersShop));
    }

    @Test
    void aBotLeavesASlotForPlayersAndSitsAloneInARealShop() {
        assertTrue(FMBot.mayEnterRealShop(true, 3, null, 9));
        assertTrue(FMBot.mayEnterRealShop(true, 2, null, 9));
        assertFalse(FMBot.mayEnterRealShop(true, 1, null, 9), "the last slot stays for a player");
        assertFalse(FMBot.mayEnterRealShop(true, 3, 8, 9), "another bot is inside");
        assertTrue(FMBot.mayEnterRealShop(true, 3, 9, 9));
        assertFalse(FMBot.mayEnterRealShop(false, 3, null, 9), "closed");
    }

    @Test
    void freeSlotsAreReadFromTheShopRoom() {
        PlayerShop shop = mock(PlayerShop.class);
        when(shop.getShopRoomInfo()).thenReturn(new byte[]{1, 3});
        assertEquals(2, new PlayerShopAdapter(shop).freeVisitorSlots());
        HiredMerchant merchant = mock(HiredMerchant.class);
        when(merchant.getShopRoomInfo()).thenReturn(new byte[]{2, 4});
        assertEquals(1, new HiredMerchantAdapter(merchant).freeVisitorSlots());
        when(merchant.getShopRoomInfo()).thenReturn(new byte[]{4, 4});   // closed (owner managing it)
        assertEquals(0, new HiredMerchantAdapter(merchant).freeVisitorSlots());
    }
}
