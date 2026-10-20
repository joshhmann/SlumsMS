package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTypes.BuyingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.BotTypes.DiceBot;

import java.util.List;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Ticket 37: who a line is for once a bot has answered (attention), and what a named line says to the bot. */
class BotAttentionTest {

    private static final int PLAYER = 4_001;
    private static final int FIREX = 20_701;
    private static final int RESME = 20_702;

    @AfterEach
    void forget() {
        BotChatRouting.dropAttention(PLAYER);
        System.clearProperty("solomapling.chat.attentionMs");
        System.clearProperty("solomapling.chat.attentionRange");
    }

    @Test
    void aBotKeepsListeningUntilThePlayerGoesQuietLeavesOrWalksOff() {
        long now = 100_000;
        BotChatRouting.attend(PLAYER, FIREX, now);
        BotChatRouting.Attention a = BotChatRouting.attention(PLAYER);
        assertEquals(FIREX, a.botId());

        assertEquals(BotChatRouting.Drift.LISTENING, BotChatRouting.drift(a, now + 40_000, true, 100, 100, 300));
        assertEquals(BotChatRouting.Drift.QUIET, BotChatRouting.drift(a, now + 51_000, true, 100, 100, 300),
                "50 s of silence by default");
        assertEquals(BotChatRouting.Drift.LEFT_MAP, BotChatRouting.drift(a, now + 1_000, true, 100, 101, 0));
        assertEquals(BotChatRouting.Drift.WALKED_AWAY, BotChatRouting.drift(a, now + 1_000, true, 100, 100, 900));
        assertEquals(BotChatRouting.Drift.GONE, BotChatRouting.drift(a, now + 1_000, false, 100, 100, 0),
                "the bot logged out");
        assertEquals(BotChatRouting.Drift.GONE, BotChatRouting.drift(null, now, true, 100, 100, 0));
    }

    @Test
    void theWindowAndRangeAreTunable() {
        System.setProperty("solomapling.chat.attentionMs", "10000");
        System.setProperty("solomapling.chat.attentionRange", "2000");
        BotChatRouting.Attention a = new BotChatRouting.Attention(FIREX, 0);
        assertEquals(BotChatRouting.Drift.QUIET, BotChatRouting.drift(a, 11_000, true, 1, 1, 0));
        assertEquals(BotChatRouting.Drift.LISTENING, BotChatRouting.drift(a, 9_000, true, 1, 1, 1_500));
    }

    @Test
    void eachReplyKeepsItListeningButOnlyTheBotThatIsListening() {
        BotChatRouting.attend(PLAYER, FIREX, 1_000);
        BotChatRouting.touchAttention(PLAYER, FIREX, 30_000);
        assertEquals(30_000, BotChatRouting.attention(PLAYER).lastAt(), "its own reply restarts the window");
        BotChatRouting.touchAttention(PLAYER, RESME, 40_000);
        assertEquals(FIREX, BotChatRouting.attention(PLAYER).botId(), "another bot's reply does not steal it");
        assertEquals(30_000, BotChatRouting.attention(PLAYER).lastAt());

        BotChatRouting.attend(PLAYER, RESME, 50_000);
        assertEquals(RESME, BotChatRouting.attention(PLAYER).botId(), "naming another bot moves it: one at a time");
        BotChatRouting.dropAttention(PLAYER);
        assertNull(BotChatRouting.attention(PLAYER));
        BotChatRouting.touchAttention(PLAYER, RESME, 60_000);
        assertNull(BotChatRouting.attention(PLAYER), "a reply after it drifted off does not bring it back");
    }

