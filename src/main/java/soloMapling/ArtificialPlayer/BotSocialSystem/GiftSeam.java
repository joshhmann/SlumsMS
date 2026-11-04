package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

/**
 * The seam the social act GIVE_GIFT calls to hand something over. Stage 4 ships only {@link #AFFINITY_ONLY}: a gesture
 * with no item (a bond GIFT event for the receiver, GIFT_GIVEN for the giver, a GIFTED push and a memory), so nothing
 * is minted and the economy is untouched. The bot-to-bot gift primitive (an item that fits the receiver, taken from
 * the giver's own holdings) plugs in by replacing {@link SocialActions#giftSeam}; this interface is all it must
 * implement.
 */
public interface GiftSeam {

    /** What a gift was: {@code done} false means nothing changed (no item to give, refused); {@code what} is for the memory and trace. */
    record Result(boolean done, String what, String reason) {
        public static Result no(String reason) {
            return new Result(false, null, reason);
        }
    }

    /** Whether {@code giver} could give {@code receiver} something now (cheap, memory only). Null: yes; else why not. */
    default String blocker(int giverId, int receiverId, long now) {
        return null;
    }

    /** Hands it over now. Must never throw; must not mint anything. */
    Result give(int giverId, int receiverId, long now);

    /** The default: an affinity-only gesture, no item moves. */
    GiftSeam AFFINITY_ONLY = (giverId, receiverId, now) -> {
        BotBonds.record(receiverId, giverId, BondEvent.GIFT, now, false);       // the receiver: warm, and it owes the giver
        BotBonds.record(giverId, receiverId, BondEvent.GIFT_GIVEN, now, false); // the giver: a little fonder
        LifeMemory receiver = LifeMemoryStore.peek(receiverId);
        if (receiver != null) {
            receiver.push(SocialProfile.Push.GIFTED);
        }
        return new Result(true, "a small gift", null);
    };
}
