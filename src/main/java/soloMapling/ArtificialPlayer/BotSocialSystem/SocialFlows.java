package soloMapling.ArtificialPlayer.BotSocialSystem;

/**
 * The existing bot-to-bot flows the registry wraps, behind one seam: the bot-talk greeting and chat, the help-train
 * ask and the help trip, the party / come-along invitation, the gossip pass. {@code BotConvo.GameSocialFlows} is the real
 * one (it calls {@code BotTalk}, {@code LfgParty}, {@code BotGossip}, {@code BotWorldGossip}); a test or simulation
 * plugs in a recording fake. Every method must be safe on a bot's own thread and must not throw.
 */
public interface SocialFlows {

    /** What a flow did: {@code ok} it happened, {@code reason} a short phrase for the log. */
    record Flow(boolean ok, String reason) {
        public static Flow no(String reason) {
            return new Flow(false, reason);
        }

        public static Flow yes(String reason) {
            return new Flow(true, reason);
        }
    }

    /**
     * Memory-only check that the flow could start now (null) or why not. For GREET, CONVERSE, ASK_HELP, OFFER_HELP,
     * SHARE_INFO and SPREAD_RUMOR.
     */
    String blocker(SocialActKind kind, int actorId, int targetId, long now);

    /** Runs the flow now (the existing talk, help or gossip path). */
    Flow run(SocialActKind kind, int actorId, int targetId, long now);

    /**
     * The invitation the actor could make to the target now (a plain duo party, a PQ party the LFG rules allow, a
     * come-along to a town), or null. The payload is whatever {@link #accept} needs; a reserved slot of a cap is held
     * until {@link #accept} or {@link #release}.
     */
    SocialActions.Invite propose(int actorId, int targetId, long now);

    /** The invited bot said yes: carries the invitation out (forms the party, starts both trips). */
    Flow accept(SocialActions.Invite invite, long now);

    /** The invitation lapsed or was declined: gives back whatever it reserved. */
    void release(SocialActions.Invite invite);
}
