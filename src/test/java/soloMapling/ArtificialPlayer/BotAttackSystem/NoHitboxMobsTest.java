package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import soloMapling.WzTestData;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TEST 2026-11-08 04:14:58 UTC: a Horntail run "cleared" with its visible parts alive. Bots had hit Horntail's
 * invisible linking body 8810018 (no hitbox: a real client can never hit it) and its death runs the event's clearPQ.
 * A bot never targets a mob a player cannot hit. See docs/bots/no-hitbox-mobs.md.
 */
class NoHitboxMobsTest {

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    /** A monster of that id (no stats: not friendly), as botMayTarget sees it. */
    private static Monster mob(int id) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(id);
        return m;
    }

    @Test
    void horntailsInvisibleBodyIsNeverABotTarget() {
        WzTestData.assumePresent("Mob.wz");
        assertFalse(BotAttackDriver.botMayTarget(mob(8810018)), "8810018 has no hitbox: no player can hit it");
        assertTrue(NoHitboxMobs.noHitbox(8810018));
    }

    @Test
    void everyVisiblePartAndOrdinaryMobStaysATarget() {
        WzTestData.assumePresent("Mob.wz");
        for (int id : new int[]{8810000, 8810001, 8810002, 8810009, 8800000, 8800002, 8800003, 100100}) {
            assertTrue(BotAttackDriver.botMayTarget(mob(id)), "mob " + id + " has a hitbox and stays a target");
        }
    }

    @Test
    void aMobWithNoWzImageIsNotRefused() {
        assertFalse(NoHitboxMobs.noHitbox(0), "no WZ data: nothing is refused");
        assertFalse(NoHitboxMobs.noHitbox(-5));
    }
}
