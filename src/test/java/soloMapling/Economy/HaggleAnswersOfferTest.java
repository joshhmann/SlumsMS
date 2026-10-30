package soloMapling.Economy;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.StateOfMind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 156, b102 (TEST 2026-10-30, Zh3lmSader): after "how much for the work gloves" -> "9,520", the player's
 * "would u do 300k" and "thats too much" got the price list again ("i got work gloves 9,520"). The quote's answer now
 * takes the offer, comes down, or names its lowest with a reason.
 */
class HaggleAnswersOfferTest {

    static final int GLOVES = 1082002;
    static final int BOT = 1_000_050_000, PLAYER = 4242;

    EconomyTestSupport t;
    org.mockito.MockedStatic<server.ItemInformationProvider> itemData;
    Character bot;
    Character player;

    @BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = org.mockito.Mockito.mockStatic(tools.DatabaseConnection.class);
             var providers = org.mockito.Mockito.mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void setUp() {
        itemData = org.mockito.Mockito.mockStatic(server.ItemInformationProvider.class);
        server.ItemInformationProvider ii = mock(server.ItemInformationProvider.class);
        when(ii.getName(GLOVES)).thenReturn("Work Gloves");
        itemData.when(server.ItemInformationProvider::getInstance).thenReturn(ii);
        t = new EconomyTestSupport();
        t.statics.put(GLOVES, 10_000L);
        bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT);
        when(bot.getName()).thenReturn("Zh3lmSader");
        player = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER);
        when(player.getName()).thenReturn("Josh");
    }

    @AfterEach
    void tearDown() {
        itemData.close();
        EconomyTestSupport.reset();
    }

    private void quote(long price) {
        BotDirectTrade.quoteForTest(BOT, PLAYER, new Item(GLOVES, (short) 0, (short) 1), price);
    }

    private static StateOfMind.Snapshot open(long asking, long offered) {
        return StateOfMind.builder().bot("Zh3lmSader", 30, "spearman").player("Josh", 75, "chief bandit", false)
                .met(true, List.of()).offer(new StateOfMind.Offer("work gloves", asking, offered)).build();
    }

    @Test
    void anOfferOverTheAskingPriceIsTakenAtTheAskingPrice() {
        quote(9_520);
        BotDirectTrade.Answer a = BotDirectTrade.chat(null, bot, player, "would u do 300k");
        assertNotNull(a);
        assertFalse(a.scripted().startsWith("i got"), a.scripted());
        assertTrue(a.scripted().contains("9,520") && a.scripted().contains("300k"), a.scripted());
        assertTrue(StateOfMind.check(a.scripted(), open(9_520, 300_000)).ok(), a.scripted());
        assertEquals(9_520, BotDirectTrade.openQuote(BOT, PLAYER).price(), "the quote stays at its price");
    }

    @Test
    void tooMuchGetsALowerPriceThenTheLowest() {
        quote(9_520);
        BotDirectTrade.Answer a = BotDirectTrade.chat(null, bot, player, "thats too much");
        assertNotNull(a);
        assertFalse(a.scripted().startsWith("i got"), "b102: no price list over a refusal: " + a.scripted());
        long down = BotDirectTrade.openQuote(BOT, PLAYER).price();
        assertTrue(down < 9_520, "it came down: " + down);
        assertTrue(StateOfMind.check(a.scripted(), open(9_520, -1)).ok(), a.scripted());
        // keep saying no: it ends at its floor with a reason, never the old list
        for (int i = 0; i < 5; i++) {
            a = BotDirectTrade.chat(null, bot, player, "nah too expensive");
            assertFalse(a.scripted().startsWith("i got"), a.scripted());
        }
        assertTrue(a.scripted().contains("lowest"), a.scripted());
    }

    @Test
    void aDealAfterTheQuoteIsAnsweredWithTheTrade() {
        quote(9_520);
        BotDirectTrade.Answer a = BotDirectTrade.chat(null, bot, player, "ok deal");
        assertTrue(a.scripted().contains("9,520") && a.scripted().contains("trade"), a.scripted());
    }

    @Test
    void aLowOfferStillHagglesInTheBand() {
        quote(9_520);
        BotDirectTrade.Answer a = BotDirectTrade.chat(null, bot, player, "would u do 9k");
        assertFalse(a.scripted().startsWith("i got"), a.scripted());
        assertTrue(StateOfMind.check(a.scripted(), open(9_520, 9_000)).ok(), a.scripted());
    }
}
