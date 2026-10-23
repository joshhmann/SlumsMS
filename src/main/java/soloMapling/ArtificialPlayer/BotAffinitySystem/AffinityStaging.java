package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
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
 * GM and {@code !qa} staging for ticket 58: a fresh on-demand bot next to the GM that already feels a chosen way about
 * them. A rival or nemesis (or {@code training}) is a training bot held on the GM's map so it fights there and can KS;
 * a friend (or {@code social}) is a social bot that hangs around. {@link #unstage} takes it away and forgets its ties.
 */
public final class AffinityStaging {

    private AffinityStaging() {
    }

    /** The staged bot's character id, or -1 when no bot could be made. */
    public static int stage(Character gm, AffinityState state, boolean training) {
        int mobLevel = MapMobIndex.level(gm.getMapId());
        boolean fights = training && MapMobIndex.mobCount(gm.getMapId()) > 0;
        int slot = BotStore.nextDynamicSlots(1);
        Point at = soloMapling.ArtificialPlayer.StageSpots.nearGm(gm); // rc6 (QA t-pb-1): spread, on the ground
        int id = fights
                ? BotGeneration.createBot(slot, at, gm.getMap(), 0, Math.max(1, mobLevel), Math.max(10, mobLevel + 12))
                : BotGeneration.createBot(slot, at, gm.getMap());
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
        }
        BotAffinity.stage(id, gm.getId(), state);
        return id;
    }

    /** Takes a staged bot out of the world and forgets every tie it had. */
    public static void unstage(int botId) {
        WorldEventSteer.unhold(botId);
        BotAffinity.forgetBot(botId);
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
