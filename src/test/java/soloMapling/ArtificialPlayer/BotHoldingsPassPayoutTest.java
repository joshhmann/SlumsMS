package soloMapling.ArtificialPlayer;

import client.Character;
import client.Client;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.MapleMap;
import net.packet.Packet;
import tools.PacketCreator;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * b225 fail-before test: passes handed by bots to the leader must drop with playerDrop = true
 * so the client recognizes the drop as a player drop rather than a mob drop from an unknown mob OID,
 * allowing it to appear on the ground and be picked up into Etc.
 */
class BotHoldingsPassPayoutTest {

    private static Character newCharacter(int id, String name) {
        Character chr = mock(Character.class);
        Client c = mock(Client.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(c);
        when(chr.getPosition()).thenReturn(new Point(100, 100));
        when(c.getPlayer()).thenReturn(chr);
        return chr;
    }

    @Test
    void payOutPassesToLeaderDropsWithPlayerDropTrue() {
        Character bot = newCharacter(1001, "TestBot");
        Character leader = newCharacter(2001, "PartyLeader");

        client.inventory.Inventory etcInv = new client.inventory.Inventory(bot, client.inventory.InventoryType.ETC, (byte) 16);
        etcInv.addItem(new Item(4001008, (short) 1, (short) 3));
        when(bot.getInventory(client.inventory.InventoryType.ETC)).thenReturn(etcInv);

        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);

        ArgumentCaptor<Boolean> playerDropCaptor = ArgumentCaptor.forClass(Boolean.class);
        when(map.spawnItemDropNoExpire(eq(bot), eq(leader), any(Item.class), any(Point.class), eq(false), playerDropCaptor.capture(), eq(false)))
                .thenAnswer(inv -> {
                    boolean pDrop = inv.getArgument(5);
                    return new server.maps.MapItem(inv.getArgument(2), inv.getArgument(3), inv.getArgument(0),
                            inv.getArgument(1), leader.getClient(), (byte) 0, pDrop);
                });

        int paid = BotHoldings.payOut(bot, 4001008, 1, leader, new Point(100, 100));
        assertEquals(1, paid);

        verify(map).spawnItemDropNoExpire(eq(bot), eq(leader), any(Item.class), any(Point.class), eq(false), anyBoolean(), eq(false));
        assertTrue(playerDropCaptor.getValue(), "b225: Bot pass payout must specify playerDrop = true for leader drops");
    }
}
