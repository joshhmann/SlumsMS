package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

/**
 * The seam the social act GIVE_GIFT calls to hand something over. {@link BotGiftSeam} is the live one: it is
 * {@code BotBotGift} (an equip that fits the receiver, potions it is short of, or a gesture; nothing is minted).
 * {@link #AFFINITY_ONLY} is the affinity-only gesture with no item, for tests and for a build without the gift
 * primitive. Replace {@link SocialActions#giftSeam} to plug in another.
 *
 * <p>Contract: {@link #give} hands the thing over and records the RECEIVER's side (a bond GIFT event on its edge to the
 * giver and the GIFTED push on its mind), as {@code BotBotGift.give} does; the act adds the giver's own edge
 * (GIFT_GIVEN), both memories and the feelings.
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
        BotBonds.record(receiverId, giverId, BondEvent.GIFT, now, false); // the receiver: warm, and it owes the giver
        LifeMemory receiver = LifeMemoryStore.peek(receiverId);
        if (receiver != null) {
            receiver.push(SocialProfile.Push.GIFTED);
        }
        return new Result(true, "a small gift", null);
    };
}
