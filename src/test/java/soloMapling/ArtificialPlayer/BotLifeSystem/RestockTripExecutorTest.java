package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotCommitments;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaSuite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

class RestockTripExecutorTest {
    @Test
    void usesOnlyTheExistingTripHookAndNeverClaimsAPurchase() {
        withFixture((bot, executor) -> {
            when(bot.requestRestockTrip()).thenReturn(true);
            var accepted = executor.start(Activity.RESTOCK).orElseThrow();
            assertTrue(accepted.fact().contains("purchase not yet confirmed"));
            verify(bot).requestRestockTrip();
            assertTrue(executor.start(Activity.SELL).isEmpty());
        });
    }

    @Test
    void declinedTripHasNoActionFact() {
        withFixture((bot, executor) -> assertTrue(executor.start(Activity.RESTOCK).isEmpty()));
    }

    @Test
    void qaAndExecuteFlagAreBothRequired() {
        withFixture((bot, executor) -> {
            System.clearProperty("slums.botlife.execute");
            assertTrue(executor.start(Activity.RESTOCK).isEmpty());
            verify(bot, never()).requestRestockTrip();
        });
        withFixture((bot, executor) -> {
            try (var qa = mockStatic(QaSuite.class)) {
                qa.when(QaSuite::isEnabled).thenReturn(false);
                assertTrue(executor.start(Activity.RESTOCK).isEmpty());
                verify(bot, never()).requestRestockTrip();
            }
        }, false);
    }

    @Test
    void conversationTradeInviteAndPartyHoldsRefuseTheTrip() {
        withFixture((bot, executor) -> {
            try (var conversations = mockStatic(BotConversation.class)) {
                conversations.when(() -> BotConversation.hasAnyChat(1_000_005_001)).thenReturn(true);
                assertTrue(executor.start(Activity.RESTOCK).isEmpty());
                verify(bot, never()).requestRestockTrip();
            }
        });
        withFixture((bot, executor) -> {
            try (var commitments = mockStatic(BotCommitments.class)) {
                commitments.when(() -> BotCommitments.inFlight(bot)).thenReturn(true);
                assertTrue(executor.start(Activity.RESTOCK).isEmpty());
                verify(bot, never()).requestRestockTrip();
            }
        });
        withFixture((bot, executor) -> {
            try (var director = mockStatic(BotPopulationDirector.class)) {
                director.when(() -> BotPopulationDirector.mayLogOut(bot)).thenReturn(false);
                assertTrue(executor.start(Activity.RESTOCK).isEmpty());
                verify(bot, never()).requestRestockTrip();
            }
        }, true, false);
    }

    private static void withFixture(java.util.function.BiConsumer<TrainingBot, RestockTripExecutor> action) {
        withFixture(action, true);
    }
    private static void withFixture(java.util.function.BiConsumer<TrainingBot, RestockTripExecutor> action, boolean mockQa) {
        withFixture(action, mockQa, true);
    }
    private static void withFixture(java.util.function.BiConsumer<TrainingBot, RestockTripExecutor> action,
                                    boolean mockQa, boolean mockDirector) {
        String old = System.getProperty("slums.botlife.execute");
        TrainingBot bot = mock(TrainingBot.class);
        Character chr = mock(Character.class);
        when(bot.getChr()).thenReturn(chr);
        when(chr.getId()).thenReturn(1_000_005_001);
        try (var qa = mockQa ? mockStatic(QaSuite.class) : null;
             var director = mockDirector ? mockStatic(BotPopulationDirector.class) : null) {
            if (qa != null) qa.when(QaSuite::isEnabled).thenReturn(true);
            if (director != null) director.when(() -> BotPopulationDirector.mayLogOut(bot)).thenReturn(true);
            System.setProperty("slums.botlife.execute", "true");
            action.accept(bot, new RestockTripExecutor(bot));
        } finally {
            if (old == null) System.clearProperty("slums.botlife.execute");
            else System.setProperty("slums.botlife.execute", old);
        }
    }
}
