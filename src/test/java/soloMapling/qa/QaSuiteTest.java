package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Start/stop bookkeeping: one run per GM, claims, the timeout, undos in reverse, logs on and off. */
class QaSuiteTest {
    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();

    @AfterEach
    void reset() {
        QaLog.reset();
    }

    /** A scenario claiming map 100 that runs {@code stage} and registers two undos. */
    static QaScenario scenario(String id, Consumer<QaContext> stage) {
        return new QaScenario() {
            public String id() { return id; }
            public String title() { return "test " + id; }
            public Set<QaLog.Topic> topics() { return EnumSet.of(QaLog.Topic.ROPE); }
            public Set<String> claims(Character gm, String[] args) { return Set.of("map:100"); }
            public int minutes(String[] args) { return 7; }
            public List<String> stage(QaContext ctx) {
                if (stage != null) {
                    stage.accept(ctx);
                }
                return List.of("staged");
            }
        };
    }

    private QaSuite suite(QaScenario... s) {
        return QaTestSupport.suite(List.of(s), world, dir, scheduler);
    }

    @Test
    void runLogsStopsAndUndoesInReverse() throws Exception {
        List<String> order = new ArrayList<>();
        QaSuite suite = suite(scenario("ld-1", ctx -> {
            ctx.onStop("first", () -> order.add("first"));
            ctx.onStop("second", () -> order.add("second"));
        }));
        Character gm = QaTestSupport.gm(1, "Admin", 100);
        List<String> out = suite.start(gm, "LD-1", new String[0]);
        assertEquals("QA ld-1 running: test ld-1", out.get(0));
        assertTrue(QaLog.on(QaLog.Topic.ROPE));
        assertEquals(List.of(7 * 60_000L), scheduler.delays);
        QaLog.any(QaLog.Topic.ROPE, "a stall");
        Path log = suite.runOf(1).logPath();
        assertTrue(suite.where(1).get(0).startsWith("Running ld-1"));

        List<String> stopped = suite.stop(1, "!qa stop");
        assertEquals(List.of("second", "first"), order);
        assertFalse(QaLog.on(QaLog.Topic.ROPE));
        assertNull(suite.runOf(1));
        assertEquals(1, scheduler.cancelled);
        assertTrue(stopped.get(0).startsWith("QA ld-1 stopped (!qa stop)"));
        String text = Files.readString(log);
        assertTrue(text.contains("# gm Admin id 1 gm level 6"));
        assertTrue(text.contains("# where map 100"));
        assertTrue(text.contains("ROPE a stall"));
        assertTrue(text.contains("CLEANUP undone: second"));
        assertEquals("No QA scenario running.", suite.stop(1, "again").get(0));
    }

    @Test
    void oneRunPerGmAndClaimsCollide() {
        QaSuite suite = suite(scenario("ld-1", null), scenario("gh-1", null));
        suite.start(QaTestSupport.gm(1, "A", 100), "ld-1", null);
        QaRefusal mine = assertThrows(QaRefusal.class, () -> suite.start(QaTestSupport.gm(1, "A", 100), "gh-1", null));
        assertTrue(mine.getMessage().contains("already running ld-1"));
        QaRefusal other = assertThrows(QaRefusal.class, () -> suite.start(QaTestSupport.gm(2, "B", 100), "gh-1", null));
        assertTrue(other.getMessage().contains("A is running ld-1"));
        assertThrows(QaRefusal.class, () -> suite.start(QaTestSupport.gm(2, "B", 100), "zz-9", null));
    }

    @Test
    void theTimeoutStopsAndTellsTheGm() {
        List<String> order = new ArrayList<>();
        QaSuite suite = suite(scenario("ld-1", ctx -> ctx.onStop("bots", () -> order.add("bots"))));
        Character gm = QaTestSupport.gm(1, "A", 100);
        world.online = gm;
        suite.start(gm, "ld-1", null);
        scheduler.once.get(0).run();
        assertEquals(List.of("bots"), order);
        assertNull(suite.runOf(1));
        assertTrue(world.messages.get(0).contains("stopped (timeout)"));
    }

    @Test
    void aRefusedStageUndoesWhatItHadDoneAndLeavesNoRun() throws Exception {
        List<String> order = new ArrayList<>();
        QaSuite suite = suite(scenario("ld-1", ctx -> {
            ctx.onStop("half", () -> order.add("half"));
            throw new QaRefusal("no");
        }));
        assertThrows(QaRefusal.class, () -> suite.start(QaTestSupport.gm(1, "A", 100), "ld-1", null));
        assertEquals(List.of("half"), order);
        assertNull(suite.runOf(1));
        assertFalse(QaLog.on(QaLog.Topic.ROPE));
        try (var files = Files.list(dir)) {
            String text = Files.readString(files.findFirst().orElseThrow());
            assertTrue(text.contains("STOP refused: no"));
        }
    }

    @Test
    void aFailingUndoDoesNotStopTheOthers() {
        List<String> order = new ArrayList<>();
        QaSuite suite = suite(scenario("ld-1", ctx -> {
            ctx.onStop("ok", () -> order.add("ok"));
            ctx.onStop("broken", () -> {
                throw new IllegalStateException("boom");
            });
        }));
        suite.start(QaTestSupport.gm(1, "A", 100), "ld-1", null);
        List<String> out = suite.stop(1, "!qa stop");
        assertEquals(List.of("ok"), order);
        assertTrue(out.get(1).startsWith("cleanup failed: broken"));
    }
}
