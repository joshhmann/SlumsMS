package soloMapling.mechanics;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 163 (TEST 2026-11-01 19:19 UTC: {@code !mechanics kpq me perfect stage5} moved AqClient to 103000804 and left
 * sniperx, candyyy and PandaPudding on 103000800, so stage 5 was never cleared and no bonus came): staging a run at a
 * later stage takes the whole party up to the leader's stage, in the same event instance and landing where the gates
 * land, and refuses when a member cannot be taken - a party short a member cannot clear either.
 */
class KpqStagingPartyFollowTest {

    /** Where each character is, so a warp that takes changes what {@code getMap()} answers (as the real one does). */
    private static final class World {
        final Map<Character, MapleMap> where = new HashMap<>();
        final List<String> lines = new ArrayList<>();

        Character character(int id, String name, MapleMap at) {
            Character c = mock(Character.class);
            lenient().when(c.getId()).thenReturn(id);
            lenient().when(c.getName()).thenReturn(name);
            lenient().when(c.getMap()).thenAnswer(inv -> where.get(c));
            where.put(c, at);
            return c;
        }

        MapleMap map(EventInstanceManager eim, int id) {
            MapleMap map = mock(MapleMap.class);
            lenient().when(map.getId()).thenReturn(id);
            lenient().when(map.getEventInstance()).thenReturn(eim);
            return map;
        }

        void takesTheWarp(Character c) {
            doAnswer(inv -> {
                where.put(c, inv.getArgument(0));
                return null;
            }).when(c).changeMap(any(MapleMap.class), any(Portal.class));
        }
    }

    @Test
    void thePartyIsTakenUpToTheLeadersStage() {
        World world = new World();
        EventInstanceManager eim = mock(EventInstanceManager.class);
        MapleMap stage1 = world.map(eim, 103000800);
        MapleMap stage5 = world.map(eim, 103000804);
        Portal landing = mock(Portal.class);
        when(stage5.getPortal("st00")).thenReturn(landing);
        Character gm = world.character(1, "AqClient", stage5);
        Character sniperx = world.character(1001, "sniperx", stage1);
        Character candyyy = world.character(1002, "candyyy", stage1);
        when(eim.getPlayers()).thenReturn(List.of(gm, sniperx, candyyy));
        world.takesTheWarp(sniperx);
        world.takesTheWarp(candyyy);

        List<KpqStaging.Straggler> behind = KpqStaging.stragglers(eim, gm);
        assertEquals(List.of("sniperx", "candyyy"), behind.stream().map(s -> s.character().getName()).toList());
        for (KpqStaging.Straggler s : behind) {
            assertSame(stage5, s.to(), "the leader's own map of the instance, never a fresh copy of it");
            assertSame(landing, s.portal(), "where the gates land the leader");
        }

        List<KpqStaging.Straggler> left = KpqStaging.bringThePartyOn(eim, gm, world.lines::add);
        assertTrue(left.isEmpty(), "everyone is on the stage now");
        verify(sniperx).changeMap(stage5, landing);
        verify(candyyy).changeMap(stage5, landing);
        assertSame(stage5, sniperx.getMap());
        assertEquals(1, world.lines.size(), world.lines.toString());
        assertTrue(world.lines.get(0).contains("sniperx, candyyy"), world.lines.get(0));
        assertTrue(KpqStaging.stragglers(eim, gm).isEmpty());
    }

    @Test
    void aMemberTheWarpDoesNotTakeIsReportedForTheRefusal() {
        World world = new World();
        EventInstanceManager eim = mock(EventInstanceManager.class);
        MapleMap stage1 = world.map(eim, 103000800);
        MapleMap stage5 = world.map(eim, 103000804);
        Portal landing = mock(Portal.class);
        when(stage5.getPortal("st00")).thenReturn(landing);
        Character gm = world.character(1, "AqClient", stage5);
        Character sniperx = world.character(1001, "sniperx", stage1);
        Character candyyy = world.character(1002, "candyyy", stage1);
        when(eim.getPlayers()).thenReturn(List.of(gm, sniperx, candyyy));
        world.takesTheWarp(sniperx); // candyyy's warp does not take

        List<KpqStaging.Straggler> left = KpqStaging.bringThePartyOn(eim, gm, world.lines::add);
        assertEquals(List.of("candyyy"), left.stream().map(s -> s.character().getName()).toList());
        assertTrue(world.lines.isEmpty(), "no all-clear line when someone stayed behind: " + world.lines);
        verify(candyyy).changeMap(stage5, landing);
    }

    @Test
    void aPartyAlreadyWithTheLeaderIsLeftAlone() {
        World world = new World();
        EventInstanceManager eim = mock(EventInstanceManager.class);
        MapleMap stage1 = world.map(eim, 103000800);
        Character gm = world.character(1, "AqClient", stage1);
        Character sniperx = world.character(1001, "sniperx", stage1);
        when(eim.getPlayers()).thenReturn(List.of(gm, sniperx));

        assertTrue(KpqStaging.bringThePartyOn(eim, gm, world.lines::add).isEmpty());
        verify(sniperx, never()).changeMap(any(MapleMap.class), any(Portal.class));
        assertTrue(world.lines.isEmpty());
    }

    @Test
    void aLeaderOffTheInstancesOwnMapMovesNobody() {
        World world = new World();
        EventInstanceManager eim = mock(EventInstanceManager.class);
        EventInstanceManager other = mock(EventInstanceManager.class);
        MapleMap elsewhere = world.map(other, 103000804);
        MapleMap recruit = world.map(null, 103000000);
        Character gm = world.character(1, "AqClient", elsewhere);
        Character sniperx = world.character(1001, "sniperx", recruit);
        when(eim.getPlayers()).thenReturn(List.of(sniperx));

        assertTrue(KpqStaging.bringThePartyOn(eim, gm, world.lines::add).isEmpty(),
                "his map is another instance's: nothing to follow to");
        verify(sniperx, never()).changeMap(any(MapleMap.class), any(Portal.class));
    }
}
