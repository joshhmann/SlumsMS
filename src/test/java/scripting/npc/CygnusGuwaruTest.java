package scripting.npc;

import client.Client;
import client.Character;
import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;
import scripting.event.EventInstanceManager;
import soloMapling.WzTestData;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.8 card cygnus-fight-unreachable: migration 071 puts Guwaru's summoner 3003903 into the chamber 271040100, and
 * plife rows load into every MapleMap of that id, so the same NPC also stands in the base (non-instance) copy of the
 * room. Only an expedition run may spawn her: without an event instance the NPC says so and does nothing, and a
 * forged confirm after the run ended must not spawn her either.
 *
 * <p>The script runs against the real {@link NPCConversationManager}; only the player, the map, the expedition and
 * the instance are mocks.
 */
class CygnusGuwaruTest {
    private static final int SUMMONER = 3003903;
    private static final int CYGNUS_ROOM = 271040100;
    private static final int HER = 8850011;

    private static final class GuwaruCm extends NPCConversationManager {
        final Character player = mock(Character.class);
        final MapleMap map = mock(MapleMap.class);
        final Expedition expedition = mock(Expedition.class);
        String ok;
        String yesNo;
        boolean disposed;

        GuwaruCm() {
            super(mock(Client.class), SUMMONER, String.valueOf(SUMMONER));
            when(player.getMap()).thenReturn(map);
        }

        @Override
        public Character getPlayer() {
            return player;
        }

        @Override
        public Expedition getExpedition(ExpeditionType type) {
            return expedition;
        }

        @Override
        public void sendOk(String text) {
            ok = text;
        }

        @Override
        public void sendYesNo(String text) {
            yesNo = text;
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static Invocable load(GuwaruCm cm) {
        ScriptEngine engine = new Loader().load("npc/" + SUMMONER + ".js");
        assertNotNull(engine, "scripts/npc/" + SUMMONER + ".js did not evaluate");
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    @Test
    void inTheBaseRoomWithNoRunHeRefusesAndSpawnsNothing() throws Exception {
        GuwaruCm cm = new GuwaruCm();
        when(cm.player.getEventInstance()).thenReturn((EventInstanceManager) null);
        when(cm.expedition.isLeader(cm.player)).thenReturn(true);   // even a leader of a parked expedition

        load(cm).invokeFunction("start", new Object[0]);

        assertNotNull(cm.ok, "the player is told why");
        assertTrue(cm.ok.toLowerCase().contains("expedition"), "the refusal names the expedition: " + cm.ok);
        assertTrue(cm.disposed);
        verify(cm.map, never()).spawnMonsterOnGroundBelow(anyInt(), anyInt(), anyInt());
        verify(cm.map, never()).destroyNPC(anyInt());
    }

    @Test
    void insideARunTheLeaderIsAskedAndConfirmingSpawnsHer() throws Exception {
        GuwaruCm cm = new GuwaruCm();
        when(cm.player.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        when(cm.expedition.isLeader(cm.player)).thenReturn(true);

        Invocable script = load(cm);
        script.invokeFunction("start", new Object[0]);
        assertNotNull(cm.yesNo, "the leader gets the confirm prompt");
        verify(cm.map, never()).spawnMonsterOnGroundBelow(anyInt(), anyInt(), anyInt());

        script.invokeFunction("action", 1, 0, 0);

        verify(cm.map).spawnMonsterOnGroundBelow(HER, -104, 115);
        verify(cm.map).destroyNPC(SUMMONER);
        assertTrue(cm.disposed);
    }

    @Test
    void aNonLeaderCannotStartOrForgeTheSpawn() throws Exception {
        GuwaruCm cm = new GuwaruCm();
        when(cm.player.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        when(cm.expedition.isLeader(any())).thenReturn(false);

        Invocable script = load(cm);
        script.invokeFunction("start", new Object[0]);
        assertNotNull(cm.ok, "only the leader is told");
        script.invokeFunction("action", 1, 0, 0);

        verify(cm.map, never()).spawnMonsterOnGroundBelow(anyInt(), anyInt(), anyInt());
        verify(cm.map, never()).destroyNPC(anyInt());
    }

    @Test
    void aConfirmThatArrivesAfterTheRunEndedSpawnsNothing() throws Exception {
        GuwaruCm cm = new GuwaruCm();
        // the prompt is open inside a run; the run ends before the player answers
        when(cm.player.getEventInstance()).thenReturn(mock(EventInstanceManager.class)).thenReturn((EventInstanceManager) null);
        when(cm.expedition.isLeader(cm.player)).thenReturn(true);

        Invocable script = load(cm);
        script.invokeFunction("start", new Object[0]);
        script.invokeFunction("action", 1, 0, 0);

        verify(cm.map, never()).spawnMonsterOnGroundBelow(anyInt(), anyInt(), anyInt());
    }

    @Test
    void theRoomHasNoMobTheStrayKillCallCouldHit() throws Exception {
        WzTestData.assumePresent("Map.wz");
        String xml = Files.readString(WzTestData.dir().resolve("Map.wz/Map/Map2/" + CYGNUS_ROOM + ".img.xml"));
        // 3003903.js also calls killMonster(8840010), Von Leon's placeholder; nothing of that id stands in the room or
        // anywhere in its data, and MapleMap.killMonster only touches monsters already on the map, so it does nothing
        // here and stays as it was.
        assertFalse(xml.contains("8840010"), "8840010 would have to stand in this room for the call to matter");
        assertTrue(xml.contains("\"life\""), "the map data was read");
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
