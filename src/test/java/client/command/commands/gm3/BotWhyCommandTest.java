package client.command.commands.gm3;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import soloMapling.qa.QaSuite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotWhyCommandTest {
    @Test
    void previewRequiresBothQaAndExplicitFlag() {
        String old = System.getProperty("slums.botlife.preview");
        try (var qa = mockStatic(QaSuite.class)) {
            System.clearProperty("slums.botlife.preview");
            qa.when(QaSuite::isEnabled).thenReturn(true);
            assertFalse(BotWhyCommand.isEnabled());
            System.setProperty("slums.botlife.preview", "true");
            assertTrue(BotWhyCommand.isEnabled());
            qa.when(QaSuite::isEnabled).thenReturn(false);
            assertFalse(BotWhyCommand.isEnabled());
        } finally {
            if (old == null) System.clearProperty("slums.botlife.preview");
            else System.setProperty("slums.botlife.preview", old);
        }
    }

    @Test
    void disabledCommandDoesNotLookUpOrTouchBotsEvenIfInvokedDirectly() {
        Client client = mock(Client.class);
        Character gm = mock(Character.class);
        when(client.getPlayer()).thenReturn(gm);
        try (var qa = mockStatic(QaSuite.class)) {
            qa.when(QaSuite::isEnabled).thenReturn(false);
            new BotWhyCommand().execute(client, new String[]{"FixtureBot"});
            verify(client).getPlayer();
            verifyNoMoreInteractions(client);
            verify(gm).yellowMessage("Bot life preview needs QA and -Dslums.botlife.preview=true.");
        }
    }
}
