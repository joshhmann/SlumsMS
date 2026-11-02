package scripting;

import client.Client;
import client.Character;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.npc.NPCConversationManager;
import scripting.portal.PortalPlayerInteraction;
import server.maps.Portal;
import tools.PacketCreator;

import javax.script.Invocable;
import javax.script.ScriptEngine;
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
 */
class CutsceneCueTest {
    private static final String METRO_EFFECT = "Effect/Direction2.img/metro/Im";

    private static final class CueCm extends NPCConversationManager {
        final Client client;
        final Character player = mock(Character.class);
        final StringBuilder dialog = new StringBuilder();
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
    }

    private static final class CuePi extends PortalPlayerInteraction {
        final Client client;

        CuePi(Client client) {
            super(client, mock(Portal.class));
            this.client = client;
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

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
