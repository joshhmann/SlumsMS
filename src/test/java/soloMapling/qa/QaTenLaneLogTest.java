package soloMapling.qa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.autoqa.SyntheticPlayers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 163: ten auto-QA lanes log in at Henesys at once, every lane's GM id is different, and a bot's SAID line is
 * addressed to the player the bot answered ({@link QaLog#toPlayer}). That routing is what keeps lane 10's convo check
 * from grading lane 3's bots: the line reaches the one run whose GM is that player, and nothing else.
 */
class QaTenLaneLogTest {

    private static final int LANES = 10;

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        QaLog.reset();
    }

    /** One run per lane, its GM the lane's first synthetic player (ids on the synthetic slots, as the lanes' are). */
    private List<QaRun> tenLanes() throws Exception {
        List<QaRun> runs = new ArrayList<>();
        for (int lane = 1; lane <= LANES; lane++) {
            int gmId = SyntheticPlayers.laneSlot(lane, 1);
            QaRun run = new QaRun("t-cv-1", gmId, SyntheticPlayers.nameOf(gmId),
                    Instant.parse("2026-11-01T09:00:00Z").plusSeconds(lane), dir, EnumSet.of(QaLog.Topic.CONVO),
                    Set.of(), Set.of(), "");
            run.open(List.of("lane " + lane));
            QaLog.attach(run);
            runs.add(run);
        }
        return runs;
    }

    @Test
    void aSaidLineReachesOnlyTheLaneItNames() throws Exception {
        List<QaRun> runs = tenLanes();
        assertEquals(LANES, runs.size());
        for (int lane = 1; lane <= LANES; lane++) {
            int gmId = SyntheticPlayers.laneSlot(lane, 1);
            QaLog.toPlayer(QaLog.Topic.CONVO, gmId, "lane " + lane + " said hello");
        }
        for (int lane = 1; lane <= LANES; lane++) {
            QaRun run = runs.get(lane - 1);
            run.stop("test", Instant.parse("2026-11-01T09:01:00Z"));
            String text = Files.readString(run.logPath());
            assertTrue(text.contains("CONVO lane " + lane + " said hello"), "lane " + lane + " hears its own line");
            for (int other = 1; other <= LANES; other++) {
                if (other != lane) {
                    assertFalse(text.contains("CONVO lane " + other + " said hello"),
                            "lane " + lane + " also heard lane " + other);
                }
            }
        }
    }

    @Test
    void aLineAddressedToALanesSecondPlayerReachesNoLane() throws Exception {
        // exactly why a check that grades SAID must speak as its own GM: a line addressed to the lane's plain player
        // matches no run's gmId, so it is dropped instead of landing in all ten
        List<QaRun> runs = tenLanes();
        for (int lane = 1; lane <= LANES; lane++) {
            QaLog.toPlayer(QaLog.Topic.CONVO, SyntheticPlayers.laneSlot(lane, 2), "not the GM");
        }
        for (QaRun run : runs) {
            run.stop("test", Instant.parse("2026-11-01T09:01:00Z"));
            assertFalse(Files.readString(run.logPath()).contains("not the GM"),
                    "a line addressed to a plain player reached " + run.gmName());
        }
    }

    @Test
    void aMapLineStillReachesEveryLane() throws Exception {
        // the map-wide topics (MANNERS, TALLY ...) are unchanged: lane k holds its map so it cannot see the others.
        List<QaRun> runs = tenLanes();
        QaLog.map(QaLog.Topic.CONVO, 0, "everyone hears this");
        for (QaRun run : runs) {
            run.stop("test", Instant.parse("2026-11-01T09:01:00Z"));
            assertTrue(Files.readString(run.logPath()).contains("everyone hears this"), run.gmName());
        }
    }
}
