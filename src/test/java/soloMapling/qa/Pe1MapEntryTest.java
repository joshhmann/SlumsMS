package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** pe-1: warps the GM between two maps on a timer and writes the entry summary at stop. */
class Pe1MapEntryTest {
    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();

    @AfterEach
    void reset() {
        QaLog.reset();
        MapEntryMeter.clear(1);
    }

    private QaSuite start(String... args) {
        QaSuite suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        world.online = gm;
        suite.start(gm, "pe-1", args);
        return suite;
    }

    @Test
    void warpsToTheOtherMapEveryIntervalAndSummarisesAtStop() throws Exception {
        QaSuite suite = start("100000000", "100000100", "4");
        assertTrue(world.calls.contains("warp 100000000"));
        assertTrue(QaLog.on(QaLog.Topic.MAP_ENTRY));
        Runnable tick = scheduler.repeating.get(scheduler.repeating.size() - 1);
        for (int i = 0; i < 3; i++) {
            tick.run();
        }
        assertEquals(0, world.calls.stream().filter("warp 100000100"::equals).count());
        tick.run();
        assertEquals(1, world.calls.stream().filter("warp 100000100"::equals).count());

        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        String text = Files.readString(log);
        assertTrue(text.contains("SUMMARY"), text);
        assertTrue(text.contains("map entries: no entries measured, warps 1"), text);
    }

    @Test
    void refusesTheSameMapTwiceOrABadInterval() {
        assertThrows(QaRefusal.class, () -> start("100000000", "100000000"));
        assertThrows(QaRefusal.class, () -> start("100000000", "100000100", "1"));
        assertThrows(QaRefusal.class, () -> start("henesys"));
    }

    @Test
    void theEntryLineAndSummaryCarryTheNumbers() {
        MapEntryMeter.Window w = new MapEntryMeter.Window(100000000, 100000100);
        byte[] spawn = {(byte) 0xA0, 0, 1, 2, 3, 4};
        for (int i = 0; i < 5; i++) {
            w.sent(() -> spawn);
        }
        w.placedNow = 3;
        w.deferred = 2;
        w.trickleSent = 2;
        w.census = "PLAYER 3 (bots 2)";
        MapEntryMeter.Result r = new MapEntryMeter.Result(100000000, 100000100, 1.5, 5, 50, 2, 210, 5, 50);
        String line = MapEntryMeter.line(r, w);
        assertTrue(line.startsWith("entry 100000000->100000100 sync_ms=1.5 sync_packets=5"), line);
        assertTrue(line.contains("deferred=2 trickled=2"), line);
        assertTrue(line.contains("objects=[PLAYER 3 (bots 2)]"), line);
        assertEquals(5, w.packets.get());
        assertEquals(50, w.bytes.get());
        Map<Integer, AtomicInteger> ops = w.byOpcode;
        assertEquals(5, ops.get(0xA0).get());

        String s = MapEntryMeter.summary(List.of(r, new MapEntryMeter.Result(1, 2, 3.0, 10, 100, 0, 0, 20, 2048)));
        assertTrue(s.startsWith("n=2 sync_ms p50/p95 1.5/3.0, sync_packets p50/p95 5/10"), s);
    }
}
