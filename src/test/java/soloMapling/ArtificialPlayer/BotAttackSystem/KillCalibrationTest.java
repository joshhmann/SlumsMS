package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.qa.QaRegistryTestAccess;
import soloMapling.server.DebugTools;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 157: kill calibration bills each kill to the time it took, by job and level band, report-only. */
class KillCalibrationTest {

    private static final int HUNTER = 310;
    private static final int MAP = 101010100;

    @AfterEach
    void resetSwitch() {
        System.clearProperty(KillCalibration.PROPERTY);
        KillCalibration.resetSwitch();
    }

    @Test
    void steadyKillsGiveTheirRateAndTheRatioAgainstTheModel() {
        KillCalibration c = new KillCalibration();
        long t = 1_000_000;
        assertFalse(c.record(1, HUNTER, 45, MAP, t, 10), "the first kill only anchors the stretch");
        for (int i = 1; i <= 30; i++) {   // a kill every 4 s = 15 a minute, the model says 10
            assertTrue(c.record(1, HUNTER, 45, MAP, t + i * 4_000L, 10));
        }
        KillCalibration.Row row = c.rows().getFirst();
        assertEquals(HUNTER, row.jobId());
        assertEquals("40-49", row.bandLabel());
        assertEquals(30, row.totalKills());
        assertEquals(15.0, row.realKpm(), 1e-9);
        assertEquals(10.0, row.modelKpm(), 1e-9);
        assertEquals(1.5, row.ratio(), 1e-9, "uniform gaps: the faded ratio is exact");
    }

    @Test
    void anAoeBurstCountsEveryKillAgainstAlmostNoTime() {
        KillCalibration c = new KillCalibration();
        long t = 5_000_000;
        c.record(2, HUNTER, 45, MAP, t, 20);
        // a swing every 9 s that kills three: 3 kills per 9 s = 20 a minute, not "a kill every 9 s" (6.7)
        for (int s = 1; s <= 30; s++) {
            long at = t + s * 9_000L;
            c.record(2, HUNTER, 45, MAP, at, 20);
            c.record(2, HUNTER, 45, MAP, at, 20);
            c.record(2, HUNTER, 45, MAP, at, 20);
        }
        KillCalibration.Row row = c.rows().getFirst();
        assertEquals(20.0, row.realKpm(), 0.6, "a sustained rate, not a per-swing one");
        assertEquals(1.0, row.ratio(), 0.03);
    }

    @Test
    void aBreakAMapChangeOrALevelUpStartsANewStretchWithoutBillingTheGap() {
        KillCalibration c = new KillCalibration();
        long t = 9_000_000;
        c.record(3, HUNTER, 45, MAP, t, 10);
        c.record(3, HUNTER, 45, MAP, t + 6_000, 10);                                  // billed: 6 s
        assertFalse(c.record(3, HUNTER, 45, MAP, t + 6_000 + KillCalibration.MAX_GAP_MS + 1, 10), "a break");
        assertFalse(c.record(3, HUNTER, 45, MAP + 1, t + 200_000, 10), "another map");
        assertFalse(c.record(3, HUNTER, 46, MAP + 1, t + 203_000, 10), "a level up");
        KillCalibration.Row row = c.rows().getFirst();
        assertEquals(1, row.totalKills());
        assertEquals(6_000, row.totalActiveMs());
        assertNull(row.ratio(), "under a minute of billed time: no ratio yet");
    }

    @Test
    void bucketsAreByJobAndTenLevelBandAndAKillWithoutAModelIsCounted() {
        KillCalibration c = new KillCalibration();
        long t = 1_000;
        c.record(4, HUNTER, 39, MAP, t, 0);
        c.record(4, HUNTER, 39, MAP, t + 3_000, 0);
        c.record(5, 210, 39, MAP, t, 12);
        c.record(5, 210, 39, MAP, t + 3_000, 12);
        List<KillCalibration.Row> rows = c.rows();
        assertEquals(2, rows.size());
        KillCalibration.Row hunter = rows.stream().filter(r -> r.jobId() == HUNTER).findFirst().orElseThrow();
        assertEquals("30-39", hunter.bandLabel());
        assertEquals(1, hunter.unmodelled());
        assertNull(hunter.ratio(), "no model, no ratio");
    }

