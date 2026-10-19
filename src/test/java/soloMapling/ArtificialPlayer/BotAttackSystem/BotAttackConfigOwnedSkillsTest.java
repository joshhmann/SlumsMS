package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import constants.skills.Crusader;
import constants.skills.Hermit;
import constants.skills.Hero;
import constants.skills.Rogue;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Real bots ticket 06: a bot attacks with the skills it actually holds, and with the table when it holds none. */
class BotAttackConfigOwnedSkillsTest {

    private static IntPredicate holding(Integer... skills) {
        Set<Integer> held = Set.of(skills);
        return held::contains;
    }

    @Test
    void aFreshHeroKeepsUsingItsThirdJobAttacksUntilItHasBrandish() {
        BotAttackConfig.JobAttacks fresh = BotAttackConfig.resolve(Job.HERO, WeaponType.SWORD2H,
                holding(Crusader.SWORD_PANIC, Crusader.SWORD_COMA));
        BotAttackConfig.JobAttacks trained = BotAttackConfig.resolve(Job.HERO, WeaponType.SWORD2H,
                holding(Crusader.SWORD_PANIC, Crusader.SWORD_COMA, Hero.BRANDISH));

        assertAll(
                () -> assertEquals(Crusader.SWORD_PANIC, fresh.single().skillId),
                () -> assertEquals(Crusader.SWORD_COMA, fresh.aoe().skillId),
                () -> assertEquals(Hero.BRANDISH, trained.single().skillId));
    }

    @Test
    void eitherWeaponFormOfAPairCounts() {
        BotAttackConfig.JobAttacks axe = BotAttackConfig.resolve(Job.CRUSADER, WeaponType.GENERAL2H_SWING,
                holding(Crusader.AXE_PANIC));

        assertEquals(Crusader.SWORD_PANIC, axe.single().skillId, "the profile is the pair; the weapon picks the form");
    }

    @Test
    void aRoleTheBotHoldsNothingForStaysEmpty() {
        BotAttackConfig.JobAttacks hermit = BotAttackConfig.resolve(Job.HERMIT, WeaponType.CLAW,
                holding(Rogue.LUCKY_SEVEN));

        assertAll(
                () -> assertEquals(Rogue.LUCKY_SEVEN, hermit.single().skillId),
                () -> assertNull(hermit.aoe(), "no Avenger yet"));
    }

    @Test
    void aBotHoldingNoneOfItsAttacksFallsBackToTheTable() {
        BotAttackConfig.JobAttacks none = BotAttackConfig.resolve(Job.HERMIT, WeaponType.CLAW, holding());
        BotAttackConfig.JobAttacks table = BotAttackConfig.resolve(Job.HERMIT, WeaponType.CLAW);

        assertAll(
                () -> assertEquals(table.single(), none.single()),
                () -> assertEquals(table.aoe(), none.aoe()),
                () -> assertEquals(Hermit.AVENGER, none.aoe().skillId));
    }
}
