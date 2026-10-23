package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.worldevents.WorldEventSteer;

import java.awt.Point;

/**
 * GM and {@code !qa} staging for the drama package: a new on-demand bot next to the GM (never stored, gone at stop)
 * that already feels a chosen way about them, at a chosen level, as a social bot that hangs around or a training bot
 * held on the GM's map.
 */
public final class DramaStaging {

    private DramaStaging() {
    }

    /**
     * A new bot {@code offset} px from the GM feeling {@code state} about them. {@code level} 0 = the map's level
     * (training) or any (social). {@code training}: it fights here (held on this map). Its id, or -1.
     */
    public static int stage(Character gm, AffinityState state, boolean training, int level, int offset) {
        int mobLevel = MapMobIndex.level(gm.getMapId());
        boolean fights = training && MapMobIndex.mobCount(gm.getMapId()) > 0;
        int slot = BotStore.nextDynamicSlots(1);
        // rc6 (QA t-pb-1): either side at a random distance, on the ground, clear of everyone (was a row at +offset)
        Point at = soloMapling.ArtificialPlayer.StageSpots.nearGm(gm);
        int lo = level > 0 ? level : fights ? Math.max(1, mobLevel) : 0;
        int hi = level > 0 ? level : fights ? Math.max(10, mobLevel + 12) : 0;
        int id = lo > 0 ? BotGeneration.createBot(slot, at, gm.getMap(), 0, lo, hi) : BotGeneration.createBot(slot, at,
                gm.getMap());
        if (id <= 0) {
            return -1;
        }
        Character bot = BotHelpers.getCharFromChannelStorage(id);
        if (bot == null) {
            return -1;
        }
        if (fights) {
            WorldEventSteer.hold(id, gm.getMapId());
            BotTypeManager.convertBotType(bot, BotTypeManager.BotType.TRAINING_BOT);
        } else {
            BotTypeManager.convertBotType(bot, BotTypeManager.BotType.SOCIAL_BOT);
            soloMapling.ArtificialPlayer.StageSpots.pin(id); // rc6 (QA cv-1): it stays next to the GM
        }
        if (state != null && state != AffinityState.NEUTRAL) {
            BotAffinity.stage(id, gm.getId(), state);
        }
        return id;
    }

    /** Takes a staged bot away: its ties, its staged romances and couple, its scene holds, then the bot itself. */
    public static void unstage(int botId) {
        WorldEventSteer.unhold(botId);
        soloMapling.ArtificialPlayer.StageSpots.unpin(botId);
        DramaKit.unbusy(botId);
        BotAffinity.forgetBot(botId);
        BotRomance.forgetBot(botId);
        BotCouples.Couple c = BotCouples.coupleOf(botId);
        if (c != null && c.staged()) {
            BotCouples.breakUp(c.id());
        }
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        if (bot == null) {
            return;
        }
        BotSM sm = CharacterStorage.getBotById(botId);
        if (sm != null) {
            sm.setRunning(false);
            sm.stopScheduledTask();
        }
        BotGeneration.removeBotFromServer(bot);
    }
}
