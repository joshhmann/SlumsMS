package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * vb-7 (1.5.2): AutoQaOne is a level 3 GM, and storage and Duey refuse GM levels under MINIMUM_GM_LEVEL_TO_USE_* (4
 * since 1.5.2), so the steps run with the pretend player at GM level 0 and give its level back afterwards.
 */
class GameQaWorldPlainPlayerTest {

    private static Character gmOfLevel(int level) {
        Character chr = mock(Character.class);
        doCallRealMethod().when(chr).setGMLevel(anyInt());
        when(chr.gmLevel()).thenCallRealMethod();
        when(chr.isGM()).thenCallRealMethod();
        chr.setGMLevel(level);
        return chr;
    }

    @Test
    void theStepRunsAsAPlainPlayerAndTheGmLevelComesBack() {
        Character chr = gmOfLevel(3);
        assertTrue(chr.isGM(), "level 3 is a GM the storage and Duey gates refuse at the 1.5.2 defaults");
        AtomicBoolean ranAsPlayer = new AtomicBoolean();
        GameQaWorld.asPlainPlayer(chr, () -> ranAsPlayer.set(!chr.isGM() && chr.gmLevel() == 0));
        assertTrue(ranAsPlayer.get());
        assertEquals(3, chr.gmLevel());
    }

    @Test
    void aStepThatThrowsStillGivesTheGmLevelBack() {
        Character chr = gmOfLevel(3);
        assertThrows(IllegalStateException.class, () -> GameQaWorld.asPlainPlayer(chr, () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(3, chr.gmLevel());
        assertTrue(chr.isGM());
    }

    @Test
    void aPlayerStaysAPlayer() {
        Character chr = gmOfLevel(0);
        GameQaWorld.asPlainPlayer(chr, () -> {
        });
        assertFalse(chr.isGM());
        assertEquals(0, chr.gmLevel());
    }
}
