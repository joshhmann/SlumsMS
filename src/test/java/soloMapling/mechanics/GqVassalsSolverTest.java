package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guild Quest stage 3, the vassals ({@link GqVassalsSolver}) in a mini world that applies the steps the solver returns
 * and re-implements what {@code scripts/npc/9040006.js} does with the floor (the first talk makes the combo, a later one
 * reads exactly four items in the four areas), so the stage only clears when the offerings really are in place.
 * Drops are owned for ever by the bot that dropped them (a bot's drop is only visible to itself,
 * {@link PassHandover#mayTake}), mob drops are free for all, a reactor's drop belongs to its hitter.
 */
class GqVassalsSolverTest {

    static final int FOUNTAIN = 990_000_500;
    static final int FOOD = 990_000_501;
    static final int DRINK = 990_000_502;
    static final GqVassalsSolver SOLVER = new GqVassalsSolver();
    /** Map9/990000500.img.xml:2254-2279. */
    static final int[][] AREAS = {{-230, 120, -139, 167}, {-103, 116, 22, 167}, {71, 117, 204, 169},
            {222, 128, 318, 168}};

    static MechanicsModel.Stage stage() {
        return new MechanicsModel.Stage(5, FOUNTAIN, "the vassals", 9_040_006, "gq-vassals", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), new MechanicsModel.Clear("stage3clear", "true", false));
    }

    static Rectangle rect(int i) {
        return new Rectangle(AREAS[i][0], AREAS[i][1], AREAS[i][2] - AREAS[i][0], AREAS[i][3] - AREAS[i][1]);
    }

    // ---------------------------------------------------------------- the arithmetic

    @Test
    void theComboIsReadWithItsLeadingZerosAndEveryItemKindMapsToItsDigit() {
        assertArrayEquals(new int[]{0, 0, 3, 0}, GqVassalsSolver.digitsOf("30"), "30 is 0030");
        assertArrayEquals(new int[]{0, 0, 3, 0}, GqVassalsSolver.digitsOf("30.0"), "a number written as a double");
        assertArrayEquals(new int[]{0, 0, 0, 0}, GqVassalsSolver.digitsOf("0"));
        assertArrayEquals(new int[]{0, 1, 2, 3}, GqVassalsSolver.digitsOf("123"), "0123");
        assertArrayEquals(new int[]{3, 3, 3, 3}, GqVassalsSolver.digitsOf("3333"));
        for (String bad : List.of("4000", "abc", "12345", "-1", "1.5", "", "reset")) {
            assertNull(GqVassalsSolver.digitsOf(bad), bad);
        }
        assertEquals(4_001_027, GqVassalsSolver.itemFor(0), "Medal of Valor");
        assertEquals(4_001_028, GqVassalsSolver.itemFor(1), "Scroll of Wisdom");
        assertEquals(4_001_029, GqVassalsSolver.itemFor(2), "Spoiled Food");
        assertEquals(4_001_030, GqVassalsSolver.itemFor(3), "Jr. Necki Drink");

        for (int n = 0; n <= 9999; n++) {
            int[] d = GqVassalsSolver.digitsOf(Integer.toString(n));
            boolean fits = n % 10 <= 3 && n / 10 % 10 <= 3 && n / 100 % 10 <= 3 && n / 1000 <= 3;
            assertEquals(fits, d != null, "combo " + n);
            if (fits) {
                int[] itemInArea = new int[4];
                for (int slot = 0; slot < 4; slot++) {
                    itemInArea[slot] = GqVassalsSolver.itemFor(d[slot]);
                }
                assertEquals(n, GqVassalsSolver.comboOf(itemInArea), "the script's formula gives the combo back");
            }
        }
    }

    @Test
    void theDropPointOfEachAreaLiesInThatAreaOnly() {
        World w = new World(5);
        for (int slot = 0; slot < 4; slot++) {
            Rectangle viaView = GqVassalsSolver.area(w, slot);
            assertEquals(rect(slot), viaView, "the view's area " + slot);
            w.useViewAreas = false;
            assertEquals(rect(slot), GqVassalsSolver.area(w, slot), "the WZ fallback is the same rectangle");
            w.useViewAreas = true;
            int[] at = GqVassalsSolver.placePoint(viaView);
            for (int other = 0; other < 4; other++) {
                assertEquals(other == slot, rect(other).contains(at[0], at[1]), "point " + slot + " in area " + other);
            }
        }
    }

    // ---------------------------------------------------------------- frame

    @Test
    void membersOnThePreviousRoomsWalkOutAndInThroughTheNamedDoors() {
        assertEquals(new SolverStep.EnterPortal("out00"), GqVassalsSolver.route(990_000_410));
        assertEquals(new SolverStep.EnterPortal("out00"), GqVassalsSolver.route(990_000_420));
        assertEquals(new SolverStep.EnterPortal("out00"), GqVassalsSolver.route(990_000_430));
        assertEquals(new SolverStep.EnterPortal("out00"), GqVassalsSolver.route(990_000_431));
        assertEquals(new SolverStep.EnterPortal("out00"), GqVassalsSolver.route(990_000_440));
        assertEquals(new SolverStep.ScriptedPortal("next00"), GqVassalsSolver.route(990_000_400));
        assertEquals(new SolverStep.EnterPortal("next01"), GqVassalsSolver.route(990_000_401));
        assertNull(GqVassalsSolver.route(FOUNTAIN));
        assertNull(GqVassalsSolver.route(FOOD));
        assertNull(GqVassalsSolver.route(DRINK));

        World w = new World(5);
        w.bots.get(3).map = 990_000_431;
        assertEquals(new SolverStep.EnterPortal("out00"), SOLVER.next(w, w.member(w.bots.get(3))));
    }

    @Test
    void anUnprotectedMemberGetsTheProtectionStepFirst() {
        World w = new World(5);
        Bot b = w.bots.get(2);
        b.wearing = false;
        assertInstanceOf(SolverStep.Wait.class, SOLVER.next(w, w.member(b)), "no earring on the member");
        assertEquals(GqProtection.step(w, w.member(b)), SOLVER.next(w, w.member(b)));
        b.bag.put(GqProtection.EARRING, 1);
        assertInstanceOf(SolverStep.EquipItem.class, SOLVER.next(w, w.member(b)));
        // even the leader with no combo yet puts the earring on before it walks up to the guardian
        Bot leader = w.bots.get(1);
        leader.wearing = false;
        leader.bag.put(GqProtection.EARRING, 1);
        assertInstanceOf(SolverStep.EquipItem.class, SOLVER.next(w, w.member(leader)));
    }

    // ---------------------------------------------------------------- talks

    @Test
    void theLeaderTalksOnceForTheComboAndAgainOnlyWhenExactlyTheRightFourLie() {
        World w = new World(5);
        w.combos.add("1230");
        Bot leader = w.bots.get(1);
        // no combo: the leader makes it, the others wait for it and name the guardian
        SolverStep first = SOLVER.next(w, w.member(leader));
        assertEquals(new SolverStep.TalkNpc(9_040_006, 0, 0), first);
        SolverStep other = SOLVER.next(w, w.member(w.bots.get(2)));
        assertInstanceOf(SolverStep.Wait.class, other);
        assertTrue(((SolverStep.Wait) other).why().contains("9040006"));
        w.apply(leader, first);
        assertEquals("1230", w.props.get("stage3combo"));
        assertEquals(1, w.firstTalks);

        // combo made: the leader never makes another and talks again only with the four right items down
        assertFalse(SOLVER.next(w, w.member(leader)) instanceof SolverStep.TalkNpc, "nothing is down");
        w.placeRight("1230", List.of(2, 3, 4));
        assertFalse(SOLVER.next(w, w.member(leader)) instanceof SolverStep.TalkNpc, "three of four");
        Set<Integer> three = Set.of(2, 3, 4);
        assertEquals(3, w.floor.stream().filter(d -> three.contains(d.dropper)).count());
        w.placeRight("1230", List.of(5), 3);
        assertEquals(new SolverStep.TalkNpc(9_040_006, 0, 0), SOLVER.next(w, w.member(leader)), "all four are down");
        assertEquals(1, w.firstTalks);
    }

    @Test
    void aWrongItemOnTheFloorIsPickedUpAndNeverLeftBehind() {
        World w = new World(6);
        w.props.put("stage3combo", "1230");
        w.props.put("stage3attempt", "1");
        w.placeRight("1230", List.of(2, 3, 4, 5));
        Bot leader = w.bots.get(1);
        assertEquals(new SolverStep.TalkNpc(9_040_006, 0, 0), SOLVER.next(w, w.member(leader)));

        // a knight's free-for-all drop: any member loots it, and the leader does not talk meanwhile
        w.floor.add(new Drop(FOUNTAIN, 4_001_028, 500, 145, 0, 0, 5));
        assertEquals(new SolverStep.Loot(4_001_028), SOLVER.next(w, w.member(w.bots.get(6))));
        assertEquals(new SolverStep.Loot(4_001_028), SOLVER.next(w, w.member(leader)),
                "the leader itself clears the floor before it talks");
        w.floor.remove(w.floor.size() - 1);

        // an old offering only its dropper can see (bot 6 dropped it where no area is): bot 6 takes it back, the leader
        // sees through every member that the floor is not clean and waits
        w.floor.add(new Drop(FOUNTAIN, 4_001_027, 0, 145, 6, 6, 6));
        assertEquals(new SolverStep.Loot(4_001_027), SOLVER.next(w, w.member(w.bots.get(6))));
        SolverStep l = SOLVER.next(w, w.member(leader));
        assertInstanceOf(SolverStep.Wait.class, l);
        assertFalse(l instanceof SolverStep.TalkNpc);
        // the right offerings themselves are never picked up again by their bearers
        assertFalse(SOLVER.next(w, w.member(w.bots.get(2))) instanceof SolverStep.Loot);
    }

    @Test
    void theLeaderDoesNotTalkAgainAfterTheVassalsRefusedARightLookingFloor() {
        World w = new World(5);
        w.props.put("stage3combo", "1230");
        w.props.put("stage3attempt", "2");
        w.placeRight("1230", List.of(2, 3, 4, 5));
        SolverStep s = SOLVER.next(w, w.member(w.bots.get(1)));
        assertInstanceOf(SolverStep.Wait.class, s);
        assertTrue(((SolverStep.Wait) s).why().contains("refused"));
    }

    @Test
    void aHumanLeaderIsToldWhenToTalk() {
        World w = new World(5);
        w.bots.get(1).human = true;
        RunMember leader = w.member(w.bots.get(1));
        assertFalse(leader.bot());
        Optional<String> first = SOLVER.leaderPrompt(w, leader);
        assertTrue(first.isPresent() && first.get().contains("9040006") && first.get().contains("@1"), "" + first);

        w.combos.add("2031");
        w.talk(w.bots.get(1));
        assertEquals("2031", w.props.get("stage3combo"));
        assertTrue(SOLVER.leaderPrompt(w, leader).isEmpty(), "the offerings are not down yet");

        // the bots do the carrying; the human is never asked for a step, and talks when told
        int ticks = 0;
        while (SOLVER.leaderPrompt(w, leader).isEmpty() && ticks++ < 1500) {
            w.tick();
        }
        assertTrue(ticks < 1500, "the bots put the four offerings down");
        assertTrue(SOLVER.leaderPrompt(w, leader).get().contains("again"));
        assertFalse(w.cleared());
        w.talk(w.bots.get(1));
        assertTrue(w.cleared());
        assertEquals(0, w.wrongGuesses);
    }

    // ---------------------------------------------------------------- sources

    @Test
    void eachSourceIsHitUntilItsDropAppearsAndTheDecoysAreNeverTouched() {
        World w = new World(5);
        w.combos.add("2200"); // two Spoiled Food from 501, two Medals from the knights
        assertTrue(w.run(600), "cleared");
        List<Integer> used = new ArrayList<>();
        for (int oid = 100; oid <= 103; oid++) {
            int n = w.hits.getOrDefault(oid, 0);
            if (n > 0) {
                assertEquals(5, n, "reactor " + oid + " is hit until its drop falls, not beyond");
                used.add(oid);
            }
        }
        assertEquals(2, used.size(), "two bearers, two reactors");
        for (int oid = 104; oid <= 107; oid++) {
            assertEquals(0, w.hits.getOrDefault(oid, 0), "decoy 9202010 " + oid);
        }
        for (int oid = 200; oid <= 207; oid++) {
            assertEquals(0, w.hits.getOrDefault(oid, 0), "no drink reactor is needed for 2200: " + oid);
        }
        assertEquals(2, w.mobs.stream().filter(m -> m.id == 9_300_022).count(), "two of the four Black Knights died");
        assertEquals(4, w.mobs.stream().filter(m -> m.id == 9_300_023).count(), "no Myst Knight needed");
        assertEquals(4, w.removedOnClear, "exactly the four right offerings lay there when the leader talked");
    }

    @Test
    void missingKnightsMakeAWaitThatNamesTheMobTheItemAndTheUnverifiedDrop() {
        World w = new World(5);
        w.combos.add("0000");
        w.mobs.clear(); // nobody left to kill, nothing on the floor
        assertFalse(w.run(80));
        String text = w.lastWaitWith("9300022");
        assertTrue(text != null && text.contains("4001027") && text.contains("live DB unverified"), "" + w.waits);
    }

    @Test
    void reactorsThatDropNothingAreSpentAndTheWaitNamesReactorAndItem() {
        World w = new World(5);
        w.combos.add("2222");
        w.reactorsDrop = false;
        assertFalse(w.run(800));
        String text = w.lastWaitWith("9202003");
        assertTrue(text != null && text.contains("4001029") && text.contains("live DB unverified"), "" + w.waits);
        for (int oid = 100; oid <= 103; oid++) {
            assertTrue(w.hits.getOrDefault(oid, 0) <= 5, "a spent reactor is not hit again: " + oid);
        }
        for (int oid = 104; oid <= 107; oid++) {
            assertEquals(0, w.hits.getOrDefault(oid, 0), "decoys stay untouched");
        }
        assertEquals(0, w.rightGuesses);
        assertEquals(0, w.wrongGuesses);
    }

    @Test
    void drinksComeFromTheSecondLarderAndHandOverFixesASourceThatRanDry() {
        World w = new World(5);
        w.combos.add("3333");
        assertTrue(w.run(700), "four Jr. Necki Drinks from 9202004 on 990000502");
        for (int oid = 200; oid <= 203; oid++) {
            assertEquals(5, w.hits.getOrDefault(oid, 0));
        }
        assertEquals(0, w.wrongGuesses);

        // a bearer whose knights are all dead is handed the spare Medal another bot picked up
        World h = new World(5);
        h.props.put("stage3combo", "0");
        h.props.put("stage3attempt", "1");
        h.mobs.clear();
        for (int slot = 0; slot < 4; slot++) {
            h.memory.put(GqVassalsSolver.claimKey("0", slot), 2 + slot);
        }
        h.bots.get(6).bag.put(4_001_027, 1);
        assertEquals(new SolverStep.DropFor(4_001_027, 1, 2), SOLVER.next(h, h.member(h.bots.get(6))));
        // from another room it first walks to the bearer's map
        h.bots.get(6).map = FOOD;
        assertEquals(new SolverStep.EnterPortal("out00"), SOLVER.next(h, h.member(h.bots.get(6))));
    }

    // ---------------------------------------------------------------- the whole stage

    @Test
    void theWholeStageClearsForEveryKindOfCombo() {
        for (String raw : List.of("0", "30", "123", "1230", "3333", "2222", "1111", "3210", "3000", "2013", "21")) {
            World w = new World(5);
            w.combos.add(raw);
            // members scattered over the previous stage's rooms walk in through the doors
            w.bots.get(2).map = 990_000_431;
            w.bots.get(3).map = 990_000_400;
            w.bots.get(4).map = 990_000_401;
            w.bots.get(5).map = 990_000_440;
            assertTrue(w.run(1500), "combo " + raw + " clears; waits " + w.waits);
            assertEquals("true", w.props.get("stage3clear"));
            assertEquals(1, w.firstTalks, "the combo is made once (" + raw + ")");
            assertEquals(0, w.wrongGuesses, "no wrong guess (" + raw + ")");
            assertEquals(0, w.nullTalks, "the second talk comes only when the floor is exactly right (" + raw + ")");
            assertEquals(1, w.rightGuesses);
        }
    }

    @Test
    void aResetComboWithOldOfferingsStillLyingEndsWithOnlyTheNewFour() {
        World w = new World(5);
        w.props.put("stage3combo", "reset");
        w.combos.add("3210");
        // the old combo 0123 lies in the four areas, each dropped by a different bot (and so seen by that bot only)
        w.placeRight("123", List.of(2, 3, 4, 5));
        assertEquals(4, w.floorOn(FOUNTAIN).size());
        w.memory.clear();
        assertTrue(w.run(1500), "cleared; waits " + w.waits);
        assertEquals(0, w.wrongGuesses);
        assertEquals("3210", w.props.get("stage3combo"));
        assertEquals(0, w.nullTalks);
    }

    @Test
    void aBotThatDiesBeforePlacingFreesItsSlotAndOneThatLeavesAfterPlacingStillCounts() {
        World w = new World(5);
        w.combos.add("123");
        for (int i = 0; i < 8; i++) {
            w.tick();
        }
        Integer owner = (Integer) w.memory.get(GqVassalsSolver.claimKey("123", 3));
        assertTrue(owner != null, "slot 3 was claimed");
        w.bots.get(owner).alive = false;
        assertTrue(w.run(1500), "clears with a bearer dead; waits " + w.waits);
        assertEquals(0, w.wrongGuesses);

        World v = new World(5);
        v.combos.add("123");
        int guard = 0;
        Bot placed = null;
        while (placed == null && guard++ < 1500) {
            v.tick();
            for (Drop d : v.floorOn(FOUNTAIN)) {
                if (d.dropper > 0 && d.itemId >= 4_001_027) {
                    placed = v.bots.get(d.dropper);
                }
            }
        }
        assertTrue(placed != null, "somebody placed an offering");
        placed.present = false;
        assertTrue(v.run(1500), "clears after a bearer left with its item in place; waits " + v.waits);
        assertEquals(0, v.wrongGuesses);
        assertEquals(0, v.nullTalks);
    }

    @Test
    void aWorldThatNeverGrantsTheEarningStepStaysUnclearedAndKeepsWaiting() {
        World w = new World(5);
        w.combos.add("0123");
        w.reactorsDrop = false;
        w.knightsDrop = false;
        assertFalse(w.run(800));
        assertFalse(w.props.containsKey("stage3clear"));
        assertTrue(w.waits.stream().anyMatch(t -> t.contains("live DB unverified")), "" + w.waits);
        assertEquals(0, w.rightGuesses);
    }

    // ================================================================ the mini world

    static final class Bot {
        final int id;
        final boolean leader;
        boolean human;
        int map = FOUNTAIN;
        int x;
        int y = 145;
        boolean wearing = true;
        boolean alive = true;
        boolean present = true;
        final Map<Integer, Integer> bag = new HashMap<>();

        Bot(int id, boolean leader) {
            this.id = id;
            this.leader = leader;
        }
    }

    static final class Drop {
        final int map;
        final int itemId;
        final int x;
        final int y;
        final int owner;
        final int dropper;
        final long at;

        Drop(int map, int itemId, int x, int y, int owner, int dropper, long at) {
            this.map = map;
            this.itemId = itemId;
            this.x = x;
            this.y = y;
            this.owner = owner;
            this.dropper = dropper;
            this.at = at;
        }
    }

    static final class Mob {
        final int id;
        final int x;
        final int y;

        Mob(int id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    static final class React {
        final int map;
        final int oid;
        final int id;
        int state;
        final int x;
        final int y;

        React(int map, int oid, int id, int x, int y) {
            this.map = map;
            this.oid = oid;
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    static final class World implements StageView {
        final MechanicsModel.Stage stage = GqVassalsSolverTest.stage();
        final Map<Integer, Bot> bots = new TreeMap<>();
        final Map<String, String> props = new HashMap<>();
        final Map<String, Object> memory = new ConcurrentHashMap<>();
        final List<Drop> floor = new ArrayList<>();
        final List<Mob> mobs = new ArrayList<>();
        final List<React> reactors = new ArrayList<>();
        final Deque<String> combos = new ArrayDeque<>();
        final Map<Integer, Integer> hits = new HashMap<>();
        final List<String> waits = new ArrayList<>();
        long now = 1_000;
        boolean knightsDrop = true;
        boolean reactorsDrop = true;
        boolean useViewAreas = true;
        int firstTalks;
        int nullTalks;
        int wrongGuesses;
        int rightGuesses;
        int removedOnClear;

        /** A leader (id 1) and {@code workers} bots (ids 2..), all on 500 with the earring on, a full set of sources. */
        World(int workers) {
            bots.put(1, new Bot(1, true));
            for (int i = 0; i < workers; i++) {
                bots.put(2 + i, new Bot(2 + i, false));
            }
            // (no property is set until the fountain's first talk)
            int[][] knights = {{9_300_022, -181}, {9_300_022, -181}, {9_300_022, 272}, {9_300_022, 272},
                    {9_300_023, -40}, {9_300_023, -40}, {9_300_023, 148}, {9_300_023, 148}};
            for (int[] k : knights) {
                mobs.add(new Mob(k[0], k[1], 144));
            }
            int[][] food = {{-414, -341}, {2, -341}, {-1123, -190}, {1031, -157}};
            for (int i = 0; i < 4; i++) {
                reactors.add(new React(FOOD, 100 + i, 9_202_003, food[i][0], food[i][1]));
                reactors.add(new React(FOOD, 104 + i, 9_202_010, food[i][0] + 50, food[i][1]));
                reactors.add(new React(DRINK, 200 + i, 9_202_004, food[i][0], food[i][1]));
                reactors.add(new React(DRINK, 204 + i, 9_202_011, food[i][0] + 50, food[i][1]));
            }
        }

        RunMember member(Bot b) {
            return new RunMember(b.id, b.leader, !b.human, MechanicsPersona.PERFECT);
        }

        // ---- StageView

        @Override public MechanicsModel.Stage stage() { return stage; }
        @Override public String roleOf(int characterId) { return "all"; }
        @Override public OptionalInt answerFor(int characterId) { return OptionalInt.empty(); }
        @Override public int itemCount(int characterId, int itemId) {
            Bot b = bots.get(characterId);
            return b == null ? 0 : b.bag.getOrDefault(itemId, 0);
        }
        @Override public Optional<String> eventProperty(String key) { return Optional.ofNullable(props.get(key)); }
        @Override public int[] positionOf(int characterId) {
            Bot b = bots.get(characterId);
            return new int[]{b.x, b.y};
        }
        @Override public List<RunMember> members() {
            List<RunMember> out = new ArrayList<>();
            for (Bot b : bots.values()) {
                if (b.present) {
                    out.add(member(b));
                }
            }
            return out;
        }
        @Override public int mapOf(int characterId) { return bots.get(characterId).map; }
        @Override public boolean wearing(int characterId, int itemId) {
            return itemId == GqProtection.EARRING && bots.get(characterId).wearing;
        }
        @Override public boolean alive(int characterId) { return bots.get(characterId).alive; }
        @Override public long nowMs() { return now; }
        @Override public Map<String, Object> stageMemory() { return memory; }
        @Override public java.awt.Rectangle area(int index) {
            return useViewAreas && index >= 0 && index < 4 ? rect(index) : null;
        }
        @Override public int floorItemsOn(int mapId, int itemId, int forCharacterId) {
            int n = 0;
            for (Drop d : floor) {
                if (d.map == mapId && d.itemId == itemId
                        && PassHandover.mayTake(forCharacterId, d.owner, d.dropper, (byte) 0)) {
                    n++;
                }
            }
            return n;
        }
        @Override public int floorItems(int itemId, int forCharacterId) {
            return floorItemsOn(FOUNTAIN, itemId, forCharacterId);
        }
        @Override public Optional<FloorDrop> dropBy(int dropperId, int itemId) {
            Drop best = null;
            for (Drop d : floor) {
                if (d.map == FOUNTAIN && d.itemId == itemId && d.dropper == dropperId && (best == null || d.at > best.at)) {
                    best = d;
                }
            }
            return best == null ? Optional.empty() : Optional.of(new FloorDrop(best.x, best.y, best.owner, best.at));
        }
        @Override public List<ReactorPlacement> reactorsOn(int mapId) {
            List<ReactorPlacement> out = new ArrayList<>();
            for (React r : reactors) {
                if (r.map == mapId) {
                    out.add(new ReactorPlacement(r.oid, r.id, "", r.state, r.x, r.y));
                }
            }
            return out;
        }
        @Override public int mobsAliveOn(int mapId, int mobId) {
            return mapId == FOUNTAIN ? (int) mobs.stream().filter(m -> m.id == mobId).count() : 0;
        }

        // ---- helpers for the tests

        List<Drop> floorOn(int map) {
            return floor.stream().filter(d -> d.map == map).toList();
        }

        String lastWaitWith(String needle) {
            for (int i = waits.size() - 1; i >= 0; i--) {
                if (waits.get(i).contains(needle)) {
                    return waits.get(i);
                }
            }
            return null;
        }

        /** The right offerings for {@code raw}, each dropped by one of {@code bearers} (slot order), claims set. */
        void placeRight(String raw, List<Integer> bearers) {
            placeRight(raw, bearers, 0);
        }

        void placeRight(String raw, List<Integer> bearers, int firstSlot) {
            int[] d = GqVassalsSolver.digitsOf(raw);
            for (int i = 0; i < bearers.size(); i++) {
                int slot = firstSlot + i;
                int[] at = GqVassalsSolver.placePoint(rect(slot));
                floor.add(new Drop(FOUNTAIN, GqVassalsSolver.itemFor(d[slot]), at[0], at[1], bearers.get(i),
                        bearers.get(i), now++));
                memory.put(GqVassalsSolver.claimKey(raw, slot), bearers.get(i));
            }
        }

        boolean run(int maxTicks) {
            for (int t = 0; t < maxTicks && !cleared(); t++) {
                tick();
            }
            return cleared();
        }

        void tick() {
            now += 1_000;
            if (cleared()) {
                return;
            }
            for (Bot b : new ArrayList<>(bots.values())) {
                if (!b.present || b.human || !b.alive) {
                    continue;
                }
                apply(b, SOLVER.next(this, member(b)));
            }
        }

        // ---- the steps' effect

        void apply(Bot b, SolverStep s) {
            switch (s) {
                case SolverStep.MoveTo m -> {
                    b.x = m.x();
                    b.y = m.y();
                }
                case SolverStep.EquipItem e -> {
                    if (b.bag.getOrDefault(e.itemId(), 0) > 0) {
                        b.wearing = true;
                    }
                }
                case SolverStep.Wait w -> waits.add(w.why());
                case SolverStep.EnterPortal p -> portal(b, p.portalName());
                case SolverStep.ScriptedPortal p -> {
                    if (b.map == 990_000_400 && p.portalName().equals("next00")) {
                        b.map = 990_000_401;
                    }
                }
                case SolverStep.TalkNpc t -> {
                    if (t.npcId() == 9_040_006) {
                        talk(b);
                    }
                }
                case SolverStep.Fight f -> fight(b, f);
                case SolverStep.Loot l -> loot(b, l.itemId());
                case SolverStep.PlaceItem p -> place(b, p);
                case SolverStep.DropFor d -> dropFor(b, d);
                case SolverStep.HitReactorByOid h -> hit(b, h.objectId());
                default -> {
                }
            }
        }

        private void portal(Bot b, String name) {
            int to = 0;
            switch (b.map) {
                case FOUNTAIN -> to = name.equals("in00") ? FOOD : name.equals("in01") ? DRINK : 0;
                case FOOD, DRINK -> to = name.equals("out00") ? FOUNTAIN : 0;
                case 990_000_401 -> to = name.equals("next01") ? FOUNTAIN : 0;
                case 990_000_410, 990_000_420, 990_000_430, 990_000_440 -> to = name.equals("out00") ? 990_000_400 : 0;
                case 990_000_431 -> to = name.equals("out00") ? 990_000_430 : 0;
                default -> to = 0;
            }
            if (to != 0) {
                b.map = to;
            }
        }

        /** What 9040006.js does for the leader: the first talk makes the combo, a later one reads the floor. */
        void talk(Bot b) {
            if (b.map != FOUNTAIN || !b.leader) {
                return;
            }
            String combo = props.get("stage3combo");
            if (combo == null || combo.equals("reset")) {
                props.put("stage3combo", combos.pop());
                props.put("stage3attempt", "1");
                firstTalks++;
                return;
            }
            List<Drop> items = new ArrayList<>();
            for (Drop d : floor) {
                if (d.map == FOUNTAIN && d.itemId >= 4_001_027 && d.itemId <= 4_001_030) {
                    items.add(d);
                }
            }
            if (items.size() != 4) {
                nullTalks++;
                return;
            }
            int[] inArea = {-1, -1, -1, -1};
            for (Drop d : items) {
                for (int i = 0; i < 4; i++) {
                    if (rect(i).contains(d.x, d.y)) {
                        inArea[i] = d.itemId - 4_001_027;
                        break;
                    }
                }
            }
            for (int v : inArea) {
                if (v == -1) {
                    nullTalks++;
                    return;
                }
            }
            int guess = inArea[0] * 1000 + inArea[1] * 100 + inArea[2] * 10 + inArea[3];
            if (guess == Integer.parseInt(combo)) {
                props.put("stage3clear", "true");
                rightGuesses++;
                removedOnClear = items.size();
                floor.removeAll(items);
            } else {
                wrongGuesses++;
                props.put("stage3attempt", Integer.toString(Integer.parseInt(props.get("stage3attempt")) + 1));
            }
        }

        private void fight(Bot b, SolverStep.Fight f) {
            if (b.map != FOUNTAIN || b.bag.getOrDefault(f.itemId(), 0) >= f.untilItemCount()) {
                return;
            }
            Mob target = null;
            for (Mob m : mobs) {
                if (f.ofKind(m.id) && (target == null || Math.abs(m.x - b.x) < Math.abs(target.x - b.x))) {
                    target = m;
                }
            }
            if (target == null) {
                return;
            }
            mobs.remove(target);
            b.x = target.x;
            if (knightsDrop) {
                int item = target.id == 9_300_022 ? 4_001_027 : 4_001_028;
                floor.add(new Drop(FOUNTAIN, item, target.x, target.y, 0, 0, now++));
            }
        }

        private void loot(Bot b, int itemId) {
            Drop best = null;
            for (Drop d : floor) {
                if (d.map == b.map && d.itemId == itemId && PassHandover.mayTake(b.id, d.owner, d.dropper, (byte) 0)
                        && (best == null || Math.abs(d.x - b.x) < Math.abs(best.x - b.x))) {
                    best = d;
                }
            }
            if (best != null) {
                floor.remove(best);
                b.bag.merge(itemId, 1, Integer::sum);
                b.x = best.x;
            }
        }

        private void place(Bot b, SolverStep.PlaceItem p) {
            if (b.map != FOUNTAIN || b.bag.getOrDefault(p.itemId(), 0) < p.count()) {
                return;
            }
            b.bag.merge(p.itemId(), -p.count(), Integer::sum);
            b.x = p.x();
            floor.add(new Drop(FOUNTAIN, p.itemId(), p.x(), p.y(), b.id, b.id, now++));
        }

        private void dropFor(Bot b, SolverStep.DropFor d) {
            if (b.bag.getOrDefault(d.itemId(), 0) < d.count()) {
                return;
            }
            b.bag.merge(d.itemId(), -d.count(), Integer::sum);
            floor.add(new Drop(b.map, d.itemId(), b.x, b.y, d.toCharacterId(), b.id, now++));
        }

        /** Five hits: states 0..4 each take a hit, the fifth (on the inert state 4) runs act() and drops. */
        private void hit(Bot b, int oid) {
            for (React r : reactors) {
                if (r.map == b.map && r.oid == oid) {
                    hits.merge(oid, 1, Integer::sum);
                    r.state++;
                    if (r.state == GqVassalsSolver.SPENT_STATE && reactorsDrop) {
                        int item = r.id == 9_202_003 ? 4_001_029 : r.id == 9_202_004 ? 4_001_030 : 0;
                        if (item != 0) {
                            floor.add(new Drop(r.map, item, r.x, r.y, b.id, 0, now++));
                        }
                    }
                    return;
                }
            }
        }
    }
}
