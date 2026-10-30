package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Position;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 KPQ automation: the orchestrator plays all of Kerning PQ (stages 1-5, the prize, the bonus, the way out)
 * against a simulation of our scripts: Cloto ({@code scripts/npc/9020001.js}: the question grid, the rectangle check
 * of stages 2-4 exactly as {@code rectangleStages} counts, 10 passes on stage 5, the prize warp), the portal gates
 * ({@code kpq0..3}: open on the clear property) and Nella's exit. No server; every run is seeded.
 */
class KpqFullRunTest {

    static final int COUPON = 4001007, PASS = 4001008, CLOTO = 9020001, NELLA = 9020002;
    static final int S1 = 103000800, S5 = 103000804, BONUS = 103000805, EXIT = 103000890;
    static final long TICK = 250;

    static Content kpq() {
        return KpqStageDataTest.kpq();
    }

    static RunMember bot(int id, boolean leader, MechanicsPersona p) {
        return new RunMember(id, leader, true, p);
    }

    /** The whole PQ as our scripts play it. */
    static final class World implements StageView {
        final RunState run;
        final Random rnd;
        final Map<Integer, Integer> mapOf = new HashMap<>();
        final Map<Integer, int[]> pos = new HashMap<>();
        final Map<Integer, Map<Integer, Integer>> inv = new HashMap<>();
        final Map<Integer, Integer> grid = new HashMap<>();
        final Map<String, String> props = new HashMap<>();
        final List<int[]> floor = new ArrayList<>(); // {itemId, ownerId (0 = anyone), fromMob 0/1}
        int stage5Mobs = 11;
        long now;
        final List<String> wrongChecks = new ArrayList<>();
        final List<String> rightChecks = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final Map<Integer, Integer> prizes = new HashMap<>();
        final Map<Integer, Integer> playerWait = new HashMap<>();
        final Map<Integer, Integer> bonusWait = new HashMap<>();
        final List<Integer> stage5PassesFor = new ArrayList<>();

        World(RunState run, long seed) {
            this.run = run;
            this.rnd = new Random(seed);
            for (RunMember m : run.members()) {
                mapOf.put(m.characterId(), S1);
                pos.put(m.characterId(), new int[]{-1000, 80});
            }
        }

        @Override
        public Stage stage() {
            return run.stageData();
        }

        @Override
        public List<RunMember> members() {
            return run.members().stream().filter(m -> inInstance(m.characterId())).toList();
        }

        boolean inInstance(int id) {
            int map = mapOf.getOrDefault(id, -1);
            return map >= S1 && map <= BONUS;
        }

        @Override
        public String roleOf(int id) {
            return run.roleOf(id);
        }

        @Override
        public OptionalInt answerFor(int id) {
            if (stage().answerProperty() != null) {
                return CombinationSolver.answer(this);
            }
            int q = grid.getOrDefault(id, -1);
            return q > 0 ? stage().answer(q) : OptionalInt.empty();
        }

        @Override
        public int itemCount(int id, int itemId) {
            return inv.getOrDefault(id, Map.of()).getOrDefault(itemId, 0);
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.ofNullable(props.get(key));
        }

        @Override
        public int[] positionOf(int id) {
            return pos.getOrDefault(id, new int[]{0, 0}).clone();
        }

        @Override
        public int memberStatus(int id) {
            return grid.getOrDefault(id, -1);
        }

        @Override
        public int floorItems(int itemId, int forId) {
            return (int) floor.stream().filter(f -> f[0] == itemId && (f[1] == forId || f[1] == 0)).count();
        }

        @Override
        public List<Integer> wrongTriesLeft(int id) {
            return run.wrongTriesLeft(id);
        }

        @Override
        public Optional<int[]> npcPosition(int npcId) {
            return npcId == CLOTO ? Optional.of(new int[]{2_000, 0}) : Optional.empty();
        }

        @Override
        public int mapOf(int id) {
            return mapOf.getOrDefault(id, -1);
        }

        @Override
        public int liveMobs() {
            return stage().mapId() == S5 ? stage5Mobs : 0;
        }

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return run.stageMemory();
        }

