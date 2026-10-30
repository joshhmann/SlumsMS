package soloMapling.ArtificialPlayer.BotSupplySystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSupplySystem.JunkSort.Kind;
import soloMapling.ArtificialPlayer.BotSupplySystem.JunkSort.Owned;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 162 part 4: what the town junk sale keeps back (self-reserve and valuables shelf), off by default.
class JunkSortTest {

    private static Owned eq(String name, String track, int reqLevel, int[] rel, int slots, double trade) {
        return new Owned(name, track, true, reqLevel, rel, slots, trade, false, false);
    }

    @Test
    void offByDefault() {
        assertFalse(JunkSort.enabled());
        assertTrue(JunkSort.keeps(null).isEmpty());
    }

    @Test
    void keepsTheUndominatedPiecesOfEachTrackAndSellsTheRest() {
        Owned good = eq("good", "slot:7", 30, new int[]{5, 3}, 5, 0);
        Owned worse = eq("worse", "slot:7", 30, new int[]{3, 1}, 5, 0);          // beaten everywhere, same level
        Owned different = eq("different", "slot:7", 30, new int[]{1, 8}, 5, 0);  // a stat mix nothing beats
        Owned tooHigh = eq("tooHigh", "slot:7", 80, new int[]{20, 20}, 7, 0);    // beyond the horizon
        Owned blank = eq("blank", "slot:8", 10, new int[]{0, 0}, 0, 0);          // nothing for its job, no slots
        Map<Object, Kind> sorted = JunkSort.sort(40, List.of(good, worse, different, tooHigh, blank));
        assertEquals(Kind.RESERVE, sorted.get("good"));
        assertEquals(Kind.RESERVE, sorted.get("different"));
        assertEquals(Kind.JUNK, sorted.get("worse"));
        assertEquals(Kind.JUNK, sorted.get("tooHigh"));
        assertEquals(Kind.JUNK, sorted.get("blank"));
    }

    @Test
    void anotherClassesGearIsNeverReserved() {
        Owned mage = new Owned("mage", "slot:1", false, 10, new int[]{9}, 7, 0, false, false);
        assertEquals(Kind.JUNK, JunkSort.sort(40, List.of(mage)).get("mage"));
    }

    @Test
    void theWornPieceIsARivalNotASaleItem() {
        Owned worn = new Owned("worn", "slot:7", true, 30, new int[]{9, 9}, 5, 0, false, true);
        Owned spare = eq("spare", "slot:7", 30, new int[]{5, 3}, 5, 0);
        Map<Object, Kind> sorted = JunkSort.sort(40, List.of(worn, spare));
        assertFalse(sorted.containsKey("worn"));
        assertEquals(Kind.JUNK, sorted.get("spare"));
    }

    @Test
    void atMostThreeReservesPerTrack() {
        List<Owned> pieces = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pieces.add(eq("p" + i, "slot:7", 30, new int[]{i, 10 - i}, i, 0));   // all incomparable
        }
        long reserved = JunkSort.sort(40, pieces).values().stream().filter(k -> k == Kind.RESERVE).count();
        assertEquals(JunkSort.TRACK_CAP, reserved);
    }

    @Test
    void theShelfKeepsTheBestRollsAndIsBounded() {
        List<Owned> pieces = new ArrayList<>();
        for (int i = 0; i < 40; i++) {   // another class's gear: never reserved, only shelved by value
            pieces.add(new Owned("v" + i, "slot:1", false, 10, new int[]{0}, 0, 6 + i, false, false));
        }
        Map<Object, Kind> sorted = JunkSort.sort(40, pieces);
        long shelf = sorted.values().stream().filter(k -> k == Kind.SHELF).count();
        // the top 16 by value (scores 45..30), plus the far-above-base ones below them (29..25: five, under the cap)
        assertEquals(JunkSort.SHELF_SLOTS + 5, shelf);
        List<Owned> rich = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rich.add(new Owned("r" + i, "slot:1", false, 10, new int[]{0}, 0, 30 + i, false, false));
        }
        assertEquals(JunkSort.SHELF_SLOTS + JunkSort.MAX_ABSOLUTE, JunkSort.sort(40, rich).values().stream()
                .filter(k -> k == Kind.SHELF).count(), "the never-sell keep is bounded");
        assertEquals(Kind.SHELF, sorted.get(pieces.get(39).item()));
        assertEquals(Kind.JUNK, sorted.get(pieces.get(0).item()));
    }
}
