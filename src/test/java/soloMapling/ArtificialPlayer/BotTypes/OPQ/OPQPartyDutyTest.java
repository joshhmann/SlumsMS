package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "PQ bots are not great": a bot that accepted a player's party invite never left that party unless the leader
 * walked into stage 1. Invite a lobby bot by mistake, or change your mind, and the slot was gone for the session.
 */
class OPQPartyDutyTest {

    private static final int HENESYS = 100000000;

    @Test
    void leaderInStageOneMeansWarpInAndWork() {
        assertEquals(OPQPartyDuty.Duty.FOLLOW, OPQPartyDuty.decide(OPQConstants.OPQ_STAGE_1, false, 0));
        assertEquals(OPQPartyDuty.Duty.FOLLOW,
                OPQPartyDuty.decide(OPQConstants.OPQ_STAGE_1, false, OPQPartyDuty.PRE_RUN_GRACE_MS * 10));
    }

    @Test
    void aLeaderAssemblingThePartyInTheLobbyHasFiveMinutes() {
        assertEquals(OPQPartyDuty.Duty.WAIT, OPQPartyDuty.decide(OPQConstants.OPQ_LOBBY, false,
                OPQPartyDuty.PRE_RUN_GRACE_MS - 1));
        assertEquals(OPQPartyDuty.Duty.LEAVE, OPQPartyDuty.decide(OPQConstants.OPQ_LOBBY, false,
                OPQPartyDuty.PRE_RUN_GRACE_MS));
    }

    @Test
    void afterARunTheLobbyWaitIsShort() {
        assertEquals(OPQPartyDuty.Duty.WAIT, OPQPartyDuty.decide(OPQConstants.OPQ_LOBBY, true,
                OPQPartyDuty.POST_RUN_GRACE_MS - 1));
        assertEquals(OPQPartyDuty.Duty.LEAVE, OPQPartyDuty.decide(OPQConstants.OPQ_LOBBY, true,
                OPQPartyDuty.POST_RUN_GRACE_MS));
    }

    @Test
    void aLeaderWhoWanderedOffIsGivenNinetySeconds() {
        assertEquals(OPQPartyDuty.Duty.WAIT, OPQPartyDuty.decide(HENESYS, false, 0));
        assertEquals(OPQPartyDuty.Duty.LEAVE, OPQPartyDuty.decide(HENESYS, false, OPQPartyDuty.ELSEWHERE_GRACE_MS));
    }

    @Test
    void aRunWeMissedCannotBeJoinedFromTheLobby() {
        // Leader is already in the tower or stage 2: we are in the lobby with nothing to do.
        assertEquals(OPQPartyDuty.Duty.LEAVE,
                OPQPartyDuty.decide(OPQConstants.OPQ_STAGE_2, false, OPQPartyDuty.ELSEWHERE_GRACE_MS));
    }

    @Test
    void anOfflineLeaderReleasesTheSlotQuickly() {
        assertEquals(OPQPartyDuty.Duty.WAIT, OPQPartyDuty.decide(-1, false, 0));
        assertEquals(OPQPartyDuty.Duty.LEAVE, OPQPartyDuty.decide(-1, false, OPQPartyDuty.LEADER_GONE_GRACE_MS));
    }
}
