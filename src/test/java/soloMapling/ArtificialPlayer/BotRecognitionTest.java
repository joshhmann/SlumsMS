package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 26: who a bot greets as someone it knows, and how rarely it says hi first. */
class BotRecognitionTest {

    private static final long NOW = TimeUnit.DAYS.toMillis(400);
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private static BotMemory.Note own(String text, long at) {
        return new BotMemory.Note(1, 5, 2001, text, BotMemory.Source.DIRECT, 0, 0, at, 0);
    }

    private static BotMemory.Note heard(String text, long at) {
        return new BotMemory.Note(2, 5, 2001, text, BotMemory.Source.HEARSAY, 7, 1, at, 0);
    }

    @Test
    void onlyAnOwnFriendlyNoteMakesAPlayerKnown() {
        assertTrue(BotRecognition.hasOwnNote(List.of(own("said they're saving for a pink adventurer cape", NOW))));
        assertFalse(BotRecognition.hasOwnNote(List.of()));
        assertFalse(BotRecognition.hasOwnNote(List.of(heard("hit 30 last week", NOW))), "hearsay is not knowing someone");
        assertFalse(BotRecognition.hasOwnNote(List.of(own(BotChatActions.flagNote(
                soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage.Con.SCAM), NOW))), "a grudge is not a friend");
    }

    @Test
    void aBotSaysHiFirstOnlyRarelyAndNeverRightAfterAChat() {
        List<BotMemory.Note> known = List.of(own("wants to be a bishop", NOW - DAY));
        assertTrue(BotRecognition.shouldGreetOnSight(known, false, false, 0, NOW, 0.1));
        assertFalse(BotRecognition.shouldGreetOnSight(known, false, false, 0, NOW, 0.9), "the roll");
        assertFalse(BotRecognition.shouldGreetOnSight(known, true, false, 0, NOW, 0.1), "wary");
        assertFalse(BotRecognition.shouldGreetOnSight(known, false, true, 0, NOW, 0.1), "already talking");
        assertFalse(BotRecognition.shouldGreetOnSight(List.of(own("wants to be a bishop", NOW - 60_000)), false, false,
                0, NOW, 0.1), "they only just parted");
        assertFalse(BotRecognition.shouldGreetOnSight(known, false, false, NOW - TimeUnit.HOURS.toMillis(2), NOW, 0.1),
                "greeted on sight a little while ago");
        assertTrue(BotRecognition.shouldGreetOnSight(known, false, false, NOW - TimeUnit.HOURS.toMillis(7), NOW, 0.1));
        assertFalse(BotRecognition.shouldGreetOnSight(List.of(heard("wants to be a bishop", NOW - DAY)), false, false,
                0, NOW, 0.1), "never on hearsay");
    }

    /** m02 stage B: a busy bot opens no chat, and does not even read memory or spend the pair's greet budget. */
    @Test
    void aBusyBotNeverOpensItsMemoryForAHelloItWillNotSay() {
        FakeMemoryTable table = new FakeMemoryTable(false);
        table.install();
        client.Character chr = client.Character.getDefault(new client.BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + 6);
        BotSM bot = mock(BotSM.class);
        when(bot.getChr()).thenReturn(chr);
        client.Character player = client.Character.getDefault(mock(client.Client.class));
        player.setID(5151);
        player.setName("Josh");
        try {
            System.setProperty("solomapling.convo.planner", "true");
            when(bot.activitySnapshot()).thenReturn(BotRoster.Activity.of("grind", 0, null));
            assertFalse(BotRecognition.maybeGreetOnSight(bot, player), "grinding: the bot says nothing");
            assertEquals(0, table.pairReads.get(), "a busy bot does not read memory for a hello it will not say");
            when(bot.activitySnapshot()).thenReturn(BotRoster.Activity.of("social", 0, null));
            BotRecognition.maybeGreetOnSight(bot, player);
            assertTrue(table.pairReads.get() > 0, "a free bot does look them up");
        } finally {
            System.clearProperty("solomapling.convo.planner");
            BotMemory.useConnectionSourceForTest(null);
        }
    }

    @Test
    void theScriptedHelloIsShortCasualAndNeverQuotesANote() {
        Random rng = new Random(7);
        for (int i = 0; i < 200; i++) {
            String line = BotRecognition.scriptedHello("Josh", rng);
            assertTrue(line.length() <= 20, line);
            assertTrue(line.toLowerCase().matches("[a-z0-9 !:)]+"), line);
        }
    }
}
