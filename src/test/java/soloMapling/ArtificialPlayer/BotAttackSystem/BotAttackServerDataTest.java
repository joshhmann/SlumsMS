package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.WzTestData;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Ticket 32, with the server's WZ (-Dwz-path=<wz>; skipped without): the live server reads the poses, weapons,
 * skillTypes and keydown poses from its own Skill.wz and the weapon info from its own Character.wz - this checks
 * they are the client's (ClientAttackData), so the bots send on the live server what BotAttackClientValidityTest
 * checked against the client.
 */
class BotAttackServerDataTest {

    @AfterEach
    void restore() {
        SkillPoseFixture.install();
    }

    @Test
    void theServersSkillPosesAreTheClients() {
        WzTestData.assumePresent("Skill.wz");
        BotSkillActions.useServerData();
        List<String> differ = new ArrayList<>();
        // the skills the bots use (the client data holds a few ids twice - 8001007 in two images - so not all)
        for (BotAttackProfile p : BotAttackConfig.allProfiles()) {
            ClientAttackData.Skill c = ClientAttackData.INSTANCE.skills.get(p.skillId);
            if (c == null) {
                continue;
            }
            BotSkillActions.SkillPose s = BotSkillActions.pose(c.id());
            if (!s.actions().equals(c.actions()) || !s.levelActions().equals(c.levelActions())
                    || s.weapon() != c.weapon() || s.skillType() != c.skillType()
                    || !s.prepareAction().equals(c.prepareAction())) {
                differ.add(c.id() + ": server " + s + ", client " + c);
            }
        }
        assertTrue(differ.isEmpty(), String.join("\n", differ));
    }

    @Test
    void theServersWeaponsAreTheClients() {
        WzTestData.assumePresent("Character.wz");
        BotClientPoses.useServerData();
        List<String> differ = new ArrayList<>();
        for (ClientAttackData.Weapon c : ClientAttackData.INSTANCE.weapons.values()) {
            if (BotWeapons.kindOf(c.id()) == null) {
                continue;
            }
            BotClientPoses.WeaponInfo s = BotClientPoses.weapon(c.id());
            if (s.attackType() != c.attackType() || s.attackSpeed() != c.attackSpeed()
                    || !s.afterImage().equals(c.afterImage())) {
                differ.add(c.id() + ": server " + s + ", client " + c);
            }
        }
        assertTrue(differ.isEmpty(), differ.size() + " differ:\n" + String.join("\n", differ));
    }
}
