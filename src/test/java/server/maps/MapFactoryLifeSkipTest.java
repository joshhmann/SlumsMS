package server.maps;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import provider.Data;
import provider.wz.XMLDomMapleData;
import server.life.NPC;
import soloMapling.WzTestData;

import java.awt.Point;
import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-E1 (HOLES server/world-and-maps): one bad {@code life} entry aborted the whole map load, because
 * {@code MapFactory.loadLife} dereferenced the {@code null} a missing mob/NPC id gives back. A map with one bad spawn
 * load minus that spawn instead, with one warning that names the map, the entry and the reason.
 */
class MapFactoryLifeSkipTest {

    private static final int MAP_ID = 910000123;

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("p1-e1-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    private final List<Runnable> undo = new ArrayList<>();

    private Capture attachWarnings() {
        Logger logger = (Logger) LogManager.getLogger(MapFactory.class);
        Level level = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.DEBUG); // the test config turns the root logger off
        Capture capture = new Capture();
        capture.start();
        logger.addAppender(capture);
        undo.add(() -> {
            logger.removeAppender(capture);
            capture.stop();
            Configurator.setLevel(logger.getName(), level);
        });
        return capture;
    }

    @AfterEach
    void detach() {
        for (int i = undo.size() - 1; i >= 0; i--) {
            undo.get(i).run();
        }
        undo.clear();
    }

    /** Three life entries: a good NPC, a monster id the game has no data for, and another good NPC. */
    private static final String MAP_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <imgdir name="910000123.img">
              <imgdir name="life">
                <imgdir name="0">
                  <string name="id" value="9010000"/>
                  <string name="type" value="n"/>
                  <int name="x" value="10"/>
                  <int name="y" value="20"/>
                </imgdir>
                <imgdir name="1">
                  <string name="id" value="999999999"/>
                  <string name="type" value="m"/>
                  <int name="x" value="30"/>
                  <int name="y" value="40"/>
                </imgdir>
                <imgdir name="2">
                  <string name="id" value="9010001"/>
                  <string name="type" value="n"/>
                  <int name="x" value="50"/>
                  <int name="y" value="60"/>
                </imgdir>
              </imgdir>
            </imgdir>
            """;

    private static void loadLifeFromWz(MapleMap map, Data mapData) throws Exception {
        // the loader is private: the test drives the exact method the map load calls, before and after the fix alike
        Method method = MapFactory.class.getDeclaredMethod("loadLifeFromWz", MapleMap.class, Data.class);
        method.setAccessible(true);
        try {
            method.invoke(null, map, mapData);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw (Error) cause;
        }
    }

    @Test
    void oneBadLifeEntryIsSkippedAndLoggedWhileTheRestOfTheMapLoads() throws Exception {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("String.wz");
        WzTestData.assumePresent("Npc.wz");
        WzTestData.assumePresent("Mob.wz");

        Path file = Files.createTempFile("p1-e1-map-life", ".xml");
        Files.writeString(file, MAP_XML);
        Data mapData;
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            mapData = new XMLDomMapleData(in, Path.of("."));
        } finally {
            Files.deleteIfExists(file);
        }

        MapleMap map = new MapleMap(MAP_ID, 0, 0, MAP_ID, 1.0f);
        Capture warnings = attachWarnings();

        loadLifeFromWz(map, mapData);

        List<MapObject> objects = map.getMapObjects();
        assertEquals(2, objects.size(), "the two good NPCs spawn, the bad entry does not: " + objects);
        MapObject first = objects.get(0);
        MapObject second = objects.get(1);
        assertInstanceOf(NPC.class, first);
        assertInstanceOf(NPC.class, second);
        assertEquals(new Point(10, 20), first.getPosition());
        assertEquals(new Point(50, 60), second.getPosition());

        assertEquals(1, warnings.lines.size(), "exactly one warning for the one bad entry: " + warnings.lines);
        String line = warnings.lines.get(0);
        assertTrue(line.contains(String.valueOf(MAP_ID)), "the warning names the map: " + line);
        assertTrue(line.contains("999999999"), "the warning names the life id: " + line);
        assertTrue(line.contains("m"), "the warning names the life type: " + line);
    }
}
