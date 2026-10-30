package client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Audit b134: a Safety Charm counts for the death it was used on only, and never changes an alt's respawn HP. */
class SafetyCharmRespawnTest {

    private static Character dying(boolean alt) throws Exception {
        Character chr = mock(Character.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doNothing().when(chr).changeMap(anyInt());
        doNothing().when(chr).cancelAllBuffsOnDeath();
        doNothing().when(chr).updateHp(anyInt());
        doNothing().when(chr).addMPHP(anyInt(), anyInt());
        doNothing().when(chr).setStance(anyInt());
        doNothing().when(chr).updateCouponRates();
        doReturn(1000).when(chr).getClientMaxHp();
        doReturn(1000).when(chr).getClientMaxMp();
        doReturn(alt).when(chr).isOwnedAlt();
        charm(chr).setBoolean(chr, true); // this death was saved by a charm
        return chr;
    }

    private static Field charm(Character chr) throws Exception {
        Field f = Character.class.getDeclaredField("usedSafetyCharm");
        f.setAccessible(true);
        return f;
    }

    @Test
    void theCharmCountsOnceForAPlayer() throws Exception {
        Character chr = dying(false);
        chr.respawn(100000000);
        verify(chr).addMPHP(300, 300);
        assertFalse(charm(chr).getBoolean(chr), "used up");
        chr.respawn(100000000); // the next death, no charm
        verify(chr).updateHp(50);
    }

    @Test
    void anAltAlwaysGetsUpAtFiftyHp() throws Exception {
        Character chr = dying(true);
        chr.respawn(100000000);
        verify(chr, never()).addMPHP(anyInt(), anyInt());
        verify(chr).updateHp(50);
    }
}
