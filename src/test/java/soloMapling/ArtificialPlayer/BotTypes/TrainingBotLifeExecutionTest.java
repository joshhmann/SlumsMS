package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotCommitments;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotConvo.SayDo;
import soloMapling.ArtificialPlayer.BotLifeSystem.*;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.qa.QaSuite;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainingBotLifeExecutionTest {
    private static final LifeDecision.Result RESTOCK = new LifeDecision.Result(List.of(),
            Optional.of(LifeDecision.Activity.RESTOCK), LifeDecision.Outcome.CHOOSE);

    @Test
    void queuedRequestStartsOnOwnerDrainOnlyOnceAndReturnsThroughTheExistingPhases() throws Exception {
        withBot((bot, chr) -> {
            var request = bot.prepareLifeActivity(RESTOCK);
            assertEquals(LifeActivityQueue.Offer.QUEUED, bot.submitLifeActivity(request));
            assertEquals("GRIND", phase(bot), "submitting from a worker cannot change the activity");
            assertEquals(LifeActivityQueue.Offer.DUPLICATE, bot.submitLifeActivity(request));
            try (var movement = mockStatic(GCMovement.class); var sayDo = mockStatic(SayDo.class)) {
                drain(bot);
                assertEquals("GO_TOWN", phase(bot));
                assertEquals(request.number(), bot.lifeActivityState().active());
                assertFalse((boolean) field("restockCall").get(bot), "do not hand work to the legacy cross-thread flag");
                assertEquals(LifeActivityQueue.Offer.STALE, bot.submitLifeActivity(request));
                drain(bot);
                movement.verify(() -> GCMovement.stop(chr), times(1));
                sayDo.verify(() -> SayDo.event(chr, SayDo.Ev.RESUPPLY, -1), times(1));
                enter(bot, TrainingBot.Phase.GRIND);
                assertEquals(0, bot.lifeActivityState().active());
                assertEquals(LifeActivityQueue.End.COMPLETED, bot.lifeActivityState().end());
                assertEquals(request.number(), bot.lifeActivityState().lastEnded());
            }
        });
    }

    @Test
    void aConversationOrTradeThatStartsAfterSubmissionRefusesTheTrip() throws Exception {
        for (boolean trade : List.of(false, true)) {
            withBot((bot, chr) -> {
                var request = bot.prepareLifeActivity(RESTOCK);
                bot.submitLifeActivity(request);
                try (var movement = mockStatic(GCMovement.class); var sayDo = mockStatic(SayDo.class)) {
                    if (trade) {
                        try (var holds = mockStatic(BotCommitments.class)) {
                            holds.when(() -> BotCommitments.inFlight(bot)).thenReturn(true);
                            drain(bot);
                        }
                    } else {
                        try (var chat = mockStatic(BotConversation.class)) {
                            chat.when(() -> BotConversation.hasAnyChat(chr.getId())).thenReturn(true);
                            drain(bot);
                        }
                    }
                    assertEquals("GRIND", phase(bot));
                    assertEquals(0, bot.lifeActivityState().active());
                    assertEquals(0, bot.lifeActivityState().waiting());
                    movement.verifyNoInteractions();
                    sayDo.verifyNoInteractions();
                }
            });
        }
    }

    @Test
    void directCallsAndDisabledFlagsCannotStartTrips() throws Exception {
        withBot((bot, chr) -> {
            try (var movement = mockStatic(GCMovement.class)) {
                assertTrue(new RestockTripExecutor(bot).start(LifeDecision.Activity.RESTOCK).isEmpty());
                assertFalse(bot.startLifeRestockTrip());
                var request = bot.prepareLifeActivity(RESTOCK);
                bot.submitLifeActivity(request);
                System.clearProperty("slums.botlife.execute");
                drain(bot);
                assertEquals("GRIND", phase(bot));
                assertEquals(0, bot.lifeActivityState().active());
                movement.verifyNoInteractions();
            }
        });
    }

    @Test
    void phaseChangesAndStoppingDiscardOldPlansIncludingPreviouslyPreparedRequests() throws Exception {
        withBot((bot, chr) -> {
            var request = bot.prepareLifeActivity(RESTOCK);
            enter(bot, TrainingBot.Phase.DECIDE);
            assertEquals(LifeActivityQueue.Offer.STALE, bot.submitLifeActivity(request));
            var newer = bot.prepareLifeActivity(RESTOCK);
            bot.submitLifeActivity(newer);
            try (var movement = mockStatic(GCMovement.class)) {
                bot.stopScheduledTask();
                assertTrue(bot.lifeActivityState().closed());
                assertEquals(0, bot.lifeActivityState().waiting());
                assertEquals(LifeActivityQueue.Offer.CLOSED, bot.submitLifeActivity(newer));
                assertThrows(IllegalStateException.class, () -> bot.prepareLifeActivity(RESTOCK));
            }
        });
    }

    private interface Body { void run(TrainingBot bot, Character chr) throws Exception; }

    private static void withBot(Body body) throws Exception {
        String old = System.getProperty("slums.botlife.execute");
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_777_081);
        chr.setName("LifeBot");
        chr.setLevel(30);
        chr.setMap(mock(server.maps.MapleMap.class));
        TrainingBot bot = spy(new TrainingBot(chr));
        doNothing().when(bot).nudgeSoon(anyLong());
        bot.setRunning(true);
        field("phase").set(bot, TrainingBot.Phase.GRIND);
        try (var qa = mockStatic(QaSuite.class); var director = mockStatic(BotPopulationDirector.class)) {
            qa.when(QaSuite::isEnabled).thenReturn(true);
            director.when(() -> BotPopulationDirector.mayLogOut(bot)).thenReturn(true);
            System.setProperty("slums.botlife.execute", "true");
            body.run(bot, chr);
        } finally {
            if (old == null) System.clearProperty("slums.botlife.execute");
            else System.setProperty("slums.botlife.execute", old);
        }
    }

    private static Field field(String name) throws Exception {
        Field f = TrainingBot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
    private static String phase(TrainingBot bot) throws Exception { return field("phase").get(bot).toString(); }
    private static void drain(TrainingBot bot) throws Exception {
        Method drain = TrainingBot.class.getDeclaredMethod("runLifeActivities");
        drain.setAccessible(true);
        drain.invoke(bot);
    }
    private static void enter(TrainingBot bot, TrainingBot.Phase next) throws Exception {
        Method enter = TrainingBot.class.getDeclaredMethod("enterPhase", TrainingBot.Phase.class);
        enter.setAccessible(true);
        enter.invoke(bot, next);
    }
}
