package client;

import net.server.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 1.5.2: Mount.empty is reached from Character.empty on every logout, and it unregistered mount hunger through
 * getClient().getWorldServer(). When the client was already cleared (or the world was gone under a shutdown) that threw
 * a NullPointerException, which aborted Character.empty halfway: the live log showed it as
 * "World.unregisterMountHunger ... because Client.getWorldServer() is null" under Client.clear.
 */
class MountHungerTest {

    @Test
    void emptyingAMountWithNoWorldDoesNotThrow() throws Exception {
        Character owner = mock(Character.class, CALLS_REAL_METHODS);
        doReturn(null).when(owner).getClient();          // logout already detached the client

        Mount mount = new Mount(owner, 1902000, 1004);

        assertDoesNotThrow(mount::empty);

        java.lang.reflect.Field ownerField = Mount.class.getDeclaredField("owner");
        ownerField.setAccessible(true);
        assertNull(ownerField.get(mount), "the mount must still let go of its owner");
    }

    @Test
    void emptyingAMountUnregistersItsHungerOnTheLiveWorld() {
        World world = mock(World.class);
        Client client = mock(Client.class);
        doReturn(world).when(client).getWorldServer();

        Character owner = mock(Character.class, CALLS_REAL_METHODS);
        doReturn(client).when(owner).getClient();

        new Mount(owner, 1902000, 1004).empty();

        verify(world).unregisterMountHunger(owner);
    }
}
