package soloMapling.qa;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** b140: {@code hc-1} stages one Bishop follower server-side and says so, for the GM's char id. */
class HealColumnScenarioTest {
    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();
    final List<String> feed = new ArrayList<>();
    final List<int[]> feedChars = new ArrayList<>();
    QaLog.SinkHandle handle;
    QaSuite suite;

    @BeforeEach
    void partySupport() {
        System.setProperty("slums.bots.partysupport", "true"); // hc-1 refuses without it, as TEST and live set it
    }

    @AfterEach
    void reset() {
        if (handle != null) {
            handle.close();
        }
        System.clearProperty("slums.bots.partysupport");
        QaLog.reset();
    }

    private void listen() {
        handle = QaLog.sink((topic, mapId, ids, line) -> {
            if (topic == QaLog.Topic.STAGING) {
                feed.add(line);
                feedChars.add(ids);
            }
        });
        handle.topics(EnumSet.of(QaLog.Topic.STAGING));
    }

    private Character gm(int mapId) {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(7, "Admin", mapId);
        world.online = gm;
        listen();
        return gm;
    }

    @Test
    void hc1StagesABishopFollowerOnAMapWithMobsAndNamesItForTheGm() throws IOException {
        Character gm = gm(100000000);
        suite.start(gm, "hc-1", new String[0]);

        assertEquals("warp 104040000", world.calls.get(0));
        int bot = 1_000_000_100;
        assertTrue(world.calls.contains("stage follower job 232 lv 120 " + bot), world.calls.toString());
        assertEquals(List.of("STAGE FOLLOWER cid=" + bot + " job=BISHOP lv=120 party=1 gm=Admin id=7 map=104040000"),
                feed);
        assertEquals(7, feedChars.get(0)[0], "the line must name the caller: the runner filters on its char id");
        assertTrue(suite.runOf(7).maps().contains(104040000));

        String log = Files.readString(suite.runOf(7).logPath());
        assertTrue(log.contains("staged STAGE FOLLOWER cid=" + bot), log);
        assertTrue(log.contains("is a level 120 Bishop, following you, in party 1"), log);
    }

    @Test
    void hc1TakesTheLevelFromItsArgument() {
        Character gm = gm(100000000);
        suite.start(gm, "hc-1", new String[]{"130"});
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("stage follower job 232 lv 130 ")),
                world.calls.toString());
        assertTrue(feed.get(0).contains(" job=BISHOP lv=130 "), feed.toString());
    }

    @Test
    void hc1RefusesAFullPartyBeforeWarping() {
        Character gm = QaTestSupport.gm(7, "Admin", 100000000);
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Party party = mock(Party.class);
        List<PartyCharacter> six = new ArrayList<>(Collections.nCopies(6, mock(PartyCharacter.class)));
        when(party.getMembers()).thenReturn(six);
        when(gm.getParty()).thenReturn(party);

        assertThrows(QaRefusal.class, () -> suite.start(gm, "hc-1", new String[0]));
        assertTrue(world.calls.isEmpty(), world.calls.toString());
    }

    @Test
    void hc1RefusesWhenNothingCouldBeStagedAndSaysNothingStaged() {
        world.partyFull = true; // the real world's party-slot check
        Character gm = gm(100000000);

        assertThrows(QaRefusal.class, () -> suite.start(gm, "hc-1", new String[0]));
        assertTrue(world.calls.contains("warp 104040000"));
        assertTrue(feed.isEmpty(), feed.toString());
        assertNull(suite.runningId(7));
    }

    @Test
    void hc1RefusesWithoutPartySupport() {
        System.clearProperty("slums.bots.partysupport");
        Character gm = gm(100000000);

        assertThrows(QaRefusal.class, () -> suite.start(gm, "hc-1", new String[0]));
        assertTrue(world.calls.isEmpty(), world.calls.toString());
    }

    @Test
    void stopTakesTheFollowerOutOfTheWorld() {
        Character gm = gm(100000000);
        suite.start(gm, "hc-1", new String[0]);

        suite.stop(7, "!qa stop");
        assertTrue(world.calls.contains("unstage follower 1000000100"), world.calls.toString());
        assertTrue(world.despawned.contains(1_000_000_100));
    }
}
