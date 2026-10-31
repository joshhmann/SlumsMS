package soloMapling.mechanics;

import net.server.channel.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * TEST 10-31 (t-mx-4): mx-4 staged its second KPQ run on "the first other channel" and Lakelis refused it - channel
 * 2's PQ was held by an instance no run owned, so the check failed on the staging, not on two channels playing at
 * once. The second channel must be one whose KPQ is free.
 */
class KpqStagedChannelPickTest {

    @Test
    void aChannelWhoseKpqIsBusyIsSkipped() {
        Channel one = mock(Channel.class);
        Channel two = mock(Channel.class);
        Channel three = mock(Channel.class);
        Set<Channel> busy = Set.of(one, two);

        assertEquals(three, KpqLobby.firstFree(List.of(one, two, three), one, busy::contains),
                "channel 2's KPQ is busy: take channel 3");
        assertNull(KpqLobby.firstFree(List.of(one, two), one, busy::contains),
                "nothing free: the check must say so, not hand it to Lakelis");
        assertEquals(two, KpqLobby.firstFree(List.of(one, two, three), one, c -> c == three));
        assertEquals(two, KpqLobby.firstFree(java.util.Arrays.asList(null, one, two), one, c -> false),
                "a null channel is skipped");
    }
}
