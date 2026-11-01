package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 157 / b224 (auto-QA t-nv-2): one-way-in traps of the bots' world graph, on made-up worlds, and the committed
 * baseline checked against the real WZ data where a checkout has it ({@code -Dwz-path}).
 */
class NavTrapQaTest {

    private static NavTrapQa.Traps traps(int hub, Map<Integer, int[]> g) {
        return NavTrapQa.find(hub, m -> g.getOrDefault(m, new int[0]));
    }

    @Test
    void aMapWithAWayInAndNoWayOutIsATrap() {
        // 1 <-> 2 -> 3 (dead end), 2 -> 4 -> 5 -> 2 (a loop back), 6 is unreachable from the hub
        Map<Integer, int[]> g = Map.of(1, new int[]{2}, 2, new int[]{1, 3, 4}, 4, new int[]{5}, 5, new int[]{2},
                6, new int[]{1});
        NavTrapQa.Traps t = traps(1, g);
        assertEquals(5, t.reachable());
        assertEquals(4, t.canReturn());
        assertEquals(Set.of(3), t.traps());
    }

    @Test
    void everythingPastATrapDoorIsATrapToo() {
        // 1 <-> 2, 2 -> 3 -> 4 <-> 5: 3, 4 and 5 can never get back to 1
        Map<Integer, int[]> g = Map.of(1, new int[]{2}, 2, new int[]{1, 3}, 3, new int[]{4}, 4, new int[]{5},
                5, new int[]{4});
        assertEquals(Set.of(3, 4, 5), traps(1, g).traps());
    }

    @Test
    void theVirtualAndSelfEdgesAreIgnored() {
        Map<Integer, int[]> g = Map.of(1, new int[]{-2, 1, 2}, 2, new int[]{1, 2});
        NavTrapQa.Traps t = traps(1, g);
        assertEquals(2, t.reachable());
        assertTrue(t.traps().isEmpty());
    }

    @Test
    void theDiffNamesNewTrapsAndFixedOnes() {
        NavTrapQa.Traps t = new NavTrapQa.Traps(1, 10, 7, new java.util.TreeSet<>(Set.of(3, 4, 9)));
        NavTrapQa.Diff d = NavTrapQa.diff(t, Set.of(3, 4, 8));
        assertEquals(Set.of(9), d.unknown());
        assertEquals(Set.of(8), d.fixed());
    }

    @Test
    void theBaselineParsesIdsAndComments() {
        Map<Integer, String> b = NavTrapQa.parse(List.of("# header", "", "103000805 # KPQ bonus", "910000000"));
        assertEquals(Map.of(103000805, "KPQ bonus", 910000000, ""), b);
        assertTrue(NavTrapQa.baseline() != null, "the committed baseline is in the jar");
    }

    // ── b230: the search steps the way the planner does, so the intended one-way rides are not traps ──

    @AfterEach
    void restoreTransit() {
        BotTransit.reload();
        GCWorldGraph.clearRouteCache();
    }

    private static NavTrapQa.Traps plannerTraps(int hub, Map<Integer, int[]> g) {
        return NavTrapQa.find(hub, m -> GCWorldGraph.waysOn(g, m));
    }

    private static BotTransit.Edge npc(String name, int from, int to, String returnToSaved) {
        return new BotTransit.Edge(name, from, to, BotTransit.Via.NPC, 9010002, -1, null, 0, -1, null, true, 30, -1,
                null, null, returnToSaved);
    }

    @Test
    void anAreaTheRockLeavesIsNotATrap() {
        // Henesys's teleporter goes in to Pantheon (Grandis); no portal leads back, the Hyper Teleport Rock does
        int henesys = 100000000;
        int pantheon = 400000000;
        int field = 400000001;
        Map<Integer, int[]> g = Map.of(henesys, new int[]{}, pantheon, new int[]{field}, field, new int[]{pantheon});
        BotTransit.install(List.of(npc("teleporter", henesys, pantheon, null)));

        assertEquals(Set.of(pantheon, field), plannerTraps(henesys, g).traps(), "without the rock it is a trap");

        BotTransit.installHyperRock(new BotTransit.HyperRock(300, List.of(),
                List.<int[]>of(new int[]{400000000, 460000001}), List.of(henesys)));
        NavTrapQa.Traps t = plannerTraps(henesys, g);
        assertTrue(t.traps().isEmpty(), t.toString());
        assertEquals(3, t.reachable());
    }

    @Test
    void theRocksJumpIntoItsAreaIsNoWayBack() {
        // the rock jumps INTO Black Heaven from every map; counting it would make every dead end look like it has a
        // way back (dead end -> Black Heaven -> home is two jumps, which no route takes)
        int town = 104000000;
        int deadEnd = 5;
        Map<Integer, int[]> g = Map.of(town, new int[]{deadEnd}, deadEnd, new int[]{});
        BotTransit.install(List.of());
        BotTransit.installHyperRock(new BotTransit.HyperRock(300, List.of(310070000),
                List.<int[]>of(new int[]{310000000, 311000000}), List.of(town)));

        assertEquals(Set.of(deadEnd), plannerTraps(town, g).traps());
        assertArrayEquals(new int[0], GCWorldGraph.waysOn(g, deadEnd));
    }

