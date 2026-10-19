package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import constants.skills.Crusader;
import constants.skills.Pirate;
import constants.skills.Rogue;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.MapleRootSkills;

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
    void aFreshHeroKeepsUsingItsThirdJobAttacksUntilItHasRagingBlow() {
        BotAttackConfig.JobAttacks fresh = BotAttackConfig.resolve(Job.HERO, WeaponType.SWORD2H,
                holding(Crusader.SWORD_PANIC, Crusader.SWORD_COMA));
        BotAttackConfig.JobAttacks trained = BotAttackConfig.resolve(Job.HERO, WeaponType.SWORD2H,
                holding(Crusader.SWORD_PANIC, Crusader.SWORD_COMA, MapleRootSkills.RAGING_BLOW,
                        MapleRootSkills.RISING_RAGE));

        assertAll(
                () -> assertEquals(Crusader.SWORD_PANIC, fresh.single().skillId),
                () -> assertEquals(Crusader.SWORD_COMA, fresh.aoe().skillId),
                () -> assertEquals(MapleRootSkills.RAGING_BLOW, trained.single().skillId),
                () -> assertEquals(MapleRootSkills.RISING_RAGE, trained.aoe().skillId, "the custom 4th-job AoE"));
    }

    @Test
    void piratesPickTheirFirstJobAttackByWeapon() {
        BotAttackConfig.JobAttacks gun = BotAttackConfig.resolve(Job.GUNSLINGER, WeaponType.GUN,
                holding(Pirate.DOUBLE_SHOT, Pirate.FLASH_FIST));
        BotAttackConfig.JobAttacks knuckle = BotAttackConfig.resolve(Job.BRAWLER, WeaponType.KNUCKLE,
                holding(Pirate.FLASH_FIST));

        assertAll(
                () -> assertEquals(Pirate.DOUBLE_SHOT, gun.single().skillId),
                () -> assertEquals(BotAttackProfile.Route.RANGED, gun.single().route),
                () -> assertEquals(Pirate.FLASH_FIST, knuckle.single().skillId));
    }

    @Test
    void aRoleTheBotHoldsNothingForStaysEmpty() {
        BotAttackConfig.JobAttacks hermit = BotAttackConfig.resolve(Job.HERMIT, WeaponType.CLAW,
                holding(Rogue.LUCKY_SEVEN));

        assertAll(
                () -> assertEquals(Rogue.LUCKY_SEVEN, hermit.single().skillId),
                () -> assertNull(hermit.aoe(), "no Shadow Slash yet"));
    }

    @Test
    void aBotHoldingNoneOfItsAttacksFallsBackToTheTable() {
        BotAttackConfig.JobAttacks none = BotAttackConfig.resolve(Job.HERMIT, WeaponType.CLAW, holding());
        BotAttackConfig.JobAttacks table = BotAttackConfig.resolve(Job.HERMIT, WeaponType.CLAW);

        assertAll(
                () -> assertEquals(table.single(), none.single()),
                () -> assertEquals(table.aoe(), none.aoe()),
                () -> assertEquals(MapleRootSkills.SHADOW_SLASH, none.aoe().skillId));
    }
}
