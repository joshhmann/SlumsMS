package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotStore;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 1.5.1 owner QA: a town wanderer asked the hold before it started a hop to another town room, but a hop already
 * under way when the player started talking carried it off the map.
 */
class TownWandererChatHoldTest {

    @Test
    void aHopUnderWayStopsWhenAPlayerTalksToIt() throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + 65_001);
        chr.setName("Walkr");
        chr.setMapId(100000000);
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(7501);
        player.setMapId(100000000);
        TownWandererBot bot = new TownWandererBot(chr);
        Field traveling = TownWandererBot.class.getDeclaredField("traveling");
        traveling.setAccessible(true);
        traveling.set(bot, true); // on its way to the market

        assertFalse(bot.stopHopForChat(), "nobody talking: the hop goes on");
        assertTrue((boolean) traveling.get(bot));

        BotConversation.engaged(chr, player);
        assertTrue(bot.stopHopForChat(), "a player is talking to it: the hop stops");
        assertFalse((boolean) traveling.get(bot), "and it is free to stand and roam (held) again");
    }
}
