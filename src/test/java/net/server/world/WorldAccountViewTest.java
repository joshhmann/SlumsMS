package net.server.world;

import client.Character;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.2: registerAccountCharacterView and unregisterAccountCharacterView assumed the world had already loaded the
 * account's character view. When it had not, both threw a NullPointerException, and the register's caller is
 * Server.updateCharacterEntry, which Character.saveCharToDB runs before it writes the row: the save died and the
 * character kept its old data. Seen on the test server as "Cannot invoke java.util.SortedMap.put(...) because
 * java.util.Map.get(...) is null" on a save pool thread.
 */
class WorldAccountViewTest {
    private static final int UNLOADED_REGISTER = 2_000_000_401;
    private static final int UNLOADED_UNREGISTER = 2_000_000_402;

    private static World world;
    private static int weddingInterval, overtimeInterval;

    @BeforeAll
    static void startWorld() {
        // the constructor registers the world's periodic tasks, so both the timer and two intervals need a value
        server.TimerManager.getInstance().start();
        config.ServerConfig server = config.YamlConfig.config.server;
        weddingInterval = server.WEDDING_RESERVATION_INTERVAL;
        overtimeInterval = server.MAP_DAMAGE_OVERTIME_INTERVAL;
        server.WEDDING_RESERVATION_INTERVAL = 30;
        server.MAP_DAMAGE_OVERTIME_INTERVAL = 10_000;

        world = new World(1, 0, "", 1, 1, 1, 1, 1, 1, 1);
    }

    @AfterAll
    static void stopWorld() {
        world.shutdown();
        server.TimerManager.getInstance().stop();
        config.ServerConfig server = config.YamlConfig.config.server;
        server.WEDDING_RESERVATION_INTERVAL = weddingInterval;
        server.MAP_DAMAGE_OVERTIME_INTERVAL = overtimeInterval;
    }

    @Test
    void registeringForAnUnloadedAccountOpensTheViewInsteadOfThrowing() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(1_234_567);

        assertDoesNotThrow(() -> world.registerAccountCharacterView(UNLOADED_REGISTER, chr));
        assertEquals(1, world.getAccountCharactersView(UNLOADED_REGISTER).size());
    }

    @Test
    void unregisteringFromAnUnloadedAccountDoesNotThrow() {
        assertDoesNotThrow(() -> world.unregisterAccountCharacterView(UNLOADED_UNREGISTER, 1_234_567));
    }
}
