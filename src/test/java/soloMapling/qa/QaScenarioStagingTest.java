package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The staging of the scenarios against a recording world. */
class QaScenarioStagingTest {
    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();
    QaSuite suite;

    @AfterEach
    void reset() {
        QaLog.reset();
    }

    private Character start(String id, int mapId, String... args) {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", mapId);
        world.online = gm;
        suite.start(gm, id, args);
        return gm;
    }

    @Test
    void fm1StocksAShopPastTheCapWithOneOverpricedItemAndCleansUp() throws Exception {
        for (int id : QaScenarios.FM_STOCK) {
            world.values.put(id, 1_500_000);
        }
        start("fm-2", 910000001, "3");
        assertTrue(world.calls.contains("warp 910000003"));
        assertTrue(world.calls.contains("give 5140000 x1"));
        assertTrue(world.shopOpen);
        // 4 items at 1.5m pass 5.5m, plus one at twice its value
        assertEquals(5, world.shopStock.size());
        assertEquals(3_000_000, world.shopStock.get(4)[1]);
        long normal = world.shopStock.subList(0, 4).stream().mapToLong(s -> s[1]).sum();
        assertTrue(normal > QaScenarios.BUY_CAP);
        assertEquals(4, world.calls.stream().filter(c -> c.startsWith("spawn fm")).findFirst()
                .map(c -> c.split(",").length).orElse(0));
        Path log = suite.runOf(1).logPath();
        assertTrue(log.getFileName().toString().startsWith("fm-1-"));
        assertTrue(suite.runOf(1).maps().contains(910000003));

        suite.stop(1, "!qa stop");
        // the shoppers leave first, then the shop closes (items come back), then the items are taken back
        int close = world.calls.indexOf("close shop");
        int despawn = world.calls.indexOf("despawn " + 1_000_000_100);
        int takeFirst = world.calls.indexOf("take " + QaScenarios.FM_STOCK[0] + " x1");
        assertTrue(despawn >= 0 && despawn < close && close < takeFirst, world.calls.toString());
        assertEquals(4, world.despawned.size());
        assertTrue(world.inventory.values().stream().allMatch(q -> q == 0), world.inventory.toString());
        String text = Files.readString(log);
        assertTrue(text.contains("overpriced (fm-2)"));
        assertTrue(text.contains("cap at stop:"));
    }

    @Test
    void fm1RefusesWithoutPricedStockAndUndoesThePermit() {
        assertThrows(QaRefusal.class, () -> start("fm-1", 910000000));
        assertTrue(world.calls.contains("take 5140000 x1"));
        assertFalse(world.shopOpen);
    }

    @Test
    void gd1TopsUpTheFeeSpawnsBotsAndTakesBackOnlyWhatIsLeft() {
        world.meso = 500_000;
        start("gd-1", 100000000);
        assertTrue(world.calls.contains("warp 200000301"));
        assertEquals(1_500_000, world.meso);
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("spawn idle")));
        world.meso = 200_000; // the guild was created: the fee was spent
        suite.stop(1, "!qa stop");
        assertEquals(0, world.meso);
        assertEquals(3, world.despawned.size());
    }

    @Test
    void np3ClosesTheMerchantOnRequest() {
        world.merchantOpen = true;
        start("np-3", 910000000);
        assertTrue(world.calls.contains("give 5030000 x1"));
        List<String> out = suite.action(world.online, "close", new String[0]);
        assertEquals("Closed your hired merchant", out.get(0));
        assertThrows(QaRefusal.class, () -> suite.action(world.online, "reopen", new String[0]));
    }

    @Test
    void gn2GivesASwordAndScrollsAndSc1IsTheSameTest() {
        start("sc-1", 100000000, "5");
        assertTrue(world.calls.contains("give 1302000 x1"));
        assertTrue(world.calls.contains("give 2043000 x5"));
        assertEquals("gn-2", suite.runOf(1).scenarioId());
        world.inventory.put(2043000, 2); // three used
        suite.stop(1, "!qa stop");
        assertTrue(world.calls.contains("take 2043000 x2"));
    }

    @Test
    void bk1SpawnsTheShowcaseOnTheGmsMapAndDespawnsIt() {
        start("bk-1", 104040000);
        assertTrue(suite.runOf(1).maps().contains(104040000));
        assertEquals(List.of((long) 11 * 60_000), scheduler.delays); // 10 min showcase + 1
        suite.stop(1, "!qa stop");
        assertEquals(2, world.despawned.size());
    }

    @Test
    void wd1AndWd2Sample() throws Exception {
        start("wd-1", 100000000);
        assertEquals(1, scheduler.repeating.size());
        scheduler.repeating.get(0).run();
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        assertEquals(4, Files.readString(log).split("SAMPLE", -1).length - 1);

        start("wd-2", 100000000);
        assertTrue(world.calls.contains("warp 271010400 north00"));
    }

    @Test
    void cs2LeavesASummonedPet() {
        start("cs-2", 100000000);
        world.petOut = true;
        suite.stop(1, "!qa stop");
        assertFalse(world.calls.contains("take 5000000 x1"));
        assertTrue(world.calls.contains("take 1802085 x1"));
    }

    @Test
    void jq1WarpsIntoABlockedMapAndRefusesAnUnblockedOne() throws Exception {
        server.bounce.BounceZones.useForTest(null);
        try {
            start("jq-1", 100000000);
            assertTrue(world.calls.contains("warp 101000100"));
            assertTrue(suite.runOf(1).topics().contains(QaLog.Topic.BOUNCE));
            suite.stop(1, "!qa stop");
            Character gm = QaTestSupport.gm(2, "Other", 100000000);
            world.online = gm;
            assertThrows(QaRefusal.class, () -> suite.start(gm, "jq-1", new String[]{"100000000"}));
            suite.start(gm, "jq-1", new String[]{"280020001"});
            assertTrue(world.calls.contains("warp 280020001"));
        } finally {
            server.bounce.BounceZones.useForTest(null);
        }
    }
}
