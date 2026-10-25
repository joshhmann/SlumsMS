package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotLogoutTest {

    private static PartyCharacter member(int id) {
        PartyCharacter pc = mock(PartyCharacter.class);
        when(pc.getId()).thenReturn(id);
        return pc;
    }

    private static Character botIn(Party party) {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(BotStore.BOT_ID_BASE + BotStore.FIRST_PLANNED_SLOT);
        bot.setParty(party);
        return bot;
    }

    @Test
    void aPartyCountsAsAPlayersOnlyWhenARealPlayerIsInIt() {
        List<PartyCharacter> mixed = List.of(member(BotStore.BOT_ID_BASE + 9000), member(1234));
        List<PartyCharacter> bots = List.of(member(BotStore.BOT_ID_BASE + 9000), member(BotStore.BOT_ID_BASE + 9001));
        Party withPlayer = mock(Party.class);
        when(withPlayer.getMembers()).thenReturn(mixed);
        Party botsOnly = mock(Party.class);
        when(botsOnly.getMembers()).thenReturn(bots);

        assertTrue(BotLogout.partiedWithPlayer(botIn(withPlayer)));
        assertFalse(BotLogout.partiedWithPlayer(botIn(botsOnly)));
        assertFalse(BotLogout.partiedWithPlayer(botIn(null)));
    }

    /*
     * 1.5.1 owner QA: bots the owner talked to "just zoomed away". The director's logout ignored a conversation: a
     * bot a player was talking to could say bye and vanish mid-chat. It now waits for the hold; a leave the bot
     * announced itself (its own "gtg", a staged boss run) still goes, and shutdown always does.
     */
    @Test
    void aPlayerTalkingToTheBotKeepsItLoggedInUntilTheHoldEnds() throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + 66_001);
        chr.setName("Stayr");
        chr.setMapId(100000000);
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(7601);
        player.setMapId(100000000);
        BotSM bot = mock(soloMapling.ArtificialPlayer.BotTypes.TownWandererBot.class);
        when(bot.getChr()).thenReturn(chr);
        try {
            assertTrue(BotLogout.mayLeaveNow(bot, false), "nobody talking: it may go");
            BotConversation.engaged(chr, player);
            assertFalse(BotLogout.mayLeaveNow(bot, false), "mid-chat: the director's logout waits");
            assertTrue(BotLogout.mayLeaveNow(bot, true), "its own goodbye is not held by the chat");
            try (var shutdown = org.mockito.Mockito.mockStatic(soloMapling.server.BotShutdown.class)) {
                shutdown.when(soloMapling.server.BotShutdown::isQuiesced).thenReturn(true);
                assertTrue(BotLogout.mayLeaveNow(bot, false), "a shutdown outranks the chat");
            }
            soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.addActiveBot(chr.getId(), bot);
            BotLogout.request(chr.getId(), System.currentTimeMillis());
            assertTrue(BotLogout.isLeaving(chr.getId()), "the logout is queued behind the chat");
            assertFalse(BotLogout.isDeparting(chr.getId()), "no goodbye yet");
            assertTrue(BotConversation.isHoldingChat(chr), "waiting to log out, it is still held in place");
        } finally {
            soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.removeActiveBot(chr.getId());
            BotLogout.request(chr.getId(), System.currentTimeMillis()); // gone from storage: drops the queued logout
            BotConversation.holdMsForTest(BotConversation.HOLD_MS);
        }
    }

    @Test
    void thereIsAGoodbyeForTheRoomAndOneForTheParty() {
        String alone = BotLogout.line("Alone");
        String party = BotLogout.line("Party");
        assertNotNull(alone);
        assertNotNull(party);
        assertTrue(alone.length() <= 70 && party.length() <= 70, "lines fit the chat box");
    }
}
