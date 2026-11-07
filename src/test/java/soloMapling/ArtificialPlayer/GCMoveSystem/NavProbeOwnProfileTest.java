package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.6 b366: a route verdict for a regular comes from the graph of its own movement profile. TEST 7 Nov: a hasted alt
 * (speed 140) was judged by the closest graph built - the base one - told "no route" to a shelf its own jump reached,
 * and the stage was given up. With the exact graph not built yet, the probe answers "pending" (no verdict) and starts
 * building that graph; it never falls back to another profile's.
 */
class NavProbeOwnProfileTest {

    private final java.util.function.BiFunction<MapleMap, BotMovementProfile, BotNavigationGraph> exact =
            NavProbe.exactGraph;
    private final java.util.function.BiConsumer<MapleMap, BotMovementProfile> warm = NavProbe.warmGraph;

    @AfterEach
    void restore() {
        NavProbe.exactGraph = exact;
        NavProbe.warmGraph = warm;
    }

    @Test
    void withNoGraphForItsOwnProfileTheProbeIsPendingAndBuildsThatOne() {
        MapleMap map = mock(MapleMap.class);
        when(map.isSwim()).thenReturn(false);
        when(map.getId()).thenReturn(280_010_110);
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(1812, 135));
        List<BotMovementProfile> asked = new ArrayList<>();
        List<BotMovementProfile> warmed = new ArrayList<>();
        NavProbe.exactGraph = (m, p) -> {
            asked.add(p);
            return null; // the hasted profile's graph is not built yet; only the base one would be
        };
        NavProbe.warmGraph = (m, p) -> warmed.add(p);

        NavProbe.Result r = NavProbe.probe(bot, -511, 142);

        assertFalse(r.reachable());
        assertTrue(NavProbe.pending(r.reason()), "no graph of its own: pending, not a verdict - " + r.reason());
        assertEquals(asked, warmed, "the profile it was judged by is the one being built");
        assertEquals(1, warmed.size());
    }

    @Test
    void pendingIsNotANoRouteVerdict() {
        assertTrue(NavProbe.pending(NavProbe.PENDING + " (profile)"));
        assertFalse(NavProbe.pending("no route from r8 to r5 walking"));
        assertFalse(NavProbe.pending(null));
    }
}
