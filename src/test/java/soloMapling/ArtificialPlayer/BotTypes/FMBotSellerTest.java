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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Ticket 56A: only real players' shops are capped; bots' own shops and the artificial FM merchants are not.
class FMBotSellerTest {

    private static PlayerShopAdapter playerShop(int ownerId, Client client, int account) {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(ownerId);
        when(owner.getClient()).thenReturn(client);
        when(owner.getAccountID()).thenReturn(account);
        PlayerShop shop = mock(PlayerShop.class);
        when(shop.getOwner()).thenReturn(owner);
        return new PlayerShopAdapter(shop);
    }

    @Test
    void aRealPlayersShopIsCappedByItsAccount() {
        assertEquals(42, FMBot.realSellerAccount(playerShop(5, mock(Client.class), 42)));
    }

    @Test
    void aBotsShopIsNotCapped() {
        assertEquals(-1, FMBot.realSellerAccount(playerShop(1_000_000_005, mock(BotClient.class), 1)));
    }

    @Test
    void artificialMerchantsAreNotCapped() {
        assertEquals(-1, FMBot.realSellerAccount(new HiredMerchantAdapter(mock(HiredMerchantArtificial.class))));
        HiredMerchant botOwned = mock(HiredMerchant.class);
        when(botOwned.getOwnerId()).thenReturn(1_000_000_009);
        assertEquals(-1, FMBot.realSellerAccount(new HiredMerchantAdapter(botOwned)));
    }
}
