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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: Cafe PQ, six areas, one shape. Everything expected here is in the scripts' own text -
 * {@code scripts/event/CafePQ_1.js} .. {@code _6.js} and {@code scripts/npc/1052013.js}.
 */
class CafePqSolversTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;
    private static final int COMPUTER = 1052013;
    private static final int COUPON = 4001007;

    private static List<MechanicsModel.Content> areas() {
        List<MechanicsModel.Content> rows = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            MechanicsModel.Content c = MechanicsRuns.contentFor("CafePQ_" + n);
            assertNotNull(c, "content/cafepq" + n + ".yaml must be listed in content/index.txt");
            rows.add(c);
        }
        return rows;
    }

    private static MechanicsModel.Content area(int n) {
        return areas().get(n - 1);
    }

    private static List<RunMember> two() {
        return List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT));
    }

    private static RunMember member(boolean leader) {
        return leader ? two().get(0) : two().get(1);
    }

    // ---- the six rows -------------------------------------------------------------------------------

    @Test
    void theSixAreasAreSixRowsOffOneSwitchAndEachHasTheScriptsOwnGates() {
        List<MechanicsModel.Content> rows = areas();
        for (int n = 1; n <= 6; n++) {
            MechanicsModel.Content c = rows.get(n - 1);
            assertEquals("cafepq" + n, c.id());
            assertEquals("CafePQ_" + n, c.eventScript(), "one event per area: they are six separate scripts");
            assertEquals(MechanicsModel.Kind.PQ, c.kind());
            assertEquals(COMPUTER, c.entryNpc(), "the Computer is the front door on 193000000");
            assertEquals(193000000, c.recruitMap());
            assertEquals(193000000, c.exitMap());
            assertEquals(3, c.minPlayers(), "CafePQ_" + n + ".js:27");
            assertEquals(6, c.maxPlayers());
            assertEquals(21, c.minLevel(), "CafePQ_" + n + ".js:28");
            assertEquals(255, c.maxLevel());
            assertEquals(45, c.timeLimitMinutes(), "CafePQ_" + n + ".js:38");
            assertEquals(Mechanics.CAFE_FLAG, c.flag(),
                    "one switch for the six areas: they are one loop behind six doors, and six flags would be six ways"
                            + " to be half-on");
            assertEquals(1, c.stages().size(), "each area is its own event with its own single stage");
        }
    }

    @Test
    void eachAreaFarmsItsOwnMapsForTheScriptsOwnCount() {
        int[][] maps = {
                {190000000, 190000001, 190000002},
                {191000000, 191000001},
                {192000000, 192000001},
                {195000000, 195010000, 195020000, 195030000},
                {196000000, 196010000},
                {197000000, 197010000}};
        int[] needs = {400, 350, 350, 450, 500, 300};
        for (int n = 1; n <= 6; n++) {
            MechanicsModel.Stage stage = area(n).stages().get(0);
            assertEquals(maps[n - 1][0], stage.mapId(), "area " + n + " enters on its first eventMap");
            assertEquals(needs[n - 1], CafeCouponsSolver.need(viewOf(stage)), "area " + n + " needs its own count");
            assertEquals(COUPON, CafeCouponsSolver.item(viewOf(stage)));
            View view = viewOf(stage);
            assertEquals(java.util.Arrays.stream(maps[n - 1]).skip(1).boxed().toList(),
                    CafeAreas.siblingsOf(stage.mapId()).stream().toList(),
                    "the area's other maps are the script's eventMaps, in its order");
            assertEquals(java.util.Set.copyOf(CafeAreas.siblingsOf(stage.mapId())),
                    new java.util.HashSet<>(new CafeCouponsSolver().extraMaps(view)),
                    "a bot that wandered into a sibling room is not behind");
        }
    }

    @Test
    void everyAreaClearsOnTheInstanceFlagAndOnNoProperty() {
        for (MechanicsModel.Content c : areas()) {
            MechanicsModel.Clear clear = c.stages().get(0).clear();
            assertNotNull(clear);
            assertTrue(clear.onInstance(), c.id() + " clears when the Computer calls eim.clearPQ()");
            assertNull(clear.eventProperty(), c.id() + ": nothing in CafePQ_*.js writes a property on clear");
            assertNull(clear.equals());
        }
    }

    // ---- the solver ---------------------------------------------------------------------------------

    @Test
    void theLeaderTalksToTheComputerOnlyWithTheWholeCountInHand() {
        MechanicsModel.Stage stage = area(1).stages().get(0);
        View view = viewOf(stage);
        view.items.put(LEADER + ":" + COUPON, 399);

        assertFalse(new CafeCouponsSolver().next(view, member(true)) instanceof SolverStep.TalkNpc,
                "399 of 400 coupons is not a clear");

        view.items.put(LEADER + ":" + COUPON, 400);
        SolverStep.TalkNpc talk = assertInstanceOf(SolverStep.TalkNpc.class,
                new CafeCouponsSolver().next(view, member(true)));
        assertEquals(COMPUTER, talk.npcId());
        assertEquals(0, talk.itemId(), "no item rides along: 1052013.js:59 PAYS the party its coupons, it does not take"
                + " them, so a hand-in would ask the bots for something the NPC is giving them");
        assertEquals(0, talk.count());
    }

    @Test
    void aMemberWithTheWholeCountDropsThemByTheLeaderInstead() {
        MechanicsModel.Stage stage = area(1).stages().get(0);
        View view = viewOf(stage);
        view.items.put(MEMBER + ":" + COUPON, 400);

        SolverStep step = new CafeCouponsSolver().next(view, member(false));
        SolverStep.DropFor drop = assertInstanceOf(SolverStep.DropFor.class, step);
        assertEquals(COUPON, drop.itemId());
        assertEquals(LEADER, drop.toCharacterId());
    }

    @Test
    void couponsOnTheFloorAreLootedAndTheRestAreFoughtFor() {
        MechanicsModel.Stage stage = area(1).stages().get(0);
        View view = viewOf(stage);
        view.mobs = 3;

        assertEquals(COUPON, assertInstanceOf(SolverStep.Fight.class,
                new CafeCouponsSolver().next(view, member(false))).itemId(),
                "the generic fight keeps the area's coupons, which is what the script drops by hand");

        view.floor.add(COUPON);
        assertEquals(COUPON, assertInstanceOf(SolverStep.Loot.class,
                new CafeCouponsSolver().next(view, member(false))).itemId());

        view.mobs = 0;
        view.floor.clear();
        assertTrue(new CafeCouponsSolver().next(view, member(true)) instanceof SolverStep.Wait,
                "nothing alive and nothing on the floor: wait for the 15 s respawn (CafePQ_1.js:140-148)");
    }

    @Test
    void aMemberOutsideTheAreaWalksBackAndAClearedInstanceIsDone() {
        MechanicsModel.Stage stage = area(1).stages().get(0);
        View view = viewOf(stage);
        view.at.put(MEMBER, 190000001);

        assertEquals(190000000, assertInstanceOf(SolverStep.WarpTo.class,
                new CafeCouponsSolver().next(view, member(false))).mapId());

        view.cleared = true;
        assertInstanceOf(SolverStep.Done.class, new CafeCouponsSolver().next(view, member(false)));
    }

    /** The row alone: a fake view over one shipped stage. */
    private static View viewOf(MechanicsModel.Stage stage) {
        View view = new View(stage);
        view.at.put(LEADER, stage.mapId());
        view.at.put(MEMBER, stage.mapId());
        return view;
    }

    private static final class View implements StageView {
        private final MechanicsModel.Stage stage;
        private final Map<String, Integer> items = new HashMap<>();
        private final Map<Integer, Integer> at = new HashMap<>();
        private final List<Integer> floor = new ArrayList<>();
        private int mobs;

        View(MechanicsModel.Stage stage) {
            this.stage = stage;
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return two();
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
            return items.getOrDefault(characterId + ":" + itemId, 0);
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{characterId * 10, 0};
        }

        @Override
        public int mapOf(int characterId) {
            return at.getOrDefault(characterId, stage.mapId());
        }

        @Override
        public int liveMobs() {
            return mobs;
        }

        @Override
        public int floorItems(int itemId, int forCharacterId) {
            return (int) floor.stream().filter(i -> i == itemId).count();
        }

        private boolean cleared;

        @Override
        public boolean eventCleared() {
            return cleared;
        }
    }
}