        void add(int id, int itemId, int n) {
            inv.computeIfAbsent(id, k -> new HashMap<>()).merge(itemId, n, Integer::sum);
        }

        boolean isLeader(int id) {
            RunMember m = run.member(id);
            return m != null && m.leader();
        }

        int stageOf(int id) {
            return mapOf(id) - S1 + 1;
        }

        /** Cloto's script for one click; {@code next} = the player pressed "next" after the first page. */
        void cloto(int id, boolean next) {
            int stage = stageOf(id);
            if (props.containsKey(stage + "stageclear")) {
                if (stage == 5 && next) {
                    prizes.merge(id, 1, Integer::sum); // eim.giveEventReward then cm.warp(103000805, "st00")
                    mapOf.put(id, BONUS);
                }
                return;
            }
            Stage s = kpq().stage(stage).orElseThrow();
            if (stage == 1) {
                if (isLeader(id)) {
                    int need = members().size() - 1;
                    if (itemCount(id, PASS) >= need) {
                        props.put("1stageclear", "true");
                        grid.clear();
                        add(id, PASS, -need);
                    }
                    return;
                }
                int data = grid.getOrDefault(id, -1);
                if (data == -1) {
                    grid.put(id, rnd.nextInt(7) + 1);
                } else if (data > 0) {
                    int answer = s.answer(data).orElseThrow();
                    if (itemCount(id, COUPON) == answer) {
                        add(id, COUPON, -answer);
                        add(id, PASS, 1);
                        grid.put(id, 0);
                    }
                }
                return;
            }
            if (stage <= 4) {
                if (!isLeader(id)) {
                    return; // "Follow the instructions given by your party leader"
                }
                String key = s.answerProperty();
                if (!props.containsKey(key)) {
                    props.put(key, String.valueOf(rnd.nextInt(s.combos().size())));
                    return;
                }
                // rectangleStages: count every player in the instance list by the first rectangle holding him
                int[] count = new int[6];
                for (RunMember m : members()) {
                    if (mapOf(m.characterId()) != s.mapId()) {
                        continue;
                    }
                    int[] at = pos.get(m.characterId());
                    for (int j = 0; j < s.positions().size(); j++) {
                        if (new java.awt.Rectangle(s.positions().get(j).x(), s.positions().get(j).y(),
                                s.positions().get(j).w(), s.positions().get(j).h()).contains(at[0], at[1])) {
                            count[j]++;
                            break;
                        }
                    }
                }
                List<Integer> combo = s.combos().get(Integer.parseInt(props.get(key)));
                boolean accept = true;
                for (int j = 0; j < combo.size(); j++) {
                    accept &= combo.get(j) == count[j];
                }
                if (accept) {
                    props.put(stage + "stageclear", "true");
                    rightChecks.add(stage + ":" + props.get(key));
                } else {
                    wrongChecks.add(stage + ":" + java.util.Arrays.toString(java.util.Arrays.copyOf(count, combo.size())));
                }
                return;
            }
            if (isLeader(id) && itemCount(id, PASS) >= 10) {
                add(id, PASS, -10);
                props.put("5stageclear", "true");
            }
        }

