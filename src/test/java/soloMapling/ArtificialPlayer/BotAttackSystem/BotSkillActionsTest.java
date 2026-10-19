package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.inventory.WeaponType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * The client only draws another character's attack when its pose is one the skill allows and its weapon fits the
 * skill. These pin the client's action codes (read from MapleRoot's MapleStory.exe) and the poses and weapons the
 * bots get for MapleRoot's skills, the 4th-job ones that were drawn as nothing among them.
 */
class BotSkillActionsTest {

    @BeforeEach
    void useMapleRootSkillData() {
        SkillPoseFixture.install();
    }

    @Test
    void actionCodesAreTheClientsOwn() {
        assertEquals(162, BotSkillActions.CLIENT_ACTIONS.size());
        assertEquals(5, BotSkillActions.codeOf("swingO1")); // the claw and wand repeats resolve to the first code
        assertEquals(22, BotSkillActions.codeOf("shoot1"));
        assertEquals(42, BotSkillActions.codeOf("alert3"));
        assertEquals(55, BotSkillActions.codeOf("savage"));
        assertEquals(56, BotSkillActions.codeOf("avenger"));
        assertEquals(63, BotSkillActions.codeOf("brandish1"));
        assertEquals(71, BotSkillActions.codeOf("blast"));
        assertEquals(116, BotSkillActions.codeOf("souldriver"));
        assertEquals(131, BotSkillActions.codeOf("rollingSpin"));
        assertEquals(137, BotSkillActions.codeOf("finalBlow"));
        assertEquals(151, BotSkillActions.codeOf("dragonAura"));
        assertEquals(-1, BotSkillActions.codeOf("SMsolarPierce")); // MapleRoot's own, unknown to the client
    }

    @Test
    void fourthJobSkillsGetTheirOwnPose() {
        assertEquals(List.of(151), BotSkillActions.poseCodes(1121012, 1));     // Rising Rage: dragonAura
        assertEquals(List.of(63, 64), BotSkillActions.poseCodes(1121008, 5));  // Raging Blow: brandish1/2
        assertEquals(List.of(131), BotSkillActions.poseCodes(11121101, 5));    // Moon Dancer: rollingSpin
        assertEquals(List.of(116), BotSkillActions.poseCodes(11121004, 5));    // Impaling Rays: souldriver
        assertEquals(List.of(147), BotSkillActions.poseCodes(1321012, 1));     // Dark Impale: illusion
        assertEquals(List.of(137), BotSkillActions.poseCodes(21120005, 30));   // Final Blow
    }

    @Test
    void aSkillWithNoPoseTheClientKnowsUsesTheWeapons() {
        assertEquals(List.of(), BotSkillActions.poseCodes(11121203, 5)); // Solar Pierce: SMsolarPierce
        assertEquals(List.of(), BotSkillActions.poseCodes(14121001, 5)); // Quintuple Star: no action node
        assertEquals(List.of(), BotSkillActions.poseCodes(0, 0));        // a plain swing
        int action = BotAttackData.actionFor(11121203, 5, WeaponType.SWORD1H);
        assertTrue(List.of(16, 17, 5, 6, 7).contains(action), "one-handed sword pose, got " + action);
    }

    @Test
    void spearCrusherChangesPoseWithItsLevel() {
        assertEquals(List.of(53), BotSkillActions.poseCodes(1311001, 10)); // burster1
        assertEquals(List.of(54), BotSkillActions.poseCodes(1311001, 20)); // burster2
        assertEquals(53, BotAttackData.actionFor(1311001, 15, WeaponType.SPEAR_STAB));
    }

    @Test
    void theWeaponMustFitTheSkill() {
        assertTrue(BotSkillActions.weaponFits(14121001, 1472023));  // Quintuple Star, claw
        assertFalse(BotSkillActions.weaponFits(14121001, 1332041)); // ... dagger
        assertTrue(BotSkillActions.weaponFits(1311001, 1432010));   // Spear Crusher, spear
        assertFalse(BotSkillActions.weaponFits(1311001, 1442008));  // ... pole-arm
        assertTrue(BotSkillActions.weaponFits(11111006, 1302041));  // Soul Driver (client group), one-handed sword
        assertTrue(BotSkillActions.weaponFits(11111006, 1402048));  // ... two-handed sword
        assertFalse(BotSkillActions.weaponFits(11111006, 1432010)); // ... spear
        assertTrue(BotSkillActions.weaponFits(5001001, 1482000));   // a knuckle-or-gun pirate skill, knuckle
        assertFalse(BotSkillActions.weaponFits(5001001, 1302000));  // ... sword
        assertTrue(BotSkillActions.weaponFits(1121012, 1412009));   // Rising Rage takes any weapon
        assertTrue(BotSkillActions.weaponFits(0, 0));               // a plain swing always fits
    }
}
