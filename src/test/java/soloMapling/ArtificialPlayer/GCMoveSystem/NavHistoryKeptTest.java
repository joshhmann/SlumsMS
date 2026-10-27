package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.server.History;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Players' recorded moves and the stuck logs are kept; gzipped days are replayed and read like plain ones. */
class NavHistoryKeptTest {

    @TempDir
    Path dir;

    private static NavObservedEdges.Transition move(int x) {
        return new NavObservedEdges.Transition(104040000, 12, new Point(x, 100), 34, new Point(x + 60, 40));
    }

    @Test
    void aGzippedStuckLogDayReadsLikeAPlainOne() throws IOException {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(90);
        Path file = NavStuckLog.fileFor(dir, day);
        Files.writeString(file, "{\"day\":\"" + day + "\",\"spots\":[{\"map\":104040000,\"kind\":\"NO_PATH\","
                + "\"x\":1,\"y\":2,\"edge\":\"\",\"graph\":\"\",\"count\":3}]}");
        List<NavStuckLog.Spot> plain = NavStuckLog.read(file);
        assertEquals(1, plain.size());
        History.gzip(file);
        assertTrue(!Files.exists(file));
        assertEquals(plain.get(0).count, NavStuckLog.read(file).get(0).count, "the .gz twin is read");
    }

    @Test
    void theReplayReadsGzippedAndPlainMoveDays() throws IOException {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path plain = dir.resolve("moves-" + today.minusDays(60) + ".jsonl");
        Path recent = dir.resolve("moves-" + today.minusDays(2) + ".jsonl");
        Files.writeString(plain, NavPlayerLearning.line(move(10), NavObservedEdges.Outcome.ALREADY_JOINED, null, 68, 1L) + "\n");
        Files.writeString(recent, NavPlayerLearning.line(move(20), NavObservedEdges.Outcome.ALREADY_JOINED, null, 68, 2L) + "\n");
        History.gzip(plain);

        List<NavObservedEdges.Transition> all = NavPlayerReplay.fromStoredMoves(dir, Integer.MAX_VALUE);

        assertEquals(List.of(move(10), move(20)), all);
        assertEquals(List.of(move(20)), NavPlayerReplay.fromStoredMoves(dir, 7), "the days window still applies");
        assertTrue(Files.exists(dir.resolve("moves-" + today.minusDays(60) + ".jsonl.gz")));
    }
}
