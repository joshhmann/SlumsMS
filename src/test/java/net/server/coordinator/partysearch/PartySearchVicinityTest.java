package net.server.coordinator.partysearch;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ticket 164: PartySearchCoordinator.isInVicinity asked whether the CALLEE's map is a neighbour of the CALLEE's
 * map ({@code vicinityMapids.contains(calleeMapid)} with {@code vicinityMapids = mapNeighbors.get(calleeMapid)}).
 * Every entry of Etc.wz/MapNeighbors.img lists its own map id (5262 of 5262 entries checked), so the answer was
 * always true: a leader in Henesys was matched with a candidate standing anywhere in the world and the "nearby
 * maps only" rule of party search did nothing. A leader was then invited to a party with someone they could not
 * reach.
 *
 * <p>The neighbour table is passed in rather than read from Etc.wz: a test JVM has no wz directory, so
 * {@code fetchNeighbouringMaps()} returns an empty table there and the table-driven branch would never run.
 */
class PartySearchVicinityTest {

    /** An entry of MapNeighbors.img: the map itself plus its neighbours (every entry lists itself). */
    private static Map<Integer, Set<Integer>> tableWith(int mapid, int... neighbours) {
        Set<Integer> entry = new HashSet<>();
        entry.add(mapid);
        for (int neighbour : neighbours) {
            entry.add(neighbour);
        }

        Map<Integer, Set<Integer>> table = new HashMap<>();
        table.put(mapid, entry);
        return table;
    }

    @Test
    void rejectsCalleeWhoseEntryDoesNotListTheCallerMap() {
        int callerMapid = 100000000;    // Henesys
        int calleeMapid = 101030000;    // a field map: its entry lists only itself, it is not near Henesys

        assertFalse(PartySearchCoordinator.isInVicinity(tableWith(calleeMapid), callerMapid, calleeMapid));
    }

    @Test
    void acceptsCalleeWhoseEntryListsTheCallerMap() {
        int callerMapid = 100000000;    // Henesys
        int calleeMapid = 100000100;    // Henesys hunting ground: its entry lists Henesys

        assertTrue(PartySearchCoordinator.isInVicinity(tableWith(calleeMapid, callerMapid), callerMapid, calleeMapid));
    }

    @Test
    void acceptsCalleeStandingOnTheCallersMap() {
        int callerMapid = 100040000;
        int calleeMapid = 100040000;

        assertTrue(PartySearchCoordinator.isInVicinity(tableWith(calleeMapid, callerMapid), callerMapid, calleeMapid));
    }

    @Test
    void mapsWithoutAnEntryFallBackToTheMapRegionRule() {
        Map<Integer, Set<Integer>> emptyTable = new HashMap<>();

        assertTrue(PartySearchCoordinator.isInVicinity(emptyTable, 100000000, 100000100), "same region (10)");
        assertFalse(PartySearchCoordinator.isInVicinity(emptyTable, 100000000, 200000000), "different region");
        // caller ranges 90 and up are compared against the callee's 1000000 block instead (unchanged legacy rule)
        assertTrue(PartySearchCoordinator.isInVicinity(emptyTable, 900000000, 90000000));
        assertFalse(PartySearchCoordinator.isInVicinity(emptyTable, 900000000, 100000000));
    }
}
