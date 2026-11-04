package soloMapling.ArtificialPlayer.BotAttackSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 1.6.4 boss lane: the damage floor decides the attack when the average cannot (ticket 130 phase 5 phase 2). */
class BotSkillChoiceFloorTest {

    /** The boss: the single-target skill averages more, the AoE still lands something where the single bottoms out. */
    private static final double SINGLE_MIN_AGAINST_HIGH_WDEF = 1.0;
    private static final double AOE_MIN_AGAINST_HIGH_WDEF = 420.0;

    @Test
    void theFloorFlipsTheChoiceOnABossWhenTheSwitchIsOn() {
        // today's rule sends a boss to the single-target attack, whose floor against the boss's defence is nothing
        assertEquals(new BotSkillChoice.Pick(false, BotSkillChoice.Why.BOSS),
                BotSkillChoice.choose(true, 1, true, 3, true, false));
        // with the floor on, the attack that cannot do nothing wins
        BotSkillChoice.Pick pick = BotSkillChoice.choose(true, 1, true, 3, true, false,
                SINGLE_MIN_AGAINST_HIGH_WDEF, AOE_MIN_AGAINST_HIGH_WDEF, true);
        assertTrue(pick.aoe(), "the AoE's floor is the only damage this bot can actually do to the boss");
        assertEquals(BotSkillChoice.Why.FLOOR, pick.why());
    }

    @Test
    void theGuaranteedLineIsTheRangesFloor() {
        // the same formula input the average uses, read at its floor: no crit, no roll
        assertEquals(1.0, BotCombatFormula.estimateMinimumDamage(new BotCombatFormula.Range(1, 900)));
        assertEquals(37.0, BotCombatFormula.estimateMinimumDamage(new BotCombatFormula.Range(37, 900)));
    }

    @Test
    void theSwitchOffKeepsTodaysChoice() {
        assertEquals(new BotSkillChoice.Pick(false, BotSkillChoice.Why.BOSS),
                BotSkillChoice.choose(true, 1, true, 3, true, false,
                        SINGLE_MIN_AGAINST_HIGH_WDEF, AOE_MIN_AGAINST_HIGH_WDEF, false));
    }

    @Test
    void aBossTheSingleAttackCanStillHurtKeepsTheSingleAttack() {
        // the floor disagrees but not usefully: both attacks do real damage, so the rule as it stands holds
        BotSkillChoice.Pick pick = BotSkillChoice.choose(true, 1, true, 3, true, false, 900.0, 420.0, true);
        assertFalse(pick.aoe());
        assertEquals(BotSkillChoice.Why.BOSS, pick.why());
    }

    @Test
    void aPackOfOrdinaryMobsIsUnaffected() {
        // a normal mob's floors are healthy on both attacks: the pack rule stands, no measurement needed
        BotSkillChoice.Pick pick = BotSkillChoice.choose(true, 5, false, 3, true, false, 1200.0, 800.0, true);
        assertTrue(pick.aoe());
        assertEquals(BotSkillChoice.Why.PACK, pick.why());
    }

    @Test
    void anOrdinaryMobThePackAttackCannotHurtFallsToTheSingleTarget() {
        // the AoE is the pack's attacker but its floor against these mobs is nothing: the single target still lands
        BotSkillChoice.Pick pick = BotSkillChoice.choose(true, 5, false, 3, true, false, 300.0, 1.0, true);
        assertFalse(pick.aoe());
        assertEquals(BotSkillChoice.Why.FLOOR, pick.why());
    }
}
