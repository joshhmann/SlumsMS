package net.server.coordinator.partysearch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ticket 164: PartySearchCoordinator.isInVicinity asked whether the CALLEE's map is a neighbour of the CALLEE's map
 * ({@code vicinityMapids.contains(calleeMapid)} with {@code vicinityMapids = mapNeighbors.get(calleeMapid)}).
 * Every entry of Etc.wz/MapNeighbors.img lists its own map id (5262 of 5262 entries checked), so the answer was
 * always true: a leader in Henesys was matched with a candidate standing anywhere in the world and the "nearby
 * maps only" rule of party search did nothing. A leader was then invited to a party with someone they could not
 * reach.
 *
 * <p>These cases call the public {@link PartySearchCoordinator#isInVicinity(int, int)} - the wrapper production
 * uses - against a table installed with {@code installNeighboursForTest}. A test JVM has no Etc.wz and would
 * otherwise see an empty table, and driving only the table overload would leave the one-line delegation in the
 * wrapper untested: swapping its two arguments inverts the rule for every player and no test would notice.
 *
 * <p>The fixture mirrors the real data: an entry lists its own map id and the maps it leads to, and the relation is
 * not symmetric. 100000000 (a town) lists 777777777, while 777777777's own entry lists only itself.
 */
class PartySearchVicinityTest {

    private static final int TOWN = 100000000;
    private static final int FAR_MAP = 777777777;

    private Map<Integer, Set<Integer>> previousNeighbours;

    @BeforeEach
    void installFixtureTable() {
        Map<Integer, Set<Integer>> table = new HashMap<>();
        table.put(TOWN, entryOf(TOWN, FAR_MAP));
        table.put(FAR_MAP, entryOf(FAR_MAP));

        previousNeighbours = PartySearchCoordinator.installNeighboursForTest(table);
    }

    @AfterEach
    void restoreTable() {
        PartySearchCoordinator.installNeighboursForTest(previousNeighbours);
    }

    /** An entry of MapNeighbors.img: the map itself plus its neighbours (every entry lists itself). */
    private static Set<Integer> entryOf(int mapid, int... neighbours) {
        Set<Integer> entry = new HashSet<>();
        entry.add(mapid);
        for (int neighbour : neighbours) {
            entry.add(neighbour);
        }
        return entry;
    }

    @Test
    void rejectsCalleeWhoseEntryDoesNotListTheCallerMap() {
        // the callee's entry lists only itself; the caller's map is not among its neighbours
        assertFalse(PartySearchCoordinator.isInVicinity(TOWN, FAR_MAP));
    }

    @Test
    void acceptsCalleeWhoseEntryListsTheCallerMap() {
        // the town lists the far map as its neighbour, so a candidate standing there is reachable from it
        assertTrue(PartySearchCoordinator.isInVicinity(FAR_MAP, TOWN));
    }

    @Test
    void acceptsCalleeStandingOnTheCallersMap() {
        assertTrue(PartySearchCoordinator.isInVicinity(TOWN, TOWN));
    }

    @Test
    void mapsWithoutAnEntryFallBackToTheMapRegionRule() {
        PartySearchCoordinator.installNeighboursForTest(new HashMap<>());

        assertTrue(PartySearchCoordinator.isInVicinity(100000000, 100000100), "same region (10)");
        assertFalse(PartySearchCoordinator.isInVicinity(100000000, 200000000), "different region");
        // caller ranges 90 and up are compared against the callee's 1000000 block instead (unchanged legacy rule)
        assertTrue(PartySearchCoordinator.isInVicinity(900000000, 90000000));
        assertFalse(PartySearchCoordinator.isInVicinity(900000000, 100000000));
    }
}