    @Test
    void aTourHomeCountsForWhicheverTownWasSaved() {
        // Malaysia: in from Singapore's CBD, home with the tour back to the saved town (not Lith Harbor's default)
        int cbd = 540000000;
        int malaysia = 550000000;
        Map<Integer, int[]> g = Map.of(cbd, new int[]{}, malaysia, new int[]{});
        BotTransit.install(List.of(npc("Malaysia tour", cbd, malaysia, null),
                npc("Malaysia tour home", malaysia, cbd, "WORLDTOUR")));

        assertTrue(plannerTraps(cbd, g).traps().isEmpty());
        assertArrayEquals(new int[]{cbd}, GCWorldGraph.waysOn(g, malaysia));
        // the planner itself still sends a bot only back where it came from
        assertEquals(null, GCWorldGraph.dijkstra(g, malaysia, cbd, 60, 0, true, 104000000));
        assertEquals(List.of(cbd), GCWorldGraph.dijkstra(g, malaysia, cbd, 60, 0, true, cbd));
    }

    /**
     * The committed baseline is exactly today's traps of the real graph (WZ data needed: skipped without it). With
     * {@code -DnavTrap.write=<file>} it writes a fresh baseline there instead (after a deliberate data change).
     */
    @Test
    void theCommittedBaselineMatchesTheRealWorldGraph() throws Exception {
        WzTestData.assumePresent("Map.wz");
        NavTrapQa.Traps t = NavTrapQa.worldTraps();
        String out = System.getProperty("navTrap.write");
        if (out != null) {
            List<String> lines = new ArrayList<>();
            lines.add("# Ticket 157 / auto-QA t-nv-2: the accepted one-way-in traps of the bots' world graph: maps reachable");
            lines.add("# from Lith Harbor " + NavTrapQa.HUB + " with no route back over portals, scripted warps, BotTransit rides,");
            lines.add("# the tours' way home and the Hyper Teleport Rock's jump home (GCWorldGraph.waysOn).");
            lines.add("# b230 classified the old 485: intended one-way rides (Grandis, Arcane River: the rock's out_of ranges;");
            lines.add("# Malaysia: the tour home) are no traps by that rule, and the missing edges were fixed in the graph.");
            lines.add("# Only real traps belong here, each with its reason after a '#'. A trap missing from this list fails");
            lines.add("# t-nv-2. Regenerate after a deliberate data change (keeps the reasons; add one for a new map):");
            lines.add("#   mvn -o test -Dtest=NavTrapQaTest -Dwz-path=<wz> -DnavTrap.write=<this file>");
            lines.add("# " + t.reachable() + " maps reachable, " + t.canReturn() + " with a way back, " + t.traps().size()
                    + " traps.");
            Map<Integer, String> known = NavTrapQa.baseline();
            for (int id : t.traps()) {
                String why = known == null ? "" : known.getOrDefault(id, "");
                lines.add(why.isEmpty() ? id + " # TODO: classify (b230: a reason for every accepted trap)" : id + " # " + why);
            }
            Files.write(Path.of(out), lines);
            return;
        }
        assertTrue(t.reachable() > 500, "the world graph loaded: " + t.reachable() + " maps reachable");
        NavTrapQa.Diff d = NavTrapQa.diff(t, NavTrapQa.baseline().keySet());
        assertTrue(d.unknown().isEmpty(), "new traps (not in the baseline): " + d.unknown());
        assertTrue(d.fixed().isEmpty(), "baseline maps that are no longer traps: " + d.fixed());
    }

    @Test
    void aPortalToAMapWithNoImageIsNoEdge() {
        // Ristonia's Fountain Square: its hidden in00 leads to the Storage Cellar, which Map.wz does not have
        int square = 410000300;
        int cellar = 993162016;
        int street = 410000310;
        Map<Integer, int[]> g = new java.util.HashMap<>(Map.of(square, new int[]{street, cellar}, street,
                new int[]{square}));
        assertEquals(Set.of(cellar), traps(square, g).traps(), "before: a dead end");

        GCWorldGraph.dropMissingTargets(g);

        assertArrayEquals(new int[]{street}, g.get(square));
        assertArrayEquals(new int[]{square}, g.get(street));
        assertTrue(traps(square, g).traps().isEmpty());
    }

    @Test
    void everyAcceptedTrapNamesItsReason() {
        Map<Integer, String> b = NavTrapQa.baseline();
        for (Map.Entry<Integer, String> e : b.entrySet()) {
            assertTrue(!e.getValue().isBlank() && !e.getValue().startsWith("TODO"),
                    "b230: baseline map " + e.getKey() + " has no reason");
        }
        assertTrue(b.size() <= 20, "the baseline is for the few real traps, not the intended one-way rides: " + b.size());
    }
}
