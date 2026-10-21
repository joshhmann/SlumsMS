package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotChatActionsTest {

    @AfterEach
    void reset() {
        ChatStats.resetForTest();
    }

    private static Character character(int id, String name, int level) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setLevel(level);
        return chr;
    }

    private static <T extends BotSM> T bot(Class<T> type, String name) {
        T bot = mock(type);
        Character chr = character(BotStore.BOT_ID_BASE + 7, name, 40);
        when(bot.getChr()).thenReturn(chr);
        return bot;
    }

    @Test
    void aMerchantPointsAtItsShopAndNobodyOpensATradeOrGivesAnything() {
        Character player = character(1001, "Josh", 30);
        BotChatActions.Result shop = BotChatActions.act(bot(SellingMerchantBot.class, "Shopz"), player, ChatTriage.Intent.TRADE);
        assertEquals(BotChatActions.Outcome.TRADE_SHOP, shop.outcome());
        BotChatActions.Result no = BotChatActions.act(bot(TownWandererBot.class, "Walkr"), player, ChatTriage.Intent.TRADE);
        assertEquals(BotChatActions.Outcome.TRADE_DECLINED, no.outcome());
        assertNotNull(no.scripted());
        assertTrue(no.fact().contains("not selling or giving"));
        assertEquals(1L, ChatStats.snapshot().get("actions") instanceof java.util.Map<?, ?> m ? m.get("trade:trade_shop") : null);
    }

    @Test
    void aBotThatCannotAnswerAnInviteSaysNoInsteadOfLying() {
        Character player = character(1001, "Josh", 30);
        TownWandererBot walker = bot(TownWandererBot.class, "Walkr");
        assertEquals(BotChatActions.Outcome.PARTY_DECLINED, BotChatActions.act(walker, player, ChatTriage.Intent.PARTY).outcome());
        assertEquals(BotChatActions.Outcome.FOLLOW_DECLINED, BotChatActions.act(walker, player, ChatTriage.Intent.FOLLOW).outcome());
        assertEquals(BotChatActions.Outcome.FOLLOW_DECLINED,
                BotChatActions.act(walker, player, ChatTriage.Intent.COME_WITH_ME).outcome());
        assertEquals(BotChatActions.Outcome.NONE, BotChatActions.act(walker, player, ChatTriage.Intent.NONE).outcome());
    }

    @Test
    void everyFlagLeavesANoteThatMakesTheBotWary() {
        for (ChatTriage.Con con : ChatTriage.Con.values()) {
            String note = BotChatActions.flagNote(con);
            if (con == ChatTriage.Con.NORMAL) {
                assertEquals(null, note);
                continue;
            }
            assertTrue(BotChatActions.WARY_MARKERS.stream().anyMatch(note::contains), note);
            assertTrue(note.length() <= 160);
        }
    }

    @Test
    void theScriptedLinesAreShortAndLowercase() {
        for (java.util.List<String> lines : java.util.List.of(ScriptedReplies.SCAM, ScriptedReplies.MANIPULATION,
                ScriptedReplies.RWT, ScriptedReplies.PARTY_YES, ScriptedReplies.TRADE_NO, ScriptedReplies.TALK)) {
            for (String line : lines) {
                assertTrue(line.length() <= 40 && line.equals(line.toLowerCase()), line);
            }
        }
    }

    @Test
    void aModelReplyMustFitWhatTheGameDid() {
        BotChatActions.Result yes = new BotChatActions.Result(BotChatActions.Outcome.PARTY_ACCEPTED, "f", "sure inv me");
        assertTrue(yes.fits("lol sure send me the party invite"));
        assertTrue(!yes.fits("nvm i got my own grind lol"));
        BotChatActions.Result no = new BotChatActions.Result(BotChatActions.Outcome.FOLLOW_DECLINED, "f", "busy sry");
        assertTrue(no.fits("nvm i'm grinding here"));
        assertTrue(!no.fits("lol sure bro, im running low on health tho"));
        BotChatActions.Result train = new BotChatActions.Result(BotChatActions.Outcome.WHERE_TRAIN, "f", "ant tunnel i rn",
                BotChatActions.keyWord("Ant Tunnel I"));
        assertTrue(train.fits("i train at ant tunnel i, its chill"));
        assertTrue(!train.fits("idk, u can train anywhere really"));
        assertEquals("ant", BotChatActions.keyWord("Ant Tunnel I"));
        assertEquals("sleepywood", BotChatActions.keyWord("The Sleepywood"));
    }

    @Test
    void aMerchantNamesItsRealStockAndAReplyMustNameItToo() {
        MerchantStock.useItemNamesForTest(id -> id == 2040804 ? "Scroll for Gloves for ATT 60%" : null);
        try {
            SellingMerchantBot merchant = bot(SellingMerchantBot.class, "Shopz");
            soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInventory inventory =
                    new soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInventory();
            inventory.setItemForSaleMain(new client.inventory.Item(2040804, (short) 0, (short) 1));
            soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeWants wants =
                    new soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeWants();
            wants.setMesoWanted(12_500_000);
            when(merchant.getTradeInventory()).thenReturn(inventory);
            when(merchant.getTradeWants()).thenReturn(wants);
            when(merchant.getTradeMode()).thenReturn(soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeSM.TradeMode.SELLING);

            BotChatActions.Result shop = BotChatActions.act(merchant, character(1001, "Josh", 30), ChatTriage.Intent.TRADE);
            assertEquals(BotChatActions.Outcome.TRADE_SHOP, shop.outcome());
            assertTrue(shop.fact().contains("Scroll for Gloves for ATT 60% for 12.5m"), shop.fact());
            assertTrue(shop.scripted().contains("scroll for gloves for att 60%") && shop.scripted().contains("12.5m"),
                    shop.scripted());
            assertTrue(shop.fits("ya 60 gloves 12.5m, trade me"));
            assertTrue(!shop.fits("check my shop lol"), "a reply that does not name the stock is replaced");
        } finally {
            MerchantStock.useItemNamesForTest(null);
        }
    }

    @Test
    void whereAreYouIsAnsweredWithTheRealMap() { // ticket 49
        BotChatActions.Result at = BotChatActions.whereAt("Ant Tunnel I");
        assertEquals(BotChatActions.Outcome.WHERE_AT, at.outcome());
        assertTrue(at.fact().contains("Ant Tunnel I"), at.fact());
        assertTrue(at.scripted().contains("ant tunnel i"), at.scripted());
        assertTrue(at.fits("im at ant tunnel rn, come by"));
        assertTrue(!at.fits("somewhere lol"), "a reply must name the map");
        assertEquals(BotChatActions.Outcome.NONE, BotChatActions.whereAt((String) null).outcome());
    }
}
