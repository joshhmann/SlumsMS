package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAttackSystem.OffscreenCombat.RotationStep;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 b087: a level 200 Dark Knight bot did about 7x less boss damage than a Marksman. Damage here is in "skill %"
 * (WZ damage x the lines BotSwingPlan draws, MapleRoot's Skill.wz on TEST), so equal stats are assumed; the busy times
 * are the poses' lengths the driver logged on TEST (QA log, 2026-10-30: Crusher 930 ms, Dark Impale 870 ms, La Mancha
 * 1500 ms, every Marksman shot 720 ms).
 */
class BossRotationDpsTest {

    // Dark Knight (spear)
    private static final RotationStep CRUSHER = new RotationStep(250 * 6, 930, 0);            // 1311001 lv30
    private static final RotationStep DARK_IMPALE = new RotationStep(700 * 4, 870, 0);        // 1321012 lv10
    private static final RotationStep LA_MANCHA = new RotationStep(1000 * 8, 1500, 5_000);    // 1321011, 5 s cooltime
    private static final RotationStep LA_MANCHA_ULT = new RotationStep(1000 * 8, 1500, 25_000);
    // Marksman (crossbow)
    private static final RotationStep SNIPE = new RotationStep(4000 * 4, 720, 8_000);         // 3221007, 8 s cooltime
    private static final RotationStep ASTRA = new RotationStep(690 * 6, 720, 0);              // 3221010
    private static final RotationStep HIGH_SPEED = new RotationStep(3500 * 8, 720, 25_000);   // 3221009 (20 s; ult 25 s)

    /** The old estimate: the single's table lines (Crusher 3, Snipe 1) over its swing time, no cooltime. */
    private static double before(double pct, int tableLines, int wzLines, long busyMs) {
        return pct * tableLines / wzLines * 1000.0 / busyMs;
    }

    @Test
    void beforeTheDarkKnightWasAboutSevenTimesBehind() {
        double dk = before(CRUSHER.perSwing(), 3, 6, 930);     // 806 %/s
        double mm = before(SNIPE.perSwing(), 1, 4, 720);       // 5,556 %/s
        assertEquals(6.9, mm / dk, 0.1);
    }

    @Test
    void afterTheGapIsTheClassesNotTheModel() {
        // human-paced driver on a lone boss: extras, single, ultimate, AoE
        double dk = OffscreenCombat.rotationDps(List.of(LA_MANCHA, DARK_IMPALE, LA_MANCHA_ULT, DARK_IMPALE), 60_000);
        double mm = OffscreenCombat.rotationDps(List.of(SNIPE, HIGH_SPEED, ASTRA), 60_000);
        double ratio = mm / dk;
        System.out.printf("b087 after: Dark Knight %.0f %%/s, Marksman %.0f %%/s, %.2fx%n", dk, mm, ratio);
        assertTrue(ratio > 1.5 && ratio < 3.0, "Marksman/Dark Knight = " + ratio);
    }

    @Test
    void aCooltimeSkillIsNotCountedEverySwing() {
        // Snipe alone: once every 8 s, not every 0.72 s
        assertEquals(16_000 / 8.0, OffscreenCombat.rotationDps(List.of(SNIPE), 64_000), 1);
        // the old Crusher-only Dark Knight never fires La Mancha while Crusher is ready
        assertEquals(1500 * 1000.0 / 930, OffscreenCombat.rotationDps(List.of(CRUSHER, LA_MANCHA_ULT), 60_000), 20);
    }

    @Test
    void theDarkKnightSwingsDarkImpaleAndWeavesLaMancha() {
        BotAttackConfig.JobAttacks a = BotAttackConfig.resolve(Job.DARKKNIGHT, WeaponType.SPEAR_STAB);
        assertEquals(1321012, a.single().skillId);
        assertTrue(a.extras().stream().anyMatch(e -> e.profile().skillId == 1321011), a.extras().toString());
        // the Dragon Knight is unchanged
        assertEquals(1311001, BotAttackConfig.resolve(Job.DRAGONKNIGHT, WeaponType.SPEAR_STAB).single().skillId);
    }
}
