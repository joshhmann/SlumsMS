package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 b168 follow-up: a party member is in a healer's reach only on the very same map object. A member at the same
 * x/y on the same map id but another channel's copy, or the public copy of an instance map, is not.
 */
class BotPartySupportReachTest {

    private static Character chr(int id, MapleMap map, int mapId) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(mapId);
        when(c.getPosition()).thenReturn(new Point(100, 50));
        return c;
    }

    private static PartyCharacter member(Character c) {
        PartyCharacter pc = mock(PartyCharacter.class);
        when(pc.getPlayer()).thenReturn(c);
        return pc;
    }

    @Test void onlyMembersOnTheSameMapObjectAreInReach() {
        MapleMap altar = mock(MapleMap.class), publicCopy = mock(MapleMap.class), otherChannel = mock(MapleMap.class);
        Character bot = chr(1_000_000_001, altar, 280030000);
        Character together = chr(2, altar, 280030000);
        Character onPublicCopy = chr(3, publicCopy, 280030000);
        Character onOtherChannel = chr(4, otherChannel, 280030000);
        List<PartyCharacter> members = List.of(member(bot), member(together), member(onPublicCopy),
                member(onOtherChannel));
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(members);
        when(bot.getParty()).thenReturn(party);

        assertEquals(List.of(together), new BotPartySupport.GameEffects().partyInReach(bot));
    }
}
