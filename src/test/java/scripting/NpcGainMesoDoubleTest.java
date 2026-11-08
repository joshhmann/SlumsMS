package scripting;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import static org.mockito.Mockito.*;

/**
 * P0nk/Cosmic#228: a JS script can pass a non-integer Number to cm.gainMeso (e.g. {@code price * 1.5}). The Nashorn
 * binding picks the most specific overload and, without a Double one, the call fails and the NPC conversation stops
 * mid-way. The Double overload truncates toward zero, exactly like upstream's {@code gain.intValue()}.
 */
class NpcGainMesoDoubleTest {

    private final Character player = mock(Character.class);
    private final NPCConversationManager cm = new NPCConversationManager(mock(Client.class), 1012100, "1012100") {
        @Override
        public Character getPlayer() {
            return player;
        }
    };

    @Test
    void aFractionalGainIsTruncatedTowardZero() {
        cm.gainMeso(Double.valueOf(1500.9));
        verify(player).gainMeso(1500);

        cm.gainMeso(Double.valueOf(-250.7));
        verify(player).gainMeso(-250);
    }

    @Test
    void theIntegerPathIsUnchanged() {
        cm.gainMeso(1500);
        verify(player, times(1)).gainMeso(1500);
        verify(player, never()).gainMeso(1501);
    }
}
