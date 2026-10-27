package constants.game;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobMasterFeesTest {

    @Test
    void ownersTableFreeThen4mThen75mThen500m() {
        for (int free : new int[]{0, 1000, 2000, 100, 200, 300, 400, 500, 1100, 1500, 2100, 700, 710}) {
            assertEquals(0, JobMasterFees.forTarget(free), "job " + free);
        }
        for (int second : new int[]{110, 120, 130, 210, 310, 420, 520, 1110, 1510, 2110}) {
            assertEquals(4_000_000, JobMasterFees.forTarget(second), "job " + second);
        }
        for (int third : new int[]{111, 121, 211, 321, 411, 521, 1111, 2111}) {
            assertEquals(75_000_000, JobMasterFees.forTarget(third), "job " + third);
        }
        for (int fourth : new int[]{112, 122, 132, 212, 232, 312, 412, 422, 512, 522, 1112, 1512, 2112}) {
            assertEquals(500_000_000, JobMasterFees.forTarget(fourth), "job " + fourth);
        }
        assertEquals(0, JobMasterFees.forTarget(113), "MapleRoot's 5th tier keeps its own rule");
    }

    @Test
    void jobMasterChecksAndTakesTheFeeBeforeAdvancing() throws Exception {
        String js = Files.readString(Path.of("scripts/npc/9010003.js"));
        int check = js.indexOf("cm.getMeso() < fee");
        int take = js.indexOf("cm.getPlayer().gainMeso(-fee)");
        int advance = js.indexOf("jobAdvance();", take);
        assertTrue(js.contains("JobMasterFees.forTarget(job)"), "the fee comes from the table");
        assertTrue(check > 0 && take > check && advance > take, "refuse when short, then take the fee, then advance");
        // 1.6.0 Track D: cm.gainMeso(-n) counts an npc_fee; the Job Master fee has its own key, so count it once
        assertTrue(!js.contains("cm.gainMeso(-fee)") && js.contains("JobMasterFees.recordFee(fee)"),
                "the fee is counted once, as a Job Master fee");
    }
}
