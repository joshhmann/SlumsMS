package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * Guild Quest's last stage ({@link GqBossSolver}) against a small world that changes only when a step that earns the
 * change is applied: the gate opens five seconds after exactly one Protector Rock lies in its box, the boss reactor
 * wakes Ergoth on its fifth hit, the Rubian drops when he is down, and the event clears only when the leader hands it to
 * the Soul. The point of the stage is the sacrifice, so the world also keeps a <em>violation list</em>: a member who
 * stands on 800 or 900 without the earring is a violation unless he is the one who paid, and nobody who paid may go on
 * to 900.
 */
class GqBossSolverTest {

    private static final int EARRING = GqProtection.EARRING;
    private static final int RUBIAN = GqBossSolver.RUBIAN;
    private static final int M600 = GqBossSolver.M600;
    private static final int M700 = GqBossSolver.M700;
    private static final int M800 = GqBossSolver.M800;
    private static final int M900 = GqBossSolver.M900;

    static final MechanicsModel.Stage STAGE = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR)).content().stream()
            .filter(c -> c.id().equals("guildquest")).findFirst().orElseThrow().stages().get(6);

    static final class Bot {
        final int id;
        int map;
        boolean wearing = true;
        final Map<Integer, Integer> bag = new HashMap<>();

        Bot(int id, int map) {
            this.id = id;
            this.map = map;
        }
    }

    static final class World implements StageView {
        final Map<Integer, Bot> bots = new TreeMap<>();
        final List<RunMember> members = new ArrayList<>();
        final List<String> violations = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final List<String> waits = new ArrayList<>();
        final Map<String, Object> memory = new HashMap<>();
        final List<Integer> unequippedBy = new ArrayList<>();
        final List<Integer> placedBy = new ArrayList<>();
        final List<Integer> talked = new ArrayList<>();
        final List<Integer> reached900 = new ArrayList<>();
        int sacrificed = -1;
        int gate;
        int ghostGate = 1;
        int bossState;
        int ergothHp;
        boolean ergothAlive;
        int rubianOnFloor;
        long gateOpensAt = -1;
        long now = 1_000;
        boolean cleared;
        boolean rubianDrops = true;
        boolean gateAccepts = true;
        final GqBossSolver solver = new GqBossSolver();

        World(int[][] botsAt, int leader, int humanLeader) {
            for (int[] b : botsAt) {
                bots.put(b[0], new Bot(b[0], b[1]));
                members.add(new RunMember(b[0], b[0] == leader, b[0] != humanLeader, MechanicsPersona.PERFECT, 0, 20));
            }
        }

        static World party() {
            // six bots, the leader (1) already on 700 after the Soul's talk, the rest scattered over the maze rooms
            return new World(new int[][]{{1, M700}, {2, M700}, {3, 990000611}, {4, 990000631}, {5, M600}, {6, 990000620}}, 1, -1);
        }

        // ---------------------------------------------------------------------------------------- the world's rules

        void apply(int id, SolverStep step) {
            Bot b = bots.get(id);
            switch (step) {
                case SolverStep.UnequipItem u -> {
                    if (b.wearing && u.itemId() == EARRING) {
                        b.wearing = false;
                        b.bag.merge(EARRING, 1, Integer::sum);
                        unequippedBy.add(id);
                        log.add("UNEQUIP " + id + " " + u.why());
                    }
                }
                case SolverStep.EquipItem e -> {
                    if (b.bag.getOrDefault(e.itemId(), 0) > 0) {
                        b.bag.merge(e.itemId(), -1, Integer::sum);
                        b.wearing = true;
                    }
                }
                case SolverStep.PlaceItem p -> {
                    if (b.map == M800 && p.itemId() == EARRING && p.count() == 1 && "kinggate".equals(p.reactor())
                            && b.bag.getOrDefault(EARRING, 0) >= 1) {
                        b.bag.merge(EARRING, -1, Integer::sum);
                        placedBy.add(id);
                        if (gateAccepts && gateOpensAt < 0) {
                            gateOpensAt = now + 5_000;
                        }
                    }
                }
                case SolverStep.EnterPortal e -> enter(b, e.portalName());
                case SolverStep.ScriptedPortal s -> scripted(b, s.portalName());
                case SolverStep.HitReactorNamed h -> {
                    if (b.map == M900 && "boss".equals(h.reactorName()) && bossState < 5) {
                        bossState++;
                        if (bossState == 5) {
                            ergothAlive = true;
                            ergothHp = 6;
                        }
                    }
                }
                case SolverStep.Fight f -> {
                    if (b.map == M900 && ergothAlive && --ergothHp <= 0) {
                        ergothAlive = false;
                        rubianOnFloor = rubianDrops ? 1 : 0;
                    }
                }
                case SolverStep.Loot l -> {
                    if (b.map == M900 && l.itemId() == RUBIAN && rubianOnFloor > 0) {
                        rubianOnFloor--;
                        b.bag.merge(RUBIAN, 1, Integer::sum);
                    }
                }
                case SolverStep.DropFor d -> {
                    Bot to = bots.get(d.toCharacterId());
                    if (to != null && to.map == b.map && b.bag.getOrDefault(d.itemId(), 0) >= d.count()) {
                        b.bag.merge(d.itemId(), -d.count(), Integer::sum);
                        to.bag.merge(d.itemId(), d.count(), Integer::sum);
                    }
                }
                case SolverStep.TalkNpc t -> {
                    if (t.npcId() == GqBossSolver.SOUL && b.map == M900 && member(id).leader()
                            && b.bag.getOrDefault(RUBIAN, 0) > 0) {
                        b.bag.merge(RUBIAN, -1, Integer::sum);
                        talked.add(id);
                        cleared = true;
                    }
                }
                case SolverStep.Wait w -> waits.add(w.why());
                default -> {
                }
            }
            checkSafety(b);
        }

        private void enter(Bot b, String portal) {
            int from = b.map;
            int to = switch (from) {
                case M600 -> switch (portal) {
                    case "in00" -> 990000610;
                    case "in01" -> 990000620;
                    case "in02" -> 990000630;
                    default -> from;
                };
                case 990000610, 990000620, 990000630, 990000640 -> portal.equals("out00") ? M600 : from;
                case 990000611 -> portal.equals("out00") ? 990000610 : from;
                case 990000631 -> portal.equals("out00") ? 990000630 : from;
                default -> from;
            };
            move(b, to);
        }

        private void scripted(Bot b, String portal) {
            int from = b.map;
            int to = from;
            if (portal.equals("guild1F00") && (from == 990000611 || from == 990000620 || from == 990000631
                    || from == 990000641)) {
                to = M700;
            } else if (portal.equals("next00") && from == M700 && ghostGate >= 1) {
                to = M800;
            } else if (portal.equals("boss00") && from == M800 && gate >= 1) {
                to = M900;
            }
            move(b, to);
        }

        private void move(Bot b, int to) {
            b.map = to;
            if (to == M900 && !reached900.contains(b.id)) {
                reached900.add(b.id);
            }
        }

        private void checkSafety(Bot b) {
            if ((b.map == M800 || b.map == M900) && !b.wearing && !unequippedBy.contains(b.id)) {
                violations.add(b.id + " is on " + b.map + " without the earring and never took it off");
            }
            if (b.map == M900 && unequippedBy.contains(b.id) && !b.wearing) {
                violations.add(b.id + " paid for the gate and walked on to 900 without protection");
            }
        }

        int paid() {
            return placedBy.isEmpty() ? -1 : placedBy.get(0);
        }

        RunMember member(int id) {
            return members.stream().filter(m -> m.characterId() == id).findFirst().orElseThrow();
        }

        /** One second passes: the gate acts when its five seconds are up, then every bot takes its step. */
        void tick() {
            if (gateOpensAt >= 0 && now >= gateOpensAt && gate == 0) {
                gate = 1;
                sacrificed = paid();
            }
            for (RunMember m : new ArrayList<>(members)) {
                if (m.bot()) {
                    SolverStep s = solver.next(this, m);
                    apply(m.characterId(), s);
                }
            }
            now += 1_000;
        }

        int run(int maxTicks) {
            for (int i = 0; i < maxTicks && !cleared; i++) {
                tick();
            }
            return (int) ((now - 1_000) / 1_000);
        }

        // ---------------------------------------------------------------------------------------------- StageView

        @Override
        public MechanicsModel.Stage stage() {
            return STAGE;
        }

        @Override
        public List<RunMember> members() {
            return members;
        }

        @Override
        public String roleOf(int characterId) {
            return "member";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            Bot b = bots.get(characterId);
            return b == null ? 0 : b.bag.getOrDefault(itemId, 0);
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public int mapOf(int characterId) {
            return bots.get(characterId).map;
        }

        @Override
        public int reactorState(String name) {
            return name.equals("kinggate") ? gate : -1;
        }

        @Override
        public List<ReactorPlacement> reactorsOn(int mapId) {
            List<ReactorPlacement> out = new ArrayList<>();
            if (mapId == M700) {
                out.add(new ReactorPlacement(1, GqBossSolver.GHOST_GATE_REACTOR, "ghostgate", ghostGate, 1641, 180));
            }
            if (mapId == M800) {
                out.add(new ReactorPlacement(2, GqBossSolver.KING_GATE_REACTOR, "kinggate", gate, 261, 83));
            }
            if (mapId == M900) {
                out.add(new ReactorPlacement(3, GqBossSolver.BOSS_REACTOR, "boss", bossState, 344, 12));
            }
            return out;
        }

        @Override
        public int mobsAliveOn(int mapId, int mobId) {
            return mapId == M900 && ergothAlive && mobId == GqBossSolver.ERGOTH ? 1 : 0;
        }

        @Override
        public int floorItemsOn(int mapId, int itemId, int forCharacterId) {
            return mapId == M900 && itemId == RUBIAN ? rubianOnFloor : 0;
        }

        @Override
        public boolean wearing(int characterId, int itemId) {
            Bot b = bots.get(characterId);
            return b != null && itemId == EARRING && b.wearing;
        }

        @Override
        public Optional<String> cannotWear(int characterId, int itemId) {
            return Optional.empty();
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
        public boolean eventCleared() {
            return cleared;
        }
    }

    // ---------------------------------------------------------------------------------------------- the sacrificer

    @Test
    void theSacrificerIsTheHighestIdBotThatIsNotTheLeader() {
        World w = World.party();
        assertEquals(6, GqBossSolver.sacrificer(w).orElseThrow().characterId());

        // the leader pays only when he is the one bot there is
        World alone = new World(new int[][]{{1, M800}, {2, M800}, {3, M800}}, 1, 2);
        alone.members.set(2, new RunMember(3, false, false, MechanicsPersona.PERFECT, 0, 20));
        assertEquals(1, GqBossSolver.sacrificer(alone).orElseThrow().characterId());

        // a party of players has nobody to steer, so nobody can pay
        World humans = new World(new int[][]{{1, M800}}, 1, 1);
        assertTrue(GqBossSolver.sacrificer(humans).isEmpty());
    }

    @Test
    void aSacrificerWhoLeftBeforePayingIsReplacedByTheNextBot() {
        World w = World.party();
        w.members.removeIf(m -> m.characterId() == 6);
        w.bots.remove(6);

        assertEquals(5, GqBossSolver.sacrificer(w).orElseThrow().characterId());
    }

    // ------------------------------------------------------------------------------------------------- the whole stage

    @Test
    void theWholePartyClearsTheStageAndExactlyOneMemberPaysItsEarring() {
        World w = World.party();

        w.run(600);

        assertTrue(w.cleared, "the stage clears when the leader hands the Rubian to the Soul; waits: " + tail(w.waits));
        assertEquals(List.of(6), w.unequippedBy, "one member took his earring off, and it is the sacrificer");
        assertEquals(List.of(6), w.placedBy, "and the same member set that one earring in the gate");
        assertEquals(1, w.log.size());
        assertTrue(w.log.get(0).contains("decHP 30000") && w.log.get(0).contains("sacrifice")
                && w.log.get(0).contains("kinggate"), "the line says what was paid and why: " + w.log.get(0));
        assertFalse(w.reached900.contains(6), "the one who paid does not follow the party into the boss hall");
        assertTrue(w.reached900.containsAll(List.of(1, 2, 3, 4, 5)), "everyone else did: " + w.reached900);
        assertEquals(List.of(1), w.talked, "the leader handed in the Rubian, once");
        assertTrue(w.violations.isEmpty(), "nobody was on 800/900 unprotected except the one who paid: " + w.violations);
        assertEquals(M800, w.bots.get(6).map, "the sacrificer sat out on 800");
        assertEquals(0, w.bots.get(6).bag.getOrDefault(EARRING, 0));
    }

    @Test
    void nobodyEntersTheGateHallUnprotectedAndNobodyElseTakesAnEarringOff() {
        World w = World.party();
        w.members.set(5, new RunMember(6, false, true, MechanicsPersona.PERFECT, 0, 20));
        for (Bot b : w.bots.values()) {
            b.wearing = b.id != 3; // member 3 has not got his earring on yet
            if (b.id == 3) {
                b.bag.put(EARRING, 1);
            }
        }

        for (int i = 0; i < 40; i++) {
            w.tick();
        }

        assertTrue(w.bots.get(3).wearing, "a member with the earring in his bag puts it on (EquipItem) before anything else");
        assertTrue(w.violations.isEmpty(), w.violations.toString());
    }

    @Test
    void withoutAnEarringTheSacrificerCannotOpenTheGateAndTheStageSaysWhatIsMissing() {
        World w = World.party();
        w.bots.get(6).wearing = false; // never got one: the rocks' drop row is not there

        w.run(300);

        assertFalse(w.cleared);
        assertTrue(w.placedBy.isEmpty(), "nothing to set in the gate");
        assertTrue(w.waits.stream().anyMatch(s -> s.contains(String.valueOf(EARRING)) && s.contains("1032033")),
                "a Wait names the item: " + tail(w.waits));
        assertTrue(w.waits.stream().anyMatch(s -> s.contains("kinggate")), "and the others say the gate is what they wait at");
    }

    @Test
    void aGateThatDoesNotTakeTheEarringKeepsThePartyWaitingAndSaysSo() {
        World w = World.party();
        w.gateAccepts = false;

        w.run(300);

        assertFalse(w.cleared);
        assertEquals(List.of(6), w.placedBy);
        assertTrue(w.waits.stream().anyMatch(s -> s.contains("kinggate") && s.contains("shut")), tail(w.waits));
        assertTrue(w.reached900.isEmpty(), "nobody walked through a door that is shut");
    }

    @Test
    void aMissingRubianDropCostsTheStageAWaitThatNamesTheItem() {
        World w = World.party();
        w.rubianDrops = false;

        w.run(600);

        assertFalse(w.cleared);
        assertEquals(0, w.talked.size());
        assertTrue(w.waits.stream().anyMatch(s -> s.contains(String.valueOf(RUBIAN)) && s.contains("unverified")),
                "the Wait names 4001024 and says the live table is unverified: " + tail(w.waits));
    }

    @Test
    void theGhostGateStaysShutUntilTheSoulHasTalkedAndNobodyTriesTheDoor() {
        World w = World.party();
        w.ghostGate = 0;

        for (int i = 0; i < 60; i++) {
            w.tick();
        }

        assertTrue(w.bots.values().stream().noneMatch(b -> b.map == M800 || b.map == M900));
        assertTrue(w.waits.stream().anyMatch(s -> s.contains("ghostgate") && s.contains("9040003")), tail(w.waits));
    }

    @Test
    void aPartyWithAHumanLeaderStillPaysThroughABotAndHandsTheRubianToHim() {
        World w = new World(new int[][]{{1, M900}, {2, M900}, {3, M900}, {4, M900}, {5, M900}, {6, M800}}, 1, 1);
        w.gate = 1;
        w.sacrificed = 6;
        w.placedBy.add(6);
        w.unequippedBy.add(6);
        w.bots.get(6).wearing = false;
        w.bossState = 5;
        w.ergothAlive = true;
        w.ergothHp = 4;

        for (int i = 0; i < 80 && w.rubianOnFloor + w.bots.values().stream().mapToInt(b -> b.bag.getOrDefault(RUBIAN, 0)).sum() == 0;
             i++) {
            w.tick();
        }
        for (int i = 0; i < 20 && w.bots.get(1).bag.getOrDefault(RUBIAN, 0) == 0; i++) {
            w.tick();
        }

        assertEquals(1, w.bots.get(1).bag.getOrDefault(RUBIAN, 0), "the bots gave the human leader the Rubian");
        Optional<String> prompt = w.solver.leaderPrompt(w, w.member(1));
        assertTrue(prompt.isPresent() && prompt.get().contains("9040010"), "and the leader is told what to do: " + prompt);
    }

    @Test
    void theLeaderIsToldTheWayAtEachDoor() {
        World w = new World(new int[][]{{1, M700}, {2, M700}}, 1, 1);
        assertTrue(w.solver.leaderPrompt(w, w.member(1)).orElse("").contains("far right"));
        w.ghostGate = 0;
        assertTrue(w.solver.leaderPrompt(w, w.member(1)).isEmpty(), "no hint while the ghost gate is shut");
        w.bots.get(1).map = M800;
        assertTrue(w.solver.leaderPrompt(w, w.member(1)).isEmpty());
        w.gate = 1;
        assertTrue(w.solver.leaderPrompt(w, w.member(1)).orElse("").contains("boss00"));
    }

    // ----------------------------------------------------------------------------------------------- single steps

    @Test
    void onTheGateHallTheSacrificerTakesItOffThenSetsItDownAsOneRockOnTheGate() {
        World w = new World(new int[][]{{1, M800}, {2, M800}, {6, M800}}, 1, -1);

        SolverStep first = w.solver.next(w, w.member(6));
        UnequipStep(first);
        w.apply(6, first);
        SolverStep second = w.solver.next(w, w.member(6));
        SolverStep.PlaceItem place = assertInstanceOf(SolverStep.PlaceItem.class, second);
        assertEquals(EARRING, place.itemId());
        assertEquals(1, place.count());
        assertEquals("kinggate", place.reactor());
        assertEquals(261, place.x());
        assertEquals(83, place.y());
        assertTrue(place.why().contains("only 1") && place.why().contains("decHP 30000"));
    }

    private static void UnequipStep(SolverStep s) {
        SolverStep.UnequipItem u = assertInstanceOf(SolverStep.UnequipItem.class, s);
        assertEquals(EARRING, u.itemId());
        assertTrue(u.why().contains("sacrifice") && u.why().contains("decHP 30000"), u.why());
    }

    @Test
    void anotherMemberOnTheGateHallWaitsWearingItAndNeverTakesItOff() {
        World w = new World(new int[][]{{1, M800}, {2, M800}, {6, M800}}, 1, -1);

        for (int id : new int[]{1, 2}) {
            SolverStep s = w.solver.next(w, w.member(id));
            assertInstanceOf(SolverStep.Wait.class, s);
        }
    }

    @Test
    void theStageIsDoneOnceTheEventIsCleared() {
        World w = World.party();
        w.cleared = true;

        assertInstanceOf(SolverStep.Done.class, w.solver.next(w, w.member(2)));
        assertNull(w.solver.leaderPrompt(w, w.member(1)).orElse(null));
    }

    private static String tail(List<String> l) {
        return l.subList(Math.max(0, l.size() - 4), l.size()).toString();
    }

    @Test
    void aBossThatCameAndWentBeforeTheStageWasWatchingDoesNotLeaveThePartyWaitingForever() {
        World w = new World(new int[][]{{1, M900}, {2, M900}, {3, M900}}, 1, -1);
        w.gate = 1;
        w.bossState = 5; // the reactor is spent and no Ergoth is alive: the run was armed late, or the fight was faster than a tick
        w.rubianOnFloor = 1;

        for (int i = 0; i < 40 && !w.cleared; i++) {
            w.tick();
        }

        assertTrue(w.cleared, "after the grace the party looks for the Rubian and hands it in: " + tail(w.waits));
        assertTrue(w.waits.stream().anyMatch(s -> s.contains("never showed himself")), tail(w.waits));
    }
}
