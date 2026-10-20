package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.inventory.WeaponType;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.OffscreenCombat;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Real bots 08: what an offscreen grind uses up, at the rates the same fight runs at on screen. Over each stretch of
 * grinding (TrainingBot's macro tick) the bot swings OffscreenCombat's swings a minute of the attack it worked out,
 * and so:
 *  - fires that attack's ammo a swing (BotAmmo: bulletCount, Shadow Partner, Soul Arrow; Shadow Stars costs its 200
 *    stars a cast, once per its duration);
 *  - pays that attack's MP a swing (BotVitals.mpCost);
 *  - takes the mobs' touches: one in ten kills, as measured on screen, never more often than the 2.5 s the bot is
 *    invulnerable after a hit, each the average of the on-screen roll (MobContactDamage);
 *  - recovers its 10-second HP/MP tick throughout;
 * and drinks potions the way the observed bot does (under half HP, under 30% MP). Fractions carry over to the next
 * stretch, so short stretches add up to the same totals.
 *
 * When a supply would run out part way, the bot only grinds the part it could afford: accrue() returns that share of
 * the stretch, which TrainingBot applies to the EXP and loot it accrues, and leaves the bot where running out leaves
 * it (out of ammo, at a quarter HP, short of MP), so BotSupplies.shortage sends it to town just as it would on screen.
 */
public final class OffscreenSupplies {

    private OffscreenSupplies() {}

    private static final Logger log = LoggerFactory.getLogger(OffscreenSupplies.class);

    // The mobs' touches a kill, from the headless check on 164 with every bot observed: melee bots drifted down only
    // slowly (a lv 80 Dragon Knight at ~47 kills a minute lost ~130 HP a minute net of recovery) and ranged bots drank
    // HP potions at about the rate this gives. The first guess (0.6 melee, 0.2 ranged) drank several times what the
    // same bots drank on screen. One value for both: a ranged bot standing still is walked into about as often as a
    // melee bot, which kills most mobs before they reach it.
    static final double HITS_PER_KILL = 0.1;
    // The invulnerability after a hit (BotContactDamage's 2.5 s) caps how often the bot can be touched.
    static final double MAX_HITS_PER_MINUTE = 60.0 / 2.5;

    // botId -> {ammo, hp, mp} fractions still owed from earlier stretches.
    private static final Map<Integer, double[]> carry = new ConcurrentHashMap<>();

    /* What one stretch of offscreen grinding costs, before it is paid. */
    record Bill(double ammo, double hp, double mp) {
    }

    /* The bill for {@code seconds} of grinding at this pace (negative hp/mp: recovery outpaces the cost). */
    static Bill bill(Character bot, OffscreenCombat.Rate rate, WeaponType weapon, double seconds) {
        double minutes = seconds / 60.0;
        double swings = rate.swingsPerMinute() * minutes;

        double ammo = 0;
        if (rate.route() == BotAttackProfile.Route.RANGED && BotAmmo.needsAmmo(bot, weapon, rate.skillId())) {
            if (BotAmmo.showsShadowStars(bot, weapon)) {
                ammo = BotAmmo.shadowStarsCost(bot) * seconds * 1000.0 / BotAmmo.shadowStarsMs(bot);
            } else {
                ammo = swings * BotAmmo.usedPerSwing(bot, weapon, rate.skillId(), rate.skillLevel());
            }
        }

        double hits = Math.min(MAX_HITS_PER_MINUTE, rate.killsPerMinute() * HITS_PER_KILL) * minutes;
        double ticks = seconds * 1000.0 / BotVitals.REGEN_TICK_MS;
        double hp = hits * MobContactDamage.average(bot, rate.mobAttack())
                + swings * BotVitals.hpCost(rate.skillId(), rate.skillLevel())
                - ticks * BotVitals.hpRegenPerTick(bot);
        double mp = swings * BotVitals.mpCost(bot, rate.skillId(), rate.skillLevel())
                - ticks * BotVitals.mpRegenPerTick(bot);
        return new Bill(ammo, hp, mp);
    }

    /*
     * Pays for {@code seconds} of offscreen grinding at this pace out of the bot's real ammo, HP, MP and potions.
     * Returns the share of the stretch (0..1) the bot could afford to grind; null rate (a map whose mob cannot be
     * built, ground at the old abstract pace) costs only recovery.
     */
    public static double accrue(Character bot, OffscreenCombat.Rate rate, double seconds) {
        if (bot == null || seconds <= 0) {
            return 1.0;
        }
        if (rate == null || rate.swingsPerMinute() <= 0) {
            BotVitals.regen(bot, (long) (seconds * 1000));
            return 1.0;
        }
        return accrue(bot, rate, BotAttack.resolveEquippedWeaponType(bot), seconds);
    }