        void apply(Orchestrator o, int id, SolverStep step) {
            if (!inInstance(id)) {
                return;
            }
            switch (step) {
                case SolverStep.TalkNpc t -> {
                    assertEquals(CLOTO, t.npcId());
                    if (t.itemId() > 0) {
                        o.noteHandIn(run, id, t.itemId(), t.count(), now);
                    }
                    cloto(id, false);
                }
                case SolverStep.CheckAnswer c -> {
                    assertTrue(isLeader(id), "only the leader checks");
                    o.noteTry(run, id, c.tryValue(), now);
                    cloto(id, false);
                }
                case SolverStep.Converse c -> {
                    if (c.npcId() == CLOTO) {
                        cloto(id, !c.answers().isEmpty());
                    } else {
                        assertEquals(NELLA, c.npcId());
                        assertEquals(BONUS, mapOf(id));
                        mapOf.put(id, EXIT);
                    }
                }
                case SolverStep.Occupy oc -> {
                    if (rnd.nextInt(3) > 0) { // a climb or a walk takes a few beats
                        pos.put(id, new int[]{oc.x(), oc.y()});
                    }
                }
                case SolverStep.MoveTo m -> pos.put(id, new int[]{m.x(), m.y()});
                case SolverStep.Fight f -> {
                    int stage = stageOf(id);
                    if (stage == 1 && rnd.nextInt(3) == 0) {
                        add(id, COUPON, 1);
                    } else if (stage == 5) {
                        pickMobPass(id, f);
                        if (stage5Mobs > 0 && rnd.nextInt(4) == 0) {
                            stage5Mobs--;
                            floor.add(new int[]{PASS, 0, 1});
                        }
                    }
                }
                case SolverStep.DropFor d -> {
                    int have = itemCount(id, d.itemId());
                    assertTrue(d.count() > 0 && d.count() <= have, "drops what it holds");
                    add(id, d.itemId(), -d.count());
                    for (int i = 0; i < d.count(); i++) {
                        floor.add(new int[]{d.itemId(), d.toCharacterId(), 0});
                    }
                    if (d.itemId() == PASS && stageOf(id) == 5) {
                        stage5PassesFor.add(d.toCharacterId());
                    }
                }
                case SolverStep.Loot l -> {
                    for (int i = 0; i < floor.size(); i++) {
                        int[] f = floor.get(i);
                        if (f[0] == l.itemId() && (f[1] == id || f[1] == 0)) {
                            floor.remove(i);
                            add(id, l.itemId(), 1);
                            break;
                        }
                    }
                }
                case SolverStep.EnterPortal e -> {
                    int stage = stageOf(id);
                    assertEquals("next00", e.portalName());
                    if (props.containsKey(stage + "stageclear")) { // kpq0..3: the gate opens on the clear
                        mapOf.put(id, mapOf(id) + 1);
                        pos.put(id, new int[]{-1000, 80});
                    }
                }
                case SolverStep.Say s -> lines.add(id + ": " + s.line());
                default -> {
                }
            }
        }

        /** The live executor loots the passes monsters dropped before it swings (never a member's hand-over). */
        void pickMobPass(int id, SolverStep.Fight f) {
            if (f.itemId() <= 0 || itemCount(id, f.itemId()) >= f.untilItemCount()) {
                return;
            }
            for (int i = 0; i < floor.size(); i++) {
                int[] it = floor.get(i);
                if (it[0] == f.itemId() && it[2] == 1) {
                    floor.remove(i);
                    add(id, f.itemId(), 1);
                    return;
                }
            }
        }

