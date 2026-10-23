package soloMapling.ArtificialPlayer.BotCommandsPack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAttackSystem.SkillPoseFixtureAccess;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 1.5.0 rc6 (QA t-rv-4: "saw a skill cannot be used with equipped item"). A bot idling next to the player swings one of
 * its job's skills at the air now and then (BotFlavor). That swing never checked the weapon, so a bot holding a weapon
 * the skill does not take sent an attack the player's client refuses, with that message in the player's chat. It now
 * plays a plain swing instead (a mage with no wand or staff plays nothing).
 */
class FlavorSwingWeaponTest {

    @BeforeEach
    void skillData() {
        SkillPoseFixtureAccess.install();
    }

    @Test
    void aSkillSwingNeedsAWeaponTheSkillTakes() {
        assertTrue(BotAttack.drawable(14121001, 1472023), "Quintuple Star with a claw");
        assertFalse(BotAttack.drawable(14121001, 1332041), "... with a dagger: the client would complain");
        assertTrue(BotAttack.drawable(0, 1332041), "a plain swing always fits");
    }

    @Test
    void bothShowOffSwingsCheckIt() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/BotAttack.java"));
        int skill = src.indexOf("public static void skillSwing");
        int magic = src.indexOf("public static void magicSwing");
        assertTrue(src.indexOf("if (!drawable(skillId, weaponId))", skill) < magic, "skillSwing");
        assertTrue(src.indexOf("if (!drawable(skillId, weaponId))", magic) > magic, "magicSwing");
    }
}
