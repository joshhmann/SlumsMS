package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** The 1.5.0 bot drama scenarios against a recording world. */
class DramaScenariosTest {
    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();
    QaSuite suite;

    @AfterEach
    void reset() {
        QaLog.reset();
        MoveTrace.reset();
    }

    private Character gm(int level, int mapId) {
        Character gm = QaTestSupport.gm(1, "Admin", mapId);
        when(gm.getLevel()).thenReturn(level);
        return gm;
    }

    private Character start(String id, int level, String... args) {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = gm(level, 100000000);
        world.online = gm;
        suite.start(gm, id, args);
        return gm;
    }

    @Test
    void everyDramaScenarioIsRegistered() {
        List<String> ids = QaScenarios.all().stream().map(QaScenario::id).toList();
        for (String id : List.of("rm-1", "rm-2", "rm-3", "rm-4", "rv-1", "rv-2", "rv-3", "rv-4")) {
            assertTrue(ids.contains(id), id);
        }
    }

    @Test
    void rm1TurnsRomanceOnStagesACrushAndPutsItAllBack() {
        start("rm-1", 10);
        assertTrue(world.drama.contains("romance on"));
        assertTrue(world.drama.contains("romance stage crush"));
        suite.action(world.online, "ask", new String[0]);
        assertTrue(world.drama.contains("romance ask"));
        suite.action(world.online, "date", new String[0]);
        assertTrue(world.drama.contains("romance stage dating"));
        assertThrows(QaRefusal.class, () -> suite.action(world.online, "wedding", new String[0]));
        suite.stop(1, "!qa stop");
        assertEquals(1, world.dramaUnstaged);
        assertTrue(world.drama.contains("romance off"), "the GM had romance off: it goes back off");
    }

    @Test
    void rm2NeedsLevel50AndGivesARingBoxTakenBackAtStop() {
        assertThrows(QaRefusal.class, () -> start("rm-2", 30));
        start("rm-2", 60);
        assertTrue(world.drama.contains("romance stage dating 60"));
        assertEquals(1, world.inventory.get(2240000));
        suite.action(world.online, "propose", new String[0]);
        suite.action(world.online, "wedding", new String[0]);
        assertTrue(world.calls.contains("warp " + DramaScenarios.AMORIA));
        assertTrue(world.drama.contains("romance wedding"));
        suite.stop(1, "!qa stop");
        assertEquals(0, world.inventory.get(2240000));
        assertEquals(1, world.dramaUnstaged);
    }

    @Test
    void rm3StepsRunTheBreakupDrama() {
        start("rm-3", 10);
        for (String step : List.of("neglect", "breakup", "second", "confront", "flame", "gossip")) {
            suite.action(world.online, step, new String[0]);
        }
        assertTrue(world.drama.contains("romance neglect"));
        assertTrue(world.drama.contains("romance breakup"));
        assertTrue(world.drama.contains("romance stage second"));
        assertTrue(world.drama.contains("romance confront"));
        assertTrue(world.drama.contains("romance flame"));
    }

    @Test
    void rm4StagesAnExAndAPartnerAndForcesTheObjection() {
        start("rm-4", 70);
        int ex = world.drama.indexOf("romance stage ex");
        int partner = world.drama.indexOf("romance stage dating 60");
        assertTrue(ex >= 0 && partner > ex, "the ex first, the partner last (the steps act on the latest)");
        assertTrue(world.drama.contains("romance object"));
        assertTrue(world.drama.contains("romance crash"));
        suite.action(world.online, "runaway", new String[0]);
        assertTrue(world.drama.contains("romance runaway"));
        assertFalse(world.drama.contains("romance wedding"));
    }

    @Test
    void rivalScenariosNeedMobsAndStageTheRightRival() {
        world.mobs = 0;
        start("rv-1", 10);
        assertTrue(world.calls.contains("warp " + QaScenarios.HENESYS_HUNTING_GROUND), "warped to mobs");
        assertTrue(world.drama.contains("rival stage rival"));
        assertTrue(world.drama.contains("rival roast"));
        suite.action(world.online, "die", new String[0]);
        assertTrue(world.drama.contains("rival die"));
        suite.stop(1, "!qa stop");
        assertEquals(1, world.dramaUnstaged);
    }

    @Test
    void rv2KsWarEndsAtStopAndRv4ArmsTheFollow() {
        start("rv-2", 10);
        assertTrue(world.drama.contains("rival stage nemesis"));
        suite.action(world.online, "kswar", new String[]{"4"});
        assertTrue(world.drama.contains("rival kswar 4"));
        suite.stop(1, "!qa stop");
        assertTrue(world.drama.contains("rival endwar"));
        start("rv-4", 10);
        assertTrue(world.drama.contains("rival revenge"));
        start("rv-3", 10);
    }
}
