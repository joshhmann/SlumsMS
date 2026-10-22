package soloMapling.ArtificialPlayer.BotCoupleSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypeManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * GM and {@code !qa} staging for ticket 58's couples: two new on-demand bots (a man and a woman) next to the GM that are
 * a couple already, kept in memory only. {@link #unstage} breaks them up and takes them away.
 */
public final class CoupleStaging {

    private CoupleStaging() {
    }

    /** {first, second} character ids of a staged couple (any genders), or null. */
    public static int[] stage(Character gm, boolean married) {
        return stage(gm, married ? BotCouples.Stage.MARRIED : BotCouples.Stage.DATING, null);
    }

    /**
     * Two new on-demand bots next to the GM that are a couple (DATING or MARRIED) or best friends (FRIENDS).
     * {@code sameGender}: true = both the same gender, false = one of each, null = whatever comes. {first, second}
     * character ids, or null when no pair could be made here.
     */
    public static int[] stage(Character gm, BotCouples.Stage stage, Boolean sameGender) {
        Integer first = null;
        Integer second = null;
        List<Integer> extra = new ArrayList<>();
        Point pos = gm.getPosition();
        for (int i = 0; i < 10 && second == null; i++) {
            int id = BotGeneration.createBot(BotStore.nextDynamicSlots(1), new Point(pos.x + 60 + 40 * i, pos.y), gm.getMap());
            Character bot = id > 0 ? BotHelpers.getCharFromChannelStorage(id) : null;
            if (bot == null) {
                continue;
            }
            if (first == null) {
                first = id;
            } else {
                Character a = BotHelpers.getCharFromChannelStorage(first);
                boolean same = a != null && a.getGender() == bot.getGender();
                if (sameGender != null && same != sameGender) {
                    extra.add(id);
                    continue;
                }
                second = id;
            }
            BotTypeManager.convertBotType(bot, BotTypeManager.BotType.SOCIAL_BOT);
        }
        extra.forEach(CoupleStaging::remove);
        if (first == null || second == null) {
            if (first != null) {
                remove(first);
            }
            return null;
        }
        long now = System.currentTimeMillis();
        BotCouples.Couple c = BotCouples.form(first, second, stage == BotCouples.Stage.FRIENDS
                ? BotCouples.Stage.FRIENDS : BotCouples.Stage.DATING, true, now);
        if (c == null) {
            remove(first);
            remove(second);
            return null;
        }
        if (stage == BotCouples.Stage.MARRIED) {
            BotCouples.marry(c, now);
        }
        return new int[]{first, second};
    }

    /** Breaks the staged couple up and takes both bots away. */
    public static void unstage(int[] ids) {
        if (ids == null) {
            return;
        }
        for (int id : ids) {
            BotCouples.Couple c = BotCouples.coupleOf(id);
            if (c != null) {
                BotCouples.breakUp(c.id());
            }
            remove(id);
        }
    }

    static void remove(int id) {
        Character bot = BotHelpers.getCharFromChannelStorage(id);
        if (bot == null) {
            return;
        }
        BotSM sm = CharacterStorage.getBotById(id);
        if (sm != null) {
            sm.setRunning(false);
            sm.stopScheduledTask();
        }
        BotGeneration.removeBotFromServer(bot);
    }
}
