package soloMapling.autoqa;

import net.server.handlers.login.ClientVersionGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0: the checks added for features that shipped without one; their pure verdicts and their wiring. */
class Probes160Test {

    static final List<String> NEW = List.of("t-oa-1", "t-tw-1", "t-hp-1", "t-au-1", "t-ds-1", "t-np-4", "t-ck-1", "t-sc-3", "t-ss-1", "t-xc-1", "t-fy-1", "t-ti-1", "t-rb-1", "t-ac-1", "t-nc-1");

    private static Probes160.TownSample sample(long at, int target, int filled, int[] per, List<String> over) {
        return new Probes160.TownSample(at, target, filled, per, over, 200);
    }

    @Test
    void theNewChecksAreAutomatedNightlyAndUseOnlyKnownProbes() {
        Pattern probe = Pattern.compile("^(?:probe|fixture) (\\w+)");
        for (String id : NEW) {
            Check c = AutoQaCatalog.find(id);
            assertNotNull(c, id);
            assertEquals(Check.Kind.AUTO, c.kind(), id);
            assertTrue(c.nightly(), id + " stages nothing lasting");
            int probes = 0;
            for (Check.Expect e : c.expects()) {
                Matcher m = probe.matcher(e.describe());
                if (m.find()) {
                    assertTrue(Probes160.NAMES.contains(m.group(1)), id + " probes unknown " + m.group(1));
                    probes++;
                }
            }
            for (Check.Step s : c.steps()) {
                Matcher m = probe.matcher(s.describe());
                if (m.find()) {
                    assertTrue(Probes160.NAMES.contains(m.group(1)), id + " stages with unknown " + m.group(1));
                }
            }
            assertTrue(probes > 0, id);
        }
        assertNull(Probes160.probe("noSuchProbe", null), "an unknown name falls through to GameAutoQaWorld's FAIL");
    }

    @Test
    void theFishingCheckNowWantsTheChairOnAFishingMapAndBots() {
        List<String> wants = AutoQaCatalog.find("t-fh-2").expects().stream().map(Check.Expect::describe).toList();
        String started = "FISHING REAL STARTED map=120010000 area=true chair=3011000; first catch waits 90 seconds";
        String bots = "FISHING BOTS active=0 started=4 caught=2 playerCaught=1";
        String none = "FISHING BOTS active=0 started=0 caught=0 playerCaught=3";
        assertTrue(matches(wants, started), wants.toString());
        assertTrue(matches(wants, bots));
        assertTrue(matches(wants, "FISHING BOTS active=2 started=2 caught=0 playerCaught=0"));
        assertFalse(matchesBotsOnly(wants, none), "a player's catches are not bots fishing");
    }

