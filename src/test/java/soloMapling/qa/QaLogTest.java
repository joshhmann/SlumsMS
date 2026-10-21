package soloMapling.qa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaLogTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        QaLog.reset();
    }

    private QaRun run(String id, int gm, Set<QaLog.Topic> topics, Set<Integer> maps) throws Exception {
        QaRun run = new QaRun(id, gm, "Gm" + gm, Instant.parse("2026-10-21T18:30:05Z"), dir, topics, maps, Set.of());
        run.open(List.of("header"));
        return run;
    }

    @Test
    void offWhenNothingRuns() {
        for (QaLog.Topic t : QaLog.Topic.values()) {
            assertFalse(QaLog.on(t));
        }
        assertFalse(QaLog.active());
        QaLog.any(QaLog.Topic.ROPE, "nobody listens"); // no run, no error
    }

    @Test
    void onlyTheTopicsOfARunningScenarioAreOn() throws Exception {
        QaRun run = run("ld-1", 1, EnumSet.of(QaLog.Topic.ROPE), Set.of());
        QaLog.attach(run);
        assertTrue(QaLog.on(QaLog.Topic.ROPE));
        assertFalse(QaLog.on(QaLog.Topic.ATTACK));
        QaLog.detach(run);
        assertFalse(QaLog.on(QaLog.Topic.ROPE));
    }

    @Test
    void linesLandInTheRunsFileFilteredByMapAndOwner() throws Exception {
        QaRun a = run("ld-1", 1, EnumSet.of(QaLog.Topic.ROPE, QaLog.Topic.SCROLL), Set.of(101000000));
        QaRun b = run("gn-2", 2, EnumSet.of(QaLog.Topic.SCROLL), Set.of());
        QaLog.attach(a);
        QaLog.attach(b);
        QaLog.map(QaLog.Topic.ROPE, 101000000, "stall in Ellinia");
        QaLog.map(QaLog.Topic.ROPE, 104040000, "stall elsewhere");
        QaLog.owner(QaLog.Topic.SCROLL, 2, "gm 2 scrolled");
        a.stop("test", Instant.parse("2026-10-21T18:31:05Z"));
        b.stop("test", Instant.parse("2026-10-21T18:31:05Z"));

        String fa = Files.readString(a.logPath());
        String fb = Files.readString(b.logPath());
        assertEquals("ld-1-20261021T183005Z.log", a.logPath().getFileName().toString());
        assertTrue(fa.startsWith("# !qa ld-1 - Gm1 (1)"));
        assertTrue(fa.contains("ROPE stall in Ellinia"));
        assertFalse(fa.contains("stall elsewhere"));
        assertFalse(fa.contains("gm 2 scrolled"));
        assertTrue(fa.contains("SUMMARY ROPE 1 events"));
        assertTrue(fb.contains("SCROLL gm 2 scrolled"));
        assertFalse(fb.contains("stall"));
    }

    @Test
    void everyRateLimitsAKey() {
        assertTrue(QaLog.every("k", 60_000));
        assertFalse(QaLog.every("k", 60_000));
        assertTrue(QaLog.every("other", 60_000));
    }

    @Test
    void nothingIsWrittenAfterStop() throws Exception {
        QaRun run = run("wd-1", 1, EnumSet.of(QaLog.Topic.CHANNEL_LOAD), Set.of());
        QaLog.attach(run);
        run.stop("done", Instant.parse("2026-10-21T18:31:05Z"));
        long size = Files.size(run.logPath());
        run.event(QaLog.Topic.CHANNEL_LOAD, "late");
        assertEquals(size, Files.size(run.logPath()));
    }
}
