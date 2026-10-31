package client.command.commands.gm3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Ticket 170: "!hurt <name> 40" drops a player to 40% of max HP (client QA scripts); without a percent, HP 1 as before. */
class HurtCommandTest {

    @Test
    void percentParses() {
        assertEquals(40, HurtCommand.percent("40"));
        assertEquals(40, HurtCommand.percent("40%"));
        assertEquals(1, HurtCommand.percent(" 1 "));
        assertNull(HurtCommand.percent("0"));
        assertNull(HurtCommand.percent("100"));
        assertNull(HurtCommand.percent("-5"));
        assertNull(HurtCommand.percent("forty"));
        assertNull(HurtCommand.percent(null));
    }

    @Test
    void hpIsThatShareOfMaxOrOneWithout() {
        assertEquals(1, HurtCommand.hpFor(30_000, null));
        assertEquals(12_000, HurtCommand.hpFor(30_000, 40));
        assertEquals(1, HurtCommand.hpFor(1, 40));
        assertEquals(858_993_458, HurtCommand.hpFor(2_147_483_647, 40));
    }
}
