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
        /**
         * Stage 1 as TEST showed it (2026-10-30, level 30-35 bots on Ligators: about 5 coupons a minute each): a
         * swing kills a Ligator now and then, the kill drops a coupon owned by the killer, and a bot only gets
         * coupons by picking up drops (the party loot rule decides whose). Off: the old quick model (a swing has a
         * 1 in 3 chance of putting a coupon straight in the bag).
         */
        boolean ligators;
        int killOneIn = 24;     // ticks of 250 ms per kill: one Ligator every 6 s per member
        int couponOneIn = 2;    // a kill drops a coupon half the time
        boolean leaderHunts;    // a real leader hunts Ligators while he waits for passes, as players do
        boolean lazyLeader;     // a real leader who does his part only when a bot asks him to (K4)
        boolean prompted;
        boolean leaderDead;     // K5
        final Map<Integer, Integer> kills = new HashMap<>();
        final List<String> lootedFromPlayer = new ArrayList<>();

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
        public boolean alive(int id) {
            return !(leaderDead && isLeader(id));
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
            if (lazyLeader && isLeader(id)) {
                prompted = false; // did what he was asked; waits for the next ask
            }
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
                    if (stage == 1 && ligators) {
                        ligatorFight(id, f);
                    } else if (stage == 1 && rnd.nextInt(3) == 0) {
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
                case SolverStep.Say s -> {
                    lines.add(id + ": " + s.line());
                    RunMember lead = run.members().stream().filter(RunMember::leader).findFirst().orElse(null);
                    if (lead != null && s.line().contains("@" + lead.characterId())) {
                        prompted = true;
                    }
                }
                default -> {
                }
            }
        }

        /**
         * The live executor on stage 1: while short of its count it first picks up a coupon a monster dropped that
         * the loot rule lets it take, else it swings; a swing sometimes kills, and a kill sometimes drops a coupon
         * owned by the killer. Floor coupons are {COUPON, owner, 1, dropped at ms}.
         */
        void ligatorFight(int id, SolverStep.Fight f) {
            if (f.itemId() > 0 && itemCount(id, f.itemId()) < f.untilItemCount()) {
                for (int i = 0; i < floor.size(); i++) {
                    int[] it = floor.get(i);
                    if (it[0] == COUPON && it[2] == 1 && QuestionCouponsSolver.mayTakeMobCoupon(this, id, it[1],
                            now - it[3] >= 15_000)) {
                        floor.remove(i);
                        add(id, COUPON, 1);
                        RunMember owner = run.member(it[1]);
                        if (owner != null && !owner.bot() && now - it[3] < 15_000) {
                            lootedFromPlayer.add(id + " took " + it[1] + "'s coupon");
                        }
                        return;
                    }
                }
            }
            killLigator(id);
        }

        void killLigator(int id) {
            if (rnd.nextInt(killOneIn) == 0) {
                kills.merge(id, 1, Integer::sum);
                if (rnd.nextInt(couponOneIn) == 0) {
                    floor.add(new int[]{COUPON, id, 1, (int) now});
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
            if (isLeader(id) && (leaderDead || lazyLeader && !prompted && !props.containsKey("5stageclear"))) {
                return; // waits to be asked (or lies dead)
            }
            if (stage == 1) {
                if (isLeader(id)) {
                    loot(id, PASS);
                    if (itemCount(id, PASS) >= members().size() - 1) {
                        cloto(id, false);
                    } else if (ligators && leaderHunts) {
                        killLigator(id);
                    }
                    return;
                }
                int data = grid.getOrDefault(id, -1);
                if (data == -1) {
                    cloto(id, false);
                } else if (data > 0) {
                    if (itemCount(id, COUPON) < kpq().stage(1).orElseThrow().answer(data).orElseThrow()) {
                        if (ligators) {
                            playerFarms(id);
                        } else {
                            add(id, COUPON, 1);
                        }
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

        /** A real answerer on stage 1: picks up his own coupons, else swings like a bot does. */
        void playerFarms(int id) {
            for (int i = 0; i < floor.size(); i++) {
                int[] it = floor.get(i);
                if (it[0] == COUPON && it[2] == 1 && (it[1] == id || now - it[3] >= 15_000)) {
                    floor.remove(i);
                    add(id, COUPON, 1);
                    return;
                }
            }
            killLigator(id);
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

    // ---- stage 1 pace with a real leader (TEST 2026-10-30 14:09-14:15 UTC) ----

    /** Plays stage 1 only on the Ligator model; returns the world (the run has ended). */
    static World stage1(long seed, List<RunMember> party) {
        return stage1(seed, party, false);
    }

    static World stage1(long seed, List<RunMember> party, boolean leaderHunts) {
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(seed), 30 * 60_000L, 1);
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        World w = new World(run, seed);
        w.ligators = true;
        w.leaderHunts = leaderHunts;
        play(o, run, w, MAX_TICKS, null);
        assertEquals("cleared", run.endedBecause(), "seed " + seed + ": " + run.events());
        return w;
    }

    static List<RunMember> gmAndThreeBots() {
        return List.of(RunMember.player(100, true), bot(21, false, MechanicsPersona.forCharacter(21)),
                bot(22, false, MechanicsPersona.forCharacter(22)), bot(23, false, MechanicsPersona.forCharacter(23)));
    }

    /** Mean and worst stage 1 clear (s) over {@code seeds} runs of a real leader with three bots. */
    static long[] gmPace(int seeds, boolean leaderHunts) {
        long total = 0;
        long worst = 0;
        for (long seed = 1; seed <= seeds; seed++) {
            long secs = stage1(seed, gmAndThreeBots(), leaderHunts).run.clearedAfterMs() / 1000;
            total += secs;
            worst = Math.max(worst, secs);
        }
        return new long[]{total / seeds, worst};
    }

    /**
     * On TEST a GM leader with three bots had two passes after 3 minutes and the third bot still hunting alone for
     * its last 17 coupons after 5.5: each bot only ever picked up its own kills' coupons and a bot whose pass was
     * handed over stopped fighting. A party of players shares drops and helps the slowest; so must the bots.
     * On this model (each member about 5 coupons a minute, as on TEST) the old behaviour cleared in 375 s on
     * average, worst 572 s. Three hunters need about 66 coupons, so about 4.4 minutes is the floor while the leader
     * stands still; a leader who hunts too (his kills are the party's) brings it down further.
     */
    @Test
    void aRealLeaderWithThreeBotsClearsStageOneInAFewMinutes() {
        long[] idle = gmPace(40, false);
        assertTrue(idle[0] <= 300, "mean stage 1 clear " + idle[0] + " s (worst " + idle[1] + " s)");
        assertTrue(idle[1] <= 480, "worst stage 1 clear " + idle[1] + " s (mean " + idle[0] + " s)");
        long[] hunting = gmPace(40, true);
        assertTrue(hunting[0] <= 240, "leader hunting too: mean " + hunting[0] + " s (worst " + hunting[1] + " s)");
    }

    @Test
    void aBotWhosePassIsHandedOverHuntsNextToTheSlowestAndLootsNothing() {
        World w = stage1(7, gmAndThreeBots());
        assertTrue(w.run.cleared());
        // every coupon a bot holds came off the floor; helpers looted nothing (itemId 0), so nobody holds extras
        for (int id = 21; id <= 23; id++) {
            assertEquals(0, w.itemCount(id, COUPON), "bot " + id + " handed in exactly its count");
        }
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 1);
        RunState run = o.start(kpq(), Tier.NORMAL, gmAndThreeBots());
        World v = new World(run, 1);
        o.tick(run, v, 1_000);
        v.grid.put(21, 0);     // 21's pass is in
        v.grid.put(22, 2);     // 22 farms 35, holds 5
        v.grid.put(23, 7);     // 23 farms 8, holds 7
        v.add(22, COUPON, 5);
        v.add(23, COUPON, 7);
        v.pos.put(21, new int[]{-900, 80});
        v.pos.put(22, new int[]{400, 80});
        SolverStep far = o.tick(run, v, 1_250).get(21);
        assertEquals(new SolverStep.MoveTo(400, 80, "help 22 with coupons"), far, "goes to the one missing most");
        v.pos.put(21, new int[]{300, 80});
        assertEquals(new SolverStep.Fight(9300001, 0, 0), o.tick(run, v, 1_500).get(21), "fights there, loots nothing");
        v.grid.put(22, 0);
        v.grid.put(23, 0);
        assertEquals(new SolverStep.Done(), o.tick(run, v, 1_750).get(21), "nobody farms: done");
    }

    @Test
    void botsShareEachOthersCouponsButNeverTakeAFarmingPlayersOwn() {
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(2), 0, 1);
        RunState run = o.start(kpq(), Tier.NORMAL, List.of(bot(31, true, MechanicsPersona.PERFECT),
                RunMember.player(200, false), bot(32, false, MechanicsPersona.PERFECT),
                bot(33, false, MechanicsPersona.PERFECT)));
        World v = new World(run, 2);
        v.grid.put(200, 3);
        v.grid.put(32, 3);
        assertTrue(QuestionCouponsSolver.mayTakeMobCoupon(v, 32, 32, false), "its own kill");
        assertTrue(QuestionCouponsSolver.mayTakeMobCoupon(v, 32, 33, false), "a fellow bot's kill");
        assertTrue(QuestionCouponsSolver.mayTakeMobCoupon(v, 32, 31, false), "the bot leader's kill");
        assertFalse(QuestionCouponsSolver.mayTakeMobCoupon(v, 32, 200, false), "a farming player's kill");
        assertTrue(QuestionCouponsSolver.mayTakeMobCoupon(v, 32, 200, true), "anyone's once the window ran out");
        assertFalse(QuestionCouponsSolver.mayTakeMobCoupon(v, 32, 999, false), "a stranger's kill");
        v.grid.put(200, 0);
        assertTrue(QuestionCouponsSolver.mayTakeMobCoupon(v, 32, 200, false), "a player whose pass is in");

        for (long seed = 1; seed <= 20; seed++) {
            World w = stage1(seed, List.of(bot(31, true, MechanicsPersona.forCharacter(31)),
                    RunMember.player(200, false), bot(32, false, MechanicsPersona.forCharacter(32)),
                    bot(33, false, MechanicsPersona.forCharacter(33))));
            assertTrue(w.lootedFromPlayer.isEmpty(), "seed " + seed + ": " + w.lootedFromPlayer);
        }
    }

    @Test
    void fourBotsAloneAlsoClearStageOneInAFewMinutes() {
        long worst = 0;
        for (long seed = 1; seed <= 30; seed++) {
            World w = stage1(seed, List.of(bot(11, true, MechanicsPersona.forCharacter(11)),
                    bot(12, false, MechanicsPersona.forCharacter(12)), bot(13, false, MechanicsPersona.forCharacter(13)),
                    bot(14, false, MechanicsPersona.forCharacter(14))));
            worst = Math.max(worst, w.run.clearedAfterMs() / 1000);
        }
        assertTrue(worst <= 6 * 60, "worst " + worst + " s");
    }

    @Test
    void passLineTellsARealLeaderWhereClotoIs() {
        assertEquals("AqClient dropped my pass by u, take them to Cloto on the right",
                StepExecutor.passLine("AqClient", 592, -1077));
        assertEquals("AqClient dropped my pass by u, take them to Cloto", StepExecutor.passLine("AqClient", 592, 500));
        assertEquals("AqClient dropped my pass by u, take them to Cloto", StepExecutor.passLine("AqClient", null, 0));
    }

    // ---- stop / dispose (TEST 2026-10-30 14:15 UTC: a stopped run left its KPQ alive, Lakelis refused the next) ----

    /** What MechanicsRuns.finish decides for the simulated instance once the run has ended. */
    static boolean endsInstance(RunState run, World w, boolean owns) {
        boolean realInside = w.members().stream().anyMatch(m -> !m.bot());
        return MechanicsRuns.shouldEndInstance(owns, run.cleared(), false, realInside);
    }

    @Test
    void aStoppedStagedRunEndsItsInstanceEvenWithTheGmInside() {
        Orchestrator o = orchestrator(11);
        RunState run = o.start(kpq(), Tier.NORMAL, gmAndThreeBots());
        World w = new World(run, 11);
        w.ligators = true;
        play(o, run, w, 4 * 60, null); // a minute into stage 1
        assertFalse(run.ended());
        o.abort(run, "stopped by AqClient");
        assertTrue(endsInstance(run, w, true), "staging started it: the stop ends it, the GM goes out with the bots");
    }

    @Test
    void theLeaderWarpingOutAbortsTheRunAndEndsAStagedInstance() {
        Orchestrator o = orchestrator(12);
        RunState run = o.start(kpq(), Tier.NORMAL, gmAndThreeBots());
        World w = new World(run, 12);
        w.ligators = true;
        play(o, run, w, 4 * 30, null);
        w.mapOf.put(100, 103000000); // the GM's !warp 103000000: the event script unregisters him (3 bots left)
        play(o, run, w, 8, null);
        assertTrue(run.ended());
        assertTrue(run.endedBecause().startsWith("the leader left"), run.endedBecause());
        assertTrue(endsInstance(run, w, true), "three bots alone in a staged KPQ: ended, not left for 30 minutes");
    }

    @Test
    void aPlayersOwnPqIsNeverEndedUnderHimButAnEmptyOneIs() {
        Orchestrator o = orchestrator(13);
        RunState run = o.start(kpq(), Tier.NORMAL, gmAndThreeBots());
        World w = new World(run, 13);
        o.abort(run, "exception: test");
        assertFalse(endsInstance(run, w, false), "he started it at Lakelis and is still in it");
        for (int id : List.of(100, 21, 22, 23)) {
            w.mapOf.put(id, EXIT);
        }
        assertTrue(endsInstance(run, w, false), "nobody left inside: no orphan");
        assertFalse(MechanicsRuns.shouldEndInstance(true, true, false, true), "cleared with the GM in the bonus: his");
        assertFalse(MechanicsRuns.shouldEndInstance(true, false, true, false), "already disposed");
        assertTrue(MechanicsRuns.shouldEndInstance(false, true, false, false), "cleared and only bots or nobody left");
    }

    // ---- stage 1 visible tries (TEST 10-30 14:41: "will try [8] first" for 25, "[34]" for 35) ----

    @Test
    void stageOneWrongTriesAreOneNearMissAtMost() {
        Stage s1 = kpq().stage(1).orElseThrow();
        List<Integer> table = s1.answers().stream().map(MechanicsModel.Answer::value).toList();
        Random rnd = new Random(3);
        int shown = 0;
        for (int i = 0; i < 2_000; i++) {
            int right = table.get(rnd.nextInt(table.size()));
            List<Integer> wrong = QuestionCouponsSolver.plannedWrongCounts(right, table,
                    new MechanicsPersona(rnd.nextDouble(), 1.0), 2.0, rnd);
            assertTrue(wrong.size() <= 1, "one walk to Cloto at most: " + wrong);
            for (int w : wrong) {
                assertTrue(w < right && right - w <= QuestionCouponsSolver.NEAR_MISS,
                        "a near miss on the way up, not 8 for 25: " + w + " for " + right);
            }
            shown += wrong.size();
        }
        assertTrue(shown > 0, "chaotic bots still show a try");
    }

    // ---- !mechanics kpq ... stageN (TEST 10-30: "stage4" started on stage 1) ----

    @Test
    void aRunStagedAtStageFourWalksThroughTheOpenedPortalsAndPlaysTheBarrels() {
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(14), 30 * 60_000L, 4);
        RunState run = o.start(kpq(), Tier.NORMAL, List.of(bot(1, true, MechanicsPersona.PERFECT),
                bot(2, false, MechanicsPersona.PERFECT), bot(3, false, MechanicsPersona.PERFECT),
                bot(4, false, MechanicsPersona.PERFECT)));
        assertTrue(o.startAt(run, 4, 1_000));
        assertEquals(4, run.currentStage());
        World w = new World(run, 14);
        for (int s = 1; s <= 3; s++) {
            w.props.put(s + "stageclear", "true"); // what KpqStaging.stageEventTo leaves (Cloto's clearStage)
        }
        List<Map<Integer, SolverStep>> trace = new ArrayList<>();
        play(o, run, w, MAX_TICKS, trace);
        assertEquals("cleared", run.endedBecause(), run.events().toString());
        assertTrue(w.props.containsKey("4stageclear"));
        assertEquals(List.of("4:" + w.props.get("stg4Property")), w.rightChecks.stream().filter(c -> c.startsWith("4:"))
                .toList(), "stage 4 checked right");
        assertTrue(trace.stream().flatMap(m -> m.values().stream()).noneMatch(st -> st instanceof SolverStep.Fight f
                && f.itemId() == COUPON), "no coupon hunting");
        assertTrue(w.grid.isEmpty(), "nobody asked Cloto a stage 1 question");
        assertTrue(trace.stream().flatMap(m -> m.values().stream()).filter(st -> st instanceof SolverStep.EnterPortal)
                .count() >= 12, "each of four walked through three portals");
    }

    @Test
    void aStageThatIsNotPlayedIsRefused() {
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(15), 0, 3);
        RunState run = o.start(kpq(), Tier.NORMAL, List.of(bot(1, true, MechanicsPersona.PERFECT),
                bot(2, false, MechanicsPersona.PERFECT), bot(3, false, MechanicsPersona.PERFECT)));
        assertFalse(o.startAt(run, 4, 1_000), "stops after 3, cannot start on 4");
        assertEquals(1, run.currentStage());
    }

    // ---- a real leader (bot audit K4, K5) ----

    /**
     * K4: the bots never do the leader's part (the scripts only take passes and checks from the event leader) and a
     * real player is never steered; a leader who waits for the bots used to idle the run to its time limit. Now one
     * bot tells him when it is his turn, again every 40 s, and the PQ gets cleared.
     */
    @Test
    void aRealLeaderWhoWaitsToBeAskedIsAskedAndThePqGetsCleared() {
        for (long seed = 1; seed <= 5; seed++) {
            Orchestrator o = orchestrator(seed);
            RunState run = o.start(kpq(), Tier.NORMAL, gmAndThreeBots());
            World w = new World(run, seed);
            w.lazyLeader = true;
            play(o, run, w, MAX_TICKS, null);
            assertTrue(run.cleared(), "seed " + seed + ": " + run.endedBecause() + " " + w.lines);
            for (int s = 1; s <= 5; s++) {
                assertTrue(w.props.containsKey(s + "stageclear"), "seed " + seed + " stage " + s);
            }
            assertTrue(w.lines.stream().anyMatch(l -> l.contains("@100") && l.contains("Cloto")), w.lines.toString());
        }
    }

    @Test
    void remindersComeTwentySecondsAfterHisTurnThenEveryFortyNotEveryTick() {
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 1);
        RunState run = o.start(kpq(), Tier.NORMAL, gmAndThreeBots());
        World w = new World(run, 1);
        for (int id = 21; id <= 23; id++) {
            w.grid.put(id, 0);
        }
        w.add(100, PASS, 3); // his turn: all three passes in his bag
        List<Long> said = new ArrayList<>();
        for (long t = 1_000; t <= 120_000; t += TICK) {
            for (SolverStep st : o.tick(run, w, t).values()) {
                if (st instanceof SolverStep.Say s && s.line().contains("@100")) {
                    said.add(t);
                }
            }
        }
        assertEquals(List.of(21_000L, 61_000L, 101_000L), said);
    }

    /** K5: while the real leader lies dead the bots stop and one says so once; they go on when he is up. */
    @Test
    void aDeadLeaderStopsTheBots() {
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(2), 0, 1);
        RunState run = o.start(kpq(), Tier.NORMAL, gmAndThreeBots());
        World w = new World(run, 2);
        w.leaderDead = true;
        int said = 0;
        for (long t = 1_000; t <= 10_000; t += TICK) {
            for (SolverStep st : o.tick(run, w, t).values()) {
                if (st instanceof SolverStep.Say s) {
                    said++;
                    assertTrue(s.line().contains("@100"), s.line());
                } else {
                    assertTrue(st instanceof SolverStep.Wait, "nothing but waiting: " + st);
                }
            }
        }
        assertEquals(1, said);
        w.leaderDead = false;
        assertTrue(o.tick(run, w, 11_000).values().stream().anyMatch(st -> st instanceof SolverStep.TalkNpc),
                "back to asking Cloto once he is up");
    }

    /** B6: a bot whose bag is too full for the prize stops clicking Cloto after a minute; the run ends. */
    @Test
    void aBotWithAFullBagGivesUpOnThePrize() {
        Orchestrator o = orchestrator(3);
        RunState run = o.start(kpq(), Tier.NORMAL, List.of(bot(1, true, MechanicsPersona.PERFECT),
                bot(2, false, MechanicsPersona.PERFECT), bot(3, false, MechanicsPersona.PERFECT)));
        World w = new World(run, 3);
        for (int s = 1; s <= 5; s++) {
            w.props.put(s + "stageclear", "true");
        }
        for (int id = 1; id <= 3; id++) {
            w.mapOf.put(id, S5);
        }
        o.startAt(run, 5, 1_000);
        long t = 1_000;
        int claims = 0;
        for (; t < 1_000 + 5 * 60_000 && !run.ended(); t += TICK) {
            for (SolverStep st : o.tick(run, w, t).values()) {
                if (st instanceof SolverStep.Converse) {
                    claims++; // Cloto says no every time: no room (the sim never warps them)
                }
            }
        }
        assertTrue(run.ended(), run.events().toString());
        assertEquals("cleared", run.endedBecause());
        assertTrue(t <= 1_000 + Orchestrator.PRIZE_GIVE_UP_MS + 5_000, "ended at " + t);
        assertTrue(claims <= 3 * (Orchestrator.PRIZE_GIVE_UP_MS / TICK + 4), "claims " + claims);
    }
}
