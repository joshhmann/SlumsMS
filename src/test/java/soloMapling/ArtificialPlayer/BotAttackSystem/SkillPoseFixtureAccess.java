package soloMapling.ArtificialPlayer.BotAttackSystem;

/** Lets tests in other packages use MapleRoot's skill pose data (SkillPoseFixture is package-private). */
public final class SkillPoseFixtureAccess {

    private SkillPoseFixtureAccess() {
    }

    public static void install() {
        SkillPoseFixture.install();
    }
}
