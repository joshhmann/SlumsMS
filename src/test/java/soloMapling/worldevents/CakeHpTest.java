package soloMapling.worldevents;

import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.2 (owner b236): the Giant Cake's HP scales with attendance, is topped up by newcomers, and stops at a cap. */
class CakeHpTest {

    private static final long BASE = 60_000L;
    private static final long PER = CakeHp.DEFAULT_PER_HEAD;
    private static final long CAP = CakeHp.DEFAULT_CAP;

    @Test
    void noOneThereKeepsTheOldHp() {
        assertEquals(60_000L, CakeHp.hpFor(BASE, 0, PER, CAP));
    }

    @Test
    void hpScalesWithAttendeesAtSpawn() {
        assertEquals(60_000L + 36_000L, CakeHp.hpFor(BASE, 1, PER, CAP));
        assertEquals(1_500_000L, CakeHp.hpFor(BASE, 40, PER, CAP), "a full 40-bot crowd: ~4 min at ~6,100 DPS");
        assertTrue(CakeHp.hpFor(BASE, 40, PER, CAP) / 6_100.0 >= 180 && CakeHp.hpFor(BASE, 40, PER, CAP) / 6_100.0 <= 300);
    }

    @Test
    void theCapHolds() {
        assertEquals(CAP, CakeHp.hpFor(BASE, 500, PER, CAP));
        assertEquals(4_000_000L, CakeHp.hpFor(4_000_000L, 40, PER, CAP), "a base above the cap is never cut");
        assertEquals(0L, CakeHp.topUp(BASE, CAP, 60, 80, PER, CAP), "no top-up past the cap");
    }

    @Test
    void joinsWhileAliveTopItUp() {
        long max = CakeHp.hpFor(BASE, 5, PER, CAP);
        assertEquals(3 * PER, CakeHp.topUp(BASE, max, 5, 8, PER, CAP));
        assertEquals(0L, CakeHp.topUp(BASE, max, 5, 5, PER, CAP), "no newcomers");
        assertEquals(0L, CakeHp.topUp(BASE, max, 5, 2, PER, CAP), "people leaving never shrink it");
        long nearCap = CakeHp.hpFor(BASE, 45, PER, CAP); // 1,680,000
        assertEquals(CAP - nearCap, CakeHp.topUp(BASE, nearCap, 45, 50, PER, CAP), "topped up only to the cap");
    }

    @Test
    void perHeadZeroIsTheOldFixedHp() {
        assertEquals(BASE, CakeHp.hpFor(BASE, 40, 0, CAP));
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
