package soloMapling.ArtificialPlayer;

import java.util.SplittableRandom;

/**
 * A bot's fame (1.4.2, owner QA "fame numbers are totally out of spec"): bots used to get their character id as fame
 * (a debug leftover: ids start at 1,000,000,000, which the v83 client shows as nonsense). Fame is now what a player of
 * that level plausibly has: most bots little (a few people famed them), some a fair amount, a few a lot, and an
 * occasional small negative. It is derived from the bot's id and level, so a bot shows the same fame every time it is
 * loaded at a level and gains a little as it levels - no new column, nothing to save.
 */
public final class BotFame {
    /** Real players' fame never gets near the client's short range; keep bots well inside it too. */
    static final int MAX_FAME = 30_000;

    private BotFame() {
    }

    public static int forBot(int botId, int level) {
        int lvl = Math.max(1, level);
        SplittableRandom rng = new SplittableRandom(0x5EEDF4AEL ^ (long) botId * 0x9E3779B97F4A7C15L);
        double band = rng.nextDouble();
        double perLevel;
        if (band < 0.60) {
            perLevel = rng.nextDouble(0.0, 0.15);       // most: barely famed
        } else if (band < 0.90) {
            perLevel = rng.nextDouble(0.15, 0.50);      // social players
        } else {
            perLevel = rng.nextDouble(0.50, 1.20);      // well known
        }
        int fame = (int) Math.round(lvl * perLevel);
        if (rng.nextDouble() < 0.06) {                  // defamed a few times
            fame = -(1 + rng.nextInt(Math.min(20, lvl / 5 + 1)));
        }
        return Math.max(-MAX_FAME, Math.min(MAX_FAME, fame));
    }
}
