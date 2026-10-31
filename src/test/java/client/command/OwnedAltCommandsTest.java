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
        var service = inline(mock(OwnedAltService.class));
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
        var service = inline(mock(OwnedAltService.class)); when(service.summon(owner,"Second")).thenReturn(alt);
        when(service.dismiss(owner,"Second")).thenReturn(true);
        try (MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            services.when(OwnedAltService::getInstance).thenReturn(service);
            new BotMeCommand().execute(c, new String[]{"Second"}); verify(owner).yellowMessage("Second has joined you and follows you. A party was created for you. Changing channel sends your alts home.");
            new DismissCommand().execute(c, new String[]{"Second"}); verify(owner).yellowMessage("Second has been saved and dismissed.");
            when(service.summon(owner,"Second")).thenThrow(new IllegalStateException("Alt companions are not available yet."));
            new BotMeCommand().execute(c, new String[]{"Second"}); verify(owner).yellowMessage("Alt companions are not available yet.");
        }
    }

    // TEST 10-30: the tester typed a bare @dismiss, got only the usage line, and the alt stayed out and in the party.
    @Test void bareDismissSendsTheOnlyAltHomeOrAsksWhichOne() {
        Client c = mock(Client.class); Character owner = mock(Character.class); when(c.getPlayer()).thenReturn(owner);
        var service = inline(mock(OwnedAltService.class));
        when(service.dismiss(owner, "Second")).thenReturn(true);
        try (MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            services.when(OwnedAltService::getInstance).thenReturn(service);
            when(service.outNames(owner)).thenReturn(List.of("Second"));
            new DismissCommand().execute(c, new String[0]);
            verify(service).dismiss(owner, "Second");
            verify(owner).yellowMessage("Second has been saved and dismissed.");
            when(service.outNames(owner)).thenReturn(List.of("Second", "Third"));
            new DismissCommand().execute(c, new String[0]);
            verify(owner).yellowMessage("Which one? @dismiss <name> or @dismiss all. Out with you: Second, Third.");
            when(service.outNames(owner)).thenReturn(List.of());
            new DismissCommand().execute(c, new String[0]);
            verify(owner).yellowMessage("You have no alts out. Usage: @dismiss <name> | all");
            verify(service, times(1)).dismiss(any(), any());
        }
    }
    static OwnedAltService inline(OwnedAltService service) {
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(service).runAsync(any());
        when(service.summoningOpen()).thenReturn(true); // 1.6.0.4: OWNED_ALTS_SUMMON_ENABLED on
        return service;
    }
    // 1.6.0.4 (M1): the database work runs off the packet thread; nothing is said until it has run
    @Test void summonAndDismissRunOffThePacketThread() {
        Client c = mock(Client.class); Character owner = mock(Character.class); when(c.getPlayer()).thenReturn(owner);
        var service = mock(OwnedAltService.class);
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        doAnswer(inv -> queued.add(inv.getArgument(0))).when(service).runAsync(any());
        when(service.summoningOpen()).thenReturn(true);
        Character alt = mock(Character.class); when(alt.getName()).thenReturn("Second");
        when(service.summon(owner, "Second")).thenReturn(alt); when(service.dismiss(owner, "Second")).thenReturn(true);
        try (MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            services.when(OwnedAltService::getInstance).thenReturn(service);
            new BotMeCommand().execute(c, new String[]{"Second"});
            new DismissCommand().execute(c, new String[]{"Second"});
            verify(service, never()).summon(any(), any()); verify(service, never()).dismiss(any(), any());
            verifyNoInteractions(owner);
            queued.forEach(Runnable::run);
            verify(service).summon(owner, "Second"); verify(service).dismiss(owner, "Second");
            verify(owner).yellowMessage("Second has been saved and dismissed.");
        }
    }
    @Test void botmeHereAndClearerDismissLines() {
        Client c = mock(Client.class); Character owner = mock(Character.class); when(c.getPlayer()).thenReturn(owner);
        var service = inline(mock(OwnedAltService.class));
        when(service.bringHere(owner)).thenReturn(List.of("Second", "Third"));
        when(service.isBotFriend(owner, "Buddy")).thenReturn(true);
        try (MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            services.when(OwnedAltService::getInstance).thenReturn(service);
            new BotMeCommand().execute(c, new String[]{"here"});
            verify(owner).yellowMessage("Second, Third are here with you.");
            new DismissCommand().execute(c, new String[]{"Buddy"});
            verify(owner).yellowMessage("Buddy is a bot friend, not one of your alts. Expel it from your party to send it off.");
            new DismissCommand().execute(c, new String[]{"Nobody"});
            verify(owner).yellowMessage("That character is not with you. @dismiss works on alts you called with @botme.");
            when(service.bringHere(owner)).thenThrow(new IllegalStateException("Your alts can't join you inside an instance. Call them before you enter."));
            new BotMeCommand().execute(c, new String[]{"here"});
            verify(owner).yellowMessage("Your alts can't join you inside an instance. Call them before you enter.");
        }
    }
    @Test void summoningIntoAnExistingPartySaysNothingAboutCreatingOne() {
        Client c = mock(Client.class); Character owner = mock(Character.class), alt = mock(Character.class);
        when(c.getPlayer()).thenReturn(owner); when(alt.getName()).thenReturn("Second");
        when(owner.getParty()).thenReturn(mock(net.server.world.Party.class));
        var service = inline(mock(OwnedAltService.class)); when(service.summon(owner, "Second")).thenReturn(alt);
        try (MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            services.when(OwnedAltService::getInstance).thenReturn(service);
            new BotMeCommand().execute(c, new String[]{"Second"});
            verify(owner).yellowMessage("Second has joined you and follows you. Changing channel sends your alts home.");
        }
    }
}
