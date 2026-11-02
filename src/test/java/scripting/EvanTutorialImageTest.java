package scripting;

import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.portal.PortalPlayerInteraction;
import server.maps.Portal;
import tools.PacketCreator;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.4: the Evan tutorial shows an illustration before each lesson - the quest scripts ask for it with qm.sendImage
 * ("UI/tutorial/evan/N/0") and the two Evan portals with pi.sendImage. No scripting manager had that method, so the
 * very first step of the tutorial died before the guide arrow appeared.
 *
 * <p>{@link PortalPlayerInteraction} is real here, so sendImage has to be a real method; only the portal plumbing
 * around it is stubbed.
 */
class EvanTutorialImageTest {
    private static final String ROOM_ONE_IMAGE = "UI/tutorial/evan/0/0";
    private static final String EGG_IMAGE = "UI/tutorial/evan/8/0";

    private static final class EvanPi extends PortalPlayerInteraction {
        final Client client;
        final StringBuilder calls = new StringBuilder();

        EvanPi(Client client) {
            super(client, mock(Portal.class));
            this.client = client;
        }

        @Override
        public void blockPortal() {
            calls.append("block ");
        }

        @Override
        public boolean containsAreaInfo(short area, String info) {
            return false;
        }

        @Override
        public void updateAreaInfo(Short area, String info) {
            calls.append("area ");
        }

        @Override
        public void showIntro(String path) {
            calls.append("intro ");
        }
    }

    private static Invocable load(String path, EvanPi pi) {
        ScriptEngine engine = new Loader().load(path);
        assertNotNull(engine, path + " did not evaluate");
        engine.put("pi", pi);
        return (Invocable) engine;
    }

    private static boolean sentTheImage(Client client, String image) {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(sent.capture());
        return sent.getAllValues().stream()
                .anyMatch(p -> Arrays.equals(p.getBytes(), PacketCreator.showInfo(image).getBytes()));
    }

    @Test
    void evanRoomOneShowsItsTutorialImage() throws Exception {
        Client client = mock(Client.class);
        EvanPi pi = new EvanPi(client);

        assertEquals(Boolean.TRUE, load("portal/evanRoom1.js", pi).invokeFunction("enter", pi));
        assertTrue(sentTheImage(client, ROOM_ONE_IMAGE), "the tutorial image has to reach the client");
        assertTrue(pi.calls.toString().contains("area"), "the step also records the area info");
        assertTrue(pi.calls.toString().contains("intro"), "and the guide balloon still follows it");
    }

    @Test
    void theDragonEggNoticeShowsItsOwnImage() throws Exception {
        Client client = mock(Client.class);
        EvanPi pi = new EvanPi(client);

        assertEquals(Boolean.TRUE, load("portal/DragonEggNotice.js", pi).invokeFunction("enter", pi));
        assertTrue(sentTheImage(client, EGG_IMAGE));
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
