package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 163 (owner 2026-11-01: "Could we bump the qa chars to 10?"): auto-QA runs up to ten lanes of synthetic players
 * instead of four. Lane 1 is slots 1-2, lanes 2-10 are slots 7-24, after the four observers 3-6; every lane's first
 * player is a GM so it can run {@code !qa}, and every player has its own account {@code autoqa<slot>}, its own name
 * and its own hwid, because two lanes sharing a character would log the first lane out.
 *
 * <p>Slots 7-12 keep the names TEST already has ({@code AutoQaSeven}..{@code AutoQaTwelve}): the seeding looks a
 * character up by account, so renaming the table would desync the default from the real character.
 */
class AutoQaTenLanesTest {

    private static final int LANES = 10;

    @AfterEach
    void clearProperty() {
        System.clearProperty(AutoQaScheduler.PROPERTY);
    }

    @Test
    void theLaneCountGoesToTenAndClampsThere() {
        System.setProperty(AutoQaScheduler.PROPERTY, "10");
        assertEquals(LANES, AutoQaScheduler.parallel(), "ten lanes on request");
        System.setProperty(AutoQaScheduler.PROPERTY, "11");
        assertEquals(LANES, AutoQaScheduler.parallel(), "clamped to the ten lanes' players");
        System.setProperty(AutoQaScheduler.PROPERTY, "4");
        assertEquals(4, AutoQaScheduler.parallel(), "a smaller cap is still honoured");
        assertEquals(LANES, SyntheticPlayers.MAX_LANES, "the cap the property is clamped to");
    }

    @Test
    void tenLanesUseTwentyDistinctPlayers() {
        Set<Integer> slots = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> accounts = new HashSet<>();
        Set<String> hwids = new HashSet<>();
        List<String> nameList = new ArrayList<>();
        for (int lane = 1; lane <= LANES; lane++) {
            for (int logical = 1; logical <= 2; logical++) {
                int slot = SyntheticPlayers.laneSlot(lane, logical);
                assertEquals(lane, SyntheticPlayers.laneOfSlot(slot), "slot " + slot + " plays in lane " + lane);
                assertTrue(SyntheticPlayers.isLaneSlot(slot), "slot " + slot + " is a lane's");
                assertEquals(logical == 1, SyntheticPlayers.isGmSlot(slot),
                        "a lane's first player is its GM: slot " + slot);
                slots.add(slot);
                names.add(SyntheticPlayers.nameOf(slot));
                nameList.add(SyntheticPlayers.nameOf(slot));
                accounts.add("autoqa" + slot);
                hwids.add(SyntheticPlayers.hwidOf(slot));
            }
        }
        assertEquals(2 * LANES, slots.size(), "twenty slots: " + slots);
        assertEquals(2 * LANES, names.size(), "twenty names: " + nameList);
        assertEquals(2 * LANES, accounts.size(), "twenty accounts");
        assertEquals(2 * LANES, hwids.size(), "twenty hwids, or two lanes share a session");
        for (String name : nameList) {
            assertTrue(name.length() <= 12, "the client shows 12 characters: " + name);
            assertTrue(name.startsWith("AutoQa"), name);
        }
        // The numbering: lane 1 keeps 1/2, lane k >= 2 is 7 + 2(k-2) and 8 + 2(k-2), so the last lane is 23/24.
        assertEquals(7, SyntheticPlayers.laneSlot(2, 1));
        assertEquals(23, SyntheticPlayers.laneSlot(LANES, 1), "lane 10's GM");
        assertEquals(24, SyntheticPlayers.laneSlot(LANES, 2), "lane 10's second player");
    }

