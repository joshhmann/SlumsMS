package soloMapling.ArtificialPlayer.BotGuildSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.5.1 owner QA: bots the owner talked to "just zoomed away". A guild show-up and a bot couple's reunion move a bot
 * to another channel - it vanishes from the map - and their "free" checks never asked whether a player was talking
 * to it.
 */
class ChatHoldHopTest {

    @Test
    void aBotAPlayerIsTalkingToIsNotFreeForAGuildOrCoupleHop() throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + 67_001);
        chr.setName("Hopr");
        chr.setMapId(100000000);
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(7701);
        player.setMapId(100000000);
        BotSM sm = mock(TrainingBot.class);
        when(sm.getChr()).thenReturn(chr);
        Method coupleFree = soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.class
                .getDeclaredMethod("free", Character.class);
        coupleFree.setAccessible(true);
        CharacterStorage.addActiveBot(chr.getId(), sm);
        try {
            assertTrue(BotGuilds.free(chr), "nobody talking: a guild may call it");
            assertTrue((boolean) coupleFree.invoke(null, chr), "nobody talking: its partner may call it");
            BotConversation.engaged(chr, player);
            assertFalse(BotGuilds.free(chr), "mid-chat: no guild show-up hop");
            assertFalse((boolean) coupleFree.invoke(null, chr), "mid-chat: no couple hop");
        } finally {
            CharacterStorage.removeActiveBot(chr.getId());
        }
    }
}
