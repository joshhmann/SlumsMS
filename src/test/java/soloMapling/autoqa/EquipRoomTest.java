package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    }
}
