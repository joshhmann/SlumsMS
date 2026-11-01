package soloMapling.mechanics;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.6.2 (b153): a run keeps the maps its bots stand on at full movement physics. On TEST (d4f103b0e) the bot-only
 * t-mx-1/t-mx-2 runs had no real player on the stage, so their bots moved by the coarse planner and stood still under
 * their Ligators for the whole budget.
 */
class MechanicsRunsPhysicsTest {

    private static Character on(MapleMap map) {
        Character c = mock(Character.class);
        when(c.getMap()).thenReturn(map);
        return c;
    }

    @Test
    void eachMapARunsBotsStandOnIsHeldOncePerTick() {
        MapleMap stage = mock(MapleMap.class);
        MapleMap other = mock(MapleMap.class);   // a bot already through the gate
        List<MapleMap> held = new ArrayList<>();
        MechanicsRuns.keepPhysics(Arrays.asList(on(stage), on(stage), on(other), on(null), null), held::add);
        assertEquals(2, held.size());
        assertSame(stage, held.get(0));
        assertSame(other, held.get(1));
    }
}
