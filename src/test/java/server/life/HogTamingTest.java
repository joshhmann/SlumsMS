package server.life;

import constants.id.MobId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.5.4 (owner): friends could only miss the mount quest hog; every swing now counts. */
class HogTamingTest {
    private static final int HOG = MobId.TAMABLE_HOG;

    @Test
    void aMissCountsAsTwenty() {
        assertEquals(20, HogTaming.totalDamage(HOG, 0, List.of(0), 200));
    }

    @Test
    void everyLineOfAMultiHitCounts() {
        assertEquals(60, HogTaming.totalDamage(HOG, 1, List.of(0, 1, 0), 200));
    }

    @Test
    void aBigHitIsKept() {
        assertEquals(50, HogTaming.totalDamage(HOG, 50, List.of(50), 200));
    }

    @Test
    void theHogNeverDies() {
        assertEquals(10, HogTaming.totalDamage(HOG, 0, List.of(0), 11));
        assertEquals(199, HogTaming.totalDamage(HOG, 99999, List.of(99999), 200));
        assertEquals(0, HogTaming.totalDamage(HOG, 0, List.of(0), 1));
    }

    @Test
    void sevenSwingsGetItUnderTheCatchLine() {
        long hp = 200;
        for (int swing = 0; swing < 7; swing++) {
            hp -= HogTaming.totalDamage(HOG, 0, List.of(0), hp);
        }
        assertEquals(true, hp < 200 * 0.4, "hp " + hp);
    }

    @Test
    void otherMobsAreUnchanged() {
        assertEquals(0, HogTaming.totalDamage(100100, 0, List.of(0), 200));
        assertEquals(99999, HogTaming.totalDamage(100100, 99999, List.of(99999), 5));
    }
}