    @Test
    void aLineForSomeoneElseEndsIt() {
        List<BotAddressee.Candidate> nearby = List.of(new BotAddressee.Candidate(FIREX, "xFirex"),
                new BotAddressee.Candidate(RESME, "Ressurectme"));
        List<String> players = List.of("Joshy", "Al");

        assertFalse(BotChatRouting.addressesSomeoneElse(FIREX, BotAddressee.resolve("where do u train", nearby),
                "where do u train", players), "a plain follow-up stays with the bot");
        assertFalse(BotChatRouting.addressesSomeoneElse(FIREX, BotAddressee.resolve("fire u there", nearby),
                "fire u there", players), "its own nickname stays with it");
        assertTrue(BotChatRouting.addressesSomeoneElse(FIREX, BotAddressee.resolve("hey resme", nearby),
                "hey resme", players), "another bot's nickname");
        assertTrue(BotChatRouting.addressesSomeoneElse(FIREX, BotAddressee.resolve("ressurectme u there", nearby),
                "ressurectme u there", players), "another bot's name");
        assertTrue(BotChatRouting.addressesSomeoneElse(FIREX, BotAddressee.resolve("joshy wanna go", nearby),
                "joshy wanna go", players), "another player's name");
        assertFalse(BotChatRouting.addressesSomeoneElse(FIREX, BotAddressee.resolve("all good", nearby),
                "all good", players), "a two-letter name never matches inside words");
        assertFalse(BotChatRouting.namesSomeoneElse("joshyy lol", List.of("Joshy")), "whole words only");
    }

    @Test
    void aNamedLineLosesTheNameAndNicknames() {
        assertEquals("", BotChatRouting.withoutName("xFirex", "xFirex"));
        assertEquals("", BotChatRouting.withoutName("fire", "xFirex"), "a nickname alone is just calling the bot");
        assertEquals("", BotChatRouting.withoutName("FIRE!!", "xFirex"));
        assertEquals("wanna party?", BotChatRouting.withoutName("fire wanna party?", "xFirex"));
        assertEquals("where do u train", BotChatRouting.withoutName("xFirex, where do u train", "xFirex"));
        assertEquals("hey u there", BotChatRouting.withoutName("hey xfirex u there", "xFirex"));
        assertEquals("trade?", BotChatRouting.withoutName("trade?", "xFirex"));
    }

    @Test
    void everyWindowOptionHasAChatEquivalent() {
        // The window locks the player like an NPC chat, so nothing typed opens it: each of its actions is a line.
        java.util.Map<String, ChatTriage.Intent> lines = new java.util.LinkedHashMap<>();
        lines.put("party", ChatTriage.Intent.PARTY);
        lines.put("Wanna party up?", ChatTriage.Intent.PARTY);
        lines.put("Wanna team up?", ChatTriage.Intent.PARTY);
        lines.put("where do u train", ChatTriage.Intent.WHERE_TRAIN);
        lines.put("Where do you train?", ChatTriage.Intent.WHERE_TRAIN);
        lines.put("trade", ChatTriage.Intent.TRADE);
        lines.put("trade?", ChatTriage.Intent.TRADE);
        lines.put("follow", ChatTriage.Intent.FOLLOW);
        lines.put("Follow me!", ChatTriage.Intent.FOLLOW);
        for (var e : lines.entrySet()) {
            String said = BotChatRouting.withoutName("xFirex " + e.getKey(), "xFirex");
            assertEquals(e.getValue(), ChatTriage.intentRule(said).orElse(ChatTriage.Intent.NONE), said);
        }
        assertEquals(ChatTriage.Intent.NONE, ChatTriage.intentRule("i follow the rules").orElse(ChatTriage.Intent.NONE));
    }

    @Test
    void playerLikeBotsTalkAndServiceBotsKeepTheirMenus() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        assertTrue(Dispatcher.talksNaturally(new TrainingBot(chr)));
        assertTrue(Dispatcher.talksNaturally(mock(SocialBot.class)));
        assertTrue(Dispatcher.talksNaturally(mock(FollowerBot.class)));
        assertTrue(Dispatcher.talksNaturally(new TownWandererBot(chr)));
        assertFalse(Dispatcher.talksNaturally(new SellingMerchantBot(chr)), "a shop is a real choice");
        assertFalse(Dispatcher.talksNaturally(new BuyingMerchantBot(chr)));
        assertFalse(Dispatcher.talksNaturally(mock(DiceBot.class)), "a game reads its own lines");
    }
}