        /** A real player doing his part the way a player would (never steered). */
        void playerActs(int id) {
            int stage = stageOf(id);
            if (mapOf(id) == BONUS) {
                if (bonusWait.merge(id, 1, Integer::sum) > 4 * 60) {
                    mapOf.put(id, EXIT);
                }
                return;
            }
            if (stage < 5 && props.containsKey(stage + "stageclear")) {
                mapOf.put(id, mapOf(id) + 1);
                pos.put(id, new int[]{-1000, 80});
                return;
            }
            if (stage == 1) {
                if (isLeader(id)) {
                    loot(id, PASS);
                    if (itemCount(id, PASS) >= members().size() - 1) {
                        cloto(id, false);
                    }
                    return;
                }
                int data = grid.getOrDefault(id, -1);
                if (data == -1) {
                    cloto(id, false);
                } else if (data > 0) {
                    if (itemCount(id, COUPON) < kpq().stage(1).orElseThrow().answer(data).orElseThrow()) {
                        add(id, COUPON, 1);
                    } else {
                        cloto(id, false);
                    }
                } else if (itemCount(id, PASS) > 0) {
                    add(id, PASS, -1);
                    floor.add(new int[]{PASS, run.members().stream().filter(RunMember::leader).findFirst()
                            .orElseThrow().characterId(), 0});
                }
                return;
            }
            if (stage <= 4) {
                Stage s = stage();
                if (isLeader(id)) {
                    if (!props.containsKey(s.answerProperty())) {
                        cloto(id, false);
                    } else if (CombinationSolver.formationExact(this, CombinationSolver.answer(this).getAsInt())
                            && playerWait.merge(id, 1, Integer::sum) % 12 == 0) {
                        cloto(id, false); // checks once the bots look settled
                    }
                }
                List<RunMember> standers = CombinationSolver.standers(this);
                if (standers.stream().anyMatch(m -> m.characterId() == id)
                        && CombinationSolver.positionIndexOf(this, id) < 0 && props.containsKey(s.answerProperty())) {
                    @SuppressWarnings("unchecked")
                    Map<Integer, Integer> assign = (Map<Integer, Integer>) run.stageMemory()
                            .getOrDefault(CombinationSolver.K_ASSIGN, Map.of());
                    int target = CombinationSolver.target(this, leader().orElse(null),
                            CombinationSolver.answer(this).getAsInt());
                    List<Integer> left = CombinationSolver.leftForPlayers(this, assign, target);
                    if (!left.isEmpty()) {
                        Position p = s.positions().get(left.get(0));
                        pos.put(id, new int[]{p.centerX(), p.centerY()}); // takes the one the bots left him
                    }
                }
                return;
            }
            // stage 5
            if (props.containsKey("5stageclear")) {
                cloto(id, true);
                return;
            }
            if (isLeader(id)) {
                loot(id, PASS);
                if (itemCount(id, PASS) >= 10) {
                    cloto(id, false);
                }
            }
        }

