package net.server.world;

import client.Character;
import net.server.PlayerStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 1.6.5 P1-E2: a CHANGE_LEADER update with an offline old leader, a missing leader or a missing target does not throw. */
class PartyLeaderChangeOfflineTest {
    private static final int PARTY_ID = 77;

    private final World world = mock(World.class, CALLS_REAL_METHODS);
    private final Party party = mock(Party.class);

    private void wire() {
        doReturn(party).when(world).getParty(PARTY_ID);
        doReturn(new PlayerStorage()).when(world).getPlayerStorage();
    }

    @Test
    void offlineOldLeaderStillHandsOverTheStoredLeader() {
        wire();
        PartyCharacter oldLeader = mock(PartyCharacter.class);   // getPlayer() is null: offline
        PartyCharacter target = mock(PartyCharacter.class);
        doReturn(oldLeader).when(party).getLeader();

        assertDoesNotThrow(() -> world.updateParty(PARTY_ID, PartyOperation.CHANGE_LEADER, target));

        verify(party).setLeader(target);
    }

    @Test
    void missingOldLeaderEntryDoesNotThrow() {
        wire();
        PartyCharacter target = mock(PartyCharacter.class);
        doReturn(null).when(party).getLeader();

        assertDoesNotThrow(() -> world.updateParty(PARTY_ID, PartyOperation.CHANGE_LEADER, target));

        verify(party).setLeader(target);
    }

    @Test
    void offlineTargetDoesNotThrow() {
        wire();
        PartyCharacter oldLeader = mock(PartyCharacter.class);
        doReturn(mock(Character.class)).when(oldLeader).getPlayer();   // online leader, offline target below
        PartyCharacter target = mock(PartyCharacter.class);
        doReturn(oldLeader).when(party).getLeader();

        assertDoesNotThrow(() -> world.updateParty(PARTY_ID, PartyOperation.CHANGE_LEADER, target));
    }

    @Test
    void nullTargetDoesNotThrow() {
        wire();
        doReturn(mock(PartyCharacter.class)).when(party).getLeader();

        assertDoesNotThrow(() -> world.updateParty(PARTY_ID, PartyOperation.CHANGE_LEADER, null));
    }
}
