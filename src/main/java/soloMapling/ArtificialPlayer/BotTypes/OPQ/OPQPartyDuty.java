package soloMapling.ArtificialPlayer.BotTypes.OPQ;

/**
 * What an OPQ lobby bot should do while it sits in someone's party waiting for the run to start.
 * <p>
 * Before this the only exit from the lobby was "the leader is in stage 1": a bot that accepted a player's invite
 * stayed in that party for ever, holding one of the party's slots, if the player invited it and then did something
 * else. A player also cannot tell from the outside whether the bot understood the invitation was for a different
 * party quest, so the bot has to give up on its own and go back to recruiting.
 */
public final class OPQPartyDuty {

    private OPQPartyDuty() {
    }

    public enum Duty {
        /** Leader is in stage 1: warp in and do the work. */
        FOLLOW,
        /** Give it a little longer. */
        WAIT,
        /** This party is not going to run OPQ with us: leave it and go back to recruiting. */
        LEAVE
    }

    /** A player may still be inviting the rest of the party - five minutes of standing in the lobby. */
    public static final long PRE_RUN_GRACE_MS = 300_000;
    /** The run just ended: keep the party together for a quick "again?" before leaving. */
    public static final long POST_RUN_GRACE_MS = 60_000;
    /** Leader somewhere else entirely, or already past stage 1: we cannot help from the lobby. */
    public static final long ELSEWHERE_GRACE_MS = 90_000;
    /** Leader's Character is offline (PartyCharacter.setOnline(false) nulls it). */
    public static final long LEADER_GONE_GRACE_MS = 15_000;

    /**
     * @param leaderMapId leader's current map, or -1 when there is no live leader
     * @param runStarted  the bot has been inside the PQ with this party before
     * @param inPartyMs   how long the bot has been waiting in this party
     */
    public static Duty decide(int leaderMapId, boolean runStarted, long inPartyMs) {
        if (leaderMapId == OPQConstants.OPQ_STAGE_1) {
            return Duty.FOLLOW;
        }
        if (leaderMapId < 0) {
            return waited(inPartyMs, LEADER_GONE_GRACE_MS);
        }
        if (leaderMapId == OPQConstants.OPQ_LOBBY) {
            return waited(inPartyMs, runStarted ? POST_RUN_GRACE_MS : PRE_RUN_GRACE_MS);
        }
        // Anywhere else: a different map, or an OPQ map we can no longer join from the lobby.
        return waited(inPartyMs, ELSEWHERE_GRACE_MS);
    }

    private static Duty waited(long inPartyMs, long graceMs) {
        return inPartyMs >= graceMs ? Duty.LEAVE : Duty.WAIT;
    }
}
