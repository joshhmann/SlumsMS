package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;

import java.util.concurrent.ThreadLocalRandom;

/*
 * What a mob's touch takes off a bot: half the mob's physical attack, +/-20%, with a miss chance per job family
 * (thieves dodge more). The on-screen hit (GCMoveSystem.BotContactDamage) rolls it, and since real bots 08 the bot
 * really loses it; the offscreen grind (OffscreenSupplies) takes its average. One place, so both agree.
 */
public final class MobContactDamage {

    private MobContactDamage() {}

    static final double DMG_FACTOR = 0.5;    // of the mob's physical attack
    static final double DMG_SPREAD = 0.20;   // +/- around it
    static final double BASE_MISS_CHANCE = 0.10;
    static final double THIEF_MISS_CHANCE = 0.40;
    private static final int NICHE_THIEF = 4;

    public static double missChance(Character bot) {
        return bot != null && bot.getJob() != null && bot.getJob().getJobNiche() == NICHE_THIEF
                ? THIEF_MISS_CHANCE : BASE_MISS_CHANCE;
    }

    /* One touch: 0 for a miss, else the rolled damage (at least 1). */
    public static int roll(Character bot, int mobAttack) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (rng.nextDouble() < missChance(bot)) {
            return 0;
        }
        double spread = 1.0 + rng.nextDouble(-DMG_SPREAD, DMG_SPREAD);
        return (int) Math.max(1, Math.round(base(mobAttack) * spread));
    }

    /* The average a touch takes, misses included. */
    public static double average(Character bot, int mobAttack) {
        return (1.0 - missChance(bot)) * Math.max(1, base(mobAttack));
    }

    private static double base(int mobAttack) {
        return Math.max(1, mobAttack) * DMG_FACTOR;
    }
}
