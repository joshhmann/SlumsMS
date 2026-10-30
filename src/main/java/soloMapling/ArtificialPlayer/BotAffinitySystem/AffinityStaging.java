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

    /**
     * The level range a staged bot that does not fight is given. The floor is the one BotRecruitManager keeps:
     * a bot under level 10 ignores party invites outright, so a QA bot staged below it can never answer the
     * invite a check sends (auto-QA t-pb-1's rival came out at level 7 and the check saw no decision line).
     */
    static final int SOCIAL_MIN_LEVEL = 10;
    static final int SOCIAL_MAX_LEVEL = 45;

    private AffinityStaging() {
    }

    /**
     * 1.6.2 b092 (auto-QA t-af-1 failed 6 of 16 full runs): a staged social friend in the busy Free Market was drafted
     * into the town bots' cluster conversations, chatter and hot-potato barks (its log shows it chatting all run), and a
     * social bot only looks for players it likes while it is free of those, so it never greeted, invited or buffed the
     * player within the check's wait. A staged social bot is held out of that drafting; it still notices the player.
     */
    private static final java.util.Set<Integer> held = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Holds a staged bot out of the town chatter (stage does this for a social bot). */
    static void hold(int botId) {
        held.add(botId);
    }

    /** Lets a staged bot be drafted into town chatter again (the conversation checks want it talking). */
    public static void release(int botId) {
        held.remove(botId);
    }

    /** Whether this bot is a staged social affinity bot (never drafted into ambient town chatter). */
    public static boolean held(int botId) {
        return held.contains(botId);
    }

    /** The staged bot's character id, or -1 when no bot could be made. */
    public static int stage(Character gm, AffinityState state, boolean training) {
        int mobLevel = MapMobIndex.level(gm.getMapId());
        boolean fights = training && MapMobIndex.mobCount(gm.getMapId()) > 0;
        int slot = BotStore.nextDynamicSlots(1);
        // rc6 (QA t-pb-1): spread, on the ground; 162 runfix (t-cv-8): a social bot within talking distance
        Point at = fights ? soloMapling.ArtificialPlayer.StageSpots.nearGm(gm)
                : soloMapling.ArtificialPlayer.StageSpots.closeToGm(gm);
        // The level floor holds for a fighting staged bot too: a hostile one (a rival, a nemesis) fights, and on a
        // low-level mob map it came out under 10 - too young to be answered on a party invite (auto-QA t-pb-1).
        int id = fights
                ? BotGeneration.createBot(slot, at, gm.getMap(), 0, Math.max(SOCIAL_MIN_LEVEL, mobLevel),
                        Math.max(SOCIAL_MAX_LEVEL, mobLevel + 12))
                : BotGeneration.createBot(slot, at, gm.getMap(), 0, SOCIAL_MIN_LEVEL, SOCIAL_MAX_LEVEL);
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
            hold(id);
        }
        AffinityReactions.forgetBot(id); // a reused slot starts without the last holder's cooldowns (b092)
        BotAffinity.stage(id, gm.getId(), state);
        return id;
    }

    /** Takes a staged bot out of the world and forgets every tie it had. */
    public static void unstage(int botId) {
        WorldEventSteer.unhold(botId);
        soloMapling.ArtificialPlayer.StageSpots.unpin(botId);
        held.remove(botId);
        BotAffinity.forgetBot(botId);
        AffinityReactions.forgetBot(botId);
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
