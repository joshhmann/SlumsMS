package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommitments;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Need;

/**
 * The executor half of the mode switch: with needs that call for a switch, a bot at a safe point is converted
 * exactly once through the swap; anything that holds the bot (a trade, a party, a chat, a commitment, an errand, a
 * field map, the brain's own busy phases) stops it, and a refusal is counted by reason.
 */
class ModeSwitchGateTest {

    private static final int ID = 1_000_000_321;
    private static final String[] KEYS = {"slums.botlife.enabled", "slums.botlife.execute", "slums.botlife.reach",
            "slums.modeswitch"};
    private final String[] saved = new String[KEYS.length];
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<BotTypeManager.BotType> swaps = new ArrayList<>();
    private boolean swapAnswer = true;

    private Character chr;
    private TrainingBot training;
    private LifeRuntime runtime;
    private boolean townMap, hasChat, committed, hasParty, coupled;
    private String brainBlocker;

    @BeforeEach
    void setUp() {
        for (int i = 0; i < KEYS.length; i++) {
            saved[i] = System.getProperty(KEYS[i]);
            System.clearProperty(KEYS[i]); // all on by default
        }
        ModeSwitch.reset();
        SocialSim.useForTest(clock::get, 11);
        ModeSwitch.useForTest((c, type) -> {
            swaps.add(type);
            return swapAnswer;
        });
        townMap = true;
        hasChat = committed = hasParty = coupled = false;
        brainBlocker = null;
        chr = mock(Character.class);
        when(chr.getId()).thenReturn(ID);
        when(chr.getMap()).thenReturn(mock(MapleMap.class));
        when(chr.getMapId()).thenReturn(100000000);
        training = mock(TrainingBot.class);
        when(training.getChr()).thenReturn(chr);
        runtime = mock(LifeRuntime.class);
        when(runtime.lastDecision()).thenAnswer(c -> lonelySocializer());
        when(runtime.commitBlocker(anyLong())).thenAnswer(c -> brainBlocker);
    }

    @AfterEach
    void tearDown() {
        for (int i = 0; i < KEYS.length; i++) {
            if (saved[i] == null) System.clearProperty(KEYS[i]);
            else System.setProperty(KEYS[i], saved[i]);
        }
        ModeSwitch.reset();
        SocialSim.reset();
    }

    /** A valid profile led by {@code lead} at {@code share}; the rest is spread evenly over the other channels. */
    private static LifeProfile blend(LifeProfile.Archetype lead, double share) {
        var blend = new java.util.EnumMap<LifeProfile.Archetype, Double>(LifeProfile.Archetype.class);
        double rest = (1 - share) / (LifeProfile.Archetype.values().length - 1);
        for (var a : LifeProfile.Archetype.values()) blend.put(a, a == lead ? share : rest);
        return new LifeProfile(blend, 1_800_000L, 600_000L);
    }

    /** Needs and blend that call for a switch out of training: lonely, tired, no goal, a socializer. */
    private LifeRuntime.DecisionSnapshot lonelySocializer() {
        var needs = new LifeDecision.Needs(Map.of(Need.SOCIAL, 0.95, Need.FATIGUE, 0.8, Need.BOREDOM, 0.7,
                Need.PROGRESS, 0.0));
        var context = new LifePolicy.Context(needs, Map.of(), 0, 0, 0, false, false, false, null, List.of(),
                List.of());
        var profile = blend(LifeProfile.Archetype.SOCIALITE, .6);
        return new LifeRuntime.DecisionSnapshot(clock.get(), new LifeObservation.Report(null, profile, context, null,
                null));
    }

    /** Runs the bot's evaluation once a minute for up to {@code minutes}; returns whether it switched. */
    private boolean runUntilSwitch(BotSM bot, int minutes) {
        try (MockedStatic<CharacterStorage> storage = org.mockito.Mockito.mockStatic(CharacterStorage.class);
             MockedStatic<BotPopulationDirector> director = org.mockito.Mockito.mockStatic(BotPopulationDirector.class);
             MockedStatic<BotCommitments> commitments = org.mockito.Mockito.mockStatic(BotCommitments.class);
             MockedStatic<BotConversation> chats = org.mockito.Mockito.mockStatic(BotConversation.class);
             MockedStatic<LifeTowns> towns = org.mockito.Mockito.mockStatic(LifeTowns.class);
             MockedStatic<soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples> couples =
                     org.mockito.Mockito.mockStatic(soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.class)) {
            storage.when(() -> CharacterStorage.getBotById(ID)).thenReturn(bot);
            director.when(() -> BotPopulationDirector.mayLogOut(bot)).thenAnswer(c -> !committed);
            commitments.when(() -> BotCommitments.inFlight(bot)).thenReturn(false);
            chats.when(() -> BotConversation.hasAnyChat(ID)).thenAnswer(c -> hasChat);
            towns.when(() -> LifeTowns.isTownMap(anyInt())).thenAnswer(c -> townMap);
            couples.when(() -> soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.partnerOf(ID))
                    .thenAnswer(c -> coupled ? ID + 1 : -1);
            when(chr.getParty()).thenAnswer(c -> hasParty ? mock(net.server.world.Party.class) : null);
            for (int i = 0; i < minutes; i++) {
                clock.addAndGet(61_000);
                if (ModeSwitch.maybeSwitch(bot, runtime, clock.get())) return true;
            }
            return false;
        }
    }

