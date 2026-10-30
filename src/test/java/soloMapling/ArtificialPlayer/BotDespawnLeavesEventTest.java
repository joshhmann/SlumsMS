package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 (bot audit B8): a bot despawning inside a party quest leaves the instance as a disconnecting player does; it
 * used to stay on the instance's member list, so the instance outlived everyone in it.
 */
class BotDespawnLeavesEventTest {

    @Test
    void aDespawningBotDisconnectsFromItsEvent() {
        Character bot = mock(Character.class);
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(bot.getEventInstance()).thenReturn(eim);
        BotGeneration.leaveEventOnDespawn(bot);
        verify(eim).playerDisconnected(bot);
    }

    @Test
    void aDisposedEventOrNoneIsLeftAloneAndAThrowStaysInside() {
        Character bot = mock(Character.class);
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(bot.getEventInstance()).thenReturn(eim);
        when(eim.isEventDisposed()).thenReturn(true);
        BotGeneration.leaveEventOnDespawn(bot);
        verify(eim, never()).playerDisconnected(bot);

        when(eim.isEventDisposed()).thenReturn(false);
        doThrow(new IllegalStateException("script")).when(eim).playerDisconnected(bot);
        assertDoesNotThrow(() -> BotGeneration.leaveEventOnDespawn(bot), "the despawn goes on");
        BotGeneration.leaveEventOnDespawn(mock(Character.class));
    }
}
