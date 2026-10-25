package client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 1.5.2: Character.empty(true) ended with getWorldServer().registerTimedMapObject(...), the five-minute release of the
 * character's inventories. A logout during a world shutdown has no world left, so that call threw "Cannot invoke
 * net.server.world.World.registerTimedMapObject(...) because the return value of Character.getWorldServer() is null"
 * and nothing ever released the inventories. With no world to schedule on, the release now happens straight away.
 */
class LogoutWithoutWorldTest {

    @Test
    void aLogoutWithNoWorldReleasesTheInventoriesInsteadOfThrowing() throws Exception {
        Character bot = Character.getDefault(new BotClient(0, 0));   // world 0 is never running here
        bot.setID(soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 4_003);
        bot.setName("LogoutBot");
        assertNull(bot.getWorldServer(), "precondition: the character has no world to schedule the release on");

        assertDoesNotThrow(() -> bot.empty(true), "threw a NullPointerException before");

        Field inventory = Character.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        assertNull(inventory.get(bot), "the inventories were released instead of being leaked");
    }
}