    @Test
    void theSlotTableCoversLanesAndObserversOnce() {
        Set<Integer> lanePair = new HashSet<>();
        for (int lane = 1; lane <= LANES; lane++) {
            for (int logical = 1; logical <= 2; logical++) {
                lanePair.add(SyntheticPlayers.laneSlot(lane, logical));
            }
        }
        Set<Integer> observers = new HashSet<>();
        for (int slot = 3; slot <= 6; slot++) {
            observers.add(slot);
            assertTrue(SyntheticPlayers.isObserverSlot(slot), "slot " + slot + " is an observer's");
            assertFalse(SyntheticPlayers.isLaneSlot(slot), "an observer never plays in a lane: " + slot);
        }
        assertTrue(java.util.Collections.disjoint(lanePair, observers),
                "no lane player is an observer: " + lanePair);
        // Every slot from 1 to the last lane player exists and is exactly one of the two kinds: a gap would make
        // nameOf() throw half-way through a run, and an overlap would hand two lanes the same character.
        int last = 24;
        assertEquals(SyntheticPlayers.laneSlot(LANES, 2), last);
        for (int slot = 1; slot <= last; slot++) {
            boolean lane = SyntheticPlayers.isLaneSlot(slot);
            boolean observer = SyntheticPlayers.isObserverSlot(slot);
            assertTrue(lane ^ observer, "slot " + slot + " is either a lane's player or an observer: lane=" + lane
                    + " observer=" + observer);
        }
        // 24 distinct default names: a character name is unique in the world, so a duplicate would make one lane's
        // seeding fall back to a different name than the character it already has.
        Set<String> names = new HashSet<>();
        for (int slot = 1; slot <= last; slot++) {
            names.add(SyntheticPlayers.nameOf(slot));
        }
        assertEquals(last, names.size(), "24 distinct default names: " + names);
    }

    /**
     * When a real player or a bot already holds a default name the seeding falls back to
     * {@code nameOf(slot).substring(0, 7) + slot*10 + i} ("AutoQaS10", "AutoQa1130"). The new numeric defaults
     * {@code AutoQa13}..{@code AutoQa24} share their first seven characters with their own slot ("AutoQa1"), so this
     * pins the property that makes the two namespaces safe: no slot's fallback can ever be another slot's default,
     * and every fallback still fits the client's 12 characters.
     */
    @Test
    void noFallbackNameIsAnySlotsDefault() {
        Set<String> defaults = new HashSet<>();
        for (int slot = 1; slot <= 24; slot++) {
            defaults.add(SyntheticPlayers.nameOf(slot));
        }
        for (int slot = 1; slot <= 24; slot++) {
            for (int i = 0; i < 200; i++) {
                String fallback = SyntheticPlayers.fallbackName(slot, i);
                assertNotEquals(SyntheticPlayers.nameOf(slot), fallback, "slot " + slot + "'s fallback is its default");
                assertFalse(defaults.contains(fallback),
                        "slot " + slot + "'s fallback " + fallback + " is a slot's default name");
                assertTrue(fallback.length() <= 12, "the client shows 12 characters: " + fallback);
            }
        }
    }

    @Test
    void laneIsolationHoldsAtTen() {
        Set<Integer> seen = new HashSet<>();
        for (int lane = 1; lane <= LANES; lane++) {
            int gm = SyntheticPlayers.laneSlot(lane, 1);
            int other = SyntheticPlayers.laneSlot(lane, 2);
            assertArrayEquals(new int[] {gm, other}, SyntheticPlayers.lanePair(gm), "lane " + lane + "'s own pair");
            assertArrayEquals(new int[] {gm, other}, SyntheticPlayers.lanePair(other), "from either player");
            assertEquals(other, SyntheticPlayers.partnerSlot(gm), "the GM's partner");
            assertEquals(gm, SyntheticPlayers.partnerSlot(other), "and back");
            assertTrue(seen.add(gm) && seen.add(other), "lane " + lane + " shares a player with another lane");
            if (lane > 1) {
                assertFalse(SyntheticPlayers.lanePair(gm)[0] == 1 || SyntheticPlayers.lanePair(gm)[1] == 1,
                        "no lane ever acts on lane 1's players: lane " + lane);
            }
        }
        assertEquals(2 * LANES, seen.size(), "ten disjoint pairs");
        for (int observer = 3; observer <= 6; observer++) {
            assertEquals(0, SyntheticPlayers.laneOfSlot(observer), "an observer has no lane");
        }
    }
}
