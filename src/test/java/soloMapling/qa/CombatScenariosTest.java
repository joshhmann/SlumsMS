package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc3: the combat and spread scenarios (cb-1 .. cb-4, sp-1) against a recording world. */
class CombatScenariosTest {
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

    private Character start(String id, String... args) {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", 104040000);
        world.online = gm;
        world.mobs = 12;
        suite.start(gm, id, args);
        return gm;
    }

    private boolean called(String prefix) {
        return world.calls.stream().anyMatch(c -> c.startsWith(prefix));
    }

    @Test
    void everyCombatScenarioIsRegistered() {
        List<String> ids = QaScenarios.all().stream().map(QaScenario::id).toList();
        for (String id : List.of("cb-1", "cb-2", "cb-3", "cb-4", "sp-1")) {
            assertTrue(ids.contains(id), id);
        }
    }

    @Test
    void cb1StagesOneHeldGrinderPerClassAndTakesThemAwayAtStop() {
        start("cb-1");
        assertTrue(called("grinders CRUSADER,FP_MAGE,RANGER,HERMIT x4 offset 0 held at-pack"));
        suite.stop(1, "!qa stop");
        assertEquals(4, world.grindersUnstaged.size());
    }

    @Test
    void cb1TakesTheJobsGiven() {
        start("cb-1", "hero", "bishop");
        assertTrue(called("grinders hero,bishop x2"));
    }

    @Test
    void cb3StagesBotsUnderTheMapAndRunsItsSteps() {
        Character gm = start("cb-3", "3");
        assertTrue(called("grinders random x3 offset -5 free"));
        assertTrue(called("report habits"));
        suite.action(gm, "die", new String[0]);
        assertTrue(called("act die"));
    }

    @Test
    void cb4StartsNeutralBotsOnTheGmsPlatform() {
        start("cb-4");
        assertTrue(called("grinders random x3 offset 0 free at-gm"));
    }

    @Test
    void sp1StartsAClumpAndTalliesTheSpread() {
        start("sp-1", "10");
        assertTrue(called("grinders random x10"));
        assertTrue(called("spread"));
    }

    @Test
    void aMapWithoutMobsWarpsToTheHuntingGround() {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        world.online = gm;
        world.mobs = 0;
        suite.start(gm, "cb-2", new String[0]);
        assertTrue(world.calls.contains("warp " + QaScenarios.HENESYS_HUNTING_GROUND));
    }
}
