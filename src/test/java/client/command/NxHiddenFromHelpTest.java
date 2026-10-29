package client.command;

import client.Character;
import client.Client;
import client.command.commands.gm0.NxCommand;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.ExchangeService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 1.6.1 promise audit: the NX exchange is held on live, so @help leaves @nx out; @nx itself still says "not open". */
class NxHiddenFromHelpTest {

    @Test
    void helpLeavesNxOutWhileTheExchangeIsClosedAndNxStillAnswers() {
        assertNull(ExchangeService.exchange(), "no exchange running in tests");
        var executor = CommandsExecutor.freshForTest();
        var common = executor.getGmCommands().get(0);
        assertFalse(common.getLeft().contains("nx"), common.getLeft().toString());
        assertTrue(common.getLeft().contains("help"));
        assertEquals(common.getLeft().size(), common.getRight().size(), "names and descriptions stay paired");
        assertEquals(0, executor.rankForTest("nx"), "still a player command");

        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        when(c.getPlayer()).thenReturn(chr);
        new NxCommand().execute(c, new String[0]);
        verify(chr).yellowMessage("The NX exchange is not open.");
    }
}
