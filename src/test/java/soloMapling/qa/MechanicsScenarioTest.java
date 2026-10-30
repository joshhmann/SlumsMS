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
    void disposeArguments() {
        assertEquals(2, MechanicsCommand.parseDispose(new String[]{"dispose", "kpq"}, 2), "this channel");
        assertEquals(3, MechanicsCommand.parseDispose(new String[]{"dispose", "KPQ", "3"}, 2));
        assertEquals(-1, MechanicsCommand.parseDispose(new String[]{"dispose"}, 2), "names the PQ");
        assertEquals(-1, MechanicsCommand.parseDispose(new String[]{"dispose", "lpq"}, 2), "only KPQ has runs");
        assertEquals(-1, MechanicsCommand.parseDispose(new String[]{"dispose", "kpq", "x"}, 2));
        assertEquals(-1, MechanicsCommand.parseDispose(new String[]{"dispose", "kpq", "0"}, 2));
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
        assertEquals(new MechanicsCommand.KpqRequest(false, 4, false, 3),
                MechanicsCommand.parseKpq(new String[]{"kpq", "stage3"}, 1), "stop after stage 3");
    }

    @Test
    void mx2PassesOnlyAFullClearInTheTimeWithEveryPrizeTaken() {
        assertEquals("MX2 PASS cleared kpq in 1200 s (limit 1500 s) by 4 bots, 5 wrong tries shown, 4 prizes taken",
                MechanicsScenarios.fullVerdict(true, 1_200_300, "cleared", 5, 4, 4));
        assertTrue(MechanicsScenarios.fullVerdict(true, 1_501_000, "cleared", 0, 4, 4)
                .startsWith("MX2 FAIL cleared kpq only after 1501 s"));
        assertEquals("MX2 FAIL cleared kpq in 1200 s but only 3 of 4 bots took a prize",
                MechanicsScenarios.fullVerdict(true, 1_200_000, "cleared", 0, 4, 3));
        assertEquals("MX2 FAIL the leader left (disconnected)",
                MechanicsScenarios.fullVerdict(false, -1, "the leader left (disconnected)", 0, 4, 0));
        QaScenario mx2 = MechanicsScenarios.all().get(1);
        assertEquals("mx-2", mx2.id());
        QaRefusal r = assertThrows(QaRefusal.class, () -> mx2.stage(null));
        assertTrue(soloMapling.autoqa.StepsAccess.isPrecondition(r.getMessage()), r.getMessage());
    }
}
