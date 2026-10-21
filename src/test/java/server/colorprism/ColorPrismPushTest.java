package server.colorprism;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.InventoryType;
import config.YamlConfig;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the server pushes without being asked: on map entry and on an equip change. Bots get nothing. */
class ColorPrismPushTest {
    private final boolean wasEnabled = YamlConfig.config.server.COLOR_PRISM_ENABLED;
    private MapleMap map;
    private Client entrantClient, otherClient, botClient;
    private Character entrant, other, bot;

    @BeforeAll
    static void init() throws Exception {
        TintTest.initItemProvider();
    }

    @BeforeEach
    void setUp() {
        YamlConfig.config.server.COLOR_PRISM_ENABLED = true;
        ColorPrismStore.setSchemaPresent(true);
        entrantClient = mock(Client.class);
        otherClient = mock(Client.class);
        entrant = Character.getDefault(entrantClient);
        entrant.setName("Entrant");
        other = Character.getDefault(otherClient);
        other.setName("Other");
        bot = mock(Character.class);
        botClient = new BotClient(0, 0);
        when(bot.getClient()).thenReturn(botClient);
        when(bot.getId()).thenReturn(1_000_000_005);
        map = mock(MapleMap.class);
        when(map.getAllPlayers()).thenReturn(List.of(entrant, other, bot));
    }

    @AfterEach
    void tearDown() {
        YamlConfig.config.server.COLOR_PRISM_ENABLED = wasEnabled;
        ColorPrismStore.setSchemaPresent(null);
    }

    private static List<byte[]> sent(Client c, int n) {
        ArgumentCaptor<Packet> cap = ArgumentCaptor.forClass(Packet.class);
        verify(c, times(n)).sendPacket(cap.capture());
        return cap.getAllValues().stream().map(Packet::getBytes).toList();
    }

    @Test
    void anUntintedEntrantGetsItsSnapshotAndTheTableOnly() {
        other.setHairTint(-5, 0, 0);
        ColorPrism.onMapEnter(entrant, map);
        List<byte[]> got = sent(entrantClient, 2);
        assertEquals(ColorPrismPackets.SUB_SNAPSHOT, got.get(0)[2]);
        assertEquals(ColorPrismPackets.SUB_MAP_TABLE, got.get(1)[2]);
        assertEquals(1, got.get(1)[3]);                 // Other's hair
        verify(otherClient, never()).sendPacket(any());
        verify(bot, never()).sendPacket(any());
    }

    @Test
    void aTintedEntrantIsAnnouncedToTheWholeMapButNotToBots() {
        entrant.setFaceTint(-9, 0, 0);
        ColorPrism.onMapEnter(entrant, map);
        sent(entrantClient, 2);
        assertEquals(ColorPrismPackets.SUB_MAP_TABLE, sent(otherClient, 1).get(0)[2]);
        verify(bot, never()).sendPacket(any());
    }

    @Test
    void offMeansNoPushes() {
        YamlConfig.config.server.COLOR_PRISM_ENABLED = false;
        entrant.setFaceTint(-9, 0, 0);
        ColorPrism.onMapEnter(entrant, map);
        verify(entrantClient, never()).sendPacket(any());
        verify(otherClient, never()).sendPacket(any());
    }

    @Test
    void equipChangesArePushedOnlyByPlayersWithADyedEquip() {
        Character withMap = org.mockito.Mockito.spy(entrant);
        when(withMap.getMap()).thenReturn(map);
        ColorPrism.onEquipChanged(withMap);
        verify(entrantClient, never()).sendPacket(any());

        withMap.getInventory(InventoryType.EQUIP).addItemFromDB(TintTest.equip(1702000, 2));
        ((client.inventory.Equip) withMap.getInventory(InventoryType.EQUIP).getItem((short) 2)).setTint(-3, 0, 0);
        ColorPrism.onEquipChanged(withMap);
        sent(entrantClient, 2);                         // its snapshot + the table
        sent(otherClient, 1);
    }
}
