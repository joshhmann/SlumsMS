package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc3, owner decision 1: AoE on 3+ mobs, single target on bosses and lone mobs, now and then the other. */
class BotSkillChoiceTest {

    private static BotSkillChoice.Pick pick(int inReach, boolean boss, boolean sloppy) {
        return BotSkillChoice.choose(true, inReach, boss, 3, true, sloppy);
    }

    @Test
    void threeOrMoreMobsGetTheAoe() {
        assertTrue(pick(3, false, false).aoe());
        assertTrue(pick(6, false, false).aoe());
        assertEquals(BotSkillChoice.Why.PACK, pick(3, false, false).why());
    }

    @Test
    void aLoneMobOrAPairGetsTheSingleTargetSkill() {
        assertFalse(pick(1, false, false).aoe());
        assertFalse(pick(2, false, false).aoe());
        assertEquals(BotSkillChoice.Why.FEW, pick(2, false, false).why());
    }

    @Test
    void aBossAlwaysGetsTheSingleTargetSkillEvenInAPackAndEvenWhenSloppy() {
        assertFalse(pick(5, true, false).aoe());
        assertFalse(pick(5, true, true).aoe());
        assertEquals(BotSkillChoice.Why.BOSS, pick(5, true, false).why());
    }

    @Test
    void aClassWithoutASingleTargetAttackStillHitsWithItsAoe() {
        assertTrue(BotSkillChoice.choose(true, 1, false, 3, false, false).aoe());
        assertTrue(BotSkillChoice.choose(true, 1, true, 3, false, false).aoe());
    }

    @Test
    void aClassWithoutAnAoeUsesItsSingle() {
        BotSkillChoice.Pick p = BotSkillChoice.choose(false, 8, false, 3, true, true);
        assertFalse(p.aoe());
        assertEquals(BotSkillChoice.Why.NO_AOE, p.why());
    }

    @Test
    void aSloppyCastIsTheOtherClassCorrectAttack() {
        assertEquals(BotSkillChoice.Why.SLOPPY_SINGLE, pick(4, false, true).why());
        assertFalse(pick(4, false, true).aoe());
        assertEquals(BotSkillChoice.Why.SLOPPY_AOE, pick(2, false, true).why());
        assertTrue(pick(2, false, true).aoe());
        // a lone mob never gets a sloppy AoE: there is nothing to mob
        assertFalse(pick(1, false, true).aoe());
    }

    @Test
    void theThresholdIsConfigurable() {
        assertTrue(BotSkillChoice.choose(true, 2, false, 2, true, false).aoe());
        assertFalse(BotSkillChoice.choose(true, 4, false, 5, true, false).aoe());
    }
}
