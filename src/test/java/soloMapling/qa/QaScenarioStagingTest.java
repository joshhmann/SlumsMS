package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        MoveTrace.reset();
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
    void id1DropsIdleBotsInTheDensestPackAndTalliesThem() throws Exception {
        world.idleSafety = false; // whatever it was before the run comes back at stop
        start("id-1", 104040000, "3");
        assertTrue(world.calls.contains("idle safety true"));
        assertEquals(3, world.idle.size());
        assertTrue(world.idle.values().stream().allMatch("qa"::equals));
        assertTrue(suite.runOf(1).maps().contains(104040000));
        scheduler.repeating.get(0).run();
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        assertFalse(world.idleSafety, "restored");
        assertEquals(3, world.despawned.size());
        String text = Files.readString(log);
        assertEquals(3, text.split("TALLY", -1).length - 1);
        assertEquals(3, text.split("FINAL", -1).length - 1, "the final tally runs before the bots leave");
    }

    @Test
    void id1OffComparesWithoutSafetyAndRefusesAMapWithoutMobs() {
        start("id-1", 104040000, "off");
        assertFalse(world.idleSafety);
        assertEquals(QaScenarios.Id1IdleSafety.DEFAULT_BOTS, world.idle.size());
        suite.stop(1, "!qa stop");
        assertTrue(world.idleSafety);

        world.mobSpot = null;
        assertThrows(QaRefusal.class, () -> start("id-1", 104040000));
        assertTrue(world.idleSafety, "a refused run leaves idle safety as it was");
    }

    @Test
    void id1WarpsToTheHuntingGroundFromAMapWithoutMobs() {
        world.mobs = 0;
        start("id-1", 100000000);
        assertTrue(world.calls.contains("warp 104040000"));
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
    void sc2StagesAScrollingBotThatRollsEveryTickUntilItIsDone() throws Exception {
        start("sc-2", 100000000, "gambler");
        int bot = world.nextBot - 1;
        assertTrue(world.calls.contains("bot " + bot + " give 1302000 x1"), world.calls.toString());
        assertTrue(world.calls.contains("bot " + bot + " give 2043001 x4"));
        assertTrue(world.calls.contains("bot " + bot + " give 2043004 x1"));
        assertTrue(world.calls.contains("bot " + bot + " give 2340000 x1"));
        assertEquals(1, scheduler.repeating.size());
        world.scrollSteps.add("bot used 2043001 (60%) on 1302000: SUCCESS | before 0 passed | after 1 passed");
        scheduler.repeating.get(0).run();
        scheduler.repeating.get(0).run();   // nothing left: done
        scheduler.repeating.get(0).run();   // and no more rolls asked for
        assertEquals(2, world.calls.stream().filter(c -> c.equals("bot " + bot + " scroll gambler")).count());
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        String text = Files.readString(log);
        assertTrue(text.contains("SUCCESS") && text.contains("DONE"), text);
        assertEquals(1, world.despawned.size());
    }

    @Test
    void ga1GivesTicketsAtTheMachineAndRollsOnRequest() throws Exception {
        start("ga-1", 100000000);
        assertTrue(world.calls.contains("odds henesys 10000"), world.calls.toString());
        assertTrue(world.calls.contains("warp 100000100"));
        assertTrue(world.calls.contains("give 5220000 x3"));
        List<String> out = suite.action(world.online, "roll", new String[]{"rare"});
        assertEquals(List.of("rolled at henesys rare"), out);
        suite.action(world.online, "sim", new String[]{"500"});
        assertTrue(world.calls.contains("odds henesys 500"));
        assertThrows(QaRefusal.class, () -> suite.action(world.online, "spin", new String[0]));
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("take 5220000")), world.calls.toString());
        String text = Files.readString(log);
        assertTrue(text.contains("ROLL") && text.contains("rare: 5% per ticket"), text);
    }

    @Test
    void ga1RefusesAnUnknownTown() {
        suite = QaTestSupport.suite(QaScenarios.all(), new FakeQaWorld() {
            @Override public List<String> gachaOdds(String town, int n) { return List.of("no gachapon town '" + town + "'"); }
        }, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaRefusal r = assertThrows(QaRefusal.class, () -> suite.start(gm, "ga-1", new String[]{"leafre"}));
        assertTrue(r.getMessage().contains("no gachapon town 'leafre'"), r.getMessage());
    }

    @Test
    void ga2GivesABotMesosOverItsCeilingAndStepsUntilItIsUnder() throws Exception {
        start("ga-2", 100000000, "henesys", "3");
        int bot = world.nextBot - 1;
        assertTrue(world.calls.contains("warp 100000100"));
        assertTrue(world.calls.contains("bot " + bot + " meso 15000000"), world.calls.toString());
        assertEquals(1, scheduler.repeating.size());
        world.wealthSteps.add("bot bought a 1,000,000 ticket at Henesys: common Elixir x20");
        scheduler.repeating.get(0).run();
        scheduler.repeating.get(0).run();   // the fake line has no "over by": done
        assertEquals(1, world.calls.stream().filter(c -> c.equals("bot " + bot + " wealth step")).count());
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        String text = Files.readString(log);
        assertTrue(text.contains("bought a 1,000,000 ticket") && text.contains("DONE"), text);
        assertEquals(1, world.despawned.size());
    }

    @Test
    void ga2StagesARichLevel100BotByNameAtThreeTimesItsCeiling() throws Exception {
        // rc6 (QA t-wc-1): a lv 13 bot at 2.5x was done in 17 s; the line named it by id only
        start("ga-2", 100000000);
        int bot = world.nextBot - 1;
        assertTrue(world.calls.contains("idle bots lv 100"), world.calls.toString());
        assertTrue(world.calls.contains("bot " + bot + " meso 15000000"), "3x a 5,000,000 ceiling");
        String text = Files.readString(suite.runOf(1).logPath());
        assertTrue(text.contains("told GM: Botty" + bot + " (lv 30, 90 px to your right) holds 3.0x"), text);
        assertEquals(1, scheduler.repeating.size());
        suite.stop(1, "!qa stop");
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
    void lk1LogsCampersTheWatchGateAndMessySamples() throws Exception {
        start("lk-1", 100000000);
        assertEquals(1, scheduler.repeating.size());
        List<String> out = suite.action(world.online, "sample", new String[0]);
        assertTrue(out.stream().anyMatch(l -> l.contains("Brown Work Gloves for 50k")), String.join("\n", out));
        suite.action(world.online, "camp", new String[0]);
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        String text = Files.readString(log);
        assertTrue(text.contains("LIKENESS afkCampers="), text);
        assertTrue(text.contains("LIKENESS walkWhenWatched="), text);
        assertTrue(text.contains("LIKENESS MESSY sample slot"), text);
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
    void mv1ArmsTheGmsMeterReportsEachAttackAndDisarmsAtStop() throws Exception {
        Character gm = start("mv-1", 100000000, "5", "400");
        assertTrue(MoveTrace.armed(1));
        assertFalse(MoveTrace.armed(2));
        assertEquals(1, scheduler.repeating.size());
        server.movement.AbsoluteLifeMovement before = new server.movement.AbsoluteLifeMovement(0,
                new java.awt.Point(10, 20), 100, 4);
        before.setFh(7);
        before.setPixelsPerSecond(new java.awt.Point(0, 0));
        MoveTrace.move(gm, List.of(before));
        MoveTrace.attack(gm, "close", 1001004);
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        assertFalse(MoveTrace.armed(1));
        assertTrue(world.messages.stream().anyMatch(m -> m.startsWith("#1 skill 1001004 close: slide 0px")
                && m.contains("fh 7")), world.messages.toString());
        assertTrue(world.messages.stream().anyMatch(m -> m.startsWith("mv-1 summary: 1 attacks (1 grounded)")),
                world.messages.toString());
        String text = Files.readString(log);
        assertTrue(text.contains("armed: 5 attacks, window 400 ms"), text);
        assertTrue(text.contains("SLIDE #1 skill 1001004"), text);
        assertEquals(5, QaScenarios.Mv1AttackSlide.attacks(new String[]{"5"}));
        assertEquals(200, QaScenarios.Mv1AttackSlide.attacks(new String[]{"999"}));
        assertEquals(600, QaScenarios.Mv1AttackSlide.windowMs(new String[]{"5", "x"}));
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

    // ---- 1.5.2: PQ bots ----

    @Test
    void pq2PutsTheGmAndLiveOpqBotsInTheLobbyAndTakesTheBotsAway() throws Exception {
        Character gm = start("pq-2", 100000000);
        assertTrue(world.calls.contains("warp " + soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants.OPQ_LOBBY));
        assertTrue(world.calls.contains("opq lobby bots 6"), world.calls.toString());
        Path log = suite.runOf(1).logPath();
        assertTrue(suite.runOf(1).maps().contains(soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants.OPQ_STAGE_2),
                "the run watches the stages, not only the lobby");
        suite.stop(1, "!qa stop");
        assertEquals(6, world.despawned.size());
        assertTrue(gm != null);
        assertTrue(Files.readString(log).contains("recruit lines a minute"));
    }

    // ---- ticket 58 ----

    @Test
    void af1StagesASocialFriendAndUnstagesItAtStop() throws Exception {
        start("af-1", 100000000);
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("stage friend social")));
        scheduler.repeating.get(0).run();
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        assertEquals(1, world.unstaged.size());
        String text = Files.readString(log);
        assertTrue(text.contains("START") && text.contains("STATE") && text.contains("FINAL"));
    }

    @Test
    void af2StagesATrainingRivalOnAMobMapAndForcesAGrudge() {
        world.mobs = 0;
        start("af-2", 100000000, "nemesis");
        assertTrue(world.calls.contains("warp 104040000"), "hostile bots need mobs");
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("stage nemesis training")));
        List<String> out = suite.action(world.online, "grudge", new String[0]);
        assertEquals(List.of("grudge + KS spree"), out);
        assertThrows(QaRefusal.class, () -> suite.action(world.online, "hug", new String[0]));
        suite.stop(1, "!qa stop");
        assertEquals(1, world.unstaged.size());
    }

    @Test
    void af2FightsTheMobsAroundTheGmUntilTheRunStops() {
        // rc6 triage (t-af-2: no KS line): a rival's KS spree takes only mobs the player has hit
        start("af-2", 104040000);
        int before = world.mobsHit;
        suite.action(world.online, "fight", new String[0]);
        assertEquals(before + 1, world.mobsHit);
        scheduler.repeating.forEach(Runnable::run);
        assertEquals(before + 2, world.mobsHit, "and again on the tick");
        suite.stop(1, "!qa stop");
        scheduler.repeating.forEach(Runnable::run);
        assertEquals(before + 2, world.mobsHit, "not after the stop");
    }

    @Test
    void af2AndAf3HoldSpontaneousKsWarsForTheRun() {
        // rc6 (QA t-af-2): a KS war rolled on sight brought "two random bots" in during the af-2 run
        start("af-2", 104040000);
        assertTrue(world.calls.contains("hold ks wars true"));
        suite.stop(1, "!qa stop");
        assertTrue(world.calls.contains("hold ks wars false"), "allowed again at stop");
        world.calls.clear();
        start("af-3", 104040000);
        assertTrue(world.calls.contains("hold ks wars true"));
        suite.stop(1, "!qa stop");
        assertTrue(world.calls.contains("hold ks wars false"));
    }

    @Test
    void af3TurnsDramaOffAndPutsItBackAtStop() {
        world.dramaOff = false;
        start("af-3", 104040000);
        assertTrue(world.dramaOff);
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("stage nemesis training")));
        suite.action(world.online, "on", new String[0]);
        assertFalse(world.dramaOff);
        suite.action(world.online, "off", new String[0]);
        assertTrue(world.dramaOff);
        suite.stop(1, "!qa stop");
        assertFalse(world.dramaOff, "restored to what it was");
    }

    @Test
    void sg1TurnsQuietBotsOnStagesAFriendAndPutsQuietBackAtStop() {
        Character gm = start("sg-1", 100000000);
        assertTrue(soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter.get(gm.getId()).bots());
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("stage friend social")), world.calls.toString());
        suite.action(world.online, "loud", new String[0]);
        assertFalse(soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter.get(gm.getId()).bots());
        suite.action(world.online, "quiet", new String[0]);
        assertTrue(soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter.get(gm.getId()).bots());
        suite.stop(1, "!qa stop");
        assertFalse(soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter.get(gm.getId()).bots(),
                "restored to what it was");
        assertEquals(1, world.unstaged.size());
    }

    @Test
    void sg2StagesARivalTheGmNeverProvokedAndKsStartsIt() {
        Character gm = start("sg-2", 104040000);
        assertTrue(world.calls.contains("hold ks wars true"));
        int bot = world.calls.stream().filter(c -> c.startsWith("stage rival training")).findFirst()
                .map(c -> 1).orElse(0);
        assertEquals(1, bot, world.calls.toString());
        List<String> out = suite.action(world.online, "check", new String[0]);
        assertTrue(out.get(0).contains("spice no"), out.toString());
        suite.action(world.online, "ks", new String[0]);
        out = suite.action(world.online, "check", new String[0]);
        assertTrue(out.get(0).contains("spice yes"), out.toString());
        suite.action(world.online, "fresh", new String[0]);
        out = suite.action(world.online, "check", new String[0]);
        assertTrue(out.get(0).contains("spice no"), out.toString());
        suite.stop(1, "!qa stop");
        assertTrue(world.calls.contains("hold ks wars false"));
        soloMapling.ArtificialPlayer.SocialGate.resetForTest();
        assertTrue(gm.getId() > 0);
    }

    @Test
    void me1StagesAFriendAndAKpqClearAndPrintsTheCard() {
        start("me-1", 100000000);
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("stage friend social")), world.calls.toString());
        List<String> out = suite.action(world.online, "card", new String[0]);
        assertTrue(out.get(0).startsWith("ME1 CARD bot ") && out.get(0).contains("About Admin: "), out.toString());
        out = suite.action(world.online, "clear", new String[0]);
        assertTrue(out.get(0).contains("ME1 staged clear: KPQ"), out.toString());
        suite.stop(1, "!qa stop");
        assertEquals(1, world.unstaged.size());
    }

    @Test
    void me2StagesAScamTradeThatStartsIt() {
        Character gm = start("me-2", 100000000);
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("stage neutral social")), world.calls.toString());
        List<String> out = suite.action(world.online, "check", new String[0]);
        assertTrue(out.get(0).contains("started it yes"), out.toString());
        out = suite.action(world.online, "fresh", new String[0]);
        assertTrue(out.get(0).contains("started it no"), out.toString());
        out = suite.action(world.online, "scam", new String[0]);
        assertTrue(out.get(0).startsWith("ME2 staged trade: scam"), out.toString());
        assertTrue(out.get(1).contains("started it yes"), out.toString());
        suite.stop(1, "!qa stop");
        soloMapling.ArtificialPlayer.SocialGate.resetForTest();
        assertTrue(gm.getId() > 0);
    }

    @Test
    void cp1StagesACoupleStepsItAndUnstagesIt() {
        start("cp-1", 100000000, "married", "same");
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("couple ") && c.endsWith("married same")));
        int before = world.coupleSteps;
        scheduler.repeating.get(0).run();
        suite.action(world.online, "step", new String[0]);
        assertEquals(before + 2, world.coupleSteps);
        suite.stop(1, "!qa stop");
        assertEquals(1, world.couplesUnstaged.size());
    }

    @Test
    void we1NeedsChannelOneWarpsToTheChapelAndStartsTheWeddingThere() {
        world.eventChannel = false;
        assertThrows(QaRefusal.class, () -> start("we-1", 100000000));
        world.eventChannel = true;
        start("we-1", 100000000, "mixed");
        // rc6 (QA t-we-1): "we-1 mixed sent me just to amoria not the chapel"
        assertTrue(world.calls.contains("warp 680000110"), world.calls.toString());
        assertFalse(world.calls.contains("warp 680000000"));
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("couple ") && c.endsWith("dating mixed")));
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("wedding ") && c.endsWith(" on 680000110")),
                world.calls.toString());
        suite.stop(1, "!qa stop");
        assertTrue(world.calls.contains("stop weddings"));
        assertEquals(1, world.couplesUnstaged.size());
    }

    @Test
    void cp1StepsEveryTwentySeconds() {
        // rc6 (QA t-cu-1): a step a minute showed nothing within the first minute
        assertEquals(20, QaScenarios.all().stream().filter(s -> s.id().equals("cp-1")).findFirst().orElseThrow()
                .tickSeconds());
    }

    @Test
    void cp1StagesBestFriends() {
        start("cp-1", 100000000, "friends");
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("couple ") && c.endsWith("friends")));
        suite.stop(1, "!qa stop");
        assertEquals(1, world.couplesUnstaged.size());
    }

    /**
     * Ticket 69: the vb-* scenarios reach the real paths through {@code QaWorld.v83Step}, so the feature id and the
     * step name are what tells the world which feature to stage. A wrong id or a step the scenario renames would make
     * a nightly run assert nothing.
     */
    @Test
    void everyVbScenarioStagesItsOwnFeatureId() {
        List<String> asked = new ArrayList<>();
        FakeQaWorld v83 = new FakeQaWorld() {
            @Override
            public List<String> v83Step(Character gm, String feature, String step) {
                asked.add(feature + " " + step);
                return List.of("VB " + feature + " " + step + " ok");
            }
        };
        v83.online = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite s = QaTestSupport.suite(QaScenarios.all(), v83, dir, new QaTestSupport.ManualScheduler());
        for (String id : List.of("vb-1", "vb-2", "vb-3", "vb-4", "vb-5", "vb-6", "vb-7")) {
            asked.clear();
            s.start(v83.online, id, new String[0]);
            assertEquals(List.of(id + " stage"), asked, id + " stages the wrong feature");
            assertEquals(id, s.runOf(1).scenarioId());
            s.stop(1, "!qa stop");
        }
        asked.clear();
        s.start(v83.online, "vb-4", new String[0]);
        s.action(v83.online, "bottle", new String[0]);
        assertEquals(List.of("vb-4 stage", "vb-4 bottle"), asked);
        s.action(v83.online, "hog", new String[]{"1"});
        assertEquals(List.of("vb-4 stage", "vb-4 bottle", "vb-4 hog 1"), asked);
        assertTrue(s.stop(1, "!qa stop").get(0).startsWith("QA vb-4 stopped"), "the vb scenarios undo nothing, so they stop cleanly");
    }

    @Test
    void vbScenariosRefuseNonSyntheticPlayer() {
        Character realGm = org.mockito.Mockito.mock(Character.class);
        client.Client realClient = org.mockito.Mockito.mock(client.Client.class);
        org.mockito.Mockito.when(realGm.getClient()).thenReturn(realClient);
        org.mockito.Mockito.when(realGm.getId()).thenReturn(99);
        org.mockito.Mockito.when(realGm.getName()).thenReturn("RealGm");
        org.mockito.Mockito.when(realGm.getMapId()).thenReturn(100000000);
        org.mockito.Mockito.when(realGm.getPosition()).thenReturn(new java.awt.Point(0, 0));
        org.mockito.Mockito.when(realGm.gmLevel()).thenReturn(6);
        QaSuite s = QaTestSupport.suite(QaScenarios.all(), world, dir, new QaTestSupport.ManualScheduler());
        for (String id : List.of("vb-1", "vb-2", "vb-3", "vb-4", "vb-5", "vb-6", "vb-7")) {
            QaRefusal r = assertThrows(QaRefusal.class, () -> s.start(realGm, id, new String[0]));
            assertTrue(r.getMessage().contains("only runs on synthetic pretend players"), r.getMessage());
        }
    }

    @Test
    void apq1StagesTheRecruitMapChecksStageOneAndTakesTheTicketBack() throws Exception {
        world.mobs = 12;
        start("pq-1", 100000000);
        assertTrue(world.calls.contains("warp 670010100"), world.calls.toString());
        assertTrue(world.calls.contains("give 4031592 x1"));
        assertTrue(world.calls.contains("warp 670010200"), "stage 1 is checked during staging");
        for (String portal : new String[]{"male00", "female00", "go00", "go01", "go02"}) {
            assertTrue(world.calls.contains("warp 670010200 " + portal), world.calls.toString());
        }
        assertTrue(suite.runOf(1).maps().containsAll(List.of(670010100, 670010200)));
        String text = Files.readString(suite.runOf(1).logPath());
        assertTrue(text.contains("stage 1 has 12 live mobs"), text);

        List<String> out = suite.action(world.online, "where", new String[0]);
        assertEquals(1, out.size());
        assertThrows(QaRefusal.class, () -> suite.action(world.online, "start", new String[0]));

        suite.stop(1, "!qa stop");
        assertTrue(world.calls.contains("take 4031592 x1"), "the ticket goes back");
        assertEquals(0, world.inventory.getOrDefault(4031592, 0));
    }

    @Test
    void apq1RefusesWhenTheHostNpcIsNotOnTheRecruitMap() {
        world.npcPos = null;
        assertThrows(QaRefusal.class, () -> start("pq-1", 100000000));
    }
}
