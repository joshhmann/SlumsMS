package soloMapling.qa;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.npc.NPCConversationManager;
import scripting.npc.NPCScriptManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 163: an NPC conversation left open is a window: PlayerBusy holds the player off ("NPC dialog") and a trade offer
 * addressed to it is dropped. t-vb-6's job advance answers Balrog without disposing, and the next check on that lane
 * (t-mb-1) lost its megaphone answer 2 s after the claim (cap-4 run 2026-10-31, lane 2). closeWindows must end it.
 */
class GameQaWorldDialogTest {

    @Test
    void closeWindowsEndsAnOpenNpcConversation() {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        NPCConversationManager cm = mock(NPCConversationManager.class);
        when(chr.getClient()).thenReturn(client);
        when(client.getCM()).thenReturn(cm);
        NPCScriptManager manager = mock(NPCScriptManager.class);
        try (MockedStatic<NPCScriptManager> statics = mockStatic(NPCScriptManager.class)) {
            statics.when(NPCScriptManager::getInstance).thenReturn(manager);
            GameQaWorld.closeWindows(chr);
            verify(manager).dispose(client);
        }
    }

    @Test
    void closeWindowsWithNoConversationOpenDoesNothing() {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        when(chr.getClient()).thenReturn(client);
        when(client.getCM()).thenReturn(null);
        NPCScriptManager manager = mock(NPCScriptManager.class);
        try (MockedStatic<NPCScriptManager> statics = mockStatic(NPCScriptManager.class)) {
            statics.when(NPCScriptManager::getInstance).thenReturn(manager);
            GameQaWorld.closeWindows(chr);
            verify(manager, never()).dispose(org.mockito.ArgumentMatchers.any(Client.class));
        }
    }
}
