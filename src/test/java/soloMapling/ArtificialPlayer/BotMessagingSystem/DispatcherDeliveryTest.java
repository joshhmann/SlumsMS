package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * sweep#R1 (ticket 181): the dispatcher took a line off the primary queue before delivering it, and a delivery that
 * threw was printed and swallowed - the player's line was gone and no bot ever saw it. A failed line now goes back
 * on the queue once and is tried again on the next pass; a line that fails twice is dropped with a log entry, so one
 * bad line cannot loop forever.
 */
class DispatcherDeliveryTest {

    private static int nextId = 8101;

    private static ChatMessage line(String text) {
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(nextId++);
        player.setName("Josh" + nextId);
        return new ChatMessage(player, text);
    }

    @Test
    void aDispatchFailureDoesNotLoseThePlayersLine() {
        MessageQueue queue = new MessageQueue();
        ChatMessage line = line("hey, anyone selling ilbis?");
        queue.addMessage("primary", line);
        AtomicInteger calls = new AtomicInteger();
        List<ChatMessage> delivered = new ArrayList<>();
        Consumer<ChatMessage> flaky = message -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("the map changed under the dispatch");
            }
            delivered.add(message);
        };

        Dispatcher.drain(queue, flaky);
        assertSame(line, queue.peekMessage("primary"), "the line that failed is still waiting");
        assertEquals(1, calls.get(), "the retry waits for the next pass");
        assertTrue(delivered.isEmpty());

        Dispatcher.drain(queue, flaky);
        assertEquals(List.of(line), delivered, "the next pass delivers it");
        assertNull(queue.peekMessage("primary"));
    }

    @Test
    void aLineThatFailsTwiceIsDroppedSoItCannotLoopForever() {
        MessageQueue queue = new MessageQueue();
        queue.addMessage("primary", line("this line always breaks the dispatch"));
        AtomicInteger calls = new AtomicInteger();
        Consumer<ChatMessage> alwaysFails = message -> {
            calls.incrementAndGet();
            throw new IllegalStateException("broken for good");
        };

        Dispatcher.drain(queue, alwaysFails);
        assertEquals(1, queue.getQueue("primary").size(), "kept for one retry");
        Dispatcher.drain(queue, alwaysFails);
        assertEquals(2, calls.get(), "one retry, no more");
        assertNull(queue.peekMessage("primary"), "dropped after the retry fails");
        Dispatcher.drain(queue, alwaysFails);
        assertEquals(2, calls.get(), "nothing left to try");
    }

    @Test
    void aFailingLineDoesNotHoldBackTheLinesBehindIt() {
        MessageQueue queue = new MessageQueue();
        ChatMessage bad = line("breaks");
        ChatMessage first = line("first fine line");
        ChatMessage second = line("second fine line");
        queue.addMessage("primary", bad);
        queue.addMessage("primary", first);
        queue.addMessage("primary", second);
        List<ChatMessage> delivered = new ArrayList<>();

        Dispatcher.drain(queue, message -> {
            if (message == bad) {
                throw new IllegalStateException("only this one breaks");
            }
            delivered.add(message);
        });

        assertEquals(List.of(first, second), delivered, "the rest of the pass still goes through");
        assertSame(bad, queue.peekMessage("primary"), "and the failed one waits for its retry");
    }
}