        void loot(int id, int itemId) {
            for (int i = 0; i < floor.size(); i++) {
                int[] f = floor.get(i);
                if (f[0] == itemId && (f[1] == id || f[1] == 0)) {
                    floor.remove(i);
                    add(id, itemId, 1);
                    return;
                }
            }
        }
    }

    static int play(Orchestrator o, RunState run, World w, int maxTicks, List<Map<Integer, SolverStep>> trace) {
        w.now = 1_000;
        for (int t = 0; t < maxTicks; t++) {
            Map<Integer, SolverStep> steps = o.tick(run, w, w.now);
            if (run.ended()) {
                return t;
            }
            if (trace != null) {
                trace.add(new LinkedHashMap<>(steps));
            }
            for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
                w.apply(o, e.getKey(), e.getValue());
            }
            for (RunMember m : run.members()) {
                if (!m.bot() && w.inInstance(m.characterId())) {
                    w.playerActs(m.characterId());
                }
            }
            w.now += TICK;
        }
        return maxTicks;
    }

    static Orchestrator orchestrator(long seed) {
        return new Orchestrator(Orchestrator.allSolvers(), new Random(seed), 30 * 60_000L, 0);
    }

    static final int MAX_TICKS = 4 * 60 * 40;

    @Test
    void fourBotsClearAllOfKpqClaimTheirPrizesAndLeave() {
        for (long seed = 1; seed <= 30; seed++) {
            Orchestrator o = orchestrator(seed);
            List<RunMember> party = List.of(bot(11, true, MechanicsPersona.forCharacter(11)),
                    bot(12, false, MechanicsPersona.forCharacter(12)), bot(13, false, new MechanicsPersona(0.0, 1.0)),
                    bot(14, false, MechanicsPersona.AVERAGE));
            RunState run = o.start(kpq(), Tier.NORMAL, party);
            World w = new World(run, seed);
            play(o, run, w, MAX_TICKS, null);
            assertEquals("cleared", run.endedBecause(), "seed " + seed + ": " + run.events());
            assertTrue(run.cleared());
            for (int s = 1; s <= 5; s++) {
                assertTrue(w.props.containsKey(s + "stageclear"), "seed " + seed + " stage " + s);
            }
            for (int id = 11; id <= 14; id++) {
                assertEquals(1, w.prizes.getOrDefault(id, 0), "seed " + seed + ": one prize each, bot " + id);
                assertEquals(EXIT, w.mapOf(id), "seed " + seed + ": left through Nella");
            }
            assertEquals(3, w.rightChecks.size(), "one right check per combination stage");
            assertTrue(w.wrongChecks.size() <= 3 * CombinationSolver.MAX_WRONG_CHECKS, w.wrongChecks.toString());
        }
    }

    @Test
    void aChaoticBotLeaderShowsWrongFormationsFirstAndAPerfectOneNone() {
        int wrongShown = 0;
        for (long seed = 1; seed <= 20; seed++) {
            Orchestrator o = orchestrator(seed);
            List<RunMember> party = List.of(bot(1, true, new MechanicsPersona(0.0, 1.0)),
                    bot(2, false, MechanicsPersona.PERFECT), bot(3, false, MechanicsPersona.PERFECT),
                    bot(4, false, MechanicsPersona.PERFECT));
            RunState run = o.start(kpq(), Tier.NORMAL, party);
            World w = new World(run, seed);
            play(o, run, w, MAX_TICKS, null);
            assertTrue(run.cleared(), run.events().toString());
            assertEquals(w.wrongChecks.size(), run.wrongShown(1), "every wrong check was a planned try");
            wrongShown += w.wrongChecks.size();
        }
        assertTrue(wrongShown >= 20, "a chaotic leader shows wrong formations: " + wrongShown);

        Orchestrator o = orchestrator(3);
        List<RunMember> perfect = List.of(bot(1, true, MechanicsPersona.PERFECT), bot(2, false, MechanicsPersona.PERFECT),
                bot(3, false, MechanicsPersona.PERFECT), bot(4, false, MechanicsPersona.PERFECT));
        RunState run = o.start(kpq(), Tier.NORMAL, perfect);
        World w = new World(run, 3);
        play(o, run, w, MAX_TICKS, null);
        assertTrue(run.cleared(), run.events().toString());
        assertTrue(w.wrongChecks.isEmpty(), w.wrongChecks.toString());
    }

    @Test
    void aPartyOfThreePutsTheLeaderOnThePuzzleToo() {
        Orchestrator o = orchestrator(4);
        List<RunMember> party = List.of(bot(1, true, MechanicsPersona.AVERAGE), bot(2, false, MechanicsPersona.AVERAGE),
                bot(3, false, MechanicsPersona.AVERAGE));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        World w = new World(run, 4);
        List<Map<Integer, SolverStep>> trace = new ArrayList<>();
        play(o, run, w, MAX_TICKS, trace);
        assertTrue(run.cleared(), run.events().toString());
        boolean leaderOccupied = trace.stream().anyMatch(s -> s.get(1) instanceof SolverStep.Occupy);
        boolean remoteCheck = trace.stream().anyMatch(s -> s.get(1) instanceof SolverStep.CheckAnswer c
                && c.reachPx() == CombinationSolver.REMOTE_CHECK_PX);
        assertTrue(leaderOccupied && remoteCheck, "the leader stands on a position and checks from there");
    }

    @Test
    void wrongFormationsAreOneMoverChainsEndingNextToTheAnswer() {
        Content c = kpq();
        Random rnd = new Random(5);
        for (int stage = 2; stage <= 4; stage++) {
            Stage s = c.stage(stage).orElseThrow();
            for (int i = 0; i < 500; i++) {
                int answer = rnd.nextInt(s.combos().size());
                MechanicsPersona p = new MechanicsPersona(rnd.nextDouble(), rnd.nextDouble());
                List<Integer> wrong = CombinationSolver.plannedWrongCombos(s, answer, p, 2.0, rnd);
                assertTrue(wrong.size() <= CombinationSolver.MAX_WRONG_CHECKS, wrong.toString());
                assertFalse(wrong.contains(answer));
                assertEquals(wrong.size(), wrong.stream().distinct().count(), "no formation twice");
                for (int k = 0; k < wrong.size(); k++) {
                    int next = k + 1 < wrong.size() ? wrong.get(k + 1) : answer;
                    assertTrue(CombinationSolver.oneMover(s, wrong.get(k), next), "one mover: " + wrong + " -> " + answer);
                }
            }
            assertEquals(List.of(), CombinationSolver.plannedWrongCombos(s, 0, MechanicsPersona.PERFECT, 2.0, rnd));
        }
    }

    @Test
    void aRealLeaderIsNeverSteeredGetsTheFormationAndThePasses() {
        Orchestrator o = orchestrator(6);
        RunMember gm = RunMember.player(100, true);
        List<RunMember> party = List.of(gm, bot(21, false, new MechanicsPersona(0.0, 1.0)),
                bot(22, false, MechanicsPersona.AVERAGE), bot(23, false, MechanicsPersona.AVERAGE));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        World w = new World(run, 6);
        List<Map<Integer, SolverStep>> trace = new ArrayList<>();
        play(o, run, w, MAX_TICKS, trace);
        assertTrue(run.cleared(), run.events().toString());
        assertTrue(w.wrongChecks.isEmpty(), "bots form the answer straight away for a real leader: " + w.wrongChecks);
        for (Map<Integer, SolverStep> steps : trace) {
            assertFalse(steps.containsKey(100), "a real player is never given a step");
        }
        assertFalse(w.stage5PassesFor.isEmpty(), "stage 5 passes are dropped for the real leader");
        assertTrue(w.stage5PassesFor.stream().allMatch(to -> to == 100), w.stage5PassesFor.toString());
        assertTrue(w.lines.stream().anyMatch(l -> l.contains("check") || l.contains("try now") || l.contains("talk to cloto")),
                "a bot tells the real leader the formation is ready: " + w.lines);
        assertEquals(1, w.prizes.get(100), "the player took his own prize");
    }

    @Test
    void aRealMemberOnThePuzzleGetsAPositionLeftForHimAndBotsNeverTryWrongWithHim() {
        Orchestrator o = orchestrator(8);
        List<RunMember> party = List.of(bot(31, true, new MechanicsPersona(0.0, 1.0)), RunMember.player(200, false),
                bot(32, false, MechanicsPersona.AVERAGE));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        World w = new World(run, 8);
        play(o, run, w, MAX_TICKS, null);
        assertTrue(run.cleared(), run.events().toString());
        assertTrue(w.wrongChecks.isEmpty(), w.wrongChecks.toString());
    }

    @Test
    void botsDoNotLeaveARealPlayerAloneInTheBonus() {
        Orchestrator o = orchestrator(9);
        RunMember gm = RunMember.player(100, true);
        List<RunMember> party = List.of(gm, bot(41, false, new MechanicsPersona(0.0, 1.0)),
                bot(42, false, MechanicsPersona.PERFECT), bot(43, false, MechanicsPersona.PERFECT));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        World w = new World(run, 9);
        long playerInBonusWithoutBot = 0;
        w.now = 1_000;
        for (int t = 0; t < MAX_TICKS && !run.ended(); t++) {
            Map<Integer, SolverStep> steps = o.tick(run, w, w.now);
            for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
                w.apply(o, e.getKey(), e.getValue());
            }
            if (w.inInstance(100)) {
                w.playerActs(100);
            }
            if (w.mapOf(100) == BONUS && w.members().size() < 2) {
                playerInBonusWithoutBot++;
            }
            w.now += TICK;
        }
        assertTrue(run.cleared(), run.events().toString());
        assertEquals(0, playerInBonusWithoutBot, "the event would end and throw him out");
    }

    @Test
    void membersBehindWalkIntoTheOpenedPortal() {
        Orchestrator o = orchestrator(10);
        List<RunMember> party = List.of(bot(1, true, MechanicsPersona.PERFECT), bot(2, false, MechanicsPersona.PERFECT),
                bot(3, false, MechanicsPersona.PERFECT));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        World w = new World(run, 10);
        w.props.put("1stageclear", "true");
        o.tick(run, w, 1_000); // notices the clear, moves the run to stage 2
        assertEquals(2, run.currentStage());
        Map<Integer, SolverStep> steps = o.tick(run, w, 1_250);
        for (int id = 1; id <= 3; id++) {
            assertEquals(new SolverStep.EnterPortal("next00"), steps.get(id));
        }
    }
}
