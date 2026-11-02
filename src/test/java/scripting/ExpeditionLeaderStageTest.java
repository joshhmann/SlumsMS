package scripting;

import client.Client;
import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;
import server.maps.MapleMap;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.4: the expedition leader's last stage (scripts/npc/9000004.js) asked the scripting API for three things under
 * names it does not have - cm.mapMobCount() (the manager spells it countMonster()), cm.clear() (the map spells it
 * clearMapObjects()) and cm.warpMembers(map, members) (no such method at all). The stage therefore died at the first
 * of them, with the mob count already read and the stage property already written.
 *
 * <p>The script runs against the real {@link NPCConversationManager}: only the player, the map, the party and the
 * event instance are mocks, so a name the server does not define is the same error it was in production.
 */
class ExpeditionLeaderStageTest {
    private static final int NEXT_STAGE = 109020001;

    private static final class LeaderCm extends NPCConversationManager {
        final Character player = mock(Character.class);
        final MapleMap map = mock(MapleMap.class);
        final Party party = mock(Party.class);
        final List<Character> members = new ArrayList<>();
        final StringBuilder dialog = new StringBuilder();
        boolean disposed;

        LeaderCm() {
            this(mock(Client.class));
        }

        LeaderCm(Client client) {
            super(client, 9000004, "9000004");
            when(player.getMap()).thenReturn(map);
            when(client.getPlayer()).thenReturn(player); // cm.getMap() reads the client's player
        }

        @Override
        public Character getPlayer() {
            return player;
        }

        @Override
        public Party getParty() {
            return party;
        }

        @Override
        public boolean isLeader() {
            return true;
        }

        @Override
        public int countMonster() {
            return 0;
        }

        @Override
        public void givePartyExp(int amount, List<Character> party) {
            dialog.append("exp ").append(amount).append('\n');
        }

        @Override
        public void sendOk(String text) {
            dialog.append(text).append('\n');
        }

        @Override
        public void sendYesNo(String text) {
            dialog.append(text).append('\n');
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static Invocable load(LeaderCm cm, MapleMap nextStage) {
        ScriptEngine engine = new Loader().load("npc/9000004.js");
        assertNotNull(engine, "scripts/npc/9000004.js did not evaluate");
        scripting.event.EventInstanceManager eim = mock(scripting.event.EventInstanceManager.class);
        when(cm.player.getEventInstance()).thenReturn(eim);
        when(eim.getPlayers()).thenReturn(cm.members);
        when(eim.getProperty(anyString())).thenReturn("done");
        when(eim.getMapInstance(NEXT_STAGE)).thenReturn(nextStage);
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    /** start() -> runthrough of the leader's four steps up to "you may continue to the next stage". */
    private static void finishTheStage(Invocable script) throws Exception {
        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);
        script.invokeFunction("action", 1, 0, 0);
        script.invokeFunction("action", 1, 0, 0);
        script.invokeFunction("action", 1, 0, 0);
    }

    @Test
    void theStageEndsByClearingTheMapAndMovingTheEventMembersOn() throws Exception {
        LeaderCm cm = new LeaderCm();
        MapleMap nextStage = mock(MapleMap.class);
        Character inTheStage = mock(Character.class);
        Character alreadyThere = mock(Character.class);
        when(alreadyThere.getMap()).thenReturn(nextStage);
        cm.members.add(inTheStage);
        cm.members.add(alreadyThere);

        finishTheStage(load(cm, nextStage));

        verify(cm.map).clearMapObjects();
        verify(inTheStage).changeMap(nextStage, 0);
        verify(alreadyThere, never()).changeMap(any(), anyInt());
        assertTrue(cm.disposed);
        assertTrue(cm.dialog.toString().contains("exp 2500"), cm.dialog.toString());
    }

    @Test
    void theMobCountStepStillAsksTheMapsMonsters() throws Exception {
        LeaderCm cm = new LeaderCm();
        Invocable script = load(cm, mock(MapleMap.class));

        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);
        script.invokeFunction("action", 1, 0, 0);

        assertTrue(cm.dialog.toString().contains("Good job"), cm.dialog.toString());
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
