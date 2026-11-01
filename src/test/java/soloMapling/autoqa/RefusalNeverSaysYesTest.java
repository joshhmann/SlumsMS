package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * b265: t-cv-1's "a refusal never says yes" rule (2026-10-31 run) read "maybe later k" as a yes, because its yes
 * pattern takes any word "k" and its refusal guard knew only no/nah/cant/can't/busy. A refusal that merely ends in "k"
 * (chat shorthand) must not count as agreement, and a real yes after a refusal must still be caught.
 */
class RefusalNeverSaysYesTest {

    private static final String REFUSAL = "INTENT intent=come/rule tone=neutral/rule -> COME: NO (cant rn) -> nothing happens";

    /** A run whose only refusal is answered by one replied line in the exact shape ConvoQuality writes. */
    private static CheckRun run(String answer) {
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        CheckRun r = new CheckRun(AutoQaCatalog.find("t-cv-1"), w);
        r.actor(1);
        w.qa("CONVO", REFUSAL);
        w.now += 1_000;
        w.qa("CONVO", "SAID Bot1 -> player 0xab1c [bank#12, decide, own, 1400 ms, slots=none] " + answer);
        w.now += 1_000;
        return r;
    }

    /** The defect: a refusal is answered with "maybe later k", and the rule calls it a yes. */
    @Test
    void aRefusalEndingInKIsNotAYes() {
        assertNull(AutoQaCatalog.refusalNeverSaysYes(run("maybe later k")),
                "\"maybe later k\" is a refusal, not a yes");
        assertNull(AutoQaCatalog.refusalNeverSaysYes(run("maybe later, k")), "the same with a comma");
        assertNull(AutoQaCatalog.refusalNeverSaysYes(run("cant rn k")), "a plain refusal with a trailing k");
    }

    /** The cases the rule already caught must keep failing the check: a real yes after a refusal. */
    @Test
    void aRealYesAfterARefusalIsStillCaught() {
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run("sure, omw")), "a yes is still a yes");
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run("ok")), "a bare ok is still a yes");
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run("k")), "a bare k is still a yes");
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run("im coming")), "coming is still a yes");
    }
}
