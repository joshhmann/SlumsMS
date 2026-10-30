package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        assertTrue(NavTrapQa.baseline().size() > 0, "the committed baseline is in the jar");
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
            lines.add("# Ticket 157 / auto-QA t-nv-2: the one-way-in traps of the bots' world graph (reachable from Lith");
            lines.add("# Harbor " + NavTrapQa.HUB + ", no route back over portals, scripted warps and BotTransit rides).");
            lines.add("# Most are by design (PQ stages, jump-quest prize rooms, boss altars: the way out is an NPC or a");
            lines.add("# timer). A trap missing from this list fails t-nv-2. Regenerate after a deliberate data change:");
            lines.add("#   mvn -o test -Dtest=NavTrapQaTest -Dwz-path=<wz> -DnavTrap.write=<this file>");
            lines.add("# " + t.reachable() + " maps reachable, " + t.canReturn() + " with a way back, " + t.traps().size()
                    + " traps.");
            for (int id : t.traps()) {
                lines.add(String.valueOf(id));
            }
            Files.write(Path.of(out), lines);
            return;
        }
        assertTrue(t.reachable() > 500, "the world graph loaded: " + t.reachable() + " maps reachable");
        NavTrapQa.Diff d = NavTrapQa.diff(t, NavTrapQa.baseline().keySet());
        assertTrue(d.unknown().isEmpty(), "new traps (not in the baseline): " + d.unknown());
        assertTrue(d.fixed().isEmpty(), "baseline maps that are no longer traps: " + d.fixed());
    }
}
