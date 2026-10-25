package client.command;

import client.Character;
import client.Client;
import client.command.commands.gm0.BotMeCommand;
import client.command.commands.gm0.DismissCommand;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.companions.OwnedAltService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedAltCommandsTest {
    @Test void registersPlayerCommandsAndListsEligibleAltsWithoutPublicChat() {
        var executor = CommandsExecutor.freshForTest();
        assertEquals(0, executor.rankForTest("botme")); assertEquals(0, executor.rankForTest("dismiss"));
        Client c = mock(Client.class); Character owner = mock(Character.class); when(c.getPlayer()).thenReturn(owner);
        var service = mock(OwnedAltService.class);
        when(service.list(owner)).thenReturn(List.of(new OwnedAltService.Candidate(2,7,1,"Second")));
        try (MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            services.when(OwnedAltService::getInstance).thenReturn(service);
            new BotMeCommand().execute(c, new String[0]);
            verify(owner).yellowMessage("Available characters: Second. Use @botme <name> to call one.");
            new DismissCommand().execute(c, new String[]{"all"});
            verify(service).dismissAll(owner, false);
        }
    }
    @Test void summonsDismissesAndReportsGatePrivately() {
        Client c = mock(Client.class); Character owner = mock(Character.class), alt = mock(Character.class);
        when(c.getPlayer()).thenReturn(owner); when(alt.getName()).thenReturn("Second");
        var service = mock(OwnedAltService.class); when(service.summon(owner,"Second")).thenReturn(alt);
        when(service.dismiss(owner,"Second")).thenReturn(true);
        try (MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            services.when(OwnedAltService::getInstance).thenReturn(service);
            new BotMeCommand().execute(c, new String[]{"Second"}); verify(owner).yellowMessage("Second has joined you.");
            new DismissCommand().execute(c, new String[]{"Second"}); verify(owner).yellowMessage("Second has been saved and dismissed.");
            when(service.summon(owner,"Second")).thenThrow(new IllegalStateException("Alt companions are not available yet."));
            new BotMeCommand().execute(c, new String[]{"Second"}); verify(owner).yellowMessage("Alt companions are not available yet.");
        }
    }
}
