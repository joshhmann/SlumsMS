package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * b272: a bot login reads its store row, holdings, buddy list, guild and romance, and the bots' share of the connection
 * pool refuses a virtual thread instead of letting it wait. The spawn waves and the GM staging commands start their
 * spawns on virtual threads, so at 3,000 bots the login ran there and every one of those reads was refused.
 */
class BotGenerationLoginThreadTest {

    @Test
    void aLoginAskedForFromAVirtualThreadRunsItsReadsOnAPlatformThread() throws Exception {
        int slot = 1_900;
        List<Boolean> loginThreadWasVirtual = new CopyOnWriteArrayList<>();
        BotGeneration.loginThreadForTest = loginThreadWasVirtual::add;
        try {
            Thread.ofVirtual().start(() -> {
                try {
                    BotGeneration.createBot(slot, null, null); // no server here: the login body is all this needs
                } catch (RuntimeException | Error ignored) {
                    // whatever the body does after its first read is not this test's business
                }
            }).join();
        } finally {
            BotGeneration.loginThreadForTest = null;
        }

        assertEquals(List.of(false), loginThreadWasVirtual,
                "the login body ran once, on a platform thread that may wait for the bots' share");
    }
}
