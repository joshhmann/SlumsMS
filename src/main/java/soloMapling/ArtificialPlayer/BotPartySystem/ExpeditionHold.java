package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import server.expeditions.Expedition;

import java.util.ArrayList;
import java.util.List;
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
        // 1.6.7 alt-revive-respawn: the run is over for these bots, so their death counts and paid revives go with it
        joined.forEach((id, e) -> {
            if (e == exped) {
                CompanionBossRevive.runEnded(id);
            }
        });
        joined.values().removeIf(e -> e == exped);
    }

    /** The bots this expedition still holds, so the leader can be told which of them a start will leave behind. */
    static List<Integer> botsOf(Expedition exped) {
        List<Integer> ids = new ArrayList<>();
        joined.forEach((id, e) -> {
            if (e == exped) {
                ids.add(id);
            }
        });
        return ids;
    }
}
