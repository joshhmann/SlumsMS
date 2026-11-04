package soloMapling.ArtificialPlayer.BotSocialSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;

/**
 * The live {@link GiftSeam}: GIVE_GIFT hands over what {@link BotBotGift} picks (an equip that fits the receiver, potions
 * it is short of, or a gesture between a warm pair). {@code BotBotGift} keeps its own limits (per giver, per pair, per
 * receiver a day, per hour, a value cap) and records the receiver's bond and push; a switched-off or refused gift is a
 * refused act, never a fake one ({@code -Dslums.botgifts=false} stops GIVE_GIFT altogether).
 */
final class BotGiftSeam implements GiftSeam {

    private static Character chr(int id) {
        BotSM sm = CharacterStorage.getBotById(id);
        return sm == null ? null : sm.getChr();
    }

    @Override
    public String blocker(int giverId, int receiverId, long now) {
        return BotBotGift.refusal(chr(giverId), chr(receiverId), now);
    }

    @Override
    public Result give(int giverId, int receiverId, long now) {
        Character giver = chr(giverId);
        Character receiver = chr(receiverId);
        var plan = BotBotGift.choose(giver, receiver, null, now);
        if (plan.isEmpty()) {
            return Result.no("nothing worth giving");
        }
        BotBotGift.Result r = BotBotGift.give(plan.get());
        return r.ok() ? new Result(true, plan.get().describe(), null) : Result.no(r.why());
    }
}
