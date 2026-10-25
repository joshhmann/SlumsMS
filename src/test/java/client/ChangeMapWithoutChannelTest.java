package client;

import net.server.channel.Channel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 1.5.2: the delayed warps - ship, subway, elevator - run minutes later in a TimerManager task and call
 * Character.changeMap(int), which went straight through client.getChannelServer().getMapFactory(). When the channel had
 * shut down the pages logged "Cannot invoke MapManager.getMap(int) because the return value of
 * Channel.getMapFactory() is null" (test server, right after "Finished shutting down world 1") and the character stayed
 * where it was mid-travel.
 */
class ChangeMapWithoutChannelTest {

    @Test
    void warpToAMapIdOnAChannelWithNoMapsLeftDoesNothing() {
        Character chr = mock(Character.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        Client client = mock(Client.class);
        Channel channel = mock(Channel.class);
        doReturn(client).when(chr).getClient();
        doReturn(channel).when(client).getChannelServer();
        doReturn(null).when(chr).getEventInstance();
        doReturn(null).when(channel).getMapFactory();

        assertDoesNotThrow(() -> chr.changeMap(100000000));
        assertDoesNotThrow(() -> chr.changeMap(100000000, 0));
    }
}