    @Test
    void aLonelySocializerAtASafePointIsConvertedOnceToTheSocialType() {
        assertTrue(runUntilSwitch(training, 120));
        assertEquals(List.of(BotTypeManager.BotType.SOCIAL_BOT), swaps);
        assertEquals(1L, ModeSwitch.stats().get("switches"));
        assertEquals(1L, ModeSwitch.stats().get("switch TRAINING>SOCIAL"));
        assertEquals(1, ModeSwitch.census().get(ModeSwitch.Mode.SOCIAL));
    }

    @Test
    void theSwitchWaitsForTheMinimumStayAfterFirstSight() {
        assertFalse(runUntilSwitch(training, 14), "a bot first seen now has not stayed 15 min yet");
        assertTrue(swaps.isEmpty());
    }

    @Test
    void whatHoldsTheBotStopsTheSwitchAndIsCountedByReason() {
        Map<String, Consumer<ModeSwitchGateTest>> holds = Map.of(
                "brain: on its way somewhere (life_travel)", t -> t.brainBlocker = "on its way somewhere (life_travel)",
                "committed", t -> t.committed = true,
                "in a party", t -> t.hasParty = true,
                "in a chat", t -> t.hasChat = true,
                "not in a town", t -> t.townMap = false,
                "coupled", t -> t.coupled = true);
        for (var hold : holds.entrySet()) {
            ModeSwitch.reset();
            ModeSwitch.useForTest((c, type) -> {
                swaps.add(type);
                return true;
            });
            swaps.clear();
            townMap = true;
            hasChat = committed = hasParty = coupled = false;
            brainBlocker = null;
            hold.getValue().accept(this);
            assertFalse(runUntilSwitch(training, 120), hold.getKey());
            assertTrue(swaps.isEmpty(), hold.getKey());
            assertTrue(ModeSwitch.stats().getOrDefault("refused " + hold.getKey(), 0L) > 0, hold.getKey());
        }
    }

    @Test
    void anErrandAndATradeStopIt() {
        when(training.onErrand()).thenReturn(true);
        assertFalse(runUntilSwitch(training, 120));
        assertTrue(ModeSwitch.stats().getOrDefault("refused on an errand", 0L) > 0);
        ModeSwitch.reset();
        ModeSwitch.useForTest((c, type) -> {
            swaps.add(type);
            return true;
        });
        when(training.onErrand()).thenReturn(false);
        when(training.getState()).thenReturn(BotSM.BotState.TRADING);
        assertFalse(runUntilSwitch(training, 120));
        assertTrue(ModeSwitch.stats().getOrDefault("refused trading", 0L) > 0);
        assertTrue(swaps.isEmpty());
    }

    @Test
    void aRefusedSwapIsCountedAndTheBotKeepsItsMode() {
        swapAnswer = false;
        assertFalse(runUntilSwitch(training, 120));
        assertTrue(ModeSwitch.stats().getOrDefault("refused swap refused", 0L) > 0);
        assertEquals(0L, ModeSwitch.stats().get("switches"));
    }

    @Test
    void aBotThatIsNotTheRegisteredInstanceIsNeverSwitched() {
        TrainingBot stale = mock(TrainingBot.class);
        when(stale.getChr()).thenReturn(chr);
        assertFalse(runUntilSwitchAgainst(stale, training, 120));
        assertTrue(swaps.isEmpty());
        assertTrue(ModeSwitch.stats().getOrDefault("refused not the registered instance", 0L) > 0);
    }

    private boolean runUntilSwitchAgainst(BotSM caller, BotSM registered, int minutes) {
        try (MockedStatic<CharacterStorage> storage = org.mockito.Mockito.mockStatic(CharacterStorage.class)) {
            storage.when(() -> CharacterStorage.getBotById(ID)).thenReturn(registered);
            for (int i = 0; i < minutes; i++) {
                clock.addAndGet(61_000);
                if (ModeSwitch.maybeSwitch(caller, runtime, clock.get())) return true;
            }
            return false;
        }
    }

    @Test
    void aTypeOutsideTheThreeModesIsLeftAlone() {
        FollowerBot follower = mock(FollowerBot.class);
        when(follower.getChr()).thenReturn(chr);
        assertFalse(runUntilSwitch(follower, 120));
        assertEquals(0L, ModeSwitch.stats().get("evaluations"));
    }

    @Test
    void theRollbackFlagStopsEverything() {
        System.setProperty("slums.modeswitch", "false");
        assertFalse(runUntilSwitch(training, 120));
        assertEquals(0L, ModeSwitch.stats().get("evaluations"));
        System.clearProperty("slums.modeswitch");
        System.setProperty("slums.botlife.reach", "training");
        assertFalse(runUntilSwitch(training, 120), "light hosts need the brain on town and social bots too");
    }

    @Test
    void aSocialBotWithAGoalAndNoLonelinessIsConvertedBackToTraining() {
        SocialBot social = mock(SocialBot.class);
        when(social.getChr()).thenReturn(chr);
        when(runtime.lastDecision()).thenAnswer(c -> {
            var needs = new LifeDecision.Needs(Map.of(Need.SOCIAL, 0.02, Need.PROGRESS, 0.95, Need.BOREDOM, 0.6,
                    Need.FATIGUE, 0.1));
            var context = new LifePolicy.Context(needs, Map.of(), 0, 0, 0, false, false, false, null, List.of(),
                    List.of());
            var profile = blend(LifeProfile.Archetype.GRINDER, .6);
            return new LifeRuntime.DecisionSnapshot(clock.get(), new LifeObservation.Report(null, profile, context,
                    null, null));
        });
        assertTrue(runUntilSwitch(social, 120));
        assertEquals(List.of(BotTypeManager.BotType.TRAINING_BOT), swaps);
    }
}
