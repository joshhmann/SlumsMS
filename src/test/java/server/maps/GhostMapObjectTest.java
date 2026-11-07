package server.maps;

import client.Character;
import client.Client;
import net.opcodes.SendOpcode;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.6 bot-offmap-frozen (1), B-24: a character left in a map's mapobjects after it left the map's characters
 * (a "ghost") was sent to every player entering, frozen where it last stood (TEST: pwnkiller shown on 104040000 and
 * 910000000 at a Henesys spot, 0 moves all day). A player entering no longer gets it, and the map forgets it.
 */
class GhostMapObjectTest {
    @BeforeAll
    static void init() throws Exception {
        MapEntryHarness.initItemProvider();
    }

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("ghost-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
        }
    }

    private final Capture capture = new Capture();
    private Logger logger;
    private Level level;

    @BeforeEach
    void attach() {
        logger = (Logger) LogManager.getLogger(MapleMap.class);
        level = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.INFO);
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void detach() {
        logger.removeAppender(capture);
        capture.stop();
        Configurator.setLevel(logger.getName(), level);
    }

    private List<String> ghostLines() {
        return capture.lines.stream().filter(l -> l.contains("ghost map object")).toList();
    }

    private static long spawnsOf(MapEntryHarness h, Character who) {
        return h.sent.stream().filter(s -> s.opcode() == SendOpcode.SPAWN_PLAYER.getValue() && s.intAt(2) == who.getId()).count();
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, MapObject> mapobjects(MapleMap map) throws Exception {
        Field f = MapleMap.class.getDeclaredField("mapobjects");
        f.setAccessible(true);
        return (Map<Integer, MapObject>) f.get(map);
    }

    /** What the race leaves: out of characters (removePlayer ran), still in mapobjects. */
    private static void leaveAGhost(MapleMap map, Character chr) throws Exception {
        map.removePlayer(chr);
        mapobjects(map).put(chr.getObjectId(), chr);
    }

    private static Character realPlayer(MapEntryHarness h, int id, int x) {
        Client c = mock(Client.class);
        Character p = Character.getDefault(c);
        when(c.getPlayer()).thenReturn(p);
        p.setID(id);
        p.setName("Logged" + id);
        p.setPosition(new Point(x, 0));
        p.setMap(h.map);
        h.map.addPlayer(p);
        return p;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRegularThatLeftIsNotShownToAPlayerEntering(boolean trickle) throws Exception {
        try (MapEntryHarness h = new MapEntryHarness(104040000)) {
            h.trickle(trickle);
            Character ghost = h.bot(0);
            leaveAGhost(h.map, ghost);
            Character me = h.player(0);
            h.enter(me);
            h.scheduler.drain();
            assertEquals(0, spawnsOf(h, ghost), "no SPAWN_PLAYER for a regular no longer on the map");
            assertNull(mapobjects(h.map).get(ghost.getObjectId()), "the map forgets it");
            assertEquals(List.of("INFO ghost map object dropped: map=104040000 char=" + ghost.getId() + " kind=bot"),
                    ghostLines(), "logged once, as a bot");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRealPlayerWhoLoggedOutIsNotShownToAPlayerEntering(boolean trickle) throws Exception {
        try (MapEntryHarness h = new MapEntryHarness(104040000)) {
            h.trickle(trickle);
            Character gone = realPlayer(h, 7, 0);
            leaveAGhost(h.map, gone);
            Character me = h.player(0);
            h.enter(me);
            h.scheduler.drain();
            assertEquals(0, spawnsOf(h, gone), "no SPAWN_PLAYER for a player who logged out");
            assertNull(mapobjects(h.map).get(gone.getObjectId()), "the map forgets it");
            assertEquals(List.of("INFO ghost map object dropped: map=104040000 char=7 kind=player"), ghostLines(),
                    "logged once, as a player");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRegularStillOnTheMapIsShownOnceBesideAGhost(boolean trickle) throws Exception {
        try (MapEntryHarness h = new MapEntryHarness(104040000)) {
            h.trickle(trickle);
            Character ghost = h.bot(0);
            leaveAGhost(h.map, ghost);
            Character here = h.bot(10);
            Character me = h.player(0);
            h.enter(me);
            h.scheduler.drain();
            assertEquals(1, spawnsOf(h, here), "a regular really on the map is sent once, as before");
            assertSame(here, mapobjects(h.map).get(here.getObjectId()), "and stays on the map");
            assertEquals(0, spawnsOf(h, ghost));
            assertEquals(1, ghostLines().size(), "only the ghost is logged");
        }
    }

    /** What BotGeneration.placeBotOnMap leaves when it warps a bot to another map: still in this map's lists. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRegularWarpedElsewhereButStillListedHereIsNotShown(boolean trickle) throws Exception {
        try (MapEntryHarness h = new MapEntryHarness(104040000)) {
            h.trickle(trickle);
            Character moved = h.bot(0);
            moved.setMap(new MapleMap(100000000, 0, 1, 0, 1f)); // now lives in Henesys, never left this map's lists
            Character me = h.player(0);
            h.enter(me);
            h.scheduler.drain();
            assertEquals(0, spawnsOf(h, moved), "not shown where it no longer is");
            assertEquals(List.of("INFO ghost map object skipped: map=104040000 char=" + moved.getId()
                    + " kind=bot realMap=100000000"), ghostLines(), "logged once");
            Character second = h.player(0);
            h.leave(me);
            h.enter(second);
            h.scheduler.drain();
            assertEquals(1, ghostLines().size(), "not logged again on the next entry");
        }
    }
}
