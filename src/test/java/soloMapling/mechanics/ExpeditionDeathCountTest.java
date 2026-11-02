package soloMapling.mechanics;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.expeditions.ExpeditionType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The death budget itself (ticket 130 phase 5, phase 1): off by default, humans spend it, bots never do, and the last
 * death ends the run exactly once.
 */
class ExpeditionDeathCountTest {

    @AfterEach
    void switchesOff() {
        System.clearProperty(ExpeditionDeathCount.FLAG);
        System.clearProperty(ExpeditionDeathCount.CAP_PROPERTY);
        System.clearProperty(ExpeditionDeathCount.CAP_PROPERTY + ".zakum");
        System.clearProperty(ExpeditionDeathCount.CAP_PROPERTY + ".horntail");
    }

    @Test
    void anUnarmedCountNeverSpendsIt() {
        ExpeditionDeathCount count = new ExpeditionDeathCount();

        assertFalse(count.isOn());
        assertEquals(-1, count.left());
        assertEquals(ExpeditionDeathCount.Charge.OFF, count.charge());
        assertFalse(count.arm(0), "a non-positive cap leaves the count unarmed");
        assertFalse(count.arm(-3));
        assertEquals(-1, count.left());
    }

    @Test
    void theLastDeathEndsTheRunOnce() {
        ExpeditionDeathCount count = new ExpeditionDeathCount();
        assertTrue(count.arm(2));

        assertEquals(ExpeditionDeathCount.Charge.SPENT, count.charge());
        assertEquals(1, count.left());
        assertEquals(ExpeditionDeathCount.Charge.SPENT, count.charge());
        assertEquals(0, count.left());
        assertEquals(ExpeditionDeathCount.Charge.EXHAUSTED, count.charge());
        assertEquals(ExpeditionDeathCount.Charge.OFF, count.charge(), "the run ends once, later deaths spend nothing");
        assertEquals(0, count.left());
    }

    @Test
    void onlyHumansCount() {
        Character human = player(false, false);
        Character bot = player(true, false);
        Character alt = player(false, true);

        assertTrue(ExpeditionDeathCount.counts(human));
        assertFalse(ExpeditionDeathCount.counts(bot), "a population bot dying costs the party nothing");
        assertFalse(ExpeditionDeathCount.counts(alt), "an owned alt dying costs the party nothing");
        assertFalse(ExpeditionDeathCount.counts(null));
    }

    @Test
    void theSwitchDecidesWhetherARunIsArmed() {
        System.setProperty(ExpeditionDeathCount.CAP_PROPERTY, "4");

        assertEquals(0, ExpeditionDeathCount.configuredFor(ExpeditionType.ZAKUM), "off by default, whatever the cap says");
        assertEquals(0, ExpeditionDeathCount.configuredFor(null));

        System.setProperty(ExpeditionDeathCount.FLAG, "true");
        assertEquals(4, ExpeditionDeathCount.configuredFor(ExpeditionType.ZAKUM));

        System.clearProperty(ExpeditionDeathCount.CAP_PROPERTY);
        assertEquals(ExpeditionDeathCount.DEFAULT_CAP, ExpeditionDeathCount.configuredFor(ExpeditionType.HORNTAIL));

        System.setProperty(ExpeditionDeathCount.CAP_PROPERTY, "not a number");
        assertEquals(ExpeditionDeathCount.DEFAULT_CAP, ExpeditionDeathCount.configuredFor(ExpeditionType.ZAKUM));

        System.setProperty(ExpeditionDeathCount.CAP_PROPERTY + ".zakum", "2");
        assertEquals(2, ExpeditionDeathCount.configuredFor(ExpeditionType.ZAKUM), "one boss may cap its own runs");
        assertEquals(ExpeditionDeathCount.DEFAULT_CAP, ExpeditionDeathCount.configuredFor(ExpeditionType.HORNTAIL));
    }

    private static Character player(boolean bot, boolean ownedAlt) {
        Character chr = mock(Character.class);
        when(chr.isBot()).thenReturn(bot);
        when(chr.isOwnedAlt()).thenReturn(ownedAlt);
        return chr;
    }
}
