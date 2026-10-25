package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "PQ bots are not great": the orchestrator locked onto one global leader id, so a second party's bots were
 * follow-warped into the first party's instance, and a leader going offline threw a NullPointerException inside
 * every stage handler (PartyCharacter.setOnline(false) nulls the Character).
 */
class OPQOrchestratorLeaderTest {

    private static Party partyOf(Character leader, Character... members) {
        Party party = mock(Party.class);
        PartyCharacter leaderChar = mock(PartyCharacter.class);
        when(leaderChar.getPlayer()).thenReturn(leader);
        when(party.getLeader()).thenReturn(leaderChar);
        for (Character member : members) {
            when(member.getParty()).thenReturn(party);
        }
        when(leader.getParty()).thenReturn(party);
        return party;
    }

    @Test
    void aBotFollowsItsOwnLeaderNotAnotherPartysLeader() {
        Character leaderA = mock(Character.class);
        Character leaderB = mock(Character.class);
        Character botInA = mock(Character.class);
        Character botInB = mock(Character.class);
        MapleMap mapA = mock(MapleMap.class);
        MapleMap mapB = mock(MapleMap.class);
        when(leaderA.getMap()).thenReturn(mapA);
        when(leaderB.getMap()).thenReturn(mapB);
        partyOf(leaderA, botInA);
        partyOf(leaderB, botInB);

        OPQOrchestrator orchestrator = OPQOrchestrator.getInstance();

        assertSame(mapA, orchestrator.leaderMapFor(botInA));
        assertSame(mapB, orchestrator.leaderMapFor(botInB),
                "party B's bots must never be warped into party A's instance");
    }

    @Test
    void anOfflineLeaderIsNoWarpTargetInsteadOfAnNpe() {
        Character leader = mock(Character.class);
        Character bot = mock(Character.class);
        Party party = partyOf(leader, bot);
        // setOnline(false) nulls the PartyCharacter's Character - that is what the stages used to NPE on.
        PartyCharacter offline = mock(PartyCharacter.class);
        when(offline.getPlayer()).thenReturn(null);
        when(party.getLeader()).thenReturn(offline);

        assertNull(OPQOrchestrator.getInstance().leaderMapFor(bot));
    }

    @Test
    void aBotWithoutAPartyIsNoWarpTarget() {
        Character bot = mock(Character.class);
        when(bot.getParty()).thenReturn(null);

        assertNull(OPQOrchestrator.getInstance().leaderMapFor(bot));
    }
}
