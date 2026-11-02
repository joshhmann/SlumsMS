package net.server.channel.handlers;

import client.Character;
import constants.id.MapId;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The 504 rock used to teleport to whatever map id the packet named: nothing checked the character's saved list and
 * nothing asked the world-map teleport's refusal rule. The client only ever offers an entry from the saved list
 * (TrockAddMapHandler fills it), so requiring one is not a player-visible change; it closes a forged-packet hole.
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

    private static Character at(int mapId) {
        Character player = mock(Character.class);
        when(player.getMapId()).thenReturn(mapId);
        when(player.isAlive()).thenReturn(true);
        when(player.getReborns()).thenReturn(0);
        when(player.getLevel()).thenReturn(50);
        return player;
    }

    private static MapleMap map(int fieldLimit, int forcedReturn) {
        MapleMap target = mock(MapleMap.class);
        when(target.getFieldLimit()).thenReturn(fieldLimit);
        when(target.getForcedReturnId()).thenReturn(forcedReturn);
        return target;
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

    @Test void anOrdinarySavedHopIsAllowed() {
        assertNull(UseCashItemHandler.trockRefusal(at(HENESYS), ELLINIA, map(0, MapId.NONE)));
    }

    @Test void theJailCannotBeLeftOrEnteredByRock() {
        assertNotNull(UseCashItemHandler.trockRefusal(at(HENESYS), MapId.JAIL, map(0, MapId.NONE)));
        assertNotNull(UseCashItemHandler.trockRefusal(at(MapId.JAIL), ELLINIA, map(0, MapId.NONE)));
    }

    @Test void aRunningEventCannotBeLeftByRock() {
        Character player = at(103000800);
        when(player.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertNotNull(UseCashItemHandler.trockRefusal(player, HENESYS, map(0, MapId.NONE)));
    }

    @Test void theDeadCannotTeleport() {
        Character player = at(HENESYS);
        when(player.isAlive()).thenReturn(false);
        assertNotNull(UseCashItemHandler.trockRefusal(player, ELLINIA, map(0, MapId.NONE)));
    }

    @Test void instanceMapsAreClosed() {
        assertNotNull(UseCashItemHandler.trockRefusal(at(HENESYS), 980041201, map(0, 980040000)));
    }

    @Test void rebirthGatesReachTheRock() {
        assertNotNull(UseCashItemHandler.trockRefusal(at(HENESYS), 211070000, map(0, MapId.NONE)));
        Character reborn = at(HENESYS);
        when(reborn.getReborns()).thenReturn(1);
        assertNull(UseCashItemHandler.trockRefusal(reborn, 211070000, map(0, MapId.NONE)));
    }
}
