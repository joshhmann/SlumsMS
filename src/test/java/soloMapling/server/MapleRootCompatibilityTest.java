package soloMapling.server;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotHelpers;
import tools.PacketCreator;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapleRootCompatibilityTest {
    @Test
    void botAttacksKeepMapleRootPacketLayout() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(1234);
        var lines = List.of(12, 34, 56);
        var legacy = Map.of(99, lines);
        var targets = Map.of(99, new BotAttackPackets.AttackTarget((short) 50, lines));
        assertArrayEquals(PacketCreator.closeRangeAttack(chr, 1121008, 30, 0, 0x13, legacy, 4, 63, 0).getBytes(),
                BotAttackPackets.closeRangeAttack(chr, 1302000, 1121008, 30, 0, 0x13, targets, 4, 63, 0).getBytes());
        assertArrayEquals(PacketCreator.rangedAttack(chr, 4121007, 30, 0, 0x13, 2070000, legacy, 4, 1, 0).getBytes(),
                BotAttackPackets.rangedAttack(chr, 4121007, 30, 0, 0x13, 2070000, targets, 4, 1, 0).getBytes());
        assertArrayEquals(PacketCreator.magicAttack(chr, 2121001, 30, 0, 0x13, legacy, -1, 4, 1, 0).getBytes(),
                BotAttackPackets.magicAttack(chr, 2121001, 30, 0, 0x13, targets, -1, 4, 1, 0).getBytes());
    }

    @Test
    void realPlayerIdsDoNotIdentifyBots() {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(999);
        when(player.getClient()).thenReturn(mock(Client.class));
        assertFalse(BotHelpers.isBot(player));
        when(player.getId()).thenReturn(25000);
        assertFalse(BotHelpers.isBot(player));
        when(player.getClient()).thenReturn(mock(BotClient.class));
        assertTrue(BotHelpers.isBot(player));
        assertFalse(BotHelpers.isBot((Character) null));
    }

    @Test
    void botExpSetterRetainsValuesAboveIntRange() throws Exception {
        Character chr = mock(Character.class);
        Field field = Character.class.getDeclaredField("exp");
        field.setAccessible(true);
        AtomicLong storage = new AtomicLong();
        field.set(chr, storage);
        doCallRealMethod().when(chr).setExp(anyLong());
        chr.setExp(5_000_000_000L);
        assertEquals(5_000_000_000L, storage.get());
    }

    @Test
    void enablingBotsRequiresExplicitTemplate() {
        String enabled = System.getProperty("solomapling.enabled");
        String template = System.getProperty("solomapling.template-id");
        try {
            System.clearProperty("solomapling.enabled");
            System.clearProperty("solomapling.template-id");
            assertFalse(MapleRootSolo.isEnabled());
            assertThrows(IllegalStateException.class, MapleRootSolo::templateId);
            System.setProperty("solomapling.enabled", "true");
            assertFalse(MapleRootSolo.isEnabled());
            System.setProperty("solomapling.template-id", "123");
            assertTrue(MapleRootSolo.isEnabled());
            assertEquals(123, MapleRootSolo.templateId());
        } finally {
            if (enabled == null) System.clearProperty("solomapling.enabled"); else System.setProperty("solomapling.enabled", enabled);
            if (template == null) System.clearProperty("solomapling.template-id"); else System.setProperty("solomapling.template-id", template);
        }
    }
}
