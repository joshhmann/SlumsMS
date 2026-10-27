package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import server.expeditions.Expedition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.0: which bots {@link ExpeditionBots} registered in an expedition, read on every bot's macro tick
 * ({@code BotCommitments}) and follow tick ({@code FollowerFight}). Kept apart from ExpeditionBots so that hot read
 * loads nothing but this map.
 */
public final class ExpeditionHold {

    private ExpeditionHold() {
    }

    /** Bot id -> the expedition it joined (released when the expedition is disposed). */
    private static final Map<Integer, Expedition> joined = new ConcurrentHashMap<>();

    static void hold(int botId, Expedition exped) {
        joined.put(botId, exped);
    }

    /** True while this bot is registered for an expedition that is still registering or that it is inside. */
    public static boolean committed(Character bot) {
        if (bot == null || joined.isEmpty()) {
            return false;
        }
        Expedition exped = joined.get(bot.getId());
        return exped != null && exped.hasMember(bot.getId())
                && (exped.isRegistering() || bot.getEventInstance() != null);
    }

    /** A joined bot inside its expedition's instance: it fights next to its leader ({@code FollowerFight}). */
    public static boolean inRun(Character bot) {
        return bot != null && bot.getEventInstance() != null && committed(bot);
    }

    static void release(Expedition exped) {
        joined.values().removeIf(e -> e == exped);
    }
}
