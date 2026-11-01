package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.server.SoloMaplingUtilities;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Ticket 173, found by the b267 review: the three-arg {@code LifeRuntime.accepted} was {@code synchronized} and called
 * {@code friend(now)} inside the monitor. {@code friend} reads {@code PlayerStorage} (a fair read/write lock), so the
 * per-bot monitor was held over a PlayerStorage read: the same monitor-over-PlayerStorage edge that parked live
 * 1.6.0.4 for {@code Fads.step()} (b267). No cycle exists today (PlayerStorage never calls back into a LifeRuntime
 * monitor), so it is latent, not a live hang: this test is the second thread that would close the cycle.
 *
 * <p>Shape from {@link soloMapling.ArtificialPlayer.FadsLockOrderTest}: one thread holds PlayerStorage's write lock, a
 * second calls {@code accepted(HELP_FRIEND, ...)} and waits inside {@code friend()}, a third calls {@code prompt()}.
 * On the old code the third one parks on the bot's monitor behind the PlayerStorage read and never answers; now the
 * friend is read before the monitor is taken, so {@code prompt()} answers while the read still waits. The deadline is
 * 5 s so a loaded build machine cannot fail it; the old code never returns at all while the read is held.
 */
class LifeRuntimeLockOrderTest {

    private final String enabled = System.getProperty("slums.botlife.enabled");
    private final String execute = System.getProperty("slums.botlife.execute");
    private final String metrics = System.getProperty("slums.botlife.metrics");

    @AfterEach
    void restoreSwitches() {
        restore("enabled", enabled);
        restore("execute", execute);
        restore("metrics", metrics);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty("slums.botlife." + key);
        else System.setProperty("slums.botlife." + key, value);
    }

    /** Signals the moment a reader asks PlayerStorage for the friend: that is where the old {@code accepted} parked. */
    static final class SignallingStorage extends PlayerStorage {
        final CountDownLatch asked = new CountDownLatch(1);

        @Override
        public Character getCharacterById(int id) {
            asked.countDown();
            return super.getCharacterById(id);
        }
    }

    @Test
    void promptAnswersWhileTheFriendReadWaitsOnPlayerStorage() throws Exception {
        var bot = mock(TrainingBot.class);
        var chr = mock(Character.class);
        var map = mock(MapleMap.class);
        var player = mock(Character.class);
        when(bot.getChr()).thenReturn(chr);
        when(chr.getId()).thenReturn(1000005001);
        when(chr.getName()).thenReturn("LockOrderBot");
        when(chr.getMap()).thenReturn(map);
        when(player.getId()).thenReturn(42);
        when(player.getName()).thenReturn("friend42");
        when(player.getMap()).thenReturn(map);
        when(map.getChannelId()).thenReturn(1);

        var storage = new SignallingStorage();
        storage.addPlayer(player);
        var world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(storage);

        // The writer: addPlayer holds PlayerStorage's write lock while the new name is read, exactly as the game does.
        var holder = mock(Character.class);
        when(holder.getId()).thenReturn(777);
        var inWriteLock = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(holder.getName()).thenAnswer(c -> {
            inWriteLock.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "the test never released PlayerStorage's write lock");
            return "lockholder";
        });

        System.setProperty("slums.botlife.enabled", "true");
        System.setProperty("slums.botlife.execute", "true");
        System.setProperty("slums.botlife.metrics", "false");
        long now = System.currentTimeMillis();
        long acceptedBefore = LifeRuntime.metrics().get("accepted");
        var runtime = new LifeRuntime(bot);
        try (var affinity = mockStatic(BotAffinity.class)) {
            affinity.when(() -> BotAffinity.friend(anyInt(), anyInt())).thenReturn(true);
            assertTrue(runtime.friendAsk(player, false, now), "the friend ask is what accepted reads");
        }

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> writer = pool.submit(() -> storage.addPlayer(holder));
            assertTrue(inWriteLock.await(5, TimeUnit.SECONDS), "the writer never took PlayerStorage's write lock");

            Future<?> accepting = pool.submit(() -> {
                try (var utilities = mockStatic(SoloMaplingUtilities.class);
                     var affinity = mockStatic(BotAffinity.class)) {
                    utilities.when(SoloMaplingUtilities::world).thenReturn(world);
                    affinity.when(() -> BotAffinity.friend(anyInt(), anyInt())).thenReturn(true);
                    runtime.accepted(Activity.HELP_FRIEND, now, false);
                }
            });
            assertTrue(storage.asked.await(5, TimeUnit.SECONDS), "accepted never reached the PlayerStorage read");

            Future<String> asked = pool.submit(runtime::prompt);
            String answer;
            try {
                answer = asked.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException stuck) {
                throw new AssertionError("prompt() did not answer while PlayerStorage was held: accepted() held the "
                        + "bot's monitor inside friend() (ticket 173)", stuck);
            }
            assertTrue(answer.startsWith("\nDaily-life facts:"), "prompt() answered with the bot's facts: " + answer);
            assertFalse(writer.isDone(), "the write lock is still held while prompt() answers");

            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            accepting.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(acceptedBefore + 1, LifeRuntime.metrics().get("accepted"),
                "the activity is still counted, just outside the friend read");
    }
}
