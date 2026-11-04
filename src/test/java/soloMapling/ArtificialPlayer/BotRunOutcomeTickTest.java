package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotRunOutcome.Pending;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 1.6.4 run chatter: the bot's own macro tick is where it says what it has to say about a run, for every bot type
 * (they all run {@link BotSM#updateState()}), and a bot with nothing pending is untouched by it.
 */
class BotRunOutcomeTickTest {

    private static final int BOT_ID = 21777; // above BotHelpers.BOT_BASE_ID

    private static class Probe extends BotSM {
        Probe(Character chr) {
            super(chr);
            state = BotState.RUNNING;
            setRunning(true);
        }

        @Override
        public void checkPrioritySpeed() {
        }
    }

    @AfterEach
    void out() {
        CharacterStorage.removeActiveBot(BOT_ID);
    }

    private static Probe runningBot() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BOT_ID);
        chr.setName("RunChatBot");
        chr.setLevel(42);
        chr.setMap(mock(MapleMap.class));
        Probe bot = new Probe(chr);
        CharacterStorage.addActiveBot(BOT_ID, bot);
        return bot;
    }

    @Test
    void theRunningTickSaysThePendingLineOnceWhereAPlayerCanSeeIt() {
        Probe bot = runningBot();
        bot.runOutcome().set(new Pending("woo we beat Kerning PQ!", System.currentTimeMillis() + 60_000));
        try (MockedStatic<MovementCommands> move = mockStatic(MovementCommands.class);
             MockedStatic<GCMovement> gc = mockStatic(GCMovement.class);
             MockedStatic<SocialCommands> chat = mockStatic(SocialCommands.class)) {
            gc.when(() -> GCMovement.isMapObserved(any(Character.class))).thenReturn(true);
            chat.when(() -> SocialCommands.tryAmbientSpeak(any(), any())).thenReturn(true);

            bot.updateState();
            bot.updateState();

            chat.verify(() -> SocialCommands.tryAmbientSpeak(any(), eq("woo we beat Kerning PQ!")), times(1));
            assertNull(bot.runOutcome().get(), "said, so gone");
        }
    }

    @Test
    void anUnobservedMapKeepsTheLineAndSaysNothing() {
        Probe bot = runningBot();
        bot.runOutcome().set(new Pending("dang, Zakum got us", System.currentTimeMillis() + 60_000));
        try (MockedStatic<MovementCommands> move = mockStatic(MovementCommands.class);
             MockedStatic<GCMovement> gc = mockStatic(GCMovement.class);
             MockedStatic<SocialCommands> chat = mockStatic(SocialCommands.class)) {
            gc.when(() -> GCMovement.isMapObserved(any(Character.class))).thenReturn(false);

            bot.updateState();

            chat.verify(() -> SocialCommands.tryAmbientSpeak(any(), any()), never());
            assertNotNull(bot.runOutcome().get());
            assertEquals("dang, Zakum got us", bot.runOutcome().get().line());
        }
    }

    @Test
    void aBotWithNothingPendingNeverAsksWhetherItIsObserved() {
        Probe bot = runningBot();
        try (MockedStatic<MovementCommands> move = mockStatic(MovementCommands.class);
             MockedStatic<GCMovement> gc = mockStatic(GCMovement.class)) {
            bot.updateState();

            gc.verify(() -> GCMovement.isMapObserved(any(Character.class)), never());
        }
    }
}