    private static boolean matches(List<String> wants, String line) {
        for (String w : wants) {
            Matcher m = Pattern.compile("/(.*)/").matcher(w);
            if (m.find() && Pattern.compile(m.group(1)).matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesBotsOnly(List<String> wants, String line) {
        return wants.stream().filter(w -> w.contains("FISHING BOTS")).anyMatch(w -> matches(List.of(w), line));
    }

    @Test
    void theClientGateUsesTheConfiguredMinimum() {
        List<String> at10600 = GameAutoQaWorld.clientGateLines(10600, ClientVersionGate::verdictFor);
        assertTrue(at10600.get(0).startsWith("PASS MIN_CLIENT_BUILD 10600"), at10600.toString());
        assertTrue(at10600.get(0).contains("build 10599 REFUSED"), at10600.toString());
        assertTrue(GameAutoQaWorld.clientGateLines(0, ClientVersionGate::verdictFor).get(0).startsWith("FAIL the client gate is off"));
        assertTrue(GameAutoQaWorld.clientGateLines(10600, (b, m) -> "ALLOWED").get(0).startsWith("FAIL"),
                "a gate that lets an old build in fails");
        assertTrue(GameAutoQaWorld.clientGateLines(10600, (b, m) -> b >= m + 1 ? "ALLOWED" : "REFUSED: update")
                .get(0).startsWith("FAIL"), "a gate that refuses the minimum itself fails");
    }

    @Test
    void townsMovingTowardTheirTargetsPass() {
        int[] per = {80, 40, 30, 20, 10};
        assertNull(Probes160.townVerdict(sample(0, 200, 60, per, List.of()), sample(300_000, 200, 90, per, List.of())));
        assertNull(Probes160.townVerdict(sample(0, 200, 190, per, List.of()), sample(300_000, 200, 185, per, List.of())),
                "at the target");
        assertNull(Probes160.townVerdict(sample(0, 200, 150, per, List.of()), sample(300_000, 200, 145, per, List.of())),
                "holding near it");
    }

    @Test
    void townsFallingAwayOverTheMaxOrChannelOneQuietFail() {
        int[] per = {80, 40, 30, 20, 10};
        String away = Probes160.townVerdict(sample(0, 200, 100, per, List.of()), sample(300_000, 200, 60, per, List.of()));
        assertNotNull(away);
        assertTrue(away.contains("50% -> 30%"), away);
        assertTrue(Probes160.townVerdict(sample(0, 200, 30, per, List.of()), sample(300_000, 200, 30, per, List.of()))
                .contains("not moving"), "stuck low");
        String over = Probes160.townVerdict(sample(0, 200, 190, per, List.of()),
                sample(1, 200, 190, per, List.of("ch1 100000000 (Henesys) 230")));
        assertTrue(over.contains("over BOT_TOWN_MAP_MAX 200"), over);
        String quiet = Probes160.townVerdict(sample(0, 200, 190, per, List.of()),
                sample(1, 200, 190, new int[]{30, 40, 10, 5, 5}, List.of()));
        assertTrue(quiet.contains("ch2 (40 town bots) is busier than ch1 (30)"), quiet);
        assertTrue(Probes160.townVerdict(sample(0, 0, 0, per, List.of()), sample(1, 0, 0, per, List.of()))
                .contains("no targets"));
    }

    @Test
    void theHenesysDressingFollowsItsScale() {
        assertNull(Probes160.dressingVerdict(120, 30, 6, 0.25));
        assertNull(Probes160.dressingVerdict(120, 33, 6, 0.25), "each cohort rounds by under one bot");
        assertNotNull(Probes160.dressingVerdict(120, 60, 6, 0.25), "half spawned at a quarter scale");
        assertTrue(Probes160.dressingVerdict(0, 0, 0, 0.25).startsWith("SKIP"));
        assertNull(Probes160.dressingVerdict(120, 120, 6, 1.0));
    }

    @Test
    void statLinesAreReadByKey() {
        assertEquals(50_000, Probes160.statValue("HP=50000 MAXHP=50000 MP=45000 MAXMP=45000", "MAXHP"));
        assertEquals(50_000, Probes160.statValue("HP=50000 MAXHP=50000", "HP"));
        assertEquals(45_000, Probes160.statValue("MP=45000 MAXMP=45000", "MAXMP"));
        assertEquals(-1, Probes160.statValue(null, "HP"));
    }

    @Test
    void aProbesSkipLineIsTheChecksReason() {
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        Check c = Check.auto("t-zz-1", "probe only").expect(Expects.probe("later")).build();
        AutoQaRunner.CheckResult res = new AutoQaRunner(w).run(List.of(c), false, null).get(0);
        assertEquals(Check.Status.SKIP, res.status());
        assertEquals("nothing could be judged here: SKIP not here", res.reason());
    }

    /**
     * Owner 2026-10-27: Dalair's gate is level only (160, 110 for Cygnus), no Maker skill (the servers have none).
     * The gate and the 3-merge cap are checked on the pure rule.
     */
    @Test
    void dalairsGateAndCapAreLevelOnly() {
        assertTrue(Probes160.mergeGateProblems().isEmpty(), Probes160.mergeGateProblems().toString());
        assertTrue(Probes160.mergeCapProblems().isEmpty(), Probes160.mergeCapProblems().toString());
        assertTrue(client.Character.meetsMergeGate(160, false) && client.Character.meetsMergeGate(110, true));
        assertFalse(client.Character.meetsMergeGate(159, false) || client.Character.meetsMergeGate(109, true));
    }

    @Test
    void newerAreaCardsVerdictAndSamplesAreInMigration045() throws Exception {
        assertNull(Probes160.cardVerdict(20000, 8210000, 8210000));
        assertTrue(Probes160.cardVerdict(0, 8210000, 8210000).contains("045"));
        assertTrue(Probes160.cardVerdict(-1, 1, 1).contains("20000"));
        assertTrue(Probes160.cardVerdict(20000, -1, 8210000).contains("monstercarddata"));
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("database/migrations/045-newer-area-cards.sql"));
        for (int[] pair : Probes160.NEWER_AREA_CARDS) {
            assertTrue(sql.contains("(" + pair[0] + ", " + pair[1] + ")"), pair[0] + "/" + pair[1]);
        }
    }
}
