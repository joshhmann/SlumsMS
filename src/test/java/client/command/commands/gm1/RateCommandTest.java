package client.command.commands.gm1;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Ticket 59 (review 2026-10-22): !rate does its file IO off the command thread and answers when done. */
class RateCommandTest {

    @AfterEach
    void reset() {
        RateCommand.executor = soloMapling.server.ExecutorServiceManager::runAsync;
    }

    @Test
    void theWorkIsQueuedAndTheReplyComesWhenItRuns() {
        List<Runnable> queued = new ArrayList<>();
        RateCommand.executor = queued::add;
        Character player = spy(Character.getDefault(new BotClient(0, 0)));
        player.setID(4_100);
        List<String> said = new CopyOnWriteArrayList<>();
        doAnswer(c -> said.add(c.getArgument(0))).when(player).yellowMessage(anyString());
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(player);

        new RateCommand().execute(client, new String[]{"bad", "too", "formal"});
        assertEquals(1, queued.size(), "nothing ran on the command thread");
        assertTrue(said.isEmpty());

        queued.get(0).run();
        assertEquals(1, said.size());
        assertTrue(said.get(0).startsWith("No bot line"), said.toString());
    }
}
