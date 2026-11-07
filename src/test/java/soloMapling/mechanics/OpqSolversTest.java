package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: the five Orbis PQ stages against a fake room, no server. Every expectation here is what
 * {@code scripts/npc/2013001.js} asks for on that map: the altar takes one drop of twenty cloud pieces
 * ({@code scripts/reactor/2006000.js}), the storage room clears when no monster and no item is left
 * ({@code countMonsters() == 0 && countItems() == 0}), the lounge takes forty pieces, and the five levers must end
 * matching the five characters the script wrote into {@code stage6_c}.
 */
class OpqSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;

    private static MechanicsModel.Content opq() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("OrbisPQ");
        assertNotNull(content, "content/opq.yaml must be listed in content/index.txt");
        return content;
    }

    private static MechanicsModel.Stage stageAt(int mapId) {
        return opq().stages().stream().filter(s -> s.mapId() == mapId).findFirst().orElseThrow();
    }

    @Test
    void theRowIsSevenStagesToTheLeversAndItHasItsOwnSwitch() {
        MechanicsModel.Content content = opq();

        assertEquals(MechanicsModel.Kind.PQ, content.kind());
        assertEquals(Mechanics.OPQ_FLAG, content.flag(), "Orbis PQ must not answer to -Dslums.mechanics alone");
        assertEquals(List.of(920010000, 920010200, 920010300, 920010400, 920010500, 920010601, 920010700, 920010100),
                content.stages().stream().map(MechanicsModel.Stage::mapId).toList(),
                "entrance, walkway, storage, lobby, sealed room, a lounge room, the levers, the statue: script stages 0-8");
        assertEquals(List.of("statusStg0", "statusStg1", "statusStg2", "statusStg3", "statusStg4", "statusStg5",
                        "statusStg6", "statusStg8"),
                content.stages().stream().map(s -> s.clear().eventProperty()).toList());
        assertEquals("2", content.stages().get(3).clear().equals(),
                "2013001.js:920010400 writes statusStg3 = 1 and then 2 in the same talk, so 2 is the value that stays");
        Map<String, StageSolver> solvers = Orchestrator.allSolvers();
        for (MechanicsModel.Stage stage : content.stages()) {
            assertTrue(solvers.containsKey(stage.solver()), "no solver for " + stage.solver() + " in Orchestrator");
        }
    }

    // ---- stage 0, the entrance: twenty cloud pieces on the altar, then Eak -------------------------------

    @Test
    void aBotWithNothingInItsBagHitsTheClouds() {
        View view = new View(stageAt(920010000), members());
        view.map.put(LEADER, 920010000);
        view.map.put(MEMBER, 920010000);

        SolverStep.HitReactor hit = assertInstanceOf(SolverStep.HitReactor.class,
                new OpqCloudsSolver().next(view, member(false)));
        assertEquals(2002001, hit.reactorId());
    }

    @Test
    void theLeaderPutsTheTwentyPiecesOnTheAltarAsOneDrop() {
        View view = new View(stageAt(920010000), members());
        view.map.put(LEADER, 920010000);
        view.items.put(LEADER + ":4001063", 20);

        SolverStep.PlaceItem place = assertInstanceOf(SolverStep.PlaceItem.class,
                new OpqCloudsSolver().next(view, member(true)));
        assertEquals(4001063, place.itemId());
        assertEquals(20, place.count(), "roles/reactor/2006000.js only fires for a drop of exactly twenty");
        assertEquals("2006000", place.reactor());
    }

    @Test
    void eakIsAskedOnlyOnceTheAltarHasBeenUsed() {
        View view = new View(stageAt(920010000), members());
        view.map.put(LEADER, 920010000);
        assertInstanceOf(SolverStep.HitReactor.class, new OpqCloudsSolver().next(view, member(true)),
                "before the altar is hit Eak does not stand on the map at all, so the leader farms clouds too");

        view.reactors.put("2006000", 1);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqCloudsSolver().next(view, member(true)));
        assertEquals(2013001, talk.npcId());
    }

    @Test
    void aHelperDropsItsPiecesForTheLeader() {
        View view = new View(stageAt(920010000), members());
        view.map.put(LEADER, 920010000);
        view.map.put(MEMBER, 920010000);
        view.items.put(MEMBER + ":4001063", 7);

        SolverStep.DropFor drop = assertInstanceOf(SolverStep.DropFor.class,
                new OpqCloudsSolver().next(view, member(false)));
        assertEquals(4001063, drop.itemId());
        assertEquals(7, drop.count());
        assertEquals(LEADER, drop.toCharacterId());
    }

    // ---- stage 2, the storage room: nothing left on the floor -------------------------------------------

    @Test
    void theStorageRoomIsSweptOfMonstersThenOfEveryDropThenEak() {
        OpqSweepSolver solver = new OpqSweepSolver();
        View view = new View(stageAt(920010300), members());
        view.map.put(LEADER, 920010300);

        view.mobs = 3;
        assertEquals(0, assertInstanceOf(SolverStep.Fight.class, solver.next(view, member(true))).itemId(),
                "the room is cleared by fighting, no item is being farmed");

        view.mobs = 0;
        view.floor.put("0:" + LEADER, 4); // mesos and drops both count for countItems()
        assertEquals(0, assertInstanceOf(SolverStep.Loot.class, solver.next(view, member(true))).itemId(),
                "Loot(0) is the whole floor: countItems() counts mesos too");

        view.floor.clear();
        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                solver.next(view, member(true))).npcId());
    }

    // ---- stage 5, the lounge room: forty pieces, Eak is in the lounge -----------------------------------

    @Test
    void theLeaderWalksBackToTheLoungeWithFortyPieces() {
        OpqSubRoomSolver solver = new OpqSubRoomSolver();
        View view = new View(stageAt(920010601), members());
        view.map.put(LEADER, 920010601);
        view.items.put(LEADER + ":4001052", 40);

        SolverStep.WarpTo warp = assertInstanceOf(SolverStep.WarpTo.class, solver.next(view, member(true)));
        assertEquals(OpqSubRoomSolver.EAK_MAP, warp.mapId(), "Eak stands in the lounge, not in a side room");

        view.map.put(LEADER, OpqSubRoomSolver.EAK_MAP);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class, solver.next(view, member(true)));
        assertEquals(40, talk.count());
        assertEquals(4001052, talk.itemId());
    }

    @Test
    void aHelperFarmsTheSideRoomAndNeverFollowsTheLeaderOut() {
        OpqSubRoomSolver solver = new OpqSubRoomSolver();
        View view = new View(stageAt(920010601), members());
        view.map.put(LEADER, OpqSubRoomSolver.EAK_MAP); // the leader has left with his forty
        view.map.put(MEMBER, 920010601);
        view.items.put(MEMBER + ":4001052", 12);
        view.mobs = 4;

        SolverStep.Fight fight = assertInstanceOf(SolverStep.Fight.class, solver.next(view, member(false)),
                "with Eak elsewhere the pieces stay in the bag: nobody hands them over across maps");
        assertEquals(4001052, fight.itemId());

        view.items.put(MEMBER + ":4001052", 0);
        view.mobs = 0;
        view.floor.put("4001052:" + MEMBER, 5);
        assertEquals(4001052, assertInstanceOf(SolverStep.Loot.class, solver.next(view, member(false))).itemId());
    }

    // ---- stage 6, the levers: only the levers that differ from stage6_c --------------------------------

    @Test
    void theFirstTalkIsWhatPicksTheCombination() {
        View view = new View(stageAt(920010700), members());
        view.map.put(LEADER, 920010700);

        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqLeversSolver().next(view, member(true))).npcId());
        assertInstanceOf(SolverStep.Wait.class, new OpqLeversSolver().next(view, member(false)),
                "everyone else waits for Eak to write stage6_c");
    }

    @Test
    void onlyTheLeversThatDisagreeWithTheCombinationArePulled() {
        // one bot in the party owns all five levers, so the assertion names the lever and not the share-out
        View view = new View(stageAt(920010700), List.of(member(true)));
        view.map.put(LEADER, 920010700);
        view.props.put("stage6_c", "01010"); // levers 2 and 4 must be on
        for (int lever = 1; lever <= 5; lever++) {
            view.reactors.put(String.valueOf(lever), 0);
        }
        view.reactors.put("2", 1); // already right

        SolverStep.HitReactorNamed hit = assertInstanceOf(SolverStep.HitReactorNamed.class,
                new OpqLeversSolver().next(view, member(true)));
        assertEquals("4", hit.reactorName(), "lever 4 is wanted but off; lever 2 is already on and must not be toggled");

        view.reactors.put("4", 1);
        view.reactors.put("1", 1); // somebody pushed a wrong one
        assertEquals("1", assertInstanceOf(SolverStep.HitReactorNamed.class,
                new OpqLeversSolver().next(view, member(true))).reactorName());
    }

    @Test
    void theFiveLeversAreSharedOutOverTheBotsSoNoTwoToggleTheSameOne() {
        View view = new View(stageAt(920010700), List.of(
                new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT)));

        assertEquals(List.of(1, 3, 5), OpqLeversSolver.mine(view, LEADER));
        assertEquals(List.of(2, 4), OpqLeversSolver.mine(view, MEMBER));
    }

    @Test
    void onceTheCombinationMatchesTheLeaderAsksEakForTheClear() {
        View view = new View(stageAt(920010700), members());
        view.map.put(LEADER, 920010700);
        view.props.put("stage6_c", "10010");
        view.reactors.put("1", 1);
        view.reactors.put("4", 1);

        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqLeversSolver().next(view, member(true))).npcId());
        assertInstanceOf(SolverStep.Wait.class, new OpqLeversSolver().next(view, member(false)));
    }

    @Test
    void aClearedStageStopsTheSolver() {
        View view = new View(stageAt(920010700), members());
        view.props.put("statusStg6", "1");
        assertInstanceOf(SolverStep.Done.class, new OpqLeversSolver().next(view, member(true)));
    }

    // ---- the fake room ---------------------------------------------------------------------------------

    // ---- stage 3, the lobby: the day's pass on the music player -------------------------------------

    private static final int SUNDAY = 4001056;

    @Test
    void theLobbyAsksForTheDayTheMusicReactorWasArmedWith() {
        View view = new View(stageAt(920010400), members());
        view.eventStates.put(OpqLobbySolver.MUSIC_REACTOR, 3);
        assertEquals(SUNDAY + 3, OpqLobbySolver.passItem(view), "getReactItem(day) is 4001056 + day from the reactor");

        view.eventStates.put(OpqLobbySolver.MUSIC_REACTOR, 0);
        assertEquals(SUNDAY, OpqLobbySolver.passItem(view), "Sunday's run wants Sunday's pass");
    }

    @Test
    void aBotHoldingTheDaysPassPutsItOnTheMusicPlayerAndNotBefore() {
        View view = new View(stageAt(920010400), members());
        view.eventStates.put(OpqLobbySolver.MUSIC_REACTOR, 3);
        view.items.put(LEADER + ":" + (SUNDAY + 3), 1);

        SolverStep.PlaceItem place = assertInstanceOf(SolverStep.PlaceItem.class,
                new OpqLobbySolver().next(view, member(true)));
        assertEquals(SUNDAY + 3, place.itemId());
        assertEquals(1, place.count(), "activateItemReactors matches the quantity too: one pass, not a stack");
        assertEquals(OpqLobbySolver.MUSIC_REACTOR, place.reactor());
    }

    @Test
    void theWrongDaysPassIsNeverPlaced() {
        View view = new View(stageAt(920010400), members());
        view.eventStates.put(OpqLobbySolver.MUSIC_REACTOR, 3);
        view.items.put(LEADER + ":" + SUNDAY, 1);

        assertInstanceOf(SolverStep.HitReactor.class, new OpqLobbySolver().next(view, member(true)),
                "a Sunday pass on a Wednesday run matches no slot, so the party keeps looking instead of placing it");
    }

    @Test
    void aPassOnTheFloorIsLootedAndTheBoxesAreCycled() {
        View view = new View(stageAt(920010400), members());
        view.eventStates.put(OpqLobbySolver.MUSIC_REACTOR, 3);
        view.floor.put((SUNDAY + 3) + ":" + MEMBER, 1);
        assertEquals(SUNDAY + 3, assertInstanceOf(SolverStep.Loot.class,
                new OpqLobbySolver().next(view, member(false))).itemId());

        View bare = new View(stageAt(920010400), members());
        bare.eventStates.put(OpqLobbySolver.MUSIC_REACTOR, 3);
        SolverStep.HitReactor hit = assertInstanceOf(SolverStep.HitReactor.class,
                new OpqLobbySolver().next(bare, member(false)));
        assertTrue(hit.reactorId() >= OpqLobbySolver.FIRST_BOX
                        && hit.reactorId() < OpqLobbySolver.FIRST_BOX + OpqLobbySolver.DAYS,
                "the seven lp boxes are reactor ids 2002004-2002010, got " + hit.reactorId());

        bare.now = OpqLobbySolver.BOX_ROTATE_MS;
        assertEquals(hit.reactorId() + 1, assertInstanceOf(SolverStep.HitReactor.class,
                new OpqLobbySolver().next(bare, member(false))).reactorId(), "the handout moves on with time");
    }

    @Test
    void theLobbyClearsThroughEakOnceThePlayerHasItsPass() {
        View view = new View(stageAt(920010400), members());
        view.eventStates.put(OpqLobbySolver.MUSIC_REACTOR, 2);
        view.props.put("statusStg3", "0");

        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqLobbySolver().next(view, member(true))).npcId());
        assertInstanceOf(SolverStep.Wait.class, new OpqLobbySolver().next(view, member(false)),
                "one bot talks, the others hold");
    }

    // ---- stage 4, the sealed room: the split Eak rolled ----------------------------------------------

    private static View sealed(List<RunMember> members) {
        View view = new View(stageAt(920010500), members);
        view.areas.add(new java.awt.Rectangle(-205, -850, 86, 83));
        view.areas.add(new java.awt.Rectangle(-71, -963, 61, 87));
        view.areas.add(new java.awt.Rectangle(40, -850, 76, 74));
        view.eak = new int[]{-251, -900};
        view.props.put("statusStg4", "0");
        return view;
    }

    @Test
    void theSealedRoomSplitsThePartyTheWayEakRolled() {
        View view = sealed(members());
        view.props.put("stage4_0", "2");
        view.props.put("stage4_1", "1");
        view.props.put("stage4_2", "0");

        assertEquals(0, OpqSealedSolver.platformFor(OpqSealedSolver.split(view), 0));
        assertEquals(0, OpqSealedSolver.platformFor(OpqSealedSolver.split(view), 1),
                "a split of two on the first platform sends the first two members there");
        assertEquals(1, OpqSealedSolver.platformFor(OpqSealedSolver.split(view), 2));
        assertEquals(-1, OpqSealedSolver.platformFor(OpqSealedSolver.split(view), 3),
                "the split is full after three, so any further member stays off the platforms");

        SolverStep.MoveTo move = assertInstanceOf(SolverStep.MoveTo.class,
                new OpqSealedSolver().next(view, member(false)));
        assertTrue(view.area(0).contains(move.x(), move.y()), "walking to the middle of platform 1, " + move.x() + ","
                + move.y());
    }

    @Test
    void aPartyOneShortOnAPlatformNeverTalksForTheClear() {
        List<RunMember> three = List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT),
                new RunMember(3, false, true, MechanicsPersona.PERFECT));
        View view = sealed(three);
        view.props.put("stage4_0", "2");
        view.props.put("stage4_1", "1");
        view.props.put("stage4_2", "0");
        // one and one instead of two and one: the member that belongs on the first platform is on the second
        view.where.put(LEADER, new int[]{-162, -808});
        view.where.put(MEMBER, new int[]{-40, -919});
        view.where.put(3, new int[]{-251, -900});

        assertFalse(OpqSealedSolver.standingRight(view, OpqSealedSolver.split(view)));
        assertFalse(new OpqSealedSolver().next(view, member(true)) instanceof SolverStep.TalkNpc,
                "the script would only answer 'there need to be exactly 2 players on this platform', so the leader holds");
        assertInstanceOf(SolverStep.MoveTo.class, new OpqSealedSolver().next(view, member(false)),
                "the member that is on the wrong platform walks to the one the split gave it");

        // now the two and one the split asked for
        view.where.put(MEMBER, new int[]{-190, -800});
        view.where.put(3, new int[]{-40, -919});
        assertTrue(OpqSealedSolver.standingRight(view, OpqSealedSolver.split(view)));
        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqSealedSolver().next(view, member(true))).npcId(),
                "two on the first platform and one on the second is the split, so the leader talks for the clear");
    }

    @Test
    void theSplitIsReadEveryTickBecauseItIsRolledPerRun() {
        View view = sealed(members());
        view.props.put("stage4_0", "1");
        view.props.put("stage4_1", "1");
        view.props.put("stage4_2", "1");
        assertEquals(1, OpqSealedSolver.platformFor(OpqSealedSolver.split(view), 1));

        view.props.put("stage4_0", "0");
        view.props.put("stage4_1", "0");
        view.props.put("stage4_2", "3");
        assertEquals(2, OpqSealedSolver.platformFor(OpqSealedSolver.split(view), 1));
    }

    @Test
    void aMemberTheSplitDoesNotNeedWaitsBesideEakOffThePlatforms() {
        View view = sealed(List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT),
                new RunMember(3, false, true, MechanicsPersona.PERFECT)));
        view.props.put("stage4_0", "1");
        view.props.put("stage4_1", "1");
        view.props.put("stage4_2", "0");

        SolverStep.MoveTo move = assertInstanceOf(SolverStep.MoveTo.class,
                new OpqSealedSolver().next(view, new RunMember(3, false, true, MechanicsPersona.PERFECT)));
        assertEquals(-251, move.x(), "Eak's own spot on 920010500 is clear of all three areas");
        assertEquals(-900, move.y());
    }

    @Test
    void theSealedRoomAsksEakForTheSplitBeforeItStandsAnywhere() {
        View view = sealed(members());
        view.props.remove("statusStg4");
        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqSealedSolver().next(view, member(true))).npcId());
        assertInstanceOf(SolverStep.Wait.class, new OpqSealedSolver().next(view, member(false)));
    }

    // ---- the box a clear opens ----------------------------------------------------------------------

    @Test
    void theStageThatFollowsTakesThePieceTheLastClearOpened() {
        View view = sealed(members());
        view.map.put(MEMBER, 920010400);
        SolverStep.HitReactorNamed hit = assertInstanceOf(SolverStep.HitReactorNamed.class,
                new OpqSealedSolver().next(view, member(false)));
        assertEquals("stone3", hit.reactorName(), "the lobby's clear opens stone3, the third-piece box");

        view.floor.put("4001046:" + MEMBER, 1);
        assertEquals(4001046, assertInstanceOf(SolverStep.Loot.class,
                new OpqSealedSolver().next(view, member(false))).itemId());

        view.floor.clear();
        view.now = OpqBoxes.GIVE_UP_MS + 1;
        assertInstanceOf(SolverStep.WarpTo.class, new OpqSealedSolver().next(view, member(false)),
                "a box that never gives anything must not wedge the run");
    }

    // ---- the last stage: the six scars, the garden, the base ------------------------------------------

    @Test
    void everyScarTakesExactlyOnePieceAndItIsNotItsOwnNumber() {
        assertEquals(6, OpqStatueSolver.SCAR_PIECE.size());
        assertEquals(4001044, OpqStatueSolver.SCAR_PIECE.get("scar4"), "Reactor.wz/2008003 accepts 4001044");
        assertEquals(4001045, OpqStatueSolver.SCAR_PIECE.get("scar6"), "Reactor.wz/2008005 accepts 4001045");
        assertEquals(4001046, OpqStatueSolver.SCAR_PIECE.get("scar1"), "Reactor.wz/2008000 accepts 4001046");
        assertEquals(4001047, OpqStatueSolver.SCAR_PIECE.get("scar3"), "Reactor.wz/2008002 accepts 4001047");
        assertEquals(4001048, OpqStatueSolver.SCAR_PIECE.get("scar5"), "Reactor.wz/2008004 accepts 4001048");
        assertEquals(4001049, OpqStatueSolver.SCAR_PIECE.get("scar2"), "Reactor.wz/2008001 accepts 4001049");
        assertEquals(6, java.util.Set.copyOf(OpqStatueSolver.SCAR_PIECE.values()).size(), "one piece, one scar");
    }

    private static View statue(List<RunMember> members) {
        View view = new View(stageAt(920010100), members);
        view.eak = new int[]{-201, -956};
        return view;
    }

    @Test
    void aPieceIsDroppedOnTheScarThatTakesItAndNotOnTheNextFreeOne() {
        View view = statue(members());
        view.map.put(LEADER, 920010100);
        view.items.put(LEADER + ":4001046", 1);

        SolverStep.PlaceItem place = assertInstanceOf(SolverStep.PlaceItem.class,
                new OpqStatueSolver().next(view, member(true)));
        assertEquals(4001046, place.itemId());
        assertEquals("scar1", place.reactor(), "4001046 is scar1's piece, even though scar4 is free and waiting");

        view.reactors.put("scar1", 1);
        assertInstanceOf(SolverStep.Wait.class, new OpqStatueSolver().next(view, member(true)),
                "with the piece used and no other piece held there is nothing to place");
    }

    @Test
    void theLeaderOnlyOpensTheGardenOnceAllSixScarsAreFull() {
        View view = statue(members());
        view.map.put(LEADER, 920010100);
        for (String scar : List.of("scar1", "scar2", "scar3", "scar4", "scar5")) {
            view.reactors.put(scar, 1);
        }
        assertFalse(OpqStatueSolver.complete(view));
        assertInstanceOf(SolverStep.Wait.class, new OpqStatueSolver().next(view, member(true)),
                "Eak would only answer 'gather the six pieces', so the leader does not talk yet");

        view.reactors.put("scar6", 1);
        assertTrue(OpqStatueSolver.complete(view));
        assertEquals(2013001, assertInstanceOf(SolverStep.TalkNpc.class,
                new OpqStatueSolver().next(view, member(true))).npcId());
        assertInstanceOf(SolverStep.Wait.class, new OpqStatueSolver().next(view, member(false)),
                "the warp is the leader's talk");
    }

    /** The garden as Map.wz 920010800 places it: two seed pots, one Transparent-Item pot, and the Grass pot. */
    private static View garden() {
        View view = statue(members());
        view.map.put(LEADER, 920010800);
        view.placed.add(new StageView.ReactorPlacement(101, 2001000, "pot", 0, 430, 20));
        view.placed.add(new StageView.ReactorPlacement(102, 2001001, "pot", 0, 200, 20));
        view.placed.add(new StageView.ReactorPlacement(103, 2001016, "", 0, 199, 29));
        view.placed.add(new StageView.ReactorPlacement(104, 2002003, "pot", 0, -755, 19));
        return view;
    }

    /**
     * 1.6.6 b365: the garden the way a party plays it, every pot an item reactor (Reactor.wz type 100), none hit:
     * Nependeath seed in a seed pot, the Royal's Transparent Item in 2001016 for Papa Pixie, his Even Stranger Seed in
     * 2002003 for the Grass of Life. The solver used to hit 2001016 and then wait for a Grass of Life no mob drops.
     */
    @Test
    void theGardenGrowsPapaPixieFromSeedsAndTheGrassFromHisSeedNeverAHit() {
        View view = garden();
        OpqStatueSolver solver = new OpqStatueSolver();
        assertFalse(solver.next(view, member(true)) instanceof SolverStep.HitReactor, "an empty garden is not a hit");

        view.mobs = 9;
        assertEquals(OpqStatueSolver.STRANGER_SEED, assertInstanceOf(SolverStep.Fight.class,
                solver.next(view, member(true))).itemId(), "the Nependeaths, the Royals and Papa Pixie are fought");
        view.mobs = 0;

        view.floor.put(OpqStatueSolver.SEED + ":" + LEADER, 1);
        assertEquals(OpqStatueSolver.SEED, assertInstanceOf(SolverStep.Loot.class, solver.next(view, member(true))).itemId());
        view.floor.clear();
        view.items.put(LEADER + ":" + OpqStatueSolver.SEED, 1);
        SolverStep.PlaceItem seed = assertInstanceOf(SolverStep.PlaceItem.class, solver.next(view, member(true)));
        assertEquals(OpqStatueSolver.SEED, seed.itemId());
        assertEquals(430, seed.x(), "into an unplanted seed pot, by its position");
        view.placed.set(0, new StageView.ReactorPlacement(101, 2001000, "pot", 1, 430, 20));
        assertEquals(200, assertInstanceOf(SolverStep.PlaceItem.class, solver.next(view, member(true))).x(),
                "a planted pot is passed over");
        view.items.clear();

        view.items.put(LEADER + ":" + OpqStatueSolver.TRANSPARENT_ITEM, 1);
        SolverStep.PlaceItemOn pixie = assertInstanceOf(SolverStep.PlaceItemOn.class, solver.next(view, member(true)),
                "a Royal that wandered: its item is carried to the pot by hand");
        assertEquals(OpqStatueSolver.POT_REACTOR, pixie.reactorId());
        view.items.clear();

        view.items.put(LEADER + ":" + OpqStatueSolver.STRANGER_SEED, 1);
        SolverStep.PlaceItem grass = assertInstanceOf(SolverStep.PlaceItem.class, solver.next(view, member(true)));
        assertEquals(OpqStatueSolver.STRANGER_SEED, grass.itemId());
        assertEquals(-755, grass.x(), "Papa Pixie's seed goes into 2002003, the Grass pot");
        view.items.clear();

        view.floor.put("4001055:" + LEADER, 1);
        assertEquals(4001055, assertInstanceOf(SolverStep.Loot.class, solver.next(view, member(true))).itemId());
    }

    /** b365: a Nependeath seed never goes into 2002003, though it is also named "pot". */
    @Test
    void aNependeathSeedNeverGoesIntoTheGrassPot() {
        View view = garden();
        view.placed.set(0, new StageView.ReactorPlacement(101, 2001000, "pot", 1, 430, 20));
        view.placed.set(1, new StageView.ReactorPlacement(102, 2001001, "pot", 1, 200, 20));
        view.items.put(LEADER + ":" + OpqStatueSolver.SEED, 1);
        SolverStep next = new OpqStatueSolver().next(view, member(true));
        assertFalse(next instanceof SolverStep.PlaceItem, "every seed pot planted: the Grass pot is not one: " + next);
    }

    @Test
    void theRootOfLifeGoesToTheStatueBaseAndTheBaseEndsTheQuest() {
        View view = statue(members());
        view.map.put(LEADER, 920010800);
        view.items.put(LEADER + ":4001055", 1);

        assertEquals(920010100, assertInstanceOf(SolverStep.WarpTo.class,
                new OpqStatueSolver().next(view, member(true))).mapId(), "the base is in the centre tower");

        view.map.put(LEADER, 920010100);
        SolverStep.PlaceItem place = assertInstanceOf(SolverStep.PlaceItem.class,
                new OpqStatueSolver().next(view, member(true)));
        assertEquals(4001055, place.itemId());
        assertEquals(OpqStatueSolver.BASE_REACTOR, place.reactor(), "the base reactor is named minerva on the map");
        assertEquals(1, place.count(), "activateItemReactors wants the artefact's own quantity");

        view.props.put("statusStg8", "1");
        assertInstanceOf(SolverStep.Done.class, new OpqStatueSolver().next(view, member(true)),
                "scripts/reactor/2006001.js sets statusStg8 = 1 and calls clearPQ");
    }

    @Test
    void theStagesThatTakeAPieceFromTheRoomTheyClearedSayTheyActThere() {
        View sealedRoom = sealed(members());
        assertTrue(new OpqSealedSolver().extraMaps(sealedRoom).contains(920010400),
                "the lobby's clear opens stone3, and the sealed stage has to fetch it");
        assertTrue(new OpqSubRoomSolver().extraMaps(sealedRoom).contains(920010500),
                "the sealed room's clear opens stone4, and the lounge stage has to fetch it");
        View garden = statue(members());
        assertEquals(java.util.Set.of(920010800, 920010700), new OpqStatueSolver().extraMaps(garden),
                "the garden the statue sends the team to, and the levers' room with the sixth piece");
    }

    @Test
    void theFinalStageTakesTheLeversBoxBeforeItLeavesThatRoom() {
        View view = statue(members());
        view.map.put(MEMBER, 920010700);
        SolverStep.HitReactorNamed hit = assertInstanceOf(SolverStep.HitReactorNamed.class,
                new OpqStatueSolver().next(view, member(false)));
        assertEquals("stone6", hit.reactorName(), "the levers' clear opens stone6, the sixth piece's box");
    }

    private static List<RunMember> members() {
        return List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT));
    }

    private static RunMember member(boolean leader) {
        return leader ? new RunMember(LEADER, true, true, MechanicsPersona.PERFECT)
                : new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT);
    }

    private static final class View implements StageView {

        private final MechanicsModel.Stage stage;
        private final List<RunMember> members;
        private final Map<String, String> props = new HashMap<>();
        private final Map<String, Integer> items = new HashMap<>();
        private final Map<Integer, Integer> map = new HashMap<>();
        private final Map<String, Integer> floor = new HashMap<>();
        private final Map<String, Integer> reactors = new HashMap<>();
        private final Map<String, Integer> eventStates = new HashMap<>();
        private final Map<Integer, int[]> where = new HashMap<>();
        private final List<java.awt.Rectangle> areas = new ArrayList<>();
        private int[] eak;
        private final Map<String, Object> memory = new HashMap<>();
        private int mobs;
        long now;
        /** b365: the garden's reactors, as the map places them. */
        final List<StageView.ReactorPlacement> placed = new ArrayList<>();

        @Override
        public List<StageView.ReactorPlacement> reactorsOn(int mapId) {
            return mapId == 920010800 ? placed : List.of();
        }

        View(MechanicsModel.Stage stage, List<RunMember> members) {
            this.stage = stage;
            this.members = members;
        }

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return memory;
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return members;
        }

        @Override
        public String roleOf(int characterId) {
            return characterId == LEADER ? "leader" : "sweeper";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return items.getOrDefault(characterId + ":" + itemId, 0);
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.ofNullable(props.get(key));
        }

        @Override
        public int[] positionOf(int characterId) {
            return where.getOrDefault(characterId, new int[]{0, 0});
        }

        @Override
        public int reactorEventState(String name) {
            return eventStates.getOrDefault(name, -1);
        }

        @Override
        public java.awt.Rectangle area(int index) {
            return index >= 0 && index < areas.size() ? areas.get(index) : null;
        }

        @Override
        public Optional<int[]> npcPosition(int npcId) {
            return eak == null ? Optional.empty() : Optional.of(eak);
        }

        @Override
        public int mapOf(int characterId) {
            return map.getOrDefault(characterId, stage.mapId());
        }

        @Override
        public int liveMobs() {
            return mobs;
        }

        @Override
        public int floorItems(int itemId, int forCharacterId) {
            int sum = 0;
            for (Map.Entry<String, Integer> e : floor.entrySet()) {
                String[] k = e.getKey().split(":");
                int item = Integer.parseInt(k[0]);
                if (itemId > 0 && item != itemId) {
                    continue;
                }
                if (k[1].equals(String.valueOf(forCharacterId))) {
                    sum += e.getValue();
                }
            }
            return sum;
        }

        @Override
        public int reactorState(String name) {
            return reactors.getOrDefault(name, -1);
        }
    }

    /** Kept so a future stage data change that drops a stage is caught by name, not by index. */
    static List<Integer> stageMaps() {
        List<Integer> maps = new ArrayList<>();
        for (MechanicsModel.Stage stage : opq().stages()) {
            maps.add(stage.mapId());
        }
        return maps;
    }

    @Test
    void everyStageHasARoleForEveryMemberAndAClearPropertyOfItsOwn() {
        for (MechanicsModel.Stage stage : opq().stages()) {
            assertFalse(stage.roles().isEmpty(), "stage " + stage.index() + " has no roles");
            assertNotNull(stage.clear(), "stage " + stage.index() + " has no clear");
            String want = stage.mapId() == 920010400 ? "2" : "1";
            assertEquals(want, stage.clear().equals(), "every Orbis clear is statusStgN == 1, except the lobby, whose"
                    + " clearStage(3) is followed by setProperty(\"statusStg3\", \"2\") in the same talk");
        }
    }
}
