package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b280: {@code !qa run pp-1} stages the pet auto-potion scene and {@code !qa do petpot} stages it again, each step in
 * the run's log and in the event feed as {@code STAGE PETPOT} for the caller's char id; any other step name refuses.
 * The staging rules themselves are covered by {@link PetPotStagingTest}.
 */
class PetPotScenarioTest {

    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();
    final List<String> feed = new ArrayList<>();
    final List<int[]> feedChars = new ArrayList<>();
    QaLog.SinkHandle handle;
    QaSuite suite;

    @BeforeEach
    void listen() {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        handle = QaLog.sink((topic, mapId, ids, line) -> {
            if (topic == QaLog.Topic.STAGING) {
                feed.add(line);
                feedChars.add(ids);
            }
        });
        handle.topics(EnumSet.of(QaLog.Topic.STAGING));
    }

    @AfterEach
    void reset() {
        if (handle != null) {
            handle.close();
        }
        QaLog.reset();
    }

    private Character gm() {
        Character gm = QaTestSupport.gm(7, "Admin", 100000000);
        world.online = gm;
        return gm;
    }

    @Test
    void doPetpotStagesItAgain() {
        Character gm = gm();
        suite.start(gm, "pp-1", new String[0]);

        List<String> out = suite.action(gm, "petpot", new String[0]);

        assertEquals(2, world.petPotStages, "!qa do petpot runs the same staging: " + world.calls);
        assertTrue(out.get(0).startsWith("pet Brown Kitty"), out.toString());
        assertTrue(out.get(out.size() - 1).startsWith("Setup stays after !qa stop"), out.toString());
        assertTrue(feed.stream().anyMatch(l -> l.startsWith("STAGE PETPOT gave 100 red potions")), feed.toString());
    }

    @Test
    void anotherStepNameRefusesAndStagesNothing() {
        Character gm = gm();
        suite.start(gm, "pp-1", new String[0]);

        QaRefusal refusal = assertThrows(QaRefusal.class, () -> suite.action(gm, "pouch", new String[0]));

        assertTrue(refusal.getMessage().contains("petpot"), refusal.getMessage());
        assertEquals(1, world.petPotStages, "the refused step did not stage");
    }

    @Test
    void aRefusedStepIsSaidInTheGmSChatAndInTheLog() {
        world.petPotLines = List.of("REFUSED the pouch 1812002 could not be given (EQUIP inventory full?)");
        Character gm = gm();

        List<String> out = suite.start(gm, "pp-1", new String[0]);

        assertTrue(out.stream().anyMatch(l -> l.startsWith("A step refused")), out.toString());
    }
}
