package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "PQ bots are not great": the stage flags were one global boolean. Two parties running OPQ at the same time got
 * two MapleMap objects for 920010000, and the first leader to reach the top platform told the other party's bots
 * their stage 1 was over - they jumped to the tower and left the leader breaking clouds alone. The flags now
 * belong to the map instance.
 */
class OPQInstanceFlagsTest {

    private static MapleMap map(int mapId) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        return map;
    }

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
    void stageOneFinishedInOneInstanceDoesNotFinishAnother() {
        OPQSharedContext ctx = new OPQSharedContext();
        MapleMap partyA = map(OPQConstants.OPQ_STAGE_1);
        MapleMap partyB = map(OPQConstants.OPQ_STAGE_1);

        ctx.setStage1Complete(partyA, true);

        assertTrue(ctx.isStage1Complete(partyA));
        assertFalse(ctx.isStage1Complete(partyB),
                "party B's bots must keep breaking clouds until their own leader reaches the top platform");
    }

    @Test
    void stageTwoFinishedInOneInstanceDoesNotFinishAnother() {
        OPQSharedContext ctx = new OPQSharedContext();
        MapleMap partyA = map(OPQConstants.OPQ_STAGE_2);
        MapleMap partyB = map(OPQConstants.OPQ_STAGE_2);

        ctx.setStage2Complete(partyA, true);

        assertTrue(ctx.isStage2Complete(partyA));
        assertFalse(ctx.isStage2Complete(partyB));
    }

    @Test
    void aFlagCanBeTakenBackAndAMissingMapIsNeverComplete() {
        OPQSharedContext ctx = new OPQSharedContext();
        MapleMap instance = map(OPQConstants.OPQ_STAGE_1);

        ctx.setStage1Complete(instance, true);
        ctx.setStage1Complete(instance, false);

        assertFalse(ctx.isStage1Complete(instance));
        assertFalse(ctx.isStage1Complete(null));
        assertFalse(ctx.isStage2Complete(null));
        ctx.setStage1Complete(null, true); // must not blow up on a bot whose map is gone
    }

    @Test
    void aFullResetClearsEveryInstance() {
        OPQSharedContext ctx = new OPQSharedContext();
        MapleMap partyA = map(OPQConstants.OPQ_STAGE_1);
        MapleMap partyB = map(OPQConstants.OPQ_STAGE_1);
        ctx.setStage1Complete(partyA, true);
        ctx.setStage1Complete(partyB, true);

        ctx.clearCompletionFlags();

        assertFalse(ctx.isStage1Complete(partyA));
        assertFalse(ctx.isStage1Complete(partyB));
    }

    @Test
    void oneLeaderPerPartyIsPolledAndOnlyForMembersInside() {
        Character leaderA = mock(Character.class);
        Character leaderB = mock(Character.class);
        MapleMap map = map(OPQConstants.OPQ_STAGE_1);
        Character botInA = in(map, OPQConstants.OPQ_STAGE_1, leaderA);
        Character botInAToo = in(map, OPQConstants.OPQ_STAGE_1, leaderA);
        Character botInB = in(map, OPQConstants.OPQ_STAGE_1, leaderB);
        Character lobbyBot = in(map(OPQConstants.OPQ_LOBBY), OPQConstants.OPQ_LOBBY, leaderA);

        List<Character> leaders = OPQOrchestrator.leadersOf(
                List.of(botInA, botInAToo, botInB, lobbyBot));

        assertEquals(2, leaders.size(), "the lobby recruiter must not add a third run");
        assertSame(leaderA, leaders.get(0));
        assertSame(leaderB, leaders.get(1));
    }

    @Test
    void aPartyWhoseLeaderWentOfflineIsNotPolled() {
        Character bot = mock(Character.class);
        Party party = mock(Party.class);
        PartyCharacter offline = mock(PartyCharacter.class);
        when(offline.getPlayer()).thenReturn(null);
        when(party.getLeader()).thenReturn(offline);
        when(bot.getParty()).thenReturn(party);
        MapleMap map = map(OPQConstants.OPQ_STAGE_1);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(OPQConstants.OPQ_STAGE_1);

        assertTrue(OPQOrchestrator.leadersOf(List.of(bot)).isEmpty());
    }

    private static Character in(MapleMap map, int mapId, Character leader) {
        Character member = mock(Character.class);
        when(member.getMap()).thenReturn(map);
        when(member.getMapId()).thenReturn(mapId);
        partyOf(leader, member);
        return member;
    }
}
