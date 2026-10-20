package net.server.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ticket 41: channel ports follow the world's start slot, not its id, so Bera alone keeps live's 7585-7592. */
class ChannelPortTest {
    @Test
    void theFirstWorldStartedUsesTheBasePortsWhateverItsId() {
        assertEquals(7585, Channel.channelPort(7585, 0, 1));
        assertEquals(7592, Channel.channelPort(7585, 0, 8));
    }

    @Test
    void laterWorldsTakeTheNextHundred() {
        assertEquals(7685, Channel.channelPort(7585, 1, 1));
        assertEquals(7787, Channel.channelPort(7585, 2, 3));
    }
}
