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

    /** {groom, bride} character ids, or null when no couple could be made here. */
    public static int[] stage(Character gm, boolean married) {
        Integer man = null;
        Integer woman = null;
        List<Integer> extra = new ArrayList<>();
        Point pos = gm.getPosition();
        for (int i = 0; i < 8 && (man == null || woman == null); i++) {
            int id = BotGeneration.createBot(BotStore.nextDynamicSlots(1), new Point(pos.x + 60 + 40 * i, pos.y), gm.getMap());
            Character bot = id > 0 ? BotHelpers.getCharFromChannelStorage(id) : null;
            if (bot == null) {
                continue;
            }
            if (bot.getGender() == 0 && man == null) {
                man = id;
            } else if (bot.getGender() == 1 && woman == null) {
                woman = id;
            } else {
                extra.add(id);
                continue;
            }
            BotTypeManager.convertBotType(bot, BotTypeManager.BotType.SOCIAL_BOT);
        }
        extra.forEach(CoupleStaging::remove);
        if (man == null || woman == null) {
            if (man != null) {
                remove(man);
            }
            if (woman != null) {
                remove(woman);
            }
            return null;
        }
        BotCouples.Couple c = BotCouples.form(man, woman, true, System.currentTimeMillis());
        if (c == null) {
            remove(man);
            remove(woman);
            return null;
        }
        if (married) {
            BotCouples.marry(c, System.currentTimeMillis());
        }
        return new int[]{man, woman};
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
