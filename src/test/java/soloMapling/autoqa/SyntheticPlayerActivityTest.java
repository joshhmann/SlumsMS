package soloMapling.autoqa;

import client.SyntheticClient;
import net.opcodes.RecvOpcode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 auto-QA triage: the pretend player was "AFK over five minutes" five minutes into every run. */
class SyntheticPlayerActivityTest {

    @Test
    void aChatLineCountsAsThePlayersOwnAction() throws InterruptedException {
        SyntheticClient c = new SyntheticClient(1, 1, 1);
        long before = c.getLastPlayerAction();
        Thread.sleep(20);
        SyntheticPlayers.noteReceived(c, RecvOpcode.GENERAL_CHAT);
        assertTrue(c.getLastPlayerAction() > before, "a typed line must reset the AFK clock, as a real client's does");
        assertTrue(c.getLastPacket() > before);
    }

    @Test
    void aKeepaliveDoesNotCountAsAnAction() throws InterruptedException {
        SyntheticClient c = new SyntheticClient(1, 1, 1);
        long before = c.getLastPlayerAction();
        Thread.sleep(20);
        SyntheticPlayers.noteReceived(c, RecvOpcode.PONG);
        assertEquals(before, c.getLastPlayerAction());
    }
}
