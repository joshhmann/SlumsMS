package soloMapling.qa;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Ticket 59 (review 2026-10-22): {@code !qa do sense} never waits on the Decide HTTP call on the command thread. */
class GameQaWorldSenseTest {

    @AfterEach
    void reset() {
        ChatDecide.useForTest(null);
    }

    @Test
    void senseAnswersAtOnceAndTheReadFollows() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ChatDecide.useForTest(new ChatDecide((body, ms) -> {
            release.await(5, TimeUnit.SECONDS);
            return "{\"answers\": {}}";
        }, 4000, 100));
        Character gm = spy(Character.getDefault(new BotClient(0, 0)));
        gm.setID(4_200);
        List<String> said = new CopyOnWriteArrayList<>();
        CountDownLatch answered = new CountDownLatch(1);
        doAnswer(c -> {
            said.add(c.getArgument(0));
            answered.countDown();
            return null;
        }).when(gm).yellowMessage(anyString());

        long t = System.nanoTime();
        List<String> out = new GameQaWorld().convoAction(gm, "sense", List.of(), new String[]{"hmm", "whatever", "man"});
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t) < 1000, "did not wait on Decide");
        assertTrue(out.get(0).startsWith("reading"), out.toString());
        release.countDown();
        assertTrue(answered.await(10, TimeUnit.SECONDS));
        assertEquals(1, said.size());
        assertTrue(said.get(0).contains("intent="), said.toString());
    }
}
