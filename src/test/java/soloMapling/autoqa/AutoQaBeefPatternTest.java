package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.6.0 S7 "Beef": the auto-QA checks t-rv-5, t-dr-6 and t-cr-1 pass on the lines their runs write. */
class AutoQaBeefPatternTest {

    static void allPass(String id, String... lines) throws Exception {
        List<Check.Verdict> v = AutoQaCatalogPatternTest.eval(id, lines);
        v.forEach(x -> assertEquals(Check.Status.PASS, x.status(), x.toString()));
    }

    static void lastFails(String id, String[] lines, String extra) throws Exception {
        String[] more = Arrays.copyOf(lines, lines.length + 1);
        more[lines.length] = extra;
        List<Check.Verdict> v = AutoQaCatalogPatternTest.eval(id, more);
        assertEquals(Check.Status.FAIL, v.stream().filter(x -> x.status() == Check.Status.FAIL).findFirst()
                .map(Check.Verdict::status).orElse(Check.Status.PASS), extra);
    }

    static final String[] RV5 = {
            "RV5 RV5 STAGED A=Amy (1000000001) B=Bob (1000000002); Amy -> Bob: rival -40; Bob -> Amy: rival -40; crew no",
            "RV5 RV5 FACT rivalry: Amy has beef with Bob (3 run-ins); opener \"Bob u again? ugh\"; guard clean",
            "CONVO THREAD Amy + Bob on 100000000: rival beef (Amy has beef with Bob (3 run-ins))",
            "RV5 RV5 PASS Amy starts beef with Bob (bank)",
            "CONVO THREAD LINE [bank] Amy: bob u again? ugh",
            "CONVO THREAD LINE [bank] Bob: lol cry about it amy"};

    @Test
    void rv5() throws Exception {
        allPass("t-rv-5", RV5);
        lastFails("t-rv-5", RV5, "CONVO NAMEGUARD REWRITE Bob: \"xxslayerxx did it\" names [xxslayerxx] -> \"some guy did it\"");
    }

    static final String[] DR6 = {
            "DR6 DR6 STAGED A=Amy (1000000001) friend=Bob (1000000002); Amy -> Bob: friend 60",
            "RIVALRY RAGEQUIT Amy (maple sword): \"im done with this game\"; back in 0 min (staged)",
            "DR6 DR6 QUIT Amy rage quits: \"im done with this game\"",
            "DR6 DR6 GOSSIP Bob: \"Amy rage quit after a boom lol\" (Amy rage quit after a boom)",
            "DR6 DR6 GONE Amy is away (channel 2)",
            "RIVALRY RAGEQUIT BACK Amy after 0 min: \"im back lol\"",
            "DR6 DR6 PASS Amy rage quit and came back"};

    @Test
    void dr6() throws Exception {
        allPass("t-dr-6", DR6);
        lastFails("t-dr-6", DR6, "DR6 DR6 FAIL the friend knows nothing about it");
    }

    static final String[] CR1 = {
            "CR1 CR1 STAGED A=Amy (1000000001) likes B=Bob (1000000002), C=Cid (1000000003) is A's rival; crush on; couple cap 20 for this roster",
            "COUPLE CRUSH START Amy -> Bob (staged)",
            "COUPLE CRUSH NEAR Amy walks over to stand near Bob",
            "COUPLE CRUSH LINE Amy -> Bob: hi Bob :)",
            "CR1 CR1 RIVAL Cid walks up to Bob",
            "COUPLE CRUSH JEALOUS Amy (likes Bob) at Cid: Cid go away lol",
            "COUPLE FORMED couple #-3: 1000000001 + 1000000002 (staged)",
            "COUPLE CRUSH TO COUPLE #-3: Amy + Bob",
            "CR1 CR1 COUPLE couple #-3"};

    @Test
    void cr1() throws Exception {
        allPass("t-cr-1", CR1);
        lastFails("t-cr-1", CR1, "CR1 CR1 FAIL not ripe yet (3 min old, returned false)");
    }
}
