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
        // TEST t-we-1 2026-10-26 22:28 ("same"): ten bots were made and none matched the FIRST one's gender, so the run
        // was refused, though the ten held a same-gender pair many times over. Any two of the bots made so far pair up
        // now (for "same" three bots always hold a pair), and "mixed" gets more tries (65% of new bots are male).
        List<Integer> made = new ArrayList<>();
        List<Integer> genders = new ArrayList<>();
        int[] pair = null;
        for (int i = 0; i < MAX_TRIES && pair == null; i++) {
            // rc6 (QA t-pb-1): spread near the GM on the ground (was a row 40 px apart); the rest near the first
            Character firstBot = made.isEmpty() ? null : BotHelpers.getCharFromChannelStorage(made.get(0));
            Point at = firstBot == null ? soloMapling.ArtificialPlayer.StageSpots.nearGm(gm)
                    : soloMapling.ArtificialPlayer.StageSpots.nearLevel(gm.getMap(), firstBot.getPosition()); // same height
            int id = BotGeneration.createBot(BotStore.nextDynamicSlots(1), at, gm.getMap());
            Character bot = id > 0 ? BotHelpers.getCharFromChannelStorage(id) : null;
            if (bot == null) {
                continue;
            }
            made.add(id);
            genders.add(bot.getGender());
            pair = pickPair(genders, sameGender);
        }
        Integer first = pair == null ? null : made.get(pair[0]);
        Integer second = pair == null ? null : made.get(pair[1]);
        for (int id : made) {
            if (!Integer.valueOf(id).equals(first) && !Integer.valueOf(id).equals(second)) {
                remove(id);
            }
        }
        if (first == null) {
            return null;
        }
        for (int id : new int[]{first, second}) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot != null) {
                BotTypeManager.convertBotType(bot, BotTypeManager.BotType.SOCIAL_BOT);
            }
            soloMapling.ArtificialPlayer.StageSpots.pin(id); // rc6 (QA cv-1): the pair stays by the GM
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

    /** Bots made at most for one staged pair. */
    static final int MAX_TRIES = 20;

    /**
     * Indexes {i, j} (i &lt; j) of the first pair among {@code genders} that fits: the same gender ({@code same} true),
     * one of each (false) or any two (null); null when none does yet.
     */
    static int[] pickPair(List<Integer> genders, Boolean same) {
        for (int j = 1; j < genders.size(); j++) {
            for (int i = 0; i < j; i++) {
                if (same == null || genders.get(i).equals(genders.get(j)) == same) {
                    return new int[]{i, j};
                }
            }
        }
        return null;
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
        soloMapling.ArtificialPlayer.StageSpots.unpin(id);
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
