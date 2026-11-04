package soloMapling.ArtificialPlayer.BotAttackSystem;

/**
 * 1.5.0 rc3: AoE or single target, the way a player of the class picks (owner decision 1).
 * <ul>
 *   <li>The AoE (mobbing) skill when {@code minMobs} (3) or more mobs are in its reach;</li>
 *   <li>the single-target skill on a boss and on a lone mob or a pair;</li>
 *   <li>now and then the other attack (a bot's own {@code sloppy} chance): a single-target swing into a pack, or an
 *       AoE thrown at two mobs. Never a boss AoE, never an attack the class does not have.</li>
 * </ul>
 * Pure, so the rule is tested on its own; {@link BotAttackDriver} feeds it the live counts.
 */
public final class BotSkillChoice {

    /** 1.6.4 boss lane (ticket 130 phase 5 phase 2, from ticket 176 C's b087 gate): prefer the attack with the higher damage floor. */
    public static final String FLOOR_SWITCH = "slums.bossfight.damagefloor";

    /** The formula's own floor: a line that still did nothing after the target's defence ({@code BotCombatFormula.clamp}). */
    static final double FLOOR_HIT = 1.0;

    private BotSkillChoice() {
    }

    /** Off by default: with it off no floor is measured and the pick is exactly the rule above. */
    public static boolean floorOn() {
        return Boolean.parseBoolean(System.getProperty(FLOOR_SWITCH, "false").trim());
    }

    /** What the choice was and why, for the cb-1 log. */
    public enum Why { PACK, FEW, BOSS, NO_AOE, SLOPPY_SINGLE, SLOPPY_AOE, FLOOR }

    public record Pick(boolean aoe, Why why) {
    }

    /**
     * @param packAttack  the class has an AoE ready now
     * @param inReach     mobs inside that AoE's reach
     * @param boss        the nearest target is a boss
     * @param minMobs     mobs needed for the AoE (BOT_COMBAT_AOE_MIN_MOBS)
     * @param hasSingle   the class has a single-target attack ready now
     * @param sloppyRoll  this cast is one of the bot's sloppy ones
     */
    public static Pick choose(boolean packAttack, int inReach, boolean boss, int minMobs, boolean hasSingle,
                              boolean sloppyRoll) {
        if (!packAttack) {
            return new Pick(false, Why.NO_AOE);
        }
        if (boss) {
            return new Pick(!hasSingle, Why.BOSS); // a class with no single-target attack still has to hit it
        }
        boolean pack = inReach >= Math.max(1, minMobs);
        if (sloppyRoll) {
            if (pack && hasSingle) {
                return new Pick(false, Why.SLOPPY_SINGLE);
            }
            if (!pack && inReach >= 2) {
                return new Pick(true, Why.SLOPPY_AOE);
            }
        }
        if (pack) {
            return new Pick(true, Why.PACK);
        }
        return new Pick(!hasSingle, Why.FEW);
    }

    /**
     * 1.6.4 boss lane: the rule above, and then the floor. A bot ranks attacks by their average, which is blind to a
     * high-WDEF target: the skill with the top average can bottom out at the formula's floor ({@code clamp} never goes
     * below 1) while the other still does real damage. So when the two attacks' minimum damage disagree, the higher
     * floor wins - on a boss always, and on an ordinary mob only when the attack the rule picked would do nothing
     * ({@code FLOOR_HIT}), since a pack of normal mobs is what the rule above is for.
     *
     * @param singleMinimum the single-target attack's guaranteed damage on the target (its floor, no crit)
     * @param aoeMinimum    the same for the AoE
     * @param floorOn       {@code -Dslums.bossfight.damagefloor}; off means this method IS the rule above
     */
    public static Pick choose(boolean packAttack, int inReach, boolean boss, int minMobs, boolean hasSingle,
                              boolean sloppyRoll, double singleMinimum, double aoeMinimum, boolean floorOn) {
        Pick pick = choose(packAttack, inReach, boss, minMobs, hasSingle, sloppyRoll);
        if (!floorOn || !packAttack || !hasSingle || sloppyRoll) {
            return pick; // no second attack to weigh, or the bot was being sloppy on purpose
        }
        boolean singleChosen = !pick.aoe();
        double chosen = singleChosen ? singleMinimum : aoeMinimum;
        double other = singleChosen ? aoeMinimum : singleMinimum;
        if (other <= chosen) {
            return pick; // the rule already picked the attack with the better floor
        }
        if (!boss && chosen > FLOOR_HIT) {
            return pick; // a healthy floor on an ordinary mob: the pack rule is what a player does there
        }
        return new Pick(singleChosen, Why.FLOOR); // the other attack: aoe = !singleChosen
    }
}
