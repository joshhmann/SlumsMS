package server.life;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.TimerManager;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 1.6.4 (round 11): a mob whose WZ {@code revive} names an id that is not in {@code Mob.wz} used to NPE inside the
 * {@code TimerManager} task {@code Monster.killBy} schedules ({@code LifeFactory.getMonster} answers null), so the death
 * never finished - for the whole map, not just for that mob. The spawn is now skipped, named once per id, and the task
 * runs to its end.
 *
 * <p>This machine has no {@code wz/} dump, so {@code LifeFactory} cannot initialize here at all: the lookup is replaced
 * through {@link Monster#reviveTarget(int)} returning null, which is exactly what {@code LifeFactory.getMonster} answers
 * for an id {@code Mob.wz} does not have ({@code LifeFactory.getMonster} catches its own NPE and returns null).
 */
class MonsterMissingReviveTest {

    private static final int NO_SUCH_MOB = 9_999_991;
    private static final int NO_SUCH_MOB_2 = 9_999_992;

    /** The fake's revive lookup knows nothing: every id it is asked for is absent, as a stale WZ chain would be. */
    private static final class StaleChain extends Monster {
        StaleChain(MonsterStats stats) {
            super(8810120, stats);
        }

        @Override
        Monster reviveTarget(int mid) {
            return null;
        }
    }

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("monster-revive-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            Throwable t = event.getThrown();
            lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage()
                    + (t == null ? "" : " <- " + t));
        }
    }

    private final Capture capture = new Capture();
    private final Capture timerCapture = new Capture();
    private Logger logger;
    private Logger timerLogger;
    private Level level;
    private Level timerLevel;

    @BeforeEach
    void attach() {
        TimerManager.getInstance().start(); // killBy schedules the revive spawn on the server's timer
        logger = (Logger) LogManager.getLogger(Monster.class);
        level = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.DEBUG);
        capture.start();
        logger.addAppender(capture);

        timerLogger = (Logger) LogManager.getLogger(TimerManager.class);
        timerLevel = timerLogger.getLevel();
        Configurator.setLevel(timerLogger.getName(), Level.ERROR);
        timerCapture.start();
        timerLogger.addAppender(timerCapture);
    }

    @AfterEach
    void detach() {
        logger.removeAppender(capture);
        capture.stop();
        Configurator.setLevel(logger.getName(), level);
        timerLogger.removeAppender(timerCapture);
        timerCapture.stop();
        Configurator.setLevel(timerLogger.getName(), timerLevel);
    }

    private static StaleChain mob(MapleMap map, int reviveInto) {
        MonsterStats stats = new MonsterStats();
        stats.setHp(80L);
        stats.setExp(0);
        stats.setRevives(List.of(reviveInto));
        StaleChain m = new StaleChain(stats);
        m.setMap(map);
        return m;
    }

    private static MapleMap map() {
        return mock(MapleMap.class);
    }

    /** Waits for the scheduled death task to say something, so the assertion is about the task's end, not a race. */
    private List<String> await(Capture where, String needle) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            List<String> hits = new ArrayList<>();
            for (String line : where.lines) {
                if (line.contains(needle)) {
                    hits.add(line);
                }
            }
            if (!hits.isEmpty()) {
                return hits;
            }
            Thread.sleep(10);
        }
        return List.of();
    }

    @Test
    void aReviveIntoAnAbsentMobIsSkippedAndTheDeathTaskCompletes() throws Exception {
        MapleMap map = map();
        Monster dead = mob(map, NO_SUCH_MOB);

        dead.killBy(null); // synchronously: the death itself must not throw

        List<String> warned = await(capture, "revives into " + NO_SUCH_MOB);
        assertTrue(warned.size() == 1, "one warning naming the bad revive id: " + capture.lines);
        assertTrue(warned.get(0).contains("the spawn is skipped"), warned.get(0));
        assertTrue(warned.get(0).contains("8810120"), "the warning names the dead mob too: " + warned.get(0));
        verify(map, never()).spawnMonster(any());
        assertTrue(timerCapture.lines.isEmpty(), "the death task ended normally, no exception: " + timerCapture.lines);
    }

    @Test
    void theSameBadReviveIdIsNamedOnceAndTheNextDeathStillFinishes() throws Exception {
        MapleMap map = map();
        mob(map, NO_SUCH_MOB_2).killBy(null);
        assertTrue(await(capture, "revives into " + NO_SUCH_MOB_2).size() == 1, capture.lines.toString());

        mob(map, NO_SUCH_MOB_2).killBy(null);
        Thread.sleep(300); // let the second task run: it logs at DEBUG, so no second WARN
        long warns = capture.lines.stream()
                .filter(l -> l.startsWith("WARN") && l.contains(String.valueOf(NO_SUCH_MOB_2)))
                .count();
        assertTrue(warns == 1, "warned once, then debug: " + capture.lines);
        assertTrue(timerCapture.lines.isEmpty(), "both deaths finished: " + timerCapture.lines);
        verify(map, never()).spawnMonster(any());
    }
}
