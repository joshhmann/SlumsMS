package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: Pirate PQ against a fake ship, no server. Every expectation is what our scripts do - the deck opens on an
 * empty map ({@code scripts/portal/davy_next0.js}), the guide wants the three medals in order
 * ({@code scripts/npc/2094002.js:47-78}), the passage on at exactly {@code stage2 === "3"}
 * ({@code davy_next1.js}), the four doors take one key each ({@code Reactor.wz/2519000..2519003.img.xml}) and
 * {@code davy_next4.js} opens on all four plus an empty map, and the rescued leader's talk is the clear
 * ({@code scripts/npc/2094001.js:45-58}).
 */
class PiratePqSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;

    private static MechanicsModel.Content row() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("PiratePQ");
        assertNotNull(content, "content/piratepq.yaml must be listed in content/index.txt");
        return content;
    }

    private static RunMember member(boolean leader) {
        return new RunMember(leader ? LEADER : MEMBER, leader, true, MechanicsPersona.PERFECT);
    }

    private static PirateQuestSolver solver() {
        return new PirateQuestSolver();
    }

    private static View view(int map) {
        View v = new View(row(), List.of(member(true), member(false)), map);
        return v;
    }

    @Test
    void theRowIsTheScriptsGatesAndOneStageOverTheSixShipMaps() {
        MechanicsModel.Content c = row();
        assertEquals("PiratePQ", c.eventScript());
        assertEquals(3, c.minPlayers(), "PiratePQ.js:29");
        assertEquals(6, c.maxPlayers());
        assertEquals(55, c.minLevel(), "PiratePQ.js:30");
        assertEquals(255, c.maxLevel());
        assertEquals(2094002, c.entryNpc(), "the guide, scripts/npc/2094002.js");
        assertEquals(251010404, c.recruitMap(), "PiratePQ.js:33");
        assertEquals(925100700, c.exitMap(), "PiratePQ.js:32");
        assertEquals(Mechanics.PIRATE_FLAG, c.flag(), "one switch: -Dslums.mechanics.piratepq");

        MechanicsModel.Stage stage = c.stages().get(0);
        assertEquals(925100000, stage.mapId(), "the deck is where the party enters");
        assertEquals("pirate-quest", stage.solver());
        assertNotNull(stage.clear());
        assertTrue(stage.clear().onInstance(), "PiratePQ.js:337-344 writes no property: the instance flag is the clear");
        assertEquals(java.util.Set.of(925100000, 925100100, 925100200, 925100300, 925100400, 925100500),
                new java.util.HashSet<>(stage.mapSet()), "the six maps the script drives");
        assertEquals(20, PirateQuestSolver.handInCount(stageOnly(stage)),
                "the row's HAND_IN says twenty, as 2094002.js:49 does");
    }

    @Test
    void theDeckOpensOnlyOnceItIsEmpty() {
        View v = view(PirateQuestSolver.DECK);
        v.mobs = 3;
        assertTrue(assertInstanceOf(SolverStep.Fight.class, solver().next(v, member(true))).mobIds().isEmpty(),
                "mobIds empty means any of the ship's monsters");
        v.mobs = 0;
        assertEquals("davy_next0", assertInstanceOf(SolverStep.EnterPortal.class,
                solver().next(v, member(true))).portalName(),
                "scripts/portal/davy_next0.js refuses while a monster is alive");
    }

    @Test
    void theGuideAsksForTheThreeMedalsInItsOwnOrder() {
        View v = view(PirateQuestSolver.MEDALS_MAP);
        assertEquals(4001120, PirateQuestSolver.medalWanted(v), "stage2 0: twenty Rookie Medals first");
        v.props.put("stage2", "1");
        assertEquals(4001121, PirateQuestSolver.medalWanted(v));
        v.props.put("stage2", "2");
        assertEquals(4001122, PirateQuestSolver.medalWanted(v));
        v.props.put("stage2", "3");
        assertEquals(0, PirateQuestSolver.medalWanted(v), "3 means the guide is satisfied");
        assertEquals("davy_next1", assertInstanceOf(SolverStep.EnterPortal.class,
                solver().next(v, member(true))).portalName(), "davy_next1.js opens at exactly stage2 === \"3\"");
    }

    @Test
    void theLeaderHandsInTwentyOfTheMedalTheGuideWantsAndNineteenIsNotEnough() {
        View v = view(PirateQuestSolver.MEDALS_MAP);
        v.held.put(LEADER, Map.of(4001120, 19));
        SolverStep short_ = solver().next(v, member(true));
        assertTrue(!(short_ instanceof SolverStep.TalkNpc) || ((SolverStep.TalkNpc) short_).itemId() == 0,
                "nineteen is not a hand-in: " + short_);

        v.held.put(LEADER, Map.of(4001120, 20));
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class, solver().next(v, member(true)));
        assertEquals(PirateQuestSolver.GUIDE, talk.npcId());
        assertEquals(4001120, talk.itemId(), "the medal the guide is asking for right now");
        assertEquals(20, talk.count());
    }

    @Test
    void aMemberPoolsTheMedalsOnTheLeaderAndNeverHandsThemIn() {
        View v = view(PirateQuestSolver.MEDALS_MAP);
        v.held.put(MEMBER, Map.of(4001120, 20));
        SolverStep step = solver().next(v, member(false));
        assertInstanceOf(SolverStep.DropFor.class, step, "a member hands its medals to the leader: " + step);
        assertEquals(20, assertInstanceOf(SolverStep.DropFor.class, step).count());
        View floorOnly = view(PirateQuestSolver.MEDALS_MAP);
        floorOnly.floor.put(4001120, 2);
        assertEquals(4001120, assertInstanceOf(SolverStep.Loot.class,
                solver().next(floorOnly, member(false))).itemId(),
                "a medal on the floor is picked up before anything else");
    }

    @Test
    void eachKeyGoesOnTheFirstShutDoorAndAllFourOpenTheWayIn() {
        View v = view(PirateQuestSolver.DOORS_MAP);
        v.mobs = 2;
        assertInstanceOf(SolverStep.Fight.class, solver().next(v, member(true)),
                "without a key the party fights for one: 4001117 is this map's own drop");
        v.mobs = 0;
        v.held.put(LEADER, Map.of(PirateQuestSolver.KEY, 1));
        SolverStep.PlaceItemOn first = assertInstanceOf(SolverStep.PlaceItemOn.class, solver().next(v, member(true)));
        assertEquals(2519000, first.reactorId(), "the first shut door, in WZ id order");
        assertEquals(PirateQuestSolver.KEY, first.itemId());
        assertEquals(1, first.count(), "the door takes exactly one key");

        for (int door : PirateQuestSolver.DOORS) {
            v.doors.put(door, 1);
        }
        assertEquals(4, PirateQuestSolver.doorsOpen(v));
        assertEquals("davy_next4", assertInstanceOf(SolverStep.EnterPortal.class,
                solver().next(v, member(true))).portalName(), "all four open and the map empty: the way to the boss");
    }

    @Test
    void threeOpenDoorsAreNotAWayThrough() {
        View v = view(PirateQuestSolver.DOORS_MAP);
        v.doors.put(2519000, 1);
        v.doors.put(2519001, 1);
        v.doors.put(2519002, 1);
        v.mobs = 0;
        SolverStep step = solver().next(v, member(true));
        assertTrue(!(step instanceof SolverStep.EnterPortal),
                "davy_next4.js wants all four: " + step);
        assertEquals(2519003, PirateQuestSolver.firstShutDoor(v), "the fourth door is the one left");
        // and the fourth key is found, not imagined: with one in hand it goes on that door
        v.held.put(LEADER, Map.of(PirateQuestSolver.KEY, 1));
        assertEquals(2519003, assertInstanceOf(SolverStep.PlaceItemOn.class,
                solver().next(v, member(true))).reactorId());
    }

    @Test
    void theLordPirateIsFoughtBeforeAnyoneAsksForTheClear() {
        View v = view(PirateQuestSolver.BOSS_MAP);
        v.mobs = 1;
        assertInstanceOf(SolverStep.Fight.class, solver().next(v, member(true)),
                "the boss is still standing: the rescued leader's talk would be refused by nothing, but the fight is first");
        v.mobs = 0;
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class, solver().next(v, member(true)));
        assertEquals(PirateQuestSolver.RESCUED, talk.npcId(), "scripts/npc/2094001.js:45-58 is what calls clearPQ()");
        assertEquals(0, talk.itemId(), "and it takes nothing: the clear is the talk");
        assertInstanceOf(SolverStep.Wait.class, solver().next(v, member(false)), "a member waits for the leader's talk");
    }

    @Test
    void aMemberOnAForeignMapIsSentBackToTheDeck() {
        View v = view(100000000);
        assertEquals(PirateQuestSolver.DECK, assertInstanceOf(SolverStep.WarpTo.class,
                solver().next(v, member(false))).mapId());
    }

    // ---- the fake ship --------------------------------------------------------------------------------

    private static StageView stageOnly(MechanicsModel.Stage stage) {
        return new View(row(), List.of(member(true), member(false)), stage.mapId());
    }

    /**
     * One view per map, with the props, the doors and the inventories a solver may read. Everything else is the interface
     * default, so a read the solver does not make cannot be faked.
     */
    private static final class View implements StageView {
        private final MechanicsModel.Content row;
        private final List<RunMember> members;
        private int here;
        private int mobs;
        private final Map<Integer, Integer> doors = new HashMap<>();
        private final Map<String, String> props = new LinkedHashMap<>();
        private final Map<Integer, Map<Integer, Integer>> held = new HashMap<>();
        private final Map<Integer, Integer> floor = new HashMap<>();

        View(MechanicsModel.Content row, List<RunMember> members, int map) {
            this.row = row;
            this.members = members;
            this.here = map;
        }

        @Override
        public MechanicsModel.Stage stage() {
            return row.stages().get(0);
        }

        @Override
        public List<RunMember> members() {
            return members;
        }

        @Override
        public String roleOf(int characterId) {
            return characterId == LEADER ? "leader" : "collector";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return held.getOrDefault(characterId, Map.of()).getOrDefault(itemId, 0);
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.ofNullable(props.get(key));
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public int mapOf(int characterId) {
            return here;
        }

        @Override
        public int liveMobs() {
            return mobs;
        }

        @Override
        public int floorItems(int itemId, int forCharacterId) {
            return floor.getOrDefault(itemId, 0);
        }

        @Override
        public long nowMs() {
            return 0;
        }

        @Override
        public int reactorState(int reactorId) {
            if (PirateQuestSolver.DOORS.contains(reactorId)) {
                return doors.getOrDefault(reactorId, 0); // a door starts shut; -1 would mean "not on this map"
            }
            return -1;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return new HashMap<>();
        }
    }
}
