package scripting.npc;

import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * cygnus-lv30-random-crash (1.6.7): Enter on a menu dialog with no #L row crashed the client at +005AC1CA. A menu text
 * with no row goes out as an OK dialog; a menu with rows is unchanged.
 */
class MenuWithoutRowsTest {
    private static final int MSG_TYPE = 7; // opcode (2) + speaker type 4 (1) + npc id (4)

    private static byte[] sent(String text, Byte speaker) {
        Client client = mock(Client.class);
        NPCConversationManager cm = new NPCConversationManager(client, 9010000, "test");
        if (speaker == null) {
            cm.sendSimple(text);
        } else {
            cm.sendSimple(text, speaker);
        }
        ArgumentCaptor<Packet> packet = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(packet.capture());
        return packet.getValue().getBytes();
    }

    @Test
    void menuWithoutRowsGoesOutAsAnOkDialog() {
        assertEquals(0, sent("This menu has no rows.", null)[MSG_TYPE]);
        assertEquals(0, sent("No rows here either.", (byte) 1)[MSG_TYPE]);
        assertEquals(0, sent("", null)[MSG_TYPE]);
    }

    @Test
    void menuWithRowsStaysAMenu() {
        assertEquals(4, sent("Pick one:\r\n#L0#First#l\r\n#L1#Second#l", null)[MSG_TYPE]);
        assertEquals(4, sent("#L12#Only row", (byte) 1)[MSG_TYPE]);
    }

    @Test
    void onlyANumberedRowCountsAsARow() {
        assertTrue(NPCConversationManager.hasMenuRow("#L0#a"));
        assertTrue(NPCConversationManager.hasMenuRow("text #L59#F1"));
        assertFalse(NPCConversationManager.hasMenuRow("#l closing tag only"));
        assertFalse(NPCConversationManager.hasMenuRow("#L#broken"));
        assertFalse(NPCConversationManager.hasMenuRow(null));
    }
}
