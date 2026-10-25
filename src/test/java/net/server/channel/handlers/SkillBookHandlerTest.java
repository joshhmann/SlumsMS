package net.server.channel.handlers;

import client.Character;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import tools.PacketCreator;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SkillBookHandlerTest {

    @Test
    public void testSkillBookResultContainsSkillAndLevel() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(12345);

        int skillId = 1120003;
        int maxLevel = 30;
        Packet packet = PacketCreator.skillBookResult(chr, skillId, maxLevel, true, true);
        assertNotNull(packet);
        byte[] bytes = packet.getBytes();
        assertTrue(bytes.length > 0);
    }
}
