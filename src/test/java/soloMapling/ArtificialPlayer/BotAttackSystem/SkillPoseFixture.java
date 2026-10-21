package soloMapling.ArtificialPlayer.BotAttackSystem;

import soloMapling.ArtificialPlayer.BotAttackSystem.BotSkillActions.SkillPose;

/*
 * The CLIENT's Skill.wz poses, weapons, skillTypes, prepare poses and counts and its Character.wz weapon info
 * (ClientAttackData: src/test/resources/soloMapling/client-attack-data.tsv, read from the client's Data by
 * tools/bot-attack-rules/client_attack_fixture.py), installed as BotSkillActions' / BotClientPoses' sources so a test
 * sees what a viewer's client checks against. (The server's Skill.wz carries the same pose data - the fixture's
 * generator was compared with it - so the live server reading its own copy sends the same.)
 */
final class SkillPoseFixture {

    private SkillPoseFixture() {}

    static void install() {
        ClientAttackData data = ClientAttackData.INSTANCE;
        BotSkillActions.useSource(id -> {
            ClientAttackData.Skill s = data.skills.get(id);
            return s == null ? null : new SkillPose(s.actions(), s.levelActions(), s.weapon(), s.skillType(),
                    s.prepareAction());
        });
        BotClientPoses.useWeaponSource(id -> {
            ClientAttackData.Weapon w = data.weapons.get(id);
            return w == null ? null : new BotClientPoses.WeaponInfo(w.attackType(), w.attackSpeed(), w.afterImage());
        });
        BotClientPoses.useCountSource((id, level) -> {
            ClientAttackData.Skill s = data.skills.get(id);
            if (s == null) {
                return null;
            }
            int[] c = s.countsAt(level);
            // the server's StatEffect defaults a missing mobCount / attackCount / bulletCount to 1
            return new BotClientPoses.Counts(Math.max(1, c[0]), Math.max(1, c[1]), Math.max(1, c[2]));
        });
    }

    static boolean has(int skillId) {
        return ClientAttackData.INSTANCE.skills.containsKey(skillId);
    }
}
