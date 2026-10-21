package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;
import soloMapling.server.Json;

import java.awt.Point;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 04 follow-up: what players' moves are counted as, kept, capped and read back. No WZ needed. */
class NavPlayerLearningTest {

    @TempDir
    Path dir;

    private Path players;

    @BeforeEach
    void scratch() {
        NavObservedEdges.useForTest(dir, null);
        players = dir.resolve("players");
    }

    @AfterEach
    void real() {
        NavObservedEdges.useForTest(null, null);
    }

    private static NavObservedEdges.Transition move(int map, int x) {
        return new NavObservedEdges.Transition(map, 12, new Point(x, 100), 34, new Point(x + 60, 40));
    }

    @Test
    void movesAreLoggedWithoutWhoMadeThemAndCountedByOutcomeAndMap() throws Exception {
        NavObservedEdges.Ends ends = new NavObservedEdges.Ends(3, 4, BotNavigationGraph.EdgeType.JUMP, true);
        NavPlayerLearning.record(move(104040000, 10), NavObservedEdges.Outcome.ALREADY_JOINED, ends, 68);
        NavPlayerLearning.record(move(104040000, 20), NavObservedEdges.Outcome.SIGHTED, ends, 68);
        NavPlayerLearning.record(move(103000000, 30), NavObservedEdges.Outcome.NO_GRAPH, null, -1);
        NavPlayerLearning.flush();

        String today = NavPlayerLearning.day(System.currentTimeMillis());
        List<String> lines = Files.readAllLines(players.resolve("moves-" + today + ".jsonl"), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertEquals(move(104040000, 10), NavPlayerLearning.parseLine(lines.get(0)));
        Map<?, ?> first = (Map<?, ?>) Json.parse(lines.get(0));
        assertEquals(Map.of("t", true, "m", true, "k", true, "a", true, "b", true, "o", true, "g", true, "r", true),
                Map.ofEntries(first.keySet().stream().map(k -> Map.entry(String.valueOf(k), true)).toArray(Map.Entry[]::new)),
                "time, map, kind, ends, outcome, graph version, regions - nothing about the player");
        assertEquals("ALREADY_JOINED", first.get("o"));

        Map<?, ?> stats = (Map<?, ?>) Json.parse(Files.readString(players.resolve("stats.json")));
        assertEquals(3, ((Number) stats.get("moves")).intValue());
        assertEquals(2, ((Number) stats.get("fhMismatch")).intValue());
        Map<?, ?> byMap = (Map<?, ?>) stats.get("maps");
        assertEquals(List.of("104040000", "103000000"), byMap.keySet().stream().map(String::valueOf).toList(), "busiest first");
        assertEquals(1, ((Number) ((Map<?, ?>) byMap.get("103000000")).get("NO_GRAPH")).intValue());
        assertTrue(((Map<?, ?>) stats.get("days")).containsKey(today));
    }

    @Test
    void countsAndSightingsCarryOnAcrossARestart() throws Exception {
        NavObservedEdges.Ends ends = new NavObservedEdges.Ends(3, 4, BotNavigationGraph.EdgeType.JUMP, false);
        NavPlayerLearning.record(move(104040000, 10), NavObservedEdges.Outcome.SAME_REGION, ends, 68);
        NavObservedEdges.restoreSightings(Map.of("v68:104040000:JUMP:3>4:1", new long[]{1, System.currentTimeMillis()},
                "v68:104040000:JUMP:5>6:1", new long[]{1, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(200)}));
        NavPlayerLearning.record(move(104040000, 20), NavObservedEdges.Outcome.SIGHTED, ends, 68);
        NavPlayerLearning.flush();

        NavObservedEdges.useForTest(dir, null); // a restart: memory gone, same folder
        assertEquals(0, NavObservedEdges.sightingCount());
        NavPlayerLearning.ensureSightingsLoaded();
        assertEquals(1, NavObservedEdges.sightingCount(), "the fresh sighting is back, the 200-day-old one expired");
        assertEquals(1, NavObservedEdges.sightingsSnapshot().get("v68:104040000:JUMP:3>4:1")[0]);
        NavPlayerLearning.record(move(104040000, 30), NavObservedEdges.Outcome.SAME_REGION, ends, 68);
        assertEquals(2L, NavPlayerLearning.totals().get("SAME_REGION"));
        assertEquals(1L, NavPlayerLearning.totals().get("SIGHTED"));
    }

    @Test
    void aMapsDayIsCappedAndOldDaysArePruned() throws Exception {
        Files.createDirectories(players);
        Path old = players.resolve("moves-2020-01-01.jsonl");
        Files.writeString(old, "{}\n");
        for (int i = 0; i < NavPlayerLearning.MAP_DAY_CAP + 25; i++) {
            NavPlayerLearning.record(move(104040000, i), NavObservedEdges.Outcome.ALREADY_JOINED, null, 68);
        }
        NavPlayerLearning.record(move(100000000, 1), NavObservedEdges.Outcome.ALREADY_JOINED, null, 68);
        NavPlayerLearning.flush();
        String today = NavPlayerLearning.day(System.currentTimeMillis());
        assertEquals(NavPlayerLearning.MAP_DAY_CAP + 1,
                Files.readAllLines(players.resolve("moves-" + today + ".jsonl")).size(), "capped per map, the other map still logged");
        assertFalse(Files.exists(old), "older than " + NavPlayerLearning.KEEP_DAYS + " days");
        Map<?, ?> stats = (Map<?, ?>) Json.parse(Files.readString(players.resolve("stats.json")));
        assertEquals(25, ((Number) stats.get("dropped")).intValue());
        assertEquals(NavPlayerLearning.MAP_DAY_CAP + 26, ((Number) stats.get("moves")).intValue(), "counted even when not logged");
    }

    @Test
    void theSummaryIsWrittenOnlyWhenSomethingChanged() {
        assertNull(NavPlayerLearning.summarize());
        NavPlayerLearning.record(move(104040000, 10), NavObservedEdges.Outcome.ALREADY_JOINED, null, 68);
        NavPlayerLearning.record(move(104040000, 20), NavObservedEdges.Outcome.ADOPTED, null, 68);
        String line = NavPlayerLearning.summarize();
        assertNotNull(line);
        assertTrue(line.contains("2 (1 learned, 1 known)"), line);
        assertTrue(line.contains("104040000 2"), line);
        assertNull(NavPlayerLearning.summarize(), "nothing new since");
    }

    @Test
    void portalChoicesOfRealPlayersAreCountedAndKept() throws Exception {
        Character player = Character.getDefault(new BotClient(0, 0));
        NavPlayerLearning.onPortal(player, 100000000, "west00", 100010000);
        NavPlayerLearning.onPortal(player, 100000000, "west00", 100010000);
        NavPlayerLearning.onPortal(player, 100000000, "west01", 100010000);
        NavPlayerLearning.onPortal(player, 100000000, "in00", 100000000); // not a map change
        assertEquals(Map.of("west00", 2, "west01", 1), NavPlayerLearning.portalChoices(100000000, 100010000));
        NavPlayerLearning.flush();
        NavObservedEdges.useForTest(dir, null);
        assertEquals(Map.of("west00", 2, "west01", 1), NavPlayerLearning.portalChoices(100000000, 100010000), "kept");

        NavDashboardData.Dirs dirs = new NavDashboardData.Dirs(dir.resolve("v68"), dir.resolve("deep"),
                dir.resolve("observed"), dir.resolve("learned"), dir.resolve("stuck"));
        Map<String, Object> view = NavDashboardData.playerLearning(dirs);
        assertEquals(List.of(List.of("100000000>100010000", "west00", 2), List.of("100000000>100010000", "west01", 1)),
                view.get("portals"));
    }

    @Test
    void theDashboardSeesTheCounters() throws Exception {
        NavPlayerLearning.record(move(104040000, 10), NavObservedEdges.Outcome.ALREADY_JOINED, null, 68);
        NavPlayerLearning.flush();
        NavDashboardData.Dirs dirs = new NavDashboardData.Dirs(dir.resolve("v68"), dir.resolve("deep"),
                dir.resolve("observed"), dir.resolve("learned"), dir.resolve("stuck"));
        Map<String, Object> view = NavDashboardData.playerLearning(dirs);
        assertEquals(1, ((Number) view.get("moves")).intValue());
        assertEquals(Map.of("ALREADY_JOINED", 1L), view.get("totals"));
        assertTrue(((Map<?, ?>) view.get("maps")).containsKey("104040000"));
    }

    @Test
    void aDownJumpFragmentMakesTheNextLandingADownJump() {
        List<NavObservedEdges.Transition> seen = new java.util.ArrayList<>();
        NavObservedEdges.sink = seen::add;
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(77);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(104040000);
        player.setMap(map);
        NavObservedEdges.onPlayerMove(player, 100, 200, 5, constants.game.CharacterStance.PRONE_RIGHT_STANCE);
        NavObservedEdges.onPlayerDownJump(player);
        NavObservedEdges.onPlayerMove(player, 100, 260, 0, constants.game.CharacterStance.JUMP_RIGHT_STANCE);
        NavObservedEdges.onPlayerMove(player, 100, 320, 9, constants.game.CharacterStance.STAND_RIGHT_STANCE);
        NavObservedEdges.onPlayerMove(player, 100, 320, -4, constants.game.CharacterStance.STAND_RIGHT_STANCE); // a rope id while standing: ignored
        assertEquals(List.of(new NavObservedEdges.Transition(104040000, NavObservedEdges.Kind.DOWN_JUMP, 5,
                new Point(100, 200), 9, new Point(100, 320))), seen);
    }

    @Test
    void aMapWithNoCachedGraphIsCountedAsNoGraphNotDroppedSilently() throws Exception {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(999_999_001);
        NavObservedEdges.useForTest(dir, id -> map);
        NavObservedEdges.consider(move(999_999_001, 10));
        long deadline = System.currentTimeMillis() + 5_000;
        while (NavPlayerLearning.totals().get("NO_GRAPH") == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1L, NavPlayerLearning.totals().get("NO_GRAPH"));
    }

    @Test
    void storedOverlaysOfEveryFormatAreReadAndTierGated() throws Exception {
        BotNavigationGraph g = new BotNavigationGraph(104040000, 68, BotMovementProfile.base(), List.of(), Map.of(),
                Map.of(), Map.of(), java.util.Set.of());
        Path file = dir.resolve("observed").resolve("104040000.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(Map.of("version", 68, "edges", List.of(
                List.of(1, 2, 6, 10, 20, 70, 0, 400),                                     // ticket 17: a plain jump
                List.of("CLIMB", 3, 4, 0, 50, 60, 50, 10, 300, 1, 1),                      // ticket 04: every tier
                List.of("JUMP", 5, 6, 8, 0, 0, 40, -60, 450, 0, 0, 0b100)))));            // only the 125/105 tier
        assertEquals(2, NavObservedEdges.storedEdges(file, g).size(), "base: the two older ones");
        // 1.5.0 earned speed: a pre-1.5.0 mask carries only to new tiers at least as fast (125/105 -> 140/120 and up).
        BotNavigationGraph mid = new BotNavigationGraph(104040000, 68, new BotMovementProfile(125, 105), List.of(),
                Map.of(), Map.of(), Map.of(), java.util.Set.of());
        assertEquals(2, NavObservedEdges.storedEdges(file, mid).size(), "125/105 plays on the 110/100 tier now");
        BotNavigationGraph fast = new BotNavigationGraph(104040000, 68, new BotMovementProfile(140, 120), List.of(),
                Map.of(), Map.of(), Map.of(), java.util.Set.of());
        assertEquals(3, NavObservedEdges.storedEdges(file, fast).size());
        Files.writeString(file, Json.write(Map.of("version", 68, "tiers", NavObservedEdges.TIERS_FORMAT, "edges", List.of(
                List.of("JUMP", 5, 6, 8, 0, 0, 40, -60, 450, 0, 0, 0b100)))));            // the 1.5.0 120/110 tier
        assertEquals(1, NavObservedEdges.storedEdges(file, new BotNavigationGraph(104040000, 68,
                new BotMovementProfile(120, 110), List.of(), Map.of(), Map.of(), Map.of(), java.util.Set.of())).size());
    }
}
