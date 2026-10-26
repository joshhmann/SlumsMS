package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 auto-QA triage (t-rm-4): the equipRoom fixture empties the highest slots, never a live ring. */
class EquipRoomTest {

    @Test
    void freesTheHighestSlotsUntilThereIsRoom() {
        List<Short> s = List.of((short) 1, (short) 2, (short) 3, (short) 4);
        assertEquals(List.of((short) 4, (short) 3), GameAutoQaWorld.slotsToFree(0, 2, s, List.of(false, false, false, false)));
        assertEquals(List.of((short) 4), GameAutoQaWorld.slotsToFree(1, 2, s, List.of(false, false, false, false)));
        assertEquals(List.of(), GameAutoQaWorld.slotsToFree(2, 2, s, List.of(false, false, false, false)));
    }

    @Test
    void neverDropsALiveRing() {
        List<Short> s = List.of((short) 1, (short) 2, (short) 3);
        assertEquals(List.of((short) 2, (short) 1), GameAutoQaWorld.slotsToFree(0, 2, s, List.of(false, false, true)));
    }

    @Test
    void buddyIsAcceptedOnlyWhenTheBotHasThePlayerToo() {
        assertEquals("none", GameAutoQaWorld.buddyState(false, false, false, false));
        assertEquals("pending", GameAutoQaWorld.buddyState(true, true, true, false), "refused: the player's entry alone");
        assertEquals("pending", GameAutoQaWorld.buddyState(true, true, false, false));
        assertEquals("accepted", GameAutoQaWorld.buddyState(true, true, true, true));
        assertTrue(GameAutoQaWorld.dropBeforeAdd("pending"), "an unanswered entry blocks the stock add: dropped");
        assertFalse(GameAutoQaWorld.dropBeforeAdd("accepted"), "a real buddy stays");
        assertFalse(GameAutoQaWorld.dropBeforeAdd("none"));
    }

    @Test
    void buddyRoomDropsOnlyBotsAndOnlyAsManyAsNeeded() {
        // TEST 2026-10-26 t-bb-2: AutoQaOne's list was 25 of 25 and the add was refused as full
        java.util.function.IntPredicate bot = id -> id >= 1_000_000_000;
        List<Integer> full = new java.util.ArrayList<>();
        full.add(7); // a real player stays
        for (int i = 0; i < 24; i++) {
            full.add(1_000_000_000 + i);
        }
        assertEquals(List.of(1_000_000_000, 1_000_000_001), GameAutoQaWorld.buddiesToDrop(full, 25, 2, bot));
        assertEquals(List.of(), GameAutoQaWorld.buddiesToDrop(full.subList(0, 20), 25, 2, bot), "room already");
        assertEquals(List.of(), GameAutoQaWorld.buddiesToDrop(List.of(1, 2, 3), 3, 2, bot), "players only: none dropped");
    }

    @Test
    void bothBuddyChecksMakeRoomBeforeTheAdd() {
        for (String id : List.of("t-bb-1", "t-bb-2")) {
            Check c = AutoQaCatalog.all().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            int room = -1;
            int add = -1;
            for (int i = 0; i < c.steps().size(); i++) {
                String d = c.steps().get(i).describe();
                if (d.startsWith("fixture buddyRoom")) {
                    room = i;
                } else if (add < 0 && d.toLowerCase(java.util.Locale.ROOT).contains("buddy add")) {
                    add = i;
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(room >= 0 && add > room, id + ": room " + room + ", add " + add);
        }
    }
}
