package soloMapling.ArtificialPlayer.BotQuestSystem;

import java.util.Map;
import java.util.Set;

/**
 * Ticket 158 item 3: is a kill-and-turn-in quest worth it, measured in the bot's own grind currency (EXP per minute on
 * the map it is grinding)? Ported from nutnnut/Cosmic 77e0c5bc9 ({@code server.bots.BotQuestScorer}, AGPL-3.0); the
 * talk-quest half is left out (we only run mob quests).
 *
 * <p><b>Value</b> (EXP-equivalent the detour yields): the reward EXP (scaled to the bot's kill-EXP units), plus the
 * kill EXP of required mobs that are NOT on its grind map (it earns those on the detour), plus a bonus the caller may
 * pass (0 by default: mesos are not EXP).
 *
 * <p><b>Cost</b> (EXP the bot gives up): its grind EXP/min over the time spent not grinding, which is the round trip
 * to the NPCs plus the time to kill required mobs that are NOT on its grind map.
 *
 * <p>Ours: required mobs on the grind map are neither value nor cost (the bot kills them anyway). Upstream counted
 * their kill EXP as value, which made a zero-reward quest next to town look better than grinding.
 *
 * <p><b>Score</b> = value / cost, a multiple of the grind baseline: 1.0 breaks even, above 1.0 beats grinding. Pure
 * over numbers, so it unit-tests without WZ or a server.
 */
public final class BotQuestScorer {

    /** A bot takes a quest on its own only when it beats its grind by this much (players skip marginal quests). */
    public static final double TAKE_MIN_SCORE = 1.5;
    /** "recommend a quest": anything that at least breaks even. */
    public static final double RECOMMEND_MIN_SCORE = 1.0;
    /** A baseline at or below this (EXP/min) counts as "barely grinding": avoids dividing by almost nothing. */
    static final double MIN_BASELINE_EXP_PER_MINUTE = 1.0;

    private BotQuestScorer() {
    }

    /** Per-mob EXP. */
    @FunctionalInterface
    public interface MobExp {
        int expFor(int mobId);
    }

    /**
     * @param mobs              required mob id to kills needed
     * @param rewardExp         complete-action EXP, already scaled to the bot's kill-EXP units
     * @param bonus             extra EXP-equivalent value (0 for none)
     * @param overlapMobs       the required mobs that spawn on the bot's grind map
     * @param mobExp            per-mob EXP
     * @param killSecondsPerMob seconds the bot takes per kill
     * @param travelSeconds     round trip to the start and end NPCs
     * @param grindExpPerMinute the bot's grind EXP/min (the baseline)
     * @return value / cost (above 1 beats grinding); 0 when the quest is worth nothing
     */
    public static double score(Map<Integer, Integer> mobs, int rewardExp, double bonus, Set<Integer> overlapMobs,
                               MobExp mobExp, double killSecondsPerMob, double travelSeconds, double grindExpPerMinute) {
        double baseline = Math.max(MIN_BASELINE_EXP_PER_MINUTE, grindExpPerMinute);
        double value = Math.max(0, rewardExp) + Math.max(0, bonus);
        long otherKills = 0;
        for (Map.Entry<Integer, Integer> need : mobs.entrySet()) {
            if (!overlapMobs.contains(need.getKey())) {
                otherKills += need.getValue();
                value += (double) need.getValue() * Math.max(0, mobExp.expFor(need.getKey()));
            }
        }
        if (value <= 0) return 0.0;
        double costSeconds = Math.max(0, travelSeconds) + otherKills * Math.max(0.0, killSecondsPerMob);
        double forgone = costSeconds / 60.0 * baseline;
        if (forgone <= 0.0) return Double.MAX_VALUE; // NPCs on the grind map and every kill here: pure win
        return value / forgone;
    }
}
