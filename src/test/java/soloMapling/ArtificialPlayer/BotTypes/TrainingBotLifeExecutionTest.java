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
    void friendInterruptOnlyEndsTheExactDwellAndLeavesTripsAlone() throws Exception {
        withBot((bot, chr) -> {
            var request = bot.prepareLifeActivity(RESTOCK);
            bot.submitLifeActivity(request);
            try (var movement = mockStatic(GCMovement.class); var sayDo = mockStatic(SayDo.class)) {
                drain(bot);
                assertFalse(bot.interruptLifeDwell(request.number()), "do not interrupt an errand in progress");
                assertEquals(request.number(), bot.lifeActivityState().active());
                field("phase").set(bot, TrainingBot.Phase.LIFE_DWELL);
                assertFalse(bot.interruptLifeDwell(request.number() + 1), "old callbacks cannot cancel another request");
                assertTrue(bot.interruptLifeDwell(request.number()));
                assertEquals(0, bot.lifeActivityState().active());
                assertEquals(LifeActivityQueue.End.CANCELLED, bot.lifeActivityState().end());
            }
        });
    }

    @Test
    void townStayDoesNotExpireThroughAPlayerCommitment() throws Exception {
        withBot((bot, chr) -> {
            TrainingBot actor = bot;
            field("phase").set(actor, TrainingBot.Phase.LIFE_DWELL);
            field("phaseEntered").setBoolean(actor, true);
            field("phaseDeadlineMs").setLong(actor, 0);
            var dwell = TrainingBot.class.getDeclaredMethod("doLifeDwell");
            dwell.setAccessible(true);
            try (var space = mockStatic(soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.class);
                 var loiter = mockStatic(soloMapling.ArtificialPlayer.BotTownSystem.TownLoiter.class)) {
                doReturn(false).when(actor).freeForAutomaticLifeActivity(anyLong());
                dwell.invoke(actor);
                assertEquals("LIFE_DWELL", phase(actor), "a conversation or trade holds the departure");
                doReturn(true).when(actor).freeForAutomaticLifeActivity(anyLong());
                dwell.invoke(actor);
                assertEquals("DECIDE", phase(actor), "the elapsed stay ends once the hold clears");
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

    @Test
    void townArrivalKeepsBothTheProfileDurationAndTheConversationHold() throws Exception {
        String enabled = System.getProperty("slums.botlife.enabled");
        try {
            System.setProperty("slums.botlife.enabled", "true");
            for (boolean longHold : List.of(false, true)) {
                withBot((bot, chr) -> {
                    long townMs = bot.lifeRuntime().memory().profile().townDurationMs();
                    long holdMs = longHold ? townMs + 60_000 : 45_000;
                    field("phase").set(bot, TrainingBot.Phase.IN_TOWN);
                    field("phaseEntered").setBoolean(bot, false);
                    try (var chat = mockStatic(BotConversation.class);
                         var supplies = mockStatic(soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies.class);
                         var loiter = mockStatic(soloMapling.ArtificialPlayer.BotTownSystem.TownLoiter.class);
                         var speech = mockStatic(soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.class);
                         var space = mockStatic(soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.class);
                         var scrolling = mockStatic(soloMapling.ArtificialPlayer.BotScrollSystem.BotScrolling.class);
                         var wealth = mockStatic(soloMapling.ArtificialPlayer.BotWealth.class);
                         var shops = mockStatic(soloMapling.Economy.BotShops.class)) {
                        chat.when(() -> BotConversation.isHoldingChat(chr)).thenReturn(true);
                        chat.when(() -> BotConversation.holdMs(chr.getId())).thenReturn(holdMs);
                        Method arrive = TrainingBot.class.getDeclaredMethod("doInTown");
                        arrive.setAccessible(true);
                        long before = System.currentTimeMillis();
                        arrive.invoke(bot);
                        long after = System.currentTimeMillis();
                        long deadline = field("phaseDeadlineMs").getLong(bot);
                        long expected = Math.max(townMs, holdMs);
                        assertTrue(deadline >= before + expected, "keep both profile dwell and chat hold");
                        assertTrue(deadline <= after + expected);
                        long secondTick = System.currentTimeMillis();
                        arrive.invoke(bot);
                        assertTrue(field("phaseDeadlineMs").getLong(bot) >= deadline,
                                "a short chat hold must not shorten the profile's planned stay");
                        assertTrue(field("phaseDeadlineMs").getLong(bot) >= secondTick + holdMs);
                        loiter.verifyNoInteractions();
                    }
                });
            }
        } finally {
            if (enabled == null) System.clearProperty("slums.botlife.enabled");
            else System.setProperty("slums.botlife.enabled", enabled);
        }
    }

    @Test
    void fishingStartsOnlyThroughTheOwnerQueueAndNeverRestartsACancelledSession() throws Exception {
        String old = System.getProperty("slums.botlife.enabled");
        System.setProperty("slums.botlife.enabled", "true");
        try {
            withBot((bot, chr) -> {
                var active = new java.util.concurrent.atomic.AtomicBoolean();
                doReturn(true).when(bot).freeForAutomaticLifeActivity(anyLong());
                doReturn(true).when(bot).canStartLifeActivity(eq(LifeDecision.Activity.FISH), anyLong());
                try (var movement = mockStatic(GCMovement.class);
                     var chairs = mockStatic(soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.class);
                     var loiter = mockStatic(soloMapling.ArtificialPlayer.BotTownSystem.TownLoiter.class);
                     var game = mockStatic(soloMapling.fishing.GameFishing.class);
                     var policy = mockStatic(soloMapling.fishing.FishingPolicy.class)) {
                    policy.when(soloMapling.fishing.FishingPolicy::rewardsEnabled).thenReturn(true);
                    game.when(() -> soloMapling.fishing.GameFishing.destination(chr)).thenReturn(0);
                    game.when(() -> soloMapling.fishing.GameFishing.active(chr)).thenAnswer(i -> active.get());
                    game.when(() -> soloMapling.fishing.GameFishing.start(chr)).thenAnswer(i -> { active.set(true); return true; });
                    chairs.when(() -> soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botSitChair(chr, 3011000))
                            .thenAnswer(i -> { chr.setChair(3011000); return null; });
                    chairs.when(() -> soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botCancelChair(chr))
                            .thenAnswer(i -> { chr.setChair(-1); return null; });
                    assertFalse(bot.startLifeActivity(LifeDecision.Activity.FISH), "outside the actor drain cannot execute");
                    var decision = new LifeDecision.Result(List.of(), Optional.of(LifeDecision.Activity.FISH), LifeDecision.Outcome.CHOOSE);
                    var request = bot.prepareLifeActivity(decision);
                    bot.submitLifeActivity(request); drain(bot);
                    assertEquals("LIFE_DWELL", phase(bot));
                    assertEquals(LifeDecision.Activity.FISH, bot.currentLifeVisit());
                    var dwell = TrainingBot.class.getDeclaredMethod("doLifeDwell"); dwell.setAccessible(true);
                    dwell.invoke(bot); dwell.invoke(bot);
                    assertTrue(bot.lifeRuntime().prompt().contains("seated on a fishing chair"));
                    game.verify(() -> soloMapling.fishing.GameFishing.start(chr), times(1));
                    active.set(false); // movement/standing cancelled the live session
                    dwell.invoke(bot);
                    assertEquals("DECIDE", phase(bot)); assertEquals(-1, chr.getChair());
                    assertEquals(0, bot.lifeActivityState().active());
                    assertEquals(LifeActivityQueue.End.CANCELLED, bot.lifeActivityState().end());
                    game.verify(() -> soloMapling.fishing.GameFishing.start(chr), times(1));
                }
            });
        } finally {
            if (old == null) System.clearProperty("slums.botlife.enabled"); else System.setProperty("slums.botlife.enabled", old);
        }
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
