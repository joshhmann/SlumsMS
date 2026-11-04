package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActKind;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntent;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 181 stage 5: {@code BotCommitments.inFlight} has one more clause, a persisted promise falling due within two
 * minutes. That is what keeps {@code BotPopulationDirector.mayLogOut} (which asks it) from logging the bot out.
 */
class BotCommitmentsIntentsTest {

    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger(BotStore.BOT_ID_BASE + 71_000);
    private int ID, FRIEND; // a fresh pair per test: the memory store keeps what a test leaves in it
    private static final long MIN = TimeUnit.MINUTES.toMillis(1);
    private final AtomicLong clock = new AtomicLong(5_000_000_000L);

    @BeforeEach
    void setUp() {
        ID = NEXT.incrementAndGet();
        FRIEND = NEXT.incrementAndGet();
        System.clearProperty(SocialIntents.SWITCH);
        SocialSim.useForTest(clock::get, 1);
        SocialIntents.resetForTest();
        LifeMemoryStore.of(ID);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SocialIntents.SWITCH);
        SocialIntents.resetForTest();
        SocialSim.reset();
    }

    private Character chr() {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(ID);
        return c;
    }

    @Test
    void aPromiseFallingDueWithinTwoMinutesIsACommitmentAndEndingItReleasesTheBot() {
        Character c = chr();
        assertFalse(BotCommitments.inFlight(c, BotSM.BotState.RUNNING), "control: nothing promised");
        SocialIntent in = SocialIntents.promise(ID, FRIEND, SocialActKind.OFFER_HELP, 10 * MIN, 100000000, "talk:HELP",
                clock.get()).intent();
        assertFalse(BotCommitments.inFlight(c, BotSM.BotState.RUNNING), "ten minutes out");
        clock.addAndGet(8 * MIN + 1);
        assertTrue(BotCommitments.inFlight(c, BotSM.BotState.RUNNING), "inside the two minutes");
        assertFalse(BotCommitments.inFlight(c, BotSM.BotState.FINISHED), "a finished bot is never committed");
        SocialIntents.complete(ID, in.id(), clock.get());
        assertFalse(BotCommitments.inFlight(c, BotSM.BotState.RUNNING), "kept: released");
    }

    @Test
    void aBotWithoutAMemoryAndTheFlagOffAreNeverHeldByIntents() {
        Character stranger = mock(Character.class);
        when(stranger.getId()).thenReturn(BotStore.BOT_ID_BASE + 79_999);
        assertFalse(BotCommitments.inFlight(stranger, BotSM.BotState.RUNNING), "no loaded memory: nothing to read");
        SocialIntents.promise(ID, FRIEND, SocialActKind.INVITE, 0, 100000000, "talk:PARTY", clock.get());
        assertTrue(BotCommitments.inFlight(chr(), BotSM.BotState.RUNNING));
        System.setProperty(SocialIntents.SWITCH, "false");
        assertFalse(BotCommitments.inFlight(chr(), BotSM.BotState.RUNNING), "-Dslums.intents=false: no behaviour change");
    }
}
