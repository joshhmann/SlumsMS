package client;

import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic b1707d11b): five minutes after logout, Character.empty(true) nulls the
 * character's client while stale references to it can still sit in a map's character list. sendPacket then threw a
 * NullPointerException, and since every map broadcast walks that list calling sendPacket, one such straggler aborted
 * the broadcast for everyone after it (map entry and removal included). A packet to a disposed character is dropped.
 */
class DisposedCharacterPacketTest {

    private static Character newCharacter(Client client) throws Exception {
        Constructor<Character> ctor = Character.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Character chr = ctor.newInstance();
        Field f = Character.class.getDeclaredField("client");
        f.setAccessible(true);
        f.set(chr, client);
        return chr;
    }

    @Test
    void aPacketToADisposedCharacterIsDropped() throws Exception {
        Character disposed = newCharacter(null);

        assertDoesNotThrow(() -> disposed.sendPacket(mock(Packet.class)));
    }

    @Test
    void aPacketToALiveCharacterStillGoesOut() throws Exception {
        Client client = mock(Client.class);
        Character chr = newCharacter(client);
        Packet packet = mock(Packet.class);

        chr.sendPacket(packet);

        verify(client).sendPacket(packet);
    }
}
