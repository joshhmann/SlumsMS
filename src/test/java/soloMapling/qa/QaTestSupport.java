package soloMapling.qa;

import client.Character;

import java.awt.Point;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class QaTestSupport {
    private QaTestSupport() {
    }

    static Character gm(int id, String name, int mapId) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getPosition()).thenReturn(new Point(10, 20));
        when(chr.gmLevel()).thenReturn(6);
        return chr;
    }

    /** Runs scheduled tasks only when the test says so. */
    static final class ManualScheduler implements QaSuite.Scheduler {
        final List<Runnable> once = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> repeating = new ArrayList<>();
        int cancelled;

        @Override
        public Runnable schedule(Runnable task, long delayMs) {
            once.add(task);
            delays.add(delayMs);
            return () -> cancelled++;
        }

        @Override
        public Runnable repeat(Runnable task, long periodMs) {
            repeating.add(task);
            return () -> cancelled++;
        }
    }

    static Clock clock() {
        return Clock.fixed(Instant.parse("2026-10-21T18:30:05Z"), ZoneOffset.UTC);
    }

    static QaSuite suite(List<QaScenario> scenarios, QaWorld world, Path dir, ManualScheduler s) {
        return new QaSuite(scenarios, world, dir, s, clock());
    }
}
