package client.command;

import client.Character;
import client.Client;
import client.command.commands.gm4.EconStatsCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class EconStatsCommandTest {

    @Test
    void econstatsCommandIsRegisteredAtRank4() {
        CommandsExecutor ex = CommandsExecutor.freshForTest();
        assertEquals(EconStatsCommand.class, ex.classForTest("econstats"));
        assertEquals(4, ex.rankForTest("econstats"));
    }

    @Test
    void executeSendsStartingMessage() {
        Client c = mock(Client.class);
        Character player = mock(Character.class);
        when(c.getPlayer()).thenReturn(player);

        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        var prev = EconStatsCommand.setRunnerForTest(queued::add);   // never run a real snapshot in a unit test
        try {
            EconStatsCommand cmd = new EconStatsCommand();
            cmd.execute(c, new String[]{});
        } finally {
            EconStatsCommand.setRunnerForTest(prev);
        }

        verify(player).yellowMessage("Starting economy stats snapshot...");
        assertEquals(1, queued.size());
    }
}
