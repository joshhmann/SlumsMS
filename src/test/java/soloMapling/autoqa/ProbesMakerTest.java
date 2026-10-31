package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.EconomyConfig;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 112 decision 3: auto-QA t-mk-2, the bots' Maker batch; its wiring, its SKIP and its verdict rules. */
class ProbesMakerTest {

    @AfterEach
    void reset() {
        EconomyConfig.useForTest(null);
    }

    @Test
    void theCheckIsAutomatedNightlyAndUsesAKnownProbe() {
        Check c = AutoQaCatalog.find("t-mk-2");
        assertNotNull(c);
        assertEquals(Check.Kind.AUTO, c.kind());
        assertTrue(c.nightly());
        Pattern probe = Pattern.compile("^(?:probe|fixture) (\\w+)");
        int probes = 0;
        for (Check.Expect e : c.expects()) {
            Matcher m = probe.matcher(e.describe());
            if (m.find()) {
                assertTrue(ProbesMaker.NAMES.contains(m.group(1)), "unknown probe " + m.group(1));
                probes++;
            }
        }
        assertEquals(1, probes);
        assertNull(ProbesMaker.probe("noSuchProbe", null));
        for (String n : ProbesMaker.NAMES) {
            assertNull(Probes160.probe(n, null), n);
            assertNull(Probes162.probe(n, null), n);
            assertNull(Probes164.probe(n, null), n);
        }
    }

    @Test
    void withTheSwitchOffItSkipsAndNamesIt() {
        EconomyConfig.useForTest(Map.of("maker.botBatch", "false"));
        List<String> out = ProbesMaker.probe("makerBatch", null);
        assertTrue(out.get(0).startsWith("SKIP "), out.toString());
        assertTrue(out.get(0).contains("solomapling.economy.maker.botBatch"));
    }

    private static ProbesMaker.Outcome good() {
        // 1 crystal + 2 disassemblies = 3 operations, though the disassemblies gave 4 more crystals
        return new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 0, 5, 2, true, 3, 3);
    }

    @Test
    void aCleanBatchHasNoProblems() {
        assertEquals(List.of(), ProbesMaker.problems(good()));
    }

    @Test
    void theFeeMustComeOffTheWalletToTheMeso() {
        var o = good();
        var low = new ProbesMaker.Outcome(o.made(), 1, 2, 14000, 100, 14000, 0, 5, 2, true, 3, 3);
        assertTrue(ProbesMaker.problems(low).get(0).contains("the client's fee is 14000"), ProbesMaker.problems(low).toString());
        var free = new ProbesMaker.Outcome(3, 1, 2, 14000, 14000, 14000, 0, 5, 2, true, 3, 3);
        assertFalse(ProbesMaker.problems(free).isEmpty(), "disassembling for free fails");
        var zeroFee = new ProbesMaker.Outcome(3, 1, 2, 5, 5, 0, 0, 5, 2, true, 3, 3);
        assertFalse(ProbesMaker.problems(zeroFee).isEmpty(), "a staged fee of 0 proves nothing");
    }

    @Test
    void theBadItemMustBeSkippedAndTheBatchMustCarryOn() {
        var disassembled = new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 0, 5, 2, false, 3, 3);
        assertTrue(ProbesMaker.problems(disassembled).stream().anyMatch(s -> s.contains("bad equip")));
        var stoppedAtBad = new ProbesMaker.Outcome(1, 1, 2, 14000, 14000, 14000, 0, 1, 0, true, 1, 1);
        List<String> p = ProbesMaker.problems(stoppedAtBad);
        assertTrue(p.stream().anyMatch(s -> s.contains("made 1 operations, want 3")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("disassembled 0 of 2")), p.toString());
    }

    @Test
    void theCapsCountOperationsNotCrystals() {
        var byCrystals = new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 0, 5, 2, true, 5, 5);
        List<String> p = ProbesMaker.problems(byCrystals);
        assertEquals(2, p.size(), p.toString());
        assertTrue(p.get(0).contains("not 5 crystals"));
        var worldOnly = new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 0, 5, 2, true, 3, 0);
        assertEquals(1, ProbesMaker.problems(worldOnly).size());
    }

    @Test
    void noCrystalIsAFailure() {
        var o = new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 100, 0, 2, true, 3, 3);
        assertTrue(ProbesMaker.problems(o).get(0).contains("100 leftovers left"));
    }
}
