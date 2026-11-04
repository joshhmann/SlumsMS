package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialTestKit.FakeFlows;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialTestKit.FakeWorld;

import java.util.concurrent.atomic.AtomicLong;

/** The stage 4 test environment: manual clock and seed, in-memory bonds, a recording world and recording flows. */
abstract class SocialActTestBase {

    static final int ALICE = SocialTestKit.BASE + 1, BOB = SocialTestKit.BASE + 2, CLARA = SocialTestKit.BASE + 3;
    static final long T0 = 100L * 86_400_000L;

    final AtomicLong clock = new AtomicLong(T0);
    FakeWorld world;
    FakeFlows flows;

    @BeforeEach
    void setUpSocial() {
        SocialSim.useForTest(clock::get, 11L);
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get, id -> new double[]{.25, .25},
                (id, p) -> { }, id -> null);
        LifeMemoryStore.useConnectionSourceForTest(null);
        SocialBudget.resetForTest();
        SocialMetrics.resetActCountersForTest();
        SocialDecider.resetForTest();
        world = new FakeWorld(clock::get);
        flows = new FakeFlows(world);
        SocialActions.useForTest(world, flows, null);
        System.clearProperty(SocialActions.SWITCH);
    }

    @AfterEach
    void tearDownSocial() {
        SocialActions.useForTest(null, null, null);
        SocialSim.reset();
        BotBonds.useForTest(null, null, null, null, null, null);
        LifeMemoryStore.useConnectionSourceForTest(null);
        SocialBudget.resetForTest();
        SocialMetrics.resetActCountersForTest();
        System.clearProperty(SocialActions.SWITCH);
    }

    /** The bot's memory is loaded (so its mind feels things); returns it. */
    static soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory mem(int id) {
        return LifeMemoryStore.of(id);
    }

    /**
     * A seed under which {@code bot}'s first draw for {@code purpose} is under 0.05 ({@code low}) or over 0.995: a
     * deterministic way to make a chance-based outcome (the target taking an apology) go one way or the other.
     */
    long seedWith(int bot, String purpose, boolean low) {
        for (long seed = 1; seed < 20_000; seed++) {
            SocialSim.useForTest(clock::get, seed);
            double d = SocialSim.random(bot, purpose).nextDouble();
            if (low ? d < 0.05 : d > 0.995) {
                SocialSim.useForTest(clock::get, seed); // the stream starts again for the act under test
                return seed;
            }
        }
        throw new AssertionError("no seed found");
    }

    void tick(long ms) {
        clock.addAndGet(ms);
    }

    long now() {
        return clock.get();
    }
}
