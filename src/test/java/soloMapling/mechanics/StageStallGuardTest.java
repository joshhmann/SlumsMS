package soloMapling.mechanics;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.5 b344c: a party never waits for ever behind a bot that cannot do its part (TEST, 6 Nov 19:35: Zakum PQ stage 31's
 * miner stood in the big room unable to reach its ore rock, and the party waited until QA stopped the run; nothing in
 * the run would have ended it before the PQ's own 30 minutes). After three minutes of the bots busy and nothing in the
 * room changing, or at once when the planner has no route to the reactor, the bots give the stage up: the run fails on
 * "gave up stage N", the bots go back to their brains, and the real players keep the instance.
 */
class StageStallGuardTest {

    private static final long START = 1_000_000L;
    private static final int LEADER = 1;
    private static final int MINER = 2;
    private static final int HUB = 280_010_000;
    private static final int BIG_ROOM = 280_011_005;

    @AfterEach
    void restoreRouteCheck() {
        StepExecutor.routeCheck = DEFAULT_ROUTE_CHECK;
    }

    private static final StepExecutor.RouteCheck DEFAULT_ROUTE_CHECK = StepExecutor.routeCheck;

    private record Setup(Orchestrator orch, RunState run, View view) {
    }

    /** A real leader on the hub and one bot miner on stage 31, the big room's own ore rock. */
    private static Setup stage31() {
        Content zpq = MechanicsRuns.contentFor("ZakumPQ");
        Orchestrator orch = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        List<RunMember> party = List.of(new RunMember(LEADER, true, false, MechanicsPersona.PERFECT),
                new RunMember(MINER, false, true, MechanicsPersona.PERFECT));
        RunState run = orch.start(zpq, Tier.NORMAL, party);
        int idx = -1;
        for (Stage s : zpq.stages()) {
            if (s.mapId() == BIG_ROOM && s.hitReactors().contains(2_112_011)) {
                idx = s.index();
            }
        }
        assertTrue(idx > 0, "the row has the big room's ore-rock stage");
        run.markStarted(START);
        run.setStage(idx, START);
        run.setClaims(RoleClaims.claim(run.stageData(), run.members()));
        View view = new View(zpq.stages().get(idx - 1), party);
        return new Setup(orch, run, view);
    }

    @Test
    void threeMinutesOfABusyBotAndAnUnchangedRoomGiveTheStageUp() {
        Setup s = stage31();
        Map<Integer, SolverStep> first = s.orch.tick(s.run, s.view, START + 1_000);
        assertInstanceOf(SolverStep.HitReactor.class, first.get(MINER), "the miner is sent at its ore rock");
        for (long t = 10_000; t <= 170_000; t += 10_000) {
            s.orch.tick(s.run, s.view, START + t);
            assertFalse(s.run.ended(), "under three minutes: " + s.run.endedBecause());
        }
        s.orch.tick(s.run, s.view, START + 182_000);
        assertTrue(s.run.ended(), "three minutes and nothing changed");
        assertTrue(s.run.endedBecause().startsWith(Orchestrator.GAVE_UP + s.run.currentStage() + ": no progress"),
                s.run.endedBecause());
        assertEquals(RunState.Ending.LOST, s.run.ending(), "a stage given up is a lost run, not a stopped one");
    }

    @Test
    void aRoomThatKeepsChangingIsNotAStall() {
        Setup s = stage31();
        for (long t = 1_000; t <= 300_000; t += 10_000) {
            s.view.reactorState = (int) (t / 60_000) % 5; // the rock answers a hit about once a minute
            s.orch.tick(s.run, s.view, START + t);
        }
        assertFalse(s.run.ended(), "progress every minute: " + s.run.endedBecause());
    }

    @Test
    void theRealPlayersAreToldInPlainWordsWithoutTheWordBot() {
        String line = Orchestrator.GAVE_UP_TO_PLAYERS;
        assertFalse(line.toLowerCase().contains("bot"), line);
        assertTrue(line.contains("regular"), line);
    }

    @Test
    void aMinerWithNoRouteToItsRockGivesTheStageUpAtOnce() {
        Setup s = stage31();
        StepExecutor.routeCheck = (bot, x, y) -> "no route from r6 to r9 walking";
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(MINER);
        when(bot.getName()).thenReturn("Miner");
        when(bot.getMapId()).thenReturn(BIG_ROOM);
        when(bot.getPosition()).thenReturn(new java.awt.Point(141, 78));
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        server.maps.Reactor rock = mock(server.maps.Reactor.class);
        when(rock.getPosition()).thenReturn(new java.awt.Point(208, 1217));
        when(rock.getId()).thenReturn(2_112_011);
        when(map.getReactorById(2_112_011)).thenReturn(rock);
        when(bot.getMap()).thenReturn(map);
        StepExecutor exec = new StepExecutor();
        List<String> lines = new ArrayList<>();
        SolverStep hit = new SolverStep.HitReactor(2_112_011);
        try (org.mockito.MockedStatic<soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement> move =
                     org.mockito.Mockito.mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            exec.execute(s.run, s.orch, null, bot, hit, START + 1_000, lines::add);
            assertFalse(s.run.ended(), "one 'no route' can be a bot in mid-air");
            exec.execute(s.run, s.orch, null, bot, hit, START + 12_000, lines::add);
        }
        assertTrue(s.run.ended(), "two in a row: lines " + lines);
        assertTrue(s.run.endedBecause().contains("can't reach reactor 2112011"), s.run.endedBecause());
    }

    /** The stage the run stands on, a miner in the big room with its rock untouched, the leader on the hub. */
    private static final class View implements StageView {
        private final Stage stage;
        private final List<RunMember> party;
        int reactorState = 0;

        View(Stage stage, List<RunMember> party) {
            this.stage = stage;
            this.party = party;
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return party;
        }

        @Override
        public int mapOf(int characterId) {
            return characterId == MINER ? BIG_ROOM : HUB;
        }

        @Override
        public Optional<RunMember> leader() {
            return party.stream().filter(RunMember::leader).findFirst();
        }

        @Override
        public int reactorState(int reactorId) {
            return reactorState;
        }

        @Override
        public List<ReactorPlacement> reactorsOn(int mapId) {
            return mapId == BIG_ROOM ? List.of(new ReactorPlacement(10, 2_112_011, "", reactorState, 208, 1217))
                    : List.of();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{141, 78};
        }

        @Override
        public String roleOf(int characterId) {
            return "miner";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return 0;
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }
    }
}
