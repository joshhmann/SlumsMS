package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import soloMapling.ArtificialPlayer.MapleRootSkillData;
import soloMapling.ArtificialPlayer.MapleRootSkillData.SkillInfo;
import soloMapling.ArtificialPlayer.MapleRootSkills;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real bots on MapleRoot: every attack and buff the bots use is in MapleRoot's Skill.wz (the {@link MapleRootSkillData}
 * fixture) as that kind of skill, and every class - Cygnus Knights and Aran included - has real attacks.
 */
class MapleRootAttackIdsTest {

    @Test
    void everyAttackIsAnAttackInMapleRootsSkillWz() {
        List<String> wrong = new ArrayList<>();
        for (BotAttackProfile p : BotAttackConfig.allProfiles()) {
            SkillInfo info = MapleRootSkillData.get(p.skillId);
            if (info == null) {
                wrong.add(p.skillId + " is not in Skill.wz");
            } else if (!info.kind().equals("attack")) {
                wrong.add(p.skillId + " " + info.name() + " is a " + info.kind());
            } else if (p.route == BotAttackProfile.Route.MAGIC && info.job() / 100 % 10 != 2) {
                wrong.add(p.skillId + " " + info.name() + " is cast as magic by a non-mage");
            }
            if (p.numDamage > 15 || p.numAttacked > 15) {
                wrong.add(p.skillId + " does not fit the packet's 4-bit counts");
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void everyBuffIsABuffInMapleRootsSkillWz() {
        List<String> wrong = new ArrayList<>();
        for (int id : BotBuffConfig.allBuffIds()) {
            SkillInfo info = MapleRootSkillData.get(id);
            if (info == null) {
                wrong.add(id + " is not in Skill.wz");
            } else if (!info.kind().equals("buff") && id != constants.skills.DarkKnight.BERSERK) {
                // Berserk is a passive in Skill.wz (it switches itself on at low HP); the bot shows its aura as a buff.
                wrong.add(id + " " + info.name() + " is a " + info.kind());
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void thirdJobMagesShowFireRushAndNobodyABooster() {
        assertAll(
                () -> assertTrue(BotBuffConfig.buffsForJob(Job.FP_MAGE).contains(MapleRootSkills.FP_FIRE_RUSH)),
                () -> assertTrue(BotBuffConfig.buffsForJob(Job.IL_ARCHMAGE).contains(MapleRootSkills.IL_FIRE_RUSH)),
                () -> assertTrue(BotBuffConfig.buffsForJob(Job.BISHOP).contains(MapleRootSkills.PRIEST_FIRE_RUSH)),
                () -> assertTrue(BotBuffConfig.buffsForJob(Job.BLAZEWIZARD3).contains(MapleRootSkills.BW_FIRE_RUSH)),
                () -> assertTrue(BotBuffConfig.allBuffIds().stream()
                        .map(MapleRootSkillData::get)
                        .noneMatch(info -> info.name().toLowerCase().contains("booster"))));
    }

    /** Every 2nd job and up of a built class attacks with skills, not the beginner's skill-0 swing. */
    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"FIGHTER", "HERO", "PAGE", "PALADIN", "SPEARMAN", "DARKKNIGHT",
            "FP_ARCHMAGE", "IL_ARCHMAGE", "BISHOP", "BOWMASTER", "MARKSMAN", "NIGHTLORD", "SHADOWER", "BRAWLER",
            "BUCCANEER", "GUNSLINGER", "CORSAIR", "DAWNWARRIOR2", "DAWNWARRIOR4", "BLAZEWIZARD4", "WINDARCHER2",
            "WINDARCHER4", "NIGHTWALKER2", "NIGHTWALKER4", "THUNDERBREAKER2", "THUNDERBREAKER4", "ARAN1", "ARAN4"})
    void everyClassHasRealAttacks(Job job) {
        BotAttackConfig.JobAttacks attacks = BotAttackConfig.resolve(job, null);

        assertNotNull(attacks.single(), job + " single");
        assertNotEquals(0, attacks.single().skillId, job + " swings a skill");
    }

    @Test
    void cygnusFourthJobsUseTheirOwnSkills() {
        assertAll(
                () -> assertEquals(MapleRootSkills.MOON_DANCER,
                        BotAttackConfig.resolve(Job.DAWNWARRIOR4, WeaponType.SWORD1H).single().skillId),
                () -> assertEquals(MapleRootSkills.TOWERING_INFERNO,
                        BotAttackConfig.resolve(Job.BLAZEWIZARD4, WeaponType.WAND).ultimate().skillId),
                () -> assertEquals(MapleRootSkills.SONG_OF_HEAVEN,
                        BotAttackConfig.resolve(Job.WINDARCHER4, WeaponType.BOW).aoe().skillId),
                () -> assertEquals(MapleRootSkills.QUINTUPLE_STAR,
                        BotAttackConfig.resolve(Job.NIGHTWALKER4, WeaponType.CLAW).single().skillId),
                () -> assertEquals(MapleRootSkills.TYPHOON,
                        BotAttackConfig.resolve(Job.THUNDERBREAKER4, WeaponType.KNUCKLE).ultimate().skillId));
    }
}
