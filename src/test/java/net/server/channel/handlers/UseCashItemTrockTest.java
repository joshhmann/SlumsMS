package net.server.channel.handlers;

import constants.id.MapId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 504 rock used to teleport to whatever map id the packet named: nothing checked the character's saved list.
 * The client only ever offers an entry from that list (TrockAddMapHandler fills it), so requiring one is not a
 * player-visible change; it closes a forged-packet hole.
 */
class UseCashItemTrockTest {
    private static final int PLAIN_ROCK = 5040000;
    private static final int VIP_ROCK = 5041000;
    private static final int HENESYS = 100000000;
    private static final int ELLINIA = 101000000;
    private static final int LITH_HARBOUR = 104000000;

    /** A rock list as the game keeps it: ten slots, the free ones MapId.NONE. */
    private static List<Integer> saved(Integer... maps) {
        List<Integer> list = new ArrayList<>(List.of(maps));
        while (list.size() < 10) {
            list.add(MapId.NONE);
        }
        return list;
    }

    @Test void aSavedMapIsTeleportedTo() {
        assertTrue(UseCashItemHandler.savedTrockDestination(PLAIN_ROCK, ELLINIA, saved(HENESYS, ELLINIA), saved()));
    }

    @Test void aMapOutsideTheSavedListIsRefused() {
        assertFalse(UseCashItemHandler.savedTrockDestination(PLAIN_ROCK, LITH_HARBOUR,
                saved(HENESYS, ELLINIA), saved()));
    }

    @Test void theVipRockReadsTheVipListAndThePlainRockThePlainOne() {
        List<Integer> plain = saved(HENESYS);
        List<Integer> vip = saved(LITH_HARBOUR);
        assertTrue(UseCashItemHandler.savedTrockDestination(VIP_ROCK, LITH_HARBOUR, plain, vip));
        assertFalse(UseCashItemHandler.savedTrockDestination(PLAIN_ROCK, LITH_HARBOUR, plain, vip));
        assertTrue(UseCashItemHandler.savedTrockDestination(PLAIN_ROCK, HENESYS, plain, vip));
        assertFalse(UseCashItemHandler.savedTrockDestination(VIP_ROCK, HENESYS, plain, vip));
    }

    /** Both lists are padded with MapId.NONE, so an unset slot must not read as a destination. */
    @Test void theEmptySlotsAreNotAMap() {
        assertFalse(UseCashItemHandler.savedTrockDestination(PLAIN_ROCK, MapId.NONE, saved(HENESYS), saved()));
        assertFalse(UseCashItemHandler.savedTrockDestination(PLAIN_ROCK, 0, saved(HENESYS), saved()));
        assertFalse(UseCashItemHandler.savedTrockDestination(PLAIN_ROCK, -1, saved(HENESYS), saved()));
    }

    @Test void theVipRockIsTheOneFrom5041000Up() {
        assertFalse(UseCashItemHandler.isVipRock(5040000));
        assertTrue(UseCashItemHandler.isVipRock(5041000));
    }
}
