package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import constants.inventory.EquipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Every attack a bot makes is one a viewer's client draws: for each job, with each weapon it is handed
 * (BotWeapons), every attack it resolves fits that weapon and is sent in a pose the skill allows.
 */
class BotAttacksDrawnTest {

    @BeforeEach
    void useMapleRootSkillData() {
        SkillPoseFixture.install();
    }

    @Test
    void everyBotAttackFitsTheWeaponItIsHanded() {
        List<String> wrong = new ArrayList<>();
        for (Job job : Job.values()) {
            for (EquipType kind : BotWeapons.weaponsFor(job)) {
                int weaponId = kind.getValue() * 1000;
                WeaponType weapon = weaponType(weaponId);
                BotAttackConfig.JobAttacks attacks = BotAttackConfig.resolve(job, weapon);
                for (BotAttackProfile p : new BotAttackProfile[]{attacks.single(), attacks.aoe(), attacks.ultimate()}) {
                    if (p == null) {
                        continue;
                    }
                    int skillId = p.skillId;
                    if (!BotSkillActions.weaponFits(skillId, weaponId)) {
                        wrong.add(job + " with " + kind + ": " + skillId + " does not take that weapon");
                    }
                    for (int level : new int[]{1, 5, 10, 16, 20, 30}) { // bots hold the 4th-job package skills at 1-5
                        List<Integer> poses = BotSkillActions.poseCodes(skillId, level);
                        int action = BotAttackData.actionFor(skillId, level, weaponId, p.route);
                        if (!poses.isEmpty() && !poses.contains(action)) {
                            wrong.add(job + ": " + skillId + " lv " + level + " sent in pose " + action
                                    + ", allowed " + poses);
                        }
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void aMeleeBoxReachesAMobOnTheBotsOwnGround() {
        Point bot = new Point(500, 100);
        // Raging Blow facing left: lt (-200,-110), rb (-10,0) - ends at the foot line and 10 px in front.
        Rectangle ragingBlow = new Rectangle(300, -10, 190, 110);
        Rectangle box = BotAttackDriver.coverBody(ragingBlow, bot, true, false);
        assertTrue(box.contains(new Point(400, 100)), "a mob 100 px in front, on the same ground");
        assertTrue(box.contains(new Point(495, 100)), "a mob that walked into the bot");
        assertTrue(!box.contains(new Point(560, 100)), "a mob well behind the bot");
    }

    private static WeaponType weaponType(int itemId) {
        return switch (itemId / 10000 % 100) {
            case 30 -> WeaponType.SWORD1H;
            case 31, 32 -> WeaponType.GENERAL1H_SWING;
            case 33 -> WeaponType.DAGGER_OTHER;
            case 37 -> WeaponType.WAND;
            case 38 -> WeaponType.STAFF;
            case 40 -> WeaponType.SWORD2H;
            case 41, 42 -> WeaponType.GENERAL2H_SWING;
            case 43 -> WeaponType.SPEAR_STAB;
            case 44 -> WeaponType.POLE_ARM_SWING;
            case 45 -> WeaponType.BOW;
            case 46 -> WeaponType.CROSSBOW;
            case 47 -> WeaponType.CLAW;
            case 48 -> WeaponType.KNUCKLE;
            case 49 -> WeaponType.GUN;
            default -> WeaponType.NOT_A_WEAPON;
        };
    }
}
