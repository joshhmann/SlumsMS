package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 163: a character name is global in the game, so t-oa-1's fixed "AutoQaAlt" was created once by the first lane and
 * then looked up by name by every other lane (Probes160.ensureCharacter uses Character.getIdByName), which got a
 * character its account did not own: @botme refused and the probe failed at cap 4. One alt per account.
 */
class Probes160AltNameTest {

    @Test
    void eachAccountGetsItsOwnAlts() {
        String a1 = Probes160.altName(1, 'A');
        String b1 = Probes160.altName(1, 'B');
        String a11 = Probes160.altName(11, 'A');
        assertNotEquals(a1, a11, "two lanes' alts must not be the same character");
        assertNotEquals(a1, b1, "the two alts of one account must differ");
        assertEquals("Qa11A", a11);
        for (String name : new String[] {a1, b1, a11}) {
            assertTrue(name.matches("[A-Za-z][A-Za-z0-9]{3,11}"), name + " is not a character name the client accepts");
        }
    }
}
