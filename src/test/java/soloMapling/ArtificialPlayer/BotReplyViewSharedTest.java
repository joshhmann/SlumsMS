package soloMapling.ArtificialPlayer;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotConvo.BotReplyView;
import soloMapling.ArtificialPlayer.BotConvo.ReplyPlanner;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owner's answer 8: a greeting uses what is true now, and that includes what these two did together. Until m02 the
 * view always carried a null {@code shared}, so the greeting could never come back to a party or a rescue that memory
 * had kept.
 */
class BotReplyViewSharedTest {

    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final int SLOT = 7;
    private static final int PLAYER = 1001;

    private final FakeMemoryTable table = new FakeMemoryTable(false);
    private final AtomicLong clock = new AtomicLong(100 * DAY);

    @BeforeEach
    void setUp() {
        table.install();
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        // the roster and affinity reads are unreadable here: the view must still be built (they guard, the test seam
        // answers every query with an empty table)
        BotRoster.useConnectionSourceForTest(BotReplyViewSharedTest::emptyConnection);
        BotAffinity.useForTest(new soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore.Memory(),
                Runnable::run, clock::get, id -> true);
    }

    @AfterEach
    void tearDown() {
        BotMemory.useConnectionSourceForTest(null);
        BotMemory.useForTest(null, null, 0);
        BotRoster.useConnectionSourceForTest(null);
        BotAffinity.useForTest(null, null, null, null);
    }

    private static java.sql.Connection emptyConnection() throws java.sql.SQLException {
        java.sql.Connection con = mock(java.sql.Connection.class);
        when(con.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(c -> emptyStatement());
        when(con.prepareStatement(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(c -> emptyStatement());
        return con;
    }

    private static java.sql.PreparedStatement emptyStatement() throws java.sql.SQLException {
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        java.sql.ResultSet empty = mock(java.sql.ResultSet.class);
        when(ps.executeQuery()).thenReturn(empty);
        when(ps.getGeneratedKeys()).thenReturn(empty);
        return ps;
    }

    private static Character botChr() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(BotStore.BOT_ID_BASE + SLOT);
        when(chr.getName()).thenReturn("Kitsune");
        when(chr.getLevel()).thenReturn(40);
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(3100000); // hunter
        when(chr.getJob()).thenReturn(job);
        return chr;
    }

    private static Character playerChr() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(PLAYER);
        when(chr.getName()).thenReturn("Josh");
        when(chr.getLevel()).thenReturn(38);
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(1000000); // warrior
        when(chr.getJob()).thenReturn(job);
        return chr;
    }

    private ReplyPlanner.View view(boolean wary) {
        Character chr = botChr();
        BotSM bot = mock(BotSM.class);
        when(bot.getChr()).thenReturn(chr);
        return BotReplyView.of(bot, playerChr(), SLOT, "hanging out in town", "hey",
                ChatTriage.rules("hey"), false, wary);
    }

    @Test
    void theGreetingViewCarriesWhatTheTwoDidBefore() {
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.PARTY, "partied together in henesys", 3, "",
                clock.get() - 2 * HOUR);
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.NOTE, "asked me where to train", 2, "", clock.get() - HOUR);
        ReplyPlanner.View view = view(false);
        assertEquals("asked me where to train", view.note(), "the newest note is the one memory");
        assertEquals("partied together in henesys", view.shared(),
                "memory of the party is what the greeting may come back to");
    }

    @Test
    void aWaryBotKeepsItShortAndDoesNotUseTheHistory() {
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.PARTY, "partied together in henesys", 3, "", clock.get());
        assertNull(view(true).shared(), "a wary bot does not bring up the good times");
    }

    @Test
    void noEpisodeMeansNothingToComeBackTo() {
        assertNull(view(false).shared());
    }

    @Test
    void theChatEndRecordsThePlayersLevelForTheNextGreeting() {
        assertEquals(0, BotMemory.progressLevel(SLOT, PLAYER), "nothing recorded yet");
        Character chr = playerChr();
        BotMemory.progress(botChr(), chr, clock.get());
        assertEquals(38, BotMemory.progressLevel(SLOT, PLAYER), "the level at the end of the chat");
        when(chr.getLevel()).thenReturn(45);
        BotMemory.progress(botChr(), chr, clock.get() + HOUR);
        assertEquals(45, BotMemory.progressLevel(SLOT, PLAYER), "the newest level wins");
        // the progress row is not an episode: it never shows up as something the two did together
        assertNull(view(false).shared(), "a level snapshot is not a shared moment");
    }

    @Test
    void theEpisodeIsNotRepeatedWhenItIsAlreadyTheNote() {
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.PARTY, "partied together in henesys", 3, "", clock.get());
        ReplyPlanner.View view = view(false);
        // the note carries the same text: the view says it once, in the note
        assertEquals("partied together in henesys", view.note());
        assertNull(view.shared(), "the same text is not said twice: " + view.note() + " / " + view.shared());
    }
}
