package soloMapling.mechanics;

import client.Character;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import soloMapling.qa.QaLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 boss-feed-lines: {@code MechanicsRuns.feed} is the one writer of the new LPQ lines. It reaches the MECHANICS
 * feed (about the real players only) and the server log at INFO under logger {@code BossFeed}, with the leader's name as
 * the only name, and it also writes the log copy when no feed listens. The three existing run shapes stay as they were.
 */
class LpqFeedHelperTest {

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("lpq-feed-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    private static EventInstanceManager party(Character... members) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getName()).thenReturn("Ludi0");
        when(eim.getPlayers()).thenReturn(new ArrayList<>(List.of(members)));
        when(eim.getLeader()).thenReturn(members[0]);
        return eim;
    }

    @Test
    void aLineGoesToTheFeedAndToTheLogAtInfo() {
        Character josh = MechanicsRunsInstancesTest.member(7, false);
        when(josh.getName()).thenReturn("Josh");
        Character bot = MechanicsRunsInstancesTest.member(8, true);
        EventInstanceManager eim = party(josh, bot);

        List<String> heard = new ArrayList<>();
        List<int[]> about = new ArrayList<>();
        QaLog.SinkHandle sink = QaLog.sink((topic, mapId, charIds, message) -> {
            heard.add(topic + " " + message);
            about.add(charIds);
        });
        sink.topics(List.of(QaLog.Topic.MECHANICS));
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("BossFeed");
        Level was = logger.getLevel();
        Configurator.setLevel("BossFeed", Level.INFO);
        Capture capture = new Capture();
        capture.start();
        logger.addAppender(capture);
        try {
            MechanicsRuns.feed(eim, "lpq", "lpq cleared, 2 members to 922011000");
        } finally {
            logger.removeAppender(capture);
            capture.stop();
            Configurator.setLevel("BossFeed", was);
            sink.close();
        }

        String line = "RUN lpq cleared, 2 members to 922011000 [lpq, leader Josh]";
        assertEquals(List.of("MECHANICS " + line), heard);
        assertArrayEquals(new int[]{7}, about.get(0), "the feed is told the real player's id, not the regular's");
        assertEquals(1, capture.lines.size(), capture.lines.toString());
        assertEquals("[Mechanics] Ludi0 " + line, capture.lines.get(0));
    }

    @Test
    void theLogCopyIsWrittenEvenWhenNoFeedListens() {
        Character josh = MechanicsRunsInstancesTest.member(7, false);
        when(josh.getName()).thenReturn("Josh");
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("BossFeed");
        Level was = logger.getLevel();
        Configurator.setLevel("BossFeed", Level.INFO);
        Capture capture = new Capture();
        capture.start();
        logger.addAppender(capture);
        try {
            MechanicsRuns.feed(party(josh), "lpq", "lpq stage 1 hand-in 25 passes");
        } finally {
            logger.removeAppender(capture);
            capture.stop();
            Configurator.setLevel("BossFeed", was);
        }
        assertEquals(List.of("[Mechanics] Ludi0 RUN lpq stage 1 hand-in 25 passes [lpq, leader Josh]"), capture.lines);
    }

    @Test
    void aBrokenInstanceNeverBreaksTheScript() {
        assertDoesNotThrow(() -> MechanicsRuns.feed(null, "lpq", "x"));
        EventInstanceManager broken = mock(EventInstanceManager.class);
        when(broken.getLeader()).thenThrow(new IllegalStateException("disposed"));
        assertDoesNotThrow(() -> MechanicsRuns.feed(broken, "lpq", "x"));
    }
}
