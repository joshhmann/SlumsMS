package net.server;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * 1.5.2: PlayerStorage.disconnectAll - the loop that empties a channel at shutdown - called client.forceDisconnect()
 * with no guard. One character whose save threw (the log's "Error while shutting down channel 1 in world 1", a save
 * hitting a missing account view) aborted the loop, so every other character of that channel stayed connected and
 * unsaved while Channel.shutdown gave up before disposing its maps. The vectors here repeat that shape.
 */
class PlayerStorageDisconnectAllTest {

    @Test
    void oneCharacterWhoseDisconnectThrowsDoesNotStrandTheOthers() {
        PlayerStorage storage = new PlayerStorage();

        Character broken = mock(Character.class);
        Client brokenClient = mock(Client.class);
        doThrow(new NullPointerException("Cannot invoke \"java.util.SortedMap.put(Object, Object)\""))
                .when(brokenClient).forceDisconnect();
        when(broken.getClient()).thenReturn(brokenClient);
        when(broken.getId()).thenReturn(1);
        when(broken.getName()).thenReturn("Broken");

        Character healthy = mock(Character.class);
        Client healthyClient = mock(Client.class);
        when(healthy.getClient()).thenReturn(healthyClient);
        when(healthy.getId()).thenReturn(2);
        when(healthy.getName()).thenReturn("Healthy");

        storage.addPlayer(broken);
        storage.addPlayer(healthy);

        assertDoesNotThrow(storage::disconnectAll);
        verify(healthyClient).forceDisconnect();
        assertEquals(0, storage.getAllCharacters().size(), "the channel must still be emptied");
    }
}
