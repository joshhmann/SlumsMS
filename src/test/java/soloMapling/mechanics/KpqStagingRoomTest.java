package soloMapling.mechanics;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TEST 10-30: "!mechanics kpq me 3 stage4" with an alt still out and the last run's two bots not yet gone asked for 0
 * bots; BotStore threw on the worker thread and the GM saw only "Staging KPQ..." with no answer.
 */
class KpqStagingRoomTest {
    static PartyCharacter member(int id, String name) {
        PartyCharacter m = mock(PartyCharacter.class);
        when(m.getId()).thenReturn(id); when(m.getName()).thenReturn(name);
        return m;
    }

    @Test
    void aFullPartyIsAClearRefusalNamingWhoFillsIt() {
        Character gm = mock(Character.class); when(gm.getId()).thenReturn(1);
        Party party = mock(Party.class);
        List<PartyCharacter> members = List.of(member(1, "AqClient"), member(2, "AqClientAlt1"),
                member(3, "Bot1"), member(4, "Bot2"));
        when(party.getMembers()).thenReturn(members);
        when(gm.getParty()).thenReturn(party);
        String line = KpqStaging.noRoomForBots(3, 0, gm);
        assertNotNull(line);
        assertTrue(line.contains("AqClientAlt1, Bot1, Bot2"), line);
        assertTrue(line.contains("@dismiss"), line);
        assertNull(KpqStaging.noRoomForBots(3, 2, gm), "room for two: no refusal");
        assertNull(KpqStaging.noRoomForBots(0, 0, gm), "no bots asked for");
    }

    @Test
    void askingForNoBotsSpawnsNothingInsteadOfThrowing() {
        assertTrue(KpqStaging.spawnBots(mock(server.maps.MapleMap.class), 0, new java.util.ArrayList<>()).isEmpty());
    }
}
