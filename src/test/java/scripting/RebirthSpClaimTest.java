package scripting;

import client.Character;
import client.Client;
import client.Job;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** 1.5.2: the rebirth helper's "Get 100 SP" must be once per rebirth, not unlimited. */
class RebirthSpClaimTest {

    private static NPCConversationManager helper(Character chr, Map<Integer, String> record) {
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        NPCConversationManager cm = spy(new NPCConversationManager(c, 9977778, "9977778"));
        doAnswer(i -> record.getOrDefault(i.getArgument(0), "")).when(cm).getQuestProgress(anyInt());
        doAnswer(i -> {
            record.put(i.getArgument(0), String.valueOf((int) i.getArgument(1)));
            return null;
        }).when(cm).setQuestProgress(anyInt(), anyInt());
        return cm;
    }

    private static Character player(int rebirths) {
        Character chr = mock(Character.class);
        when(chr.getReborns()).thenReturn(rebirths);
        when(chr.getJob()).thenReturn(Job.WARRIOR);
        return chr;
    }

    @Test
    void noRebirthsGetsNothing() {
        Character chr = player(0);
        assertEquals(-1, helper(chr, new HashMap<>()).claimRebirthSp());
        verify(chr, never()).gainSp(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void oneClaimPerRebirthAndTheRecordSurvivesANewManager() {
        Character chr = player(2);
        Map<Integer, String> record = new HashMap<>();
        assertEquals(100, helper(chr, record).claimRebirthSp());
        assertEquals(100, helper(chr, record).claimRebirthSp()); // a fresh conversation, same stored record
        assertEquals(0, helper(chr, record).claimRebirthSp());
        verify(chr, times(2)).gainSp(eq(100), anyInt(), eq(false));

        when(chr.getReborns()).thenReturn(3); // rebirthed again
        assertEquals(100, helper(chr, record).claimRebirthSp());
        assertEquals(0, helper(chr, record).claimRebirthSp());
    }
}
