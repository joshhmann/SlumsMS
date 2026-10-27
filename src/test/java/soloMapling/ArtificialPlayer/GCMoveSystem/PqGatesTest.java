package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.0: bots skip portal scripts (shared BotClient), so a bot walked through a closed KPQ gate (kpq0..3 check
 * "&lt;N&gt;stageclear"). Inside an event instance a scripted portal is now passable only when its gate is open.
 */
class PqGatesTest {

    private static PqGates.Gate gate(String script) throws Exception {
        return PqGates.parse(Files.readString(Path.of("scripts", "portal", script + ".js")));
    }

    private static EventInstanceManager eim(String key, String value) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getProperty(key)).thenReturn(value);
        int asInt = value != null && value.matches("-?\\d+") ? Integer.parseInt(value) : 0;
        when(eim.getIntProperty(key)).thenReturn(asInt);
        return eim;
    }

    @Test
    void kpqAndLpqGatesOpenOnTheirStageClear() throws Exception {
        for (String s : new String[]{"kpq0", "kpq1", "kpq2", "kpq3", "kpq4", "lpq0", "lpq6"}) {
            PqGates.Gate g = gate(s);
            assertNotNull(g, s + " parses");
            assertFalse(g.open(eim(g.key(), null)), s + " shut before the clear");
            assertTrue(g.open(eim(g.key(), "true")), s + " open after the clear");
        }
        assertTrue(gate("kpq0").key().equals("1stageclear"));
        assertTrue(gate("lpq0").key().equals("1stageclear"), "via var avail = eim.getProperty(...)");
    }

    @Test
    void intStatusGatesFollowTheirScripts() throws Exception {
        PqGates.Gate jnr = gate("jnr1_pt00"); // statusStg1 == 1 -> warp
        assertFalse(jnr.open(eim("statusStg1", "0")));
        assertTrue(jnr.open(eim("statusStg1", "1")));
        PqGates.Gate glpq0 = gate("glpqPortal0"); // glpq1 == 0 -> blocked
        assertFalse(glpq0.open(eim("glpq1", "0")));
        assertTrue(glpq0.open(eim("glpq1", "1")));
        PqGates.Gate glpq1 = gate("glpqPortal1"); // inside an "eim != null" guard: glpq2 == 5 -> warp
        assertFalse(glpq1.open(eim("glpq2", "4")));
        assertTrue(glpq1.open(eim("glpq2", "5")));
    }

    @Test
    void scriptsWithSideEffectsOrOtherChecksAreNotGates() throws Exception {
        assertNull(gate("davy2_hd1"), "spawns the room's monsters first");
        assertNull(gate("davy_next4"), "reactor states");
        assertNull(gate("dojang_up"), "not an eim property");
    }

    private static Character botIn(EventInstanceManager eim) {
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(mock(MapleMap.class));
        when(bot.getEventInstance()).thenReturn(eim);
        return bot;
    }

    private static Portal portal(String script) {
        Portal p = mock(Portal.class);
        when(p.getScriptName()).thenReturn(script);
        when(p.getTargetMapId()).thenReturn(103000801);
        when(p.getName()).thenReturn("next00");
        return p;
    }

    @Test
    void aBotStopsAtAClosedKpqGateAndPassesOnceItOpens() {
        EventInstanceManager shut = eim("1stageclear", null);
        Character bot = botIn(shut);
        assertFalse(GCPortals.enter(bot, portal("kpq0")));
        verify(bot, never()).changeMap(any(MapleMap.class), any(Portal.class));
        verify(bot, never()).changeMap(anyInt());

        EventInstanceManager open = eim("1stageclear", "true");
        MapleMap stage2 = mock(MapleMap.class);
        when(stage2.getPortal(0)).thenReturn(mock(Portal.class));
        when(open.getMapInstance(103000801)).thenReturn(stage2);
        Character bot2 = botIn(open);
        assertTrue(GCPortals.enter(bot2, portal("kpq0")));
        verify(bot2).changeMap(any(MapleMap.class), any(Portal.class));
    }

    @Test
    void anUnknownScriptedPortalInAnEventStaysShut() {
        Character bot = botIn(mock(EventInstanceManager.class));
        assertFalse(GCPortals.enter(bot, portal("davy2_hd1")));
        verify(bot, never()).changeMap(anyInt());
    }
}
