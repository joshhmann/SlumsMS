package soloMapling.worldevents;

import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.2 (owner b236): the Giant Cake's HP scales with attendance and is aimed at a fight length (CakeHp). */
class CakeHpTest {

    private static final long BASE = 60_000L;
    private static final long PER = CakeHp.DEFAULT_PER_HEAD;
    /** 10-31 playtest: 49 attendees took the 1,800,000 HP cake down in 43 s. */
    private static final long CROWD_DPS = 42_000L;

    @Test
    void noOneThereKeepsTheOldHp() {
        assertEquals(60_000L, CakeHp.hpFor(BASE, 0, PER, 0L));
    }

    @Test
    void hpScalesWithAttendeesAtSpawn() {
        assertEquals(60_000L + 36_000L, CakeHp.hpFor(BASE, 1, PER, 0L));
        assertEquals(1_500_000L, CakeHp.hpFor(BASE, 40, PER, 0L), "40 heads at the old per-head number");
        assertEquals(1_824_000L, CakeHp.hpFor(BASE, 49, PER, 0L), "the owner's crowd");
    }

    /** b236 (the owner, 10-31): "scale hp, more bots can still jump in" - a fixed ceiling truncated that scaling. */
    @Test
    void aCrowdPastFiftyHeadsStillAddsHp() {
        assertEquals(60_000L + 60 * 36_000L, CakeHp.hpFor(BASE, 60, PER, CakeHp.DEFAULT_CAP),
                "no ceiling of its own: the old 1,800,000 would have cut this");
        assertEquals(10 * 36_000L, CakeHp.topUp(BASE, CakeHp.hpFor(BASE, 50, PER, 0L), 50, 60, PER,
                CakeHp.DEFAULT_CAP), "and late joiners still top it up");
        assertEquals(1_800_000L, CakeHp.hpFor(BASE, 60, PER, 1_800_000L),
                "what the old ceiling did to that crowd: cut it at 1.8 M");
        assertTrue(CakeHp.hpFor(BASE, 10_000, PER, 0L) <= EventTemplates.MAX_BOSS_HP, "never past the boss limit");
    }

    @Test
    void anOperatorsCapStillHolds() {
        long cap = 1_800_000L;
        assertEquals(cap, CakeHp.hpFor(BASE, 500, PER, cap));
        assertEquals(4_000_000L, CakeHp.hpFor(4_000_000L, 40, PER, cap), "a base above the cap is never cut");
        assertEquals(0L, CakeHp.topUp(BASE, cap, 60, 80, PER, cap), "no top-up past the cap");
        assertEquals(0L, CakeHp.aimTopUp(BASE, cap, CROWD_DPS * 10, 10_000L, 240L, cap), "no aim past the cap either");
    }

    @Test
    void joinsWhileAliveTopItUp() {
        long max = CakeHp.hpFor(BASE, 5, PER, 0L);
        assertEquals(3 * PER, CakeHp.topUp(BASE, max, 5, 8, PER, 0L));
        assertEquals(0L, CakeHp.topUp(BASE, max, 5, 5, PER, 0L), "no newcomers");
        assertEquals(0L, CakeHp.topUp(BASE, max, 5, 2, PER, 0L), "people leaving never shrink it");
    }

    @Test
    void perHeadZeroIsTheOldFixedHp() {
        assertEquals(BASE, CakeHp.hpFor(BASE, 40, 0, 0L));
    }

    /** The point of b236: the pool comes from what the crowd actually does, so the fight lasts the target length. */
    @Test
    void theAimIsTheCrowdsPaceTimesTheTarget() {
        long tenSeconds = 10_000L;
        long aim = CakeHp.aimFor(BASE, CROWD_DPS * 10, tenSeconds, 240L, 0L);
        assertEquals(240L * CROWD_DPS, aim);
        assertEquals(10_080_000L, aim, "the owner's 49-head crowd gets a ~4 minute cake, not a 43 second one");
        assertTrue(aim / (double) CROWD_DPS >= 200 && aim / (double) CROWD_DPS <= 260);
    }

    @Test
    void nothingIsAimedBeforeThereIsAMeasurement() {
        assertEquals(0L, CakeHp.aimFor(BASE, CROWD_DPS * 3, 3_000L, 240L, 0L), "still inside the probe");
        assertEquals(0L, CakeHp.aimFor(BASE, 0, 10_000L, 240L, 0L), "nothing hit it yet");
        assertEquals(0L, CakeHp.aimFor(BASE, CROWD_DPS * 10, 10_000L, 0L, 0L), "-Dslums.cake.targetSeconds=0");
        assertEquals(8_580_000L, CakeHp.aimTopUp(BASE, 1_500_000L, CROWD_DPS * 10, 10_000L, 240L, 0L),
                "a standing 1.5 M cake is raised to the 10.08 M aim");
    }

    @Test
    void theAimOnlyRaisesAndNeverPassesTheLimit() {
        long aim = CakeHp.aimFor(20_000_000L, 100_000L, 10_000L, 240L, 0L); // 2.4 M of aim under a 20 M base
        assertEquals(20_000_000L, aim);
        assertEquals(0L, CakeHp.aimTopUp(BASE, 12_000_000L, CROWD_DPS * 10, 10_000L, 240L, 0L),
                "already longer than the aim: no change");
        assertEquals(EventTemplates.MAX_BOSS_HP,
                CakeHp.aimFor(BASE, EventTemplates.MAX_BOSS_HP, 10_000L, 240L, 0L));
    }

    @Test
    void aLiveCakeIsRaisedMaxAndCurrentAlike() {
        MonsterStats stats = new MonsterStats();
        stats.setHp(1_000);
        Monster cake = new Monster(9400606, stats);
        cake.setStartingHp(1_000);
        cake.applyAndGetHpDamage(400, false);
        assertTrue(cake.raiseMaxHp(500));
        assertEquals(1_500L, cake.getMaxHp());
        assertEquals(1_100L, cake.getHp());
        assertFalse(cake.raiseMaxHp(0));
    }
}