    static double accrue(Character bot, OffscreenCombat.Rate rate, WeaponType weapon, double seconds) {
        Bill bill = bill(bot, rate, weapon, seconds);
        synchronized (BotVitals.lock(bot)) {
            int ammoBefore = log.isDebugEnabled() ? BotAmmo.held(bot, weapon) : 0;
            int hpPotsBefore = log.isDebugEnabled() ? BotVitals.potionCount(bot, true) : 0;
            int mpPotsBefore = log.isDebugEnabled() ? BotVitals.potionCount(bot, false) : 0;
            double[] owed = carry.computeIfAbsent(bot.getId(), id -> new double[3]);
            int retreatHp = (int) (bot.getCurrentMaxHp() * BotVitals.HP_RETREAT_AT);
            double ammoShare = 1.0;
            double hpShare = 1.0;
            double mpShare = 1.0;
            if (bill.ammo() > 0) {
                ammoShare = BotAmmo.held(bot, weapon) / (bill.ammo() + owed[0]);
            }
            if (bill.hp() > 0) {
                double spare = bot.getHp() - retreatHp + BotVitals.potionTotal(bot, true);
                hpShare = Math.max(0, spare) / (bill.hp() + owed[1]);
            }
            if (bill.mp() > 0) {
                double spare = bot.getMp() + BotVitals.potionTotal(bot, false);
                mpShare = Math.max(0, spare) / (bill.mp() + owed[2]);
            }
            double share = Math.max(0, Math.min(1, Math.min(ammoShare, Math.min(hpShare, mpShare))));

            // Ammo: whole shots fired, the fraction owed.
            owed[0] += bill.ammo() * share;
            int shots = (int) owed[0];
            if (shots > 0) {
                int fired = BotAmmo.fire(bot, weapon, shots);
                owed[0] = fired < shots ? 0 : owed[0] - shots;   // ran dry: nothing more is owed
            }
            // HP and MP: the cost (or recovery) of the share fought. The rest of the stretch the bot spends leaving
            // for town, where it rests (BotSupplies.visitTown).
            owed[1] += bill.hp() * share;
            owed[2] += bill.mp() * share;
            int hpChange = (int) owed[1];   // toward zero: positive is damage, negative recovery
            int mpChange = (int) owed[2];
            owed[1] -= hpChange;
            owed[2] -= mpChange;
            payAndPot(bot, hpChange, mpChange);
            if (hpShare < 1 && hpShare <= Math.min(ammoShare, mpShare)) {
                // HP is what ran out: the bot ends where it turns back, at the retreat line (not a rounding over it).
                BotVitals.setHp(bot, Math.max(1, Math.min(bot.getHp(), retreatHp - 1)));
            }
            if (log.isDebugEnabled()) {
                log.debug("Offscreen supplies: bot {} ({}) lv {} {} {}s at {} swings/min (skill {}): ground {}%, ammo {}->{}, "
                                + "HP pots {}->{}, MP pots {}->{}, HP {}/{}, MP {}/{}",
                        bot.getId(), bot.getName(), bot.getLevel(), bot.getJob(), Math.round(seconds),
                        String.format("%.1f", rate.swingsPerMinute()), rate.skillId(), Math.round(share * 100),
                        ammoBefore, BotAmmo.held(bot, weapon), hpPotsBefore, BotVitals.potionCount(bot, true),
                        mpPotsBefore, BotVitals.potionCount(bot, false), bot.getHp(), bot.getCurrentMaxHp(),
                        bot.getMp(), bot.getCurrentMaxMp());
            }
            return share;
        }
    }

    // Takes the stretch's HP/MP off (or gives the recovery back), drinking potions as the bot goes the way the
    // observed bot does: under half HP or 30% MP, a potion at a time, never below 1 HP.
    private static void payAndPot(Character bot, int hpCost, int mpCost) {
        int maxHp = bot.getCurrentMaxHp();
        int maxMp = bot.getCurrentMaxMp();
        if (hpCost <= 0 && mpCost <= 0) {
            BotVitals.setHpMp(bot, Math.min(maxHp, bot.getHp() - hpCost), Math.min(maxMp, bot.getMp() - mpCost));
            return;
        }
        // Lose it in pieces no bigger than what a potion is drunk at, so the potions land where they would on screen.
        long hpLeft = Math.max(0, hpCost);
        long mpLeft = Math.max(0, mpCost);
        int hp = Math.min(maxHp, bot.getHp() - Math.min(0, hpCost));
        int mp = Math.min(maxMp, bot.getMp() - Math.min(0, mpCost));
        BotVitals.setHpMp(bot, hp, mp);
        int hpStep = Math.max(1, (int) (maxHp * (1 - BotVitals.HP_POT_AT)));
        int mpStep = Math.max(1, (int) (maxMp * (1 - BotVitals.MP_POT_AT)));
        for (int guard = 0; (hpLeft > 0 || mpLeft > 0) && guard < 100_000; guard++) {
            int hpTake = (int) Math.min(hpLeft, hpStep);
            int mpTake = (int) Math.min(mpLeft, mpStep);
            hpLeft -= hpTake;
            mpLeft -= mpTake;
            BotVitals.setHpMp(bot, Math.max(1, bot.getHp() - hpTake), Math.max(0, bot.getMp() - mpTake));
            BotVitals.topUp(bot);
        }
    }

    static void clearBot(int botId) {
        carry.remove(botId);
    }
}
