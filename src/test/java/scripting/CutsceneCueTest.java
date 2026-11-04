package scripting;

import client.Client;
import client.Character;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.map.MapScriptMethods;
import scripting.npc.NPCConversationManager;
import scripting.portal.PortalPlayerInteraction;
import server.maps.Portal;
import tools.PacketCreator;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.4: the Cannoneer tutorial and the metro cutscene drive a client-side direction sequence through
 * cm/pi/ms.setDirectionStatus, lockUI2, sendDirectionInfo, setNPCValue and updateInfo, plus pi.showWZEffect on
 * metro_Chat00. None of the first six existed, so the first line of every one of those scripts - all of them open with
 * a cue - threw and the cutscene never said a word.
 *
 * <p>This server has no direction engine, so those six record and let the step continue (see the javadoc on
 * AbstractPlayerInteraction); the test pins both halves of that: the dialogue now reaches the next step, and lockUI2
 * still sends no UI lock that nothing in the script would release. showWZEffect is real work and is checked as the
 * FIELD_EFFECT packet the client paints.
 *
 * <p>The rest of that family - setDirection, startDirection, setDirectionMode and setStandAloneMode - is in the same
 * position: the tutorial map scripts open with one of them, so the same record-and-continue treatment applies here.
 * The other two scripts tested below are the ones that called a name the API never had (cm.removeNPC, pi.spawnNPC)
 * and so died before the cutscene did anything at all.
 */
class CutsceneCueTest {
    private static final String METRO_EFFECT = "Effect/Direction2.img/metro/Im";

    private static final class CueCm extends NPCConversationManager {
        final Client client;
        final Character player = mock(Character.class);
        final StringBuilder dialog = new StringBuilder();
        final List<String> removedNpcs = new ArrayList<>();
        boolean disposed;

        CueCm() {
            this(mock(Client.class));
        }

        CueCm(Client client) {
            super(client, 1096003, "1096003");
            this.client = client;
        }

        @Override
        public Character getPlayer() {
            return player;
        }

        @Override
        public void sendNext(String text) {
            dialog.append(text).append('\n');
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        @Override
        public int getMapId() {
            return 108010630;
        }

        @Override
        public void removeNpc(int mapId, int npcId) {
            removedNpcs.add(mapId + ":" + npcId);
        }

        @Override
        public void playSound(String sound) {
            // the fire cue plays off the player's map, and this mock player stands on none
        }
    }

    private static final class CueMs extends MapScriptMethods {
        final Character player = mock(Character.class);

        CueMs(Client client) {
            super(client);
        }

        @Override
        public Character getPlayer() {
            return player;
        }
    }

    private static final class CuePi extends PortalPlayerInteraction {
        final Client client;
        final Character player = mock(Character.class);
        final List<String> spawnedNpcs = new ArrayList<>();

        CuePi(Client client) {
            super(client, mock(Portal.class));
            this.client = client;
        }

        @Override
        public Character getPlayer() {
            return player;
        }

        @Override
        public void spawnNpc(int npcId, int x, int y) {
            spawnedNpcs.add(npcId + "@" + x + "," + y);
        }
    }

    private static Invocable load(String path, String binding, Object manager) {
        ScriptEngine engine = new Loader().load(path);
        assertNotNull(engine, path + " did not evaluate");
        engine.put(binding, manager);
        return (Invocable) engine;
    }

    @Test
    void theCannoneerIntroCueLetsTheDialogueStartAndUnlock() throws Exception {
        CueCm cm = new CueCm();
        Invocable script = load("npc/1096003.js", "cm", cm);

        script.invokeFunction("start");
        assertTrue(cm.dialog.toString().contains("Ook! Ook!"), cm.dialog.toString());
        assertFalse(cm.disposed);

        script.invokeFunction("action", 1, 0, 0);
        assertTrue(cm.disposed, "the second step still unlocks the UI and closes");
    }

    @Test
    void theRepeatedCueAlsoReachesTheNextStep() throws Exception {
        CueCm cm = new CueCm();
        Invocable script = load("npc/1096005.js", "cm", cm);

        script.invokeFunction("start");
        assertTrue(cm.dialog.toString().contains("All right! Let's go!"), cm.dialog.toString());
    }

    @Test
    void theMetroCutscenePaintsItsEffect() throws Exception {
        Client client = mock(Client.class);
        CuePi pi = new CuePi(client);

        assertEquals(Boolean.TRUE, load("portal/metro_Chat00.js", "pi", pi).invokeFunction("enter", pi));

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(sent.capture());
        List<Packet> packets = sent.getAllValues();
        assertTrue(packets.stream().anyMatch(p ->
                        Arrays.equals(p.getBytes(), PacketCreator.showEffect(METRO_EFFECT).getBytes())),
                "showWZEffect has to reach the client as a field effect");
    }

    @Test
    void lockUi2SendsNoLockThatNothingWouldRelease() {
        Client client = mock(Client.class);
        CueCm cm = new CueCm(client);

        cm.lockUI2();

        verify(client, never()).sendPacket(any());
    }

    @Test
    void theTutorialDirectionCuesRecordAndPaintNothing() throws Exception {
        for (String path : List.of("map/onUserEnter/cannon_tuto_01.js",
                "map/onUserEnter/Resi_tutor10.js",
                "map/onUserEnter/Resi_tutor80.js")) {
            Client client = mock(Client.class);
            CueMs ms = new CueMs(client);

            load(path, "ms", ms).invokeFunction("start", ms);

            verify(client, never()).sendPacket(any());
        }
    }

    @Test
    void theRepeatedCueTakesItsNpcOffTheMapTheScriptNames() throws Exception {
        CueCm cm = new CueCm();
        Invocable script = load("npc/1096005.js", "cm", cm);

        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);

        assertEquals(List.of("108010630:579711"), cm.removedNpcs,
                "the tutorial NPC has to come off the map it was placed on");
        assertFalse(cm.disposed);
    }

    @Test
    void theCannoneerTutorialPortalSpawnsItsNpcWhereTheScriptSays() throws Exception {
        Client client = mock(Client.class);
        CuePi pi = new CuePi(client);

        assertEquals(Boolean.TRUE, load("portal/cannon_tuto_07.js", "pi", pi).invokeFunction("enter", pi));

        assertEquals(List.of("579711@-51,-97"), pi.spawnedNpcs);
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