    @Test
    void theJsonShape() {
        KillCalibration c = new KillCalibration();
        long t = 1_000;
        for (int i = 0; i <= 20; i++) {
            c.record(6, HUNTER, 52, MAP, t + i * 5_000L, 10);
        }
        Map<String, Object> doc = c.json(123);
        assertEquals(1, doc.get("version"));
        assertEquals(123L, ((Number) doc.get("at")).longValue());
        assertEquals(true, doc.get("reportOnly"));
        assertTrue(doc.get("rules") instanceof Map<?, ?>);
        Map<?, ?> totals = (Map<?, ?>) doc.get("totals");
        assertEquals(20L, ((Number) totals.get("billed")).longValue());
        Map<?, ?> row = (Map<?, ?>) ((List<?>) doc.get("buckets")).getFirst();
        assertEquals(List.of("jobId", "job", "band", "samples", "kills", "activeMinutes", "realKpm", "modelKpm", "ratio",
                "unmodelled", "lastAt"), List.copyOf(row.keySet()));
        assertEquals("HUNTER", row.get("job"));
        assertEquals("50-59", row.get("band"));
        assertEquals(12.0, ((Number) row.get("realKpm")).doubleValue(), 1e-9);
        assertEquals(1.2, ((Number) row.get("ratio")).doubleValue(), 1e-9);
        assertNotNull(soloMapling.server.Json.write(doc));
        List<String> lines = c.summary(5);
        assertTrue(lines.get(1).startsWith("HUNTER 50-59: real 12.0/min vs model 10.0/min, x1.20"), lines.get(1));
    }

    @Test
    void offOnLiveOnOnTheTestServerAndForcedEitherWay() {
        assertFalse(DebugTools.featureFor(KillCalibration.PROPERTY, QaRegistryTestAccess.live()::get));
        assertTrue(DebugTools.featureFor(KillCalibration.PROPERTY, QaRegistryTestAccess.test()::get));
        assertTrue(DebugTools.featureFor(KillCalibration.PROPERTY,
                QaRegistryTestAccess.props("java -Dslums.killcalib=true -jar x.jar")::get));
        assertFalse(DebugTools.featureFor(KillCalibration.PROPERTY,
                QaRegistryTestAccess.props("java -Dslums.qa=true -Dslums.killcalib=false -jar x.jar")::get));
    }

    @Test
    void aRealKillOnAWatchedMapIsSeenOnlyWhileTheSwitchIsOn() {
        int map = 910000961;
        client.Character bot = client.Character.getDefault(new client.BotClient(0, 0));
        bot.setID(1_000_000_123);
        bot.setMapId(map);
        assertEquals(null, soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.lodPin(map));
        try {
            System.setProperty(KillCalibration.PROPERTY, "false");
            KillCalibration.resetSwitch();
            long seen = KillCalibration.instance().seen.sum();
            KillCalibration.onRealKill(bot);
            assertEquals(seen, KillCalibration.instance().seen.sum(), "off: not even looked at");

            System.setProperty(KillCalibration.PROPERTY, "true");
            KillCalibration.resetSwitch();
            long skipped = KillCalibration.instance().skippedNotGrinding.sum();
            KillCalibration.onRealKill(bot);
            assertEquals(seen + 1, KillCalibration.instance().seen.sum(), "on: a kill on a watched map is seen");
            assertEquals(skipped + 1, KillCalibration.instance().skippedNotGrinding.sum(), "but not billed: not grinding");
        } finally {
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.lodUnpin(map);
        }
    }
}
