package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 1.5.1 owner QA: bots the owner talked to "just zoomed away". An FM shopper never read the conversation hold: named
 * (its hint up) or talked to, it kept walking to the next door or shop.
 */
class FMBotChatHoldTest {

    @Test
    void aShopperATalkingPlayerHoldsStopsWalkingAndWaits() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + 64_001);
        chr.setName("Shopr");
        chr.setMapId(910000000);
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(7401);
        player.setMapId(910000000);
        FMBot bot = new FMBot(chr);
        try {
            assertFalse(bot.pausedForChat(), "nobody talking: it browses");
            GCMovement.move(chr, 900, 0); // on its way to a room door
            assertTrue(GCMovement.isMoving(chr));

            BotConversation.engaged(chr, player);
            assertTrue(bot.pausedForChat(), "a player is talking to it: the browse waits");
            assertFalse(GCMovement.isMoving(chr), "the walk to the door was stopped");
            assertTrue(bot.pausedForChat(), "and stays stopped for the hold");
        } finally {
            GCMovement.disable(chr);
        }
    }
}
