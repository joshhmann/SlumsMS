package soloMapling.companions;

import client.BotClient;
import client.Character;
import net.server.channel.Channel;
import net.server.world.Party;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 1.6.0.4 (P0): the production backend arms the follow on summon and stops movement first on dismissal. */
class OwnedAltBackendFollowTest {
    @Test void attachJoinsThePartyThenFollowsTheOwner() {
        Character owner = mock(Character.class), alt = mock(Character.class);
        MapleMap map = mock(MapleMap.class); Channel ch = mock(Channel.class);
        BotClient client = mock(BotClient.class);
        Party party = mock(Party.class);
        when(owner.getMap()).thenReturn(map); when(map.getChannelServer()).thenReturn(ch);
        when(alt.getClient()).thenReturn(client); when(client.getChannelServer()).thenReturn(ch);
        when(alt.getWorldServer()).thenReturn(mock(World.class));
        when(owner.getParty()).thenReturn(party); when(party.getLeaderId()).thenReturn(1); when(owner.getId()).thenReturn(1);
        when(party.getMembers()).thenReturn(List.of()); when(party.getId()).thenReturn(77);
        when(owner.getPosition()).thenReturn(new Point(10, 20));
        try (MockedStatic<Party> parties = mockStatic(Party.class);
             MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            parties.when(() -> Party.joinParty(alt, 77, true)).thenReturn(true);
            new OwnedAltBackend().attach(owner, alt);
            InOrder order = inOrder(Party.class, GCMovement.class);
            order.verify(parties, () -> Party.joinParty(alt, 77, true));
            order.verify(movement, () -> GCMovement.follow(alt, owner));
        }
    }

    @Test void aRefusedJoinNeverStartsFollowing() {
        Character owner = mock(Character.class), alt = mock(Character.class);
        MapleMap map = mock(MapleMap.class); Channel ch = mock(Channel.class);
        BotClient client = mock(BotClient.class); Party party = mock(Party.class);
        when(owner.getMap()).thenReturn(map); when(map.getChannelServer()).thenReturn(ch);
        when(alt.getClient()).thenReturn(client); when(client.getChannelServer()).thenReturn(ch);
        when(alt.getWorldServer()).thenReturn(mock(World.class));
        when(owner.getParty()).thenReturn(party); when(party.getLeaderId()).thenReturn(1); when(owner.getId()).thenReturn(1);
        when(party.getMembers()).thenReturn(List.of()); when(party.getId()).thenReturn(77);
        when(owner.getPosition()).thenReturn(new Point(10, 20));
        try (MockedStatic<Party> parties = mockStatic(Party.class);
             MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            parties.when(() -> Party.joinParty(alt, 77, true)).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> new OwnedAltBackend().attach(owner, alt));
            movement.verify(() -> GCMovement.follow(any(), any()), never());
        }
    }

    @Test void detachStopsMovementBeforeLeavingTheMap() {
        Character alt = mock(Character.class);
        MapleMap map = mock(MapleMap.class); Channel ch = mock(Channel.class); BotClient client = mock(BotClient.class);
        World world = mock(World.class);
        when(alt.getMap()).thenReturn(map); when(alt.getClient()).thenReturn(client); when(client.getChannelServer()).thenReturn(ch);
        when(alt.getWorldServer()).thenReturn(world);
        try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            new OwnedAltBackend().detach(alt);
            InOrder order = inOrder(GCMovement.class, map, alt);
            order.verify(movement, () -> GCMovement.disable(alt));
            order.verify(alt).setDisconnectedFromChannelWorld();
            order.verify(map).removePlayer(alt);
        }
    }

    @Test void bringHereWarpsOntoTheOwnersMapAndFollowsAgain() {
        Character owner = mock(Character.class), alt = mock(Character.class);
        MapleMap map = mock(MapleMap.class), elsewhere = mock(MapleMap.class);
        when(owner.getMap()).thenReturn(map); when(owner.getPosition()).thenReturn(new Point(5, 6));
        when(map.getGroundBelow(any())).thenReturn(new Point(5, 40));
        when(alt.getMap()).thenReturn(elsewhere);
        try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            new OwnedAltBackend().bringHere(owner, alt);
            movement.verify(() -> GCMovement.cancelTravel(alt));
            verify(alt).changeMap(map, new Point(5, 40));
            movement.verify(() -> GCMovement.follow(alt, owner));
        }
        // never into an instance map
        when(map.getEventInstance()).thenReturn(mock(scripting.event.EventInstanceManager.class));
        try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            new OwnedAltBackend().bringHere(owner, alt);
            verify(alt, times(1)).changeMap(any(MapleMap.class), any(Point.class));
            movement.verifyNoInteractions();
        }
    }
}
