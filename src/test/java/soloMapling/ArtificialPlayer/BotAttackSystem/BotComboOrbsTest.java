package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import constants.skills.Crusader;
import constants.skills.Hero;
import constants.skills.Warrior;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Crusader-line bots keep Combo Attack orbs like a player does, so a viewer's client draws Panic and Coma with
// their finish effect instead of a plain swing: charge with other attacks, finish on full orbs, start again.
class BotComboOrbsTest {

    @Test
    void anyOtherHitAddsAnOrbUpToTheCap() {
        assertEquals(1, BotComboOrbs.nextOrbs(0, Warrior.SLASH_BLAST, 5, false));
        assertEquals(5, BotComboOrbs.nextOrbs(4, Warrior.POWER_STRIKE, 5, false));
        assertEquals(5, BotComboOrbs.nextOrbs(5, Warrior.POWER_STRIKE, 5, false));
        assertEquals(1, BotComboOrbs.nextOrbs(0, 0, 5, false)); // a plain swing charges too
    }

    @Test
    void advancedComboCanAddTwoButNeverPassesTheCap() {
        assertEquals(3, BotComboOrbs.nextOrbs(1, Hero.BRANDISH, 10, true));
        assertEquals(10, BotComboOrbs.nextOrbs(9, Hero.BRANDISH, 10, true));
    }

    @Test
    void aFinisherSpendsEveryOrb() {
        assertEquals(0, BotComboOrbs.nextOrbs(5, Crusader.SWORD_PANIC, 5, false));
        assertEquals(0, BotComboOrbs.nextOrbs(10, Crusader.SWORD_COMA, 10, true));
    }

    @Test
    void shoutDoesNotCharge() {
        assertEquals(2, BotComboOrbs.nextOrbs(2, Crusader.SHOUT, 5, false));
    }

    @Test
    void onlyTheCrusaderLineUsesCombo() {
        assertTrue(BotComboOrbs.usesCombo(Job.CRUSADER));
        assertTrue(BotComboOrbs.usesCombo(Job.HERO));
        assertFalse(BotComboOrbs.usesCombo(Job.FIGHTER));
        assertFalse(BotComboOrbs.usesCombo(Job.WHITEKNIGHT));
        assertFalse(BotComboOrbs.usesCombo(Job.DAWNWARRIOR3));
    }

    @Test
    void aCrusaderChargesWithTheWarriorBasics() {
        BotAttackConfig.JobAttacks crusader = BotAttackConfig.resolve(Job.CRUSADER, WeaponType.SWORD1H);
        BotAttackConfig.JobAttacks basics = BotAttackConfig.resolve(Job.WARRIOR, WeaponType.SWORD1H);

        assertEquals(Crusader.SWORD_PANIC, crusader.single().skillId);
        assertEquals(basics.aoe(), BotAttackDriver.comboCharger(crusader.single(), true, WeaponType.SWORD1H));
        assertEquals(basics.single(), BotAttackDriver.comboCharger(crusader.single(), false, WeaponType.SWORD1H));
    }

    @Test
    void aHeroChargesWithItsOwnAttack() {
        BotAttackConfig.JobAttacks hero = BotAttackConfig.resolve(Job.HERO, WeaponType.SWORD2H);

        assertEquals(hero.single(), BotAttackDriver.comboCharger(hero.single(), true, WeaponType.SWORD2H));
    }
}
