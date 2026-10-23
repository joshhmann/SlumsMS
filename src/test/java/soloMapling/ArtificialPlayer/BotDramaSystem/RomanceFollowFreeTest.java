package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.5.0 rc6 (QA t-rm-1: "didnt follow me to the map"). The partner had just said yes in chat, so it was mid-chat with
 * the player it dates; that counted as busy, the follow was skipped every time (and !qa do whisper was held for the
 * same reason). Chatting with, or partying with, the player itself no longer holds it; anyone else still does.
 */
class RomanceFollowFreeTest {

    static final int PLAYER = 4242, OTHER = 4343;

    static SocialBot talkingTo(int playerId) {
        SocialBot sm = mock(SocialBot.class);
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(BotStore.BOT_ID_BASE + 77);
        when(sm.getChr()).thenReturn(chr);
        when(sm.getState()).thenReturn(BotSM.BotState.IDLE);
        when(sm.hasActiveRespondant()).thenReturn(true);
        when(sm.isTalkingTo(playerId)).thenReturn(true);
        return sm;
    }

    @Test
    void chattingWithThePlayerItDatesDoesNotHoldIt() {
        SocialBot sm = talkingTo(PLAYER);
        assertFalse(BotPopulationDirector.mayLogOut(sm), "mid-chat: the director keeps it");
        assertTrue(BotPopulationDirector.mayLogOut(sm, PLAYER), "free to follow that same player");
        assertFalse(BotPopulationDirector.mayLogOut(sm, OTHER), "chatting with someone else still holds it");
    }

    @Test
    void aPartyWithThePlayerItselfDoesNotHoldItEither() {
        SocialBot sm = mock(SocialBot.class);
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(BotStore.BOT_ID_BASE + 78);
        when(sm.getChr()).thenReturn(chr);
        when(sm.getState()).thenReturn(BotSM.BotState.IDLE);
        Party party = mock(Party.class);
        PartyCharacter me = mock(PartyCharacter.class);
        when(me.getId()).thenReturn(PLAYER);
        when(party.getMembers()).thenReturn(List.of(me));
        when(chr.getParty()).thenReturn(party);
        assertFalse(BotPopulationDirector.mayLogOut(sm));
        assertTrue(BotPopulationDirector.mayLogOut(sm, PLAYER));
        assertFalse(BotPopulationDirector.mayLogOut(sm, OTHER));
    }

    @Test
    void theFollowAndTheWhisperUseIt() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/BotRomance.java"));
        assertTrue(src.contains("!DramaKit.freeForSceneWith(bot, player)"), "onMapChanged and follow");
        assertTrue(src.contains("BotConversation.partnerOf(player.getId()) != bot.getId()"), "the whisper");
    }
}
