package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.Fads;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 say-do (ticket 114, "then they really do it"): a bot that said yes to a come-along (or whose crew's leader
 * picked a town) is handed the hang-out there through its own life queue, ending a plain hang-out stay first; before,
 * the yes only tilted its next decision and a bot hanging out kept its stay (TEST 2026-10-28: 7 of 178 come-alongs
 * arrived). A bot that cannot drop what it is doing says why, so it can answer no instead.
 */
class LifeRuntimeSayDoTest {

    static final int HENESYS = 100000000, ELLINIA = 101000000;
    static final long NOW = 1_800_000_000_000L;

    TrainingBot bot;
    Character chr;
    LifeActivityQueue queue;
    String enabled, execute;

    @BeforeEach
    void setUp() {
        enabled = System.getProperty("slums.botlife.enabled");
        execute = System.getProperty("slums.botlife.execute");
        System.setProperty("slums.botlife.enabled", "true");
        System.setProperty("slums.botlife.execute", "true");
        bot = mock(TrainingBot.class);
        chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getChr()).thenReturn(chr);
        when(bot.getRunning()).thenReturn(true);
        when(chr.getId()).thenReturn(1000007101);
        when(chr.getLevel()).thenReturn(30);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(ELLINIA);
        when(map.getChannelId()).thenReturn(1);
        queue = new LifeActivityQueue();
        when(bot.lifeActivityState()).thenAnswer(i -> queue.state());
        when(bot.freeForAutomaticLifeActivity(anyLong())).thenReturn(true);
        when(bot.canStartLifeActivity(any(), anyLong())).thenReturn(true);
        when(bot.prepareLifeActivity(any())).thenAnswer(c -> queue.prepare(c.getArgument(0), NOW, 30_000));
        when(bot.submitLifeActivity(any())).thenAnswer(c -> queue.offer(c.getArgument(0), NOW));
        when(bot.interruptLifeDwell(anyLong())).thenAnswer(c -> queue.finish(c.getArgument(0),
                LifeActivityQueue.End.CANCELLED));
    }

    @AfterEach
    void tearDown() {
        restore("slums.botlife.enabled", enabled);
        restore("slums.botlife.execute", execute);
    }

    private static void restore(String key, String old) {
        if (old == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, old);
        }
    }

    /** Starts an activity in the queue, as the bot's update does when it takes one. */
    private long start(LifeDecision.Activity activity) {
        var req = queue.prepare(new LifeDecision.Result(List.of(), Optional.of(activity), LifeDecision.Outcome.CHOOSE),
                NOW, 30_000);
        queue.offer(req, NOW);
        var run = queue.drain(NOW, true, () -> true, new LifeAcceptance.Executor() {
            @Override
            public boolean supports(LifeDecision.Activity a) {
                return true;
            }

            @Override
            public Optional<LifeAcceptance.Accepted> start(LifeDecision.Activity a) {
                return Optional.of(new LifeAcceptance.Accepted(a, "test"));
            }
        });
        return run.number();
    }

    @Test
    void aYesToComeAlongEndsTheHangOutStayAndHandsTheTripToTheLifeQueue() {
        try (var roster = mockStatic(BotRoster.class); var fads = mockStatic(Fads.class)) {
            fads.when(() -> Fads.spotFor(anyInt(), anyInt())).thenReturn(0);
            var life = new LifeRuntime(bot);
            life.phase("LIFE_DWELL", NOW);
            long stay = start(LifeDecision.Activity.HANG_OUT); // hanging out in Ellinia
            assertNull(life.commitBlocker(NOW), "a plain hang-out may be dropped for a friend");
            assertTrue(life.goTogether(HENESYS, NOW + 480_000, NOW));
            verify(bot).interruptLifeDwell(stay);
            var state = queue.state();
            assertEquals(0, state.active(), "the stay is over");
            assertTrue(state.waiting() != 0, "the trip waits for the bot's own update to start it");
            assertEquals(HENESYS, life.townDestination(), "and its hang-out goes to the town it said yes to");
        }
    }

    /**
     * t-bt-3 on TEST 2026-10-29: SilentLuv said yes to a come-along, then its own brain chose GRIND during the lines,
     * and the trip 15 s later was refused ("on its way somewhere"). A yes holds the brain until the act.
     */
    @Test
    void aBotThatSaidYesMakesNoOtherChoiceUntilTheActAndIsStillFreeForIt() {
        try (var roster = mockStatic(BotRoster.class); var fads = mockStatic(Fads.class)) {
            fads.when(() -> Fads.spotFor(anyInt(), anyInt())).thenReturn(0);
            var life = new LifeRuntime(bot);
            life.phase("GRIND", NOW);
            life.promise(NOW + 90_000);
            verify(bot).holdLifeActivity(NOW + 90_000);
            assertTrue(life.promised(NOW + 1_000));
            org.mockito.Mockito.clearInvocations(bot);
            life.tick(NOW + 1_000);
            verify(bot, never()).lifeActivityState();
            verify(bot, never()).prepareLifeActivity(any());
            assertNull(life.commitBlocker(NOW + 15_000), "still free when the act comes");
            assertTrue(life.goTogether(HENESYS, NOW + 480_000, NOW + 15_000));
            assertFalse(life.promised(NOW + 16_000), "the act is queued: the brain is free to decide after it");
            assertFalse(new LifeRuntime(bot).promised(NOW), "nothing promised by default");
        }
    }

    @Test
    void aGrinderTakesTheTripFromItsGrind() {
        try (var roster = mockStatic(BotRoster.class)) {
            var life = new LifeRuntime(bot);
            life.phase("GRIND", NOW);
            assertTrue(life.goTogether(HENESYS, NOW + 480_000, NOW));
            assertTrue(queue.hasWaiting());
            verify(bot, never()).interruptLifeDwell(anyLong());
        }
    }

    @Test
    void aBotOnATripOrInAPqLobbyCannotGoAndSaysWhy() {
        try (var roster = mockStatic(BotRoster.class)) {
            var life = new LifeRuntime(bot);
            life.phase("LIFE_TRAVEL", NOW);
            assertNotNull(life.commitBlocker(NOW));
            assertFalse(life.goTogether(HENESYS, NOW + 480_000, NOW));
            assertFalse(queue.hasWaiting(), "nothing is handed over");

            life.phase("LIFE_DWELL", NOW);
            start(LifeDecision.Activity.PARTY_QUEST); // standing in a PQ lobby: a commitment
            assertTrue(life.commitBlocker(NOW).contains("party_quest"), life.commitBlocker(NOW));
            assertFalse(life.goTogether(HENESYS, NOW + 480_000, NOW));
            verify(bot, never()).interruptLifeDwell(anyLong());
        }
    }

    @Test
    void aFullTownIsNotAYesAndTheStayIsKept() {
        try (var roster = mockStatic(BotRoster.class)) {
            when(bot.canStartLifeActivity(any(), anyLong())).thenReturn(false); // the town has no room
            var life = new LifeRuntime(bot);
            life.phase("LIFE_DWELL", NOW);
            start(LifeDecision.Activity.HANG_OUT);
            assertFalse(life.goTogether(HENESYS, NOW + 480_000, NOW));
            verify(bot, never()).interruptLifeDwell(anyLong());
            assertTrue(queue.state().active() != 0, "it keeps hanging out where it is");
        }
    }

    @Test
    void aBotHeldByATradeOrAChatCannotGo() {
        try (var roster = mockStatic(BotRoster.class)) {
            when(bot.freeForAutomaticLifeActivity(anyLong())).thenReturn(false);
            var life = new LifeRuntime(bot);
            life.phase("IN_TOWN", NOW);
            assertNotNull(life.commitBlocker(NOW));
            assertFalse(life.goTogether(HENESYS, NOW + 480_000, NOW));
        }
    }

    @Test
    void aYesToHelpKeepsTheGrinderOnThisMapForTheHelp() {
        try (var roster = mockStatic(BotRoster.class)) {
            var life = new LifeRuntime(bot);
            life.phase("GRIND", NOW);
            long until = NOW + 900_000;
            assertTrue(life.helpHere(1000007102, until, NOW));
            verify(bot).keepLifeActivity(LifeDecision.Activity.GRIND, until);
            life.phase("LIFE_DWELL", NOW);
            assertFalse(life.helpHere(1000007102, until, NOW), "a bot hanging out in town is not training here");
        }
    }

    @Test
    void anLfgTripEndsAHangOutStayToo() {
        try (var roster = mockStatic(BotRoster.class)) {
            var life = new LifeRuntime(bot);
            life.phase("LIFE_DWELL", NOW);
            long stay = start(LifeDecision.Activity.HANG_OUT);
            assertTrue(life.handOff(LifeDecision.Activity.PARTY_QUEST));
            verify(bot).interruptLifeDwell(stay);
            assertTrue(queue.hasWaiting(), "the PQ trip is queued, not refused as BUSY behind the stay");
            var request = org.mockito.ArgumentCaptor.forClass(LifeDecision.Result.class);
            verify(bot).prepareLifeActivity(request.capture());
            assertEquals(LifeDecision.Activity.PARTY_QUEST, request.getValue().selected().orElseThrow());
        }
    }
}
