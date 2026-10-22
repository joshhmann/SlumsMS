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

    private BotSkillChoice() {
    }

    /** What the choice was and why, for the cb-1 log. */
    public enum Why { PACK, FEW, BOSS, NO_AOE, SLOPPY_SINGLE, SLOPPY_AOE }

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
}
