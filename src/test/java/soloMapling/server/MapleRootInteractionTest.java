package soloMapling.server;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.server.EventMessageSystem.*;
import tools.DatabaseConnection;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapleRootInteractionTest {
    @Test
    void disabledHooksDoNothingAndEnabledFailuresDoNotInterruptPlayers() {
        String enabled = System.getProperty("solomapling.enabled");
        String template = System.getProperty("solomapling.template-id");
        try {
            System.clearProperty("solomapling.enabled");
            System.setProperty("solomapling.template-id", "123");
            AtomicInteger calls = new AtomicInteger();
            MapleRootSolo.runHook("test", calls::incrementAndGet);
            assertEquals(0, calls.get());
            System.setProperty("solomapling.enabled", "true");
            MapleRootSolo.runHook("test", calls::incrementAndGet);
            assertEquals(1, calls.get());
            assertDoesNotThrow(() -> MapleRootSolo.runHook("failing observer", () -> {
                throw new IllegalStateException("intentional regression test");
            }));
        } finally {
            restore("solomapling.enabled", enabled);
            restore("solomapling.template-id", template);
        }
    }

    @Test
    void botSavesNeverReachDatabaseEvenIfLoggedInFlagWasSet() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        Field loggedIn = Character.class.getDeclaredField("loggedIn");
        loggedIn.setAccessible(true);
        loggedIn.set(bot, true);
        doCallRealMethod().when(bot).saveCharToDB();
        doCallRealMethod().when(bot).saveCharToDB(anyBoolean());
        doCallRealMethod().when(bot).saveGuildStatus();
        try (var database = mockStatic(DatabaseConnection.class)) {
            bot.saveCharToDB();
            bot.saveCharToDB(true);
            bot.saveCharToDB(false);
            bot.saveGuildStatus();
            database.verifyNoInteractions();
        }
        verify(bot, never()).getWorldServer();
    }

    @Test
    void failedBotSubscriberDoesNotPreventOtherSubscribersReceivingEvent() {
        EventBus bus = EventBus.getInstance();
        GameEvent event = mock(GameEvent.class);
        when(event.getType()).thenReturn(EventType.MAP_ENTERED);
        EventSubscriber broken = mock(EventSubscriber.class);
        EventSubscriber healthy = mock(EventSubscriber.class);
        when(broken.matchesFilter(event)).thenThrow(new IllegalStateException("intentional bad filter"));
        when(healthy.matchesFilter(event)).thenReturn(true);
        bus.subscribe(EventType.MAP_ENTERED, broken);
        bus.subscribe(EventType.MAP_ENTERED, healthy);
        try {
            assertDoesNotThrow(() -> bus.publish(event));
            verify(healthy).onEvent(event);
            assertTrue(bus.getEventStore().getRecentEvents(1).contains(event));
        } finally {
            bus.unsubscribeAll(broken);
            bus.unsubscribeAll(healthy);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
