package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 nx-1: t-nx-1 / t-nx-2 read the lines the NX merchant really writes (NxCodeDelivery's TRADE lines with the
 * bot's name appended, the messenger's "Name : text") and a code in NXCodeManager's format.
 */
class NxCodeCheckTest {

    private static List<Check.Verdict> lineVerdicts(String id, boolean accepted) throws Exception {
        var world = new AutoQaRunnerTest.FakeWorld();
        CheckRun run = new CheckRun(AutoQaCatalog.find(id), world);
        run.actor(1);
        var buyer = (AutoQaRunnerTest.FakeActor) run.actor(2);
        run.set("p2", "AutoQaTwo");
        run.set("bot", "Cashrz");
        world.now += 1000;
        world.qa("TRADE", "NXCODE minted for AutoQaTwo, note saved (bot Cashrz)");
        String code = soloMapling.server.NXCodeManager.generateGiftCardCode();
        if (accepted) {
            buyer.hear("MESSENGER", "Cashrz : here is the 10k nx code... be sure to write it down. Remember to NOT include dashes");
            buyer.hear("MESSENGER", "Cashrz : " + code);
            world.qa("TRADE", "NXCODE typed in messenger for AutoQaTwo (bot Cashrz)");
        } else {
            world.qa("TRADE", "NXCODE messenger not accepted by AutoQaTwo: note shown (bot Cashrz)");
        }
        List<Check.Verdict> out = new ArrayList<>();
        for (Check.Expect e : AutoQaCatalog.find(id).expects()) {
            if (!e.describe().contains("walletDelta")) { // the wallet is a server probe
                out.add(e.eval(run));
            }
        }
        return out;
    }

    @Test
    void theMessengerCheckReadsTheRealLines() throws Exception {
        for (Check.Verdict v : lineVerdicts("t-nx-1", true)) {
            assertEquals(Check.Status.PASS, v.status(), v.toString());
        }
    }

    @Test
    void theNoteCheckReadsTheRealLines() throws Exception {
        for (Check.Verdict v : lineVerdicts("t-nx-2", false)) {
            assertEquals(Check.Status.PASS, v.status(), v.toString());
        }
        assertTrue(lineVerdicts("t-nx-2", true).stream().anyMatch(v -> v.status() == Check.Status.FAIL),
                "a typed messenger code is not the note path");
    }

    @Test
    void theCodePatternMatchesEveryGeneratedCode() {
        for (int i = 0; i < 200; i++) {
            assertTrue(soloMapling.server.NXCodeManager.generateGiftCardCode().matches(AutoQaCatalog.NX_CODE));
        }
    }
}
