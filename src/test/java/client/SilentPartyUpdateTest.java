package client;

import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.2: silentPartyUpdate ran World.updateParty for whatever party the character still pointed at. World.updateParty
 * throws IllegalArgumentException("no party with the specified partyid exists") once the world has dropped it, and the
 * call sits in the middle of a map change and a logout, so the throw aborted the warp or the logout after the client
 * had been told to warp. The log showed it 1127 times from bot logouts alone.
 */
class SilentPartyUpdateTest {
    private static final int PARTY_ID = 1_000_000_042;

    @Test
    void presentPartyIsAnnounced() {
        World world = mock(World.class);
        Party party = party();
        when(world.getParty(PARTY_ID)).thenReturn(party);
        Character chr = character(world, party);

        chr.silentPartyUpdate();

        verify(world).updateParty(PARTY_ID, PartyOperation.SILENT_UPDATE, chr.getMPC());
    }

    @Test
    void partyTheWorldNoLongerHoldsIsNotAnnounced() {
        World world = mock(World.class);
        Party party = party();
        when(world.getParty(PARTY_ID)).thenReturn(null);     // disbanded elsewhere
        Character chr = character(world, party);

        chr.silentPartyUpdate();

        verify(world, never()).updateParty(anyInt(), any(PartyOperation.class), any(PartyCharacter.class));
    }

    @Test
    void worldThatIsGoneIsNotAnnounced() {
        Party party = party();
        Character chr = character(null, party);     // server shutting down: Server.getWorld no longer answers

        chr.silentPartyUpdate();

        // no exception, and nothing to verify against: the assertion is that the call above did not throw
    }

    private static Party party() {
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(PARTY_ID);
        return party;
    }

    private static Character character(World world, Party party) {
        Character chr = mock(Character.class, CALLS_REAL_METHODS);
        doReturn(world).when(chr).getWorldServer();
        doReturn(party).when(chr).getParty();
        doReturn(mock(PartyCharacter.class)).when(chr).getMPC();
        return chr;
    }
}
