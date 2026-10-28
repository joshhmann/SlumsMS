package client;

import net.server.PlayerStorage;
import net.server.Server;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 1.6.0.4 (H1): party EXP for an alt asks the world for its owner by id, not the object stored at summon time. */
class OwnedAltLiveOwnerTest {
    static Character loggedIn(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id); when(c.getAccountID()).thenReturn(7); when(c.getWorld()).thenReturn(1);
        Client cl = mock(Client.class); when(cl.getPlayer()).thenReturn(c); when(c.getClient()).thenReturn(cl);
        when(c.isLoggedinWorld()).thenReturn(true); when(c.isLoggedin()).thenReturn(true);
        return c;
    }

    @Test void aStaleOwnerObjectNoLongerEarnsTheAltExp() {
        Character stored = loggedIn(1);
        Character alt = OwnedAltCharacterTest.alt(stored);
        Server server = mock(Server.class); World world = mock(World.class); PlayerStorage storage = mock(PlayerStorage.class);
        when(server.getWorld(1)).thenReturn(world); when(world.getPlayerStorage()).thenReturn(storage);
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            when(storage.getCharacterById(1)).thenReturn(stored);
            assertTrue(alt.canGainOwnedAltPartyExp(), "the stored owner is the live one");
            when(storage.getCharacterById(1)).thenReturn(null);
            assertFalse(alt.canGainOwnedAltPartyExp(), "the owner is not in the world");
            Character fresh = loggedIn(1);
            when(storage.getCharacterById(1)).thenReturn(fresh);
            assertTrue(alt.canGainOwnedAltPartyExp(), "the live owner object, whatever was stored");
            when(fresh.isLoggedinWorld()).thenReturn(false);
            assertFalse(alt.canGainOwnedAltPartyExp(), "the live owner is between channels");
        }
    }
}
