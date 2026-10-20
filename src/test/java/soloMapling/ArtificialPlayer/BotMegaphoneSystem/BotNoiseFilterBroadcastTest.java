package soloMapling.ArtificialPlayer.BotMegaphoneSystem;

import client.BotClient;
import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who actually receives a bot's map-chat packet when one viewer has turned bot chatter off. */
class BotNoiseFilterBroadcastTest {

    @AfterEach
    void reset() {
        BotNoiseFilter.resetForTest();
    }

    private static Character player(int id, String name, boolean bot, int partyId) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getPartyId()).thenReturn(partyId);
        when(chr.getClient()).thenReturn(bot ? mock(BotClient.class) : mock(Client.class));
        return chr;
    }

    @Test
    void onlyTheQuietPlayerMissesAmbientBotChat() {
        Character bot = player(9001, "Tiger", true, 77);
        Character quiet = player(1, "Josh", false, -1);
        Character normal = player(2, "Mia", false, -1);
        Character quietPartyMate = player(3, "Kay", false, 77);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(map.getAllPlayers()).thenReturn(List.of(bot, quiet, normal, quietPartyMate));
        BotNoiseFilter.set(1, new BotNoiseFilter.Setting(false, true));
        BotNoiseFilter.set(3, new BotNoiseFilter.Setting(false, true));

        Packet packet = mock(Packet.class);
        BotNoiseFilter.broadcastBotChat(bot, packet, "anyone selling ilbis?");

        verify(quiet, never()).sendPacket(any());
        verify(normal).sendPacket(packet);
        verify(quietPartyMate).sendPacket(packet);
        verify(bot, never()).sendPacket(any());
    }

    @Test
    void withNobodyQuietItIsAPlainMapBroadcast() {
        Character bot = player(9001, "Tiger", true, -1);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        Packet packet = mock(Packet.class);
        BotNoiseFilter.broadcastBotChat(bot, packet, "hi");
        verify(map).broadcastMessage(packet);
    }
}
