package soloMapling.qa;

import client.command.commands.gm3.MechanicsCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.7.0 ticket 130: mx-1's verdict line, its refusal while mechanics is off, and !mechanics kpq's arguments. */
class MechanicsScenarioTest {

    @Test
    void theVerdictPassesOnlyAClearWithinTheLimit() {
        assertEquals("MX1 PASS cleared stage 1 in 312 s (limit 900 s) by 4 bots, 2 wrong tries shown",
                MechanicsScenarios.verdict(true, 312_400, "cleared", 2, 4));
        assertTrue(MechanicsScenarios.verdict(true, 901_000, "cleared", 0, 4).startsWith("MX1 FAIL cleared stage 1"
                + " only after 901 s"));
        assertEquals("MX1 FAIL the event ended", MechanicsScenarios.verdict(false, -1, "the event ended", 0, 4));
    }

    @Test
    void mx1RefusesWhileMechanicsIsOff() {
        QaScenario mx1 = MechanicsScenarios.all().get(0);
        assertEquals("mx-1", mx1.id());
        QaRefusal r = assertThrows(QaRefusal.class, () -> mx1.stage(null));
        assertTrue(r.getMessage().contains("needs -Dslums.mechanics=true"), r.getMessage());
        // the auto-QA runner turns a refusal with "needs " into a SKIP, not a FAIL
        assertTrue(soloMapling.autoqa.StepsAccess.isPrecondition(r.getMessage()));
    }

    @Test
    void kpqArguments() {
        assertEquals(new MechanicsCommand.KpqRequest(false, 4, false), MechanicsCommand.parseKpq(new String[]{"kpq"}, 1));
        assertEquals(new MechanicsCommand.KpqRequest(true, 3, false),
                MechanicsCommand.parseKpq(new String[]{"kpq", "me"}, 1));
        assertEquals(new MechanicsCommand.KpqRequest(true, 2, true),
                MechanicsCommand.parseKpq(new String[]{"kpq", "me", "2", "perfect"}, 1));
        assertEquals(new MechanicsCommand.KpqRequest(false, 3, false),
                MechanicsCommand.parseKpq(new String[]{"kpq", "bots", "3"}, 1));
        assertNull(MechanicsCommand.parseKpq(new String[]{"kpq", "2"}, 1), "two bots alone are not a KPQ party");
        assertNull(MechanicsCommand.parseKpq(new String[]{"kpq", "me", "4"}, 1), "you + 4 is five");
        assertNull(MechanicsCommand.parseKpq(new String[]{"kpq", "lots"}, 1));
    }
}
