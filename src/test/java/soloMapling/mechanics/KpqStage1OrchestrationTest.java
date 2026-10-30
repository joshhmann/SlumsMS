package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 130 phase 1: the orchestrator and {@code question-coupons} play KPQ stage 1 against a small simulation of
 * our Cloto script ({@code scripts/npc/9020001.js}, stage 1 branch: question in the grid, exact coupon count for a
 * pass, the leader hands in one pass per other member). No server; every run is seeded and repeatable.
 */
class KpqStage1OrchestrationTest {

    static final int COUPON = 4001007;
    static final int PASS = 4001008;
    static final int CLOTO = 9020001;
    static final int LIGATOR = 9300001;
    static final long TICK = 250;

    static Content kpq() {
        return StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR)).content().stream()
                .filter(c -> c.id().equals("kpq")).findFirst().orElseThrow();
    }

    static RunMember bot(int id, boolean leader, MechanicsPersona p) {
        return new RunMember(id, leader, true, p);
    }

    /** Cloto's stage 1 as our script plays it, plus a floor and inventories. */
    static final class FakeKpq implements StageView {
        final Stage stage;
        final RunState run;
        final Random rnd;
        final Map<Integer, Integer> grid = new HashMap<>();
        final Map<Integer, Map<Integer, Integer>> inv = new HashMap<>();
        final List<int[]> floor = new ArrayList<>(); // {itemId, ownerId}
        final Map<String, String> props = new HashMap<>();
        final List<Integer> present = new ArrayList<>();
        final List<String> wrongHandIns = new ArrayList<>();
        int clotoTalks;

        FakeKpq(RunState run, long seed) {
            this.stage = run.stageData();
            this.run = run;
            this.rnd = new Random(seed);
            for (RunMember m : run.members()) {
                present.add(m.characterId());
            }
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return run.members().stream().filter(m -> present.contains(m.characterId())).toList();
        }

        @Override
        public String roleOf(int id) {
            return run.roleOf(id);
        }

        @Override
        public OptionalInt answerFor(int id) {
            int q = grid.getOrDefault(id, -1);
            return q > 0 ? stage.answer(q) : OptionalInt.empty();
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
            return new int[]{0, 0};
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

        void add(int id, int itemId, int n) {
            inv.computeIfAbsent(id, k -> new HashMap<>()).merge(itemId, n, Integer::sum);
        }

        boolean isLeader(int id) {
            RunMember m = run.member(id);
            return m != null && m.leader();
        }

        /** Cloto's stage 1 branch of scripts/npc/9020001.js, for one talk. */
        void talkToCloto(int id) {
            clotoTalks++;
            if (props.containsKey("1stageclear")) {
                return;
            }
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
            if (data == 0) {
                return;
            }
            if (data == -1) {
                grid.put(id, rnd.nextInt(7) + 1);
                return;
            }
            int answer = stage.answer(data).orElseThrow();
            if (itemCount(id, COUPON) == answer) {
                add(id, COUPON, -answer);
                add(id, PASS, 1);
                grid.put(id, 0);
            } else {
                wrongHandIns.add(id + ":" + itemCount(id, COUPON) + "/" + answer);
            }
        }

        /** What the server would do with a bot's step (the live StepExecutor), one beat of it. */
        void apply(Orchestrator o, int id, SolverStep step, long now) {
            switch (step) {
                case SolverStep.TalkNpc t -> {
                    assertEquals(CLOTO, t.npcId());
                    if (t.itemId() > 0) {
                        o.noteHandIn(run, id, t.itemId(), t.count(), now);
                    }
                    talkToCloto(id);
                }
                case SolverStep.Fight f -> {
                    assertEquals(LIGATOR, f.mobId());
                    assertEquals(COUPON, f.itemId());
                    if (rnd.nextInt(3) == 0) { // a Ligator dies and drops a coupon every few beats
                        add(id, COUPON, 1);
                    }
                }
                case SolverStep.DropFor d -> {
                    int have = itemCount(id, d.itemId());
                    assertTrue(d.count() > 0 && d.count() <= have, "drops what it holds");
                    add(id, d.itemId(), -d.count());
                    for (int i = 0; i < d.count(); i++) {
                        floor.add(new int[]{d.itemId(), d.toCharacterId()});
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
                default -> {
                }
            }
        }

        /** A real player doing his own part, the way a player would (never steered by the orchestrator). */
        void playerActs(int id) {
            if (isLeader(id)) {
                for (int i = 0; i < floor.size(); i++) {
                    if (floor.get(i)[0] == PASS && floor.get(i)[1] == id) {
                        floor.remove(i);
                        add(id, PASS, 1);
                        break;
                    }
                }
                if (itemCount(id, PASS) >= members().size() - 1) {
                    talkToCloto(id);
                }
                return;
            }
            int data = grid.getOrDefault(id, -1);
            if (data == -1) {
                talkToCloto(id);
            } else if (data > 0) {
                int answer = stage.answer(data).orElseThrow();
                if (itemCount(id, COUPON) < answer) {
                    add(id, COUPON, 1);
                } else {
                    talkToCloto(id);
                }
            } else if (itemCount(id, PASS) > 0) {
                RunMember leader = leader().orElseThrow();
                add(id, PASS, -1);
                floor.add(new int[]{PASS, leader.characterId()});
            }
        }
    }

    /** Runs the stage to its end; returns the number of ticks it took. */
    static int play(Orchestrator o, RunState run, FakeKpq kpq, int maxTicks, List<Map<Integer, SolverStep>> trace) {
        long now = 1_000;
        for (int t = 0; t < maxTicks; t++) {
            Map<Integer, SolverStep> steps = o.tick(run, kpq, now);
            if (run.ended()) {
                return t;
            }
            if (trace != null) {
                trace.add(steps);
            }
            for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
                kpq.apply(o, e.getKey(), e.getValue(), now);
            }
            for (RunMember m : kpq.members()) {
                if (!m.bot()) {
                    kpq.playerActs(m.characterId());
                }
            }
            now += TICK;
        }
        return maxTicks;
    }

    static Orchestrator orchestrator(long seed) {
        return new Orchestrator(Orchestrator.phase1Solvers(), new Random(seed), 15 * 60_000L, 1);
    }

    @Test
    void fourBotsClearStage1AndShowAtMostTwoWrongTriesEach() {
        for (long seed = 1; seed <= 40; seed++) {
            Orchestrator o = orchestrator(seed);
            List<RunMember> party = List.of(bot(11, true, MechanicsPersona.forCharacter(11)),
                    bot(12, false, MechanicsPersona.forCharacter(12)), bot(13, false, new MechanicsPersona(0.0, 1.0)),
                    bot(14, false, MechanicsPersona.AVERAGE));
            RunState run = o.start(kpq(), Tier.NORMAL, party);
            assertNull(run.endedBecause());
            FakeKpq kpq = new FakeKpq(run, seed);
            int ticks = play(o, run, kpq, 4 * 60 * 15, null);
            assertEquals("cleared", run.endedBecause(), "seed " + seed + ": " + run.events());
            assertTrue(run.cleared());
            assertTrue(ticks < 4 * 60 * 15, "seed " + seed);
            assertEquals(0, kpq.itemCount(11, PASS), "the leader handed in every pass");
            for (int id : List.of(12, 13, 14)) {
                assertTrue(run.wrongShown(id) <= QuestionCouponsSolver.MAX_WRONG_HAND_INS, "seed " + seed);
                assertEquals(0, kpq.itemCount(id, PASS), "pass given to the leader");
            }
            // every wrong hand-in Cloto saw was a planned try, and the chaotic bot (13) always shows at least one
            // unless its answer is the smallest (8 has only 7 below it, still one option)
            assertEquals(run.wrongShownTotal(), kpq.wrongHandIns.size(), "seed " + seed + " " + kpq.wrongHandIns);
            assertTrue(run.wrongShown(13) >= 1, "seed " + seed + " chaotic bot tried nothing wrong");
        }
    }

    @Test
    void perfectBotsNeverHandInAWrongCount() {
        Orchestrator o = orchestrator(7);
        List<RunMember> party = List.of(bot(1, true, MechanicsPersona.PERFECT), bot(2, false, MechanicsPersona.PERFECT),
                bot(3, false, MechanicsPersona.PERFECT));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        FakeKpq kpq = new FakeKpq(run, 7);
        play(o, run, kpq, 10_000, null);
        assertTrue(run.cleared(), run.events().toString());
        assertTrue(kpq.wrongHandIns.isEmpty(), kpq.wrongHandIns.toString());
        assertEquals(0, run.wrongShownTotal());
    }

    @Test
    void wrongCountsArePlausibleAscendingBelowTheAnswerAndAtMostTwo() {
        List<Integer> table = List.of(10, 35, 20, 25, 25, 30, 8);
        Random rnd = new Random(3);
        for (int i = 0; i < 2_000; i++) {
            int right = table.get(rnd.nextInt(table.size()));
            MechanicsPersona p = new MechanicsPersona(rnd.nextDouble(), rnd.nextDouble());
            List<Integer> wrong = QuestionCouponsSolver.plannedWrongCounts(right, table, p, 2.0, rnd);
            assertTrue(wrong.size() <= 2, wrong.toString());
            int prev = 0;
            for (int w : wrong) {
                assertTrue(w < right && w > prev, right + " " + wrong);
                assertTrue(table.contains(w) || w == right - 1, "a mixed-up answer or one short: " + w);
                prev = w;
            }
        }
        assertEquals(List.of(), QuestionCouponsSolver.plannedWrongCounts(35, table, MechanicsPersona.PERFECT, 2.0, rnd));
    }

    @Test
    void aRealLeaderGetsThePassesAndIsNeverSteered() {
        Orchestrator o = orchestrator(5);
        RunMember gm = RunMember.player(100, true);
        List<RunMember> party = List.of(gm, bot(21, false, MechanicsPersona.AVERAGE),
                bot(22, false, MechanicsPersona.AVERAGE), bot(23, false, MechanicsPersona.AVERAGE));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        assertEquals("leader", run.roleOf(100), "the player keeps the leader's job");
        FakeKpq kpq = new FakeKpq(run, 5);
        List<Map<Integer, SolverStep>> trace = new ArrayList<>();
        play(o, run, kpq, 20_000, trace);
        assertTrue(run.cleared(), run.events().toString());
        boolean droppedForPlayer = false;
        for (Map<Integer, SolverStep> steps : trace) {
            assertFalse(steps.containsKey(100), "a real player is never given a step");
            for (SolverStep s : steps.values()) {
                if (s instanceof SolverStep.DropFor d && d.itemId() == PASS) {
                    assertEquals(100, d.toCharacterId());
                    droppedForPlayer = true;
                }
            }
        }
        assertTrue(droppedForPlayer);
    }

    @Test
    void aRealAnswererDoesHisOwnQuestionAndBotsDoTheirs() {
        Orchestrator o = orchestrator(9);
        List<RunMember> party = List.of(bot(31, true, MechanicsPersona.PERFECT), RunMember.player(200, false),
                bot(32, false, MechanicsPersona.PERFECT));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        assertEquals("answerer", run.roleOf(200));
        FakeKpq kpq = new FakeKpq(run, 9);
        play(o, run, kpq, 20_000, null);
        assertTrue(run.cleared(), run.events().toString());
        assertEquals(0, kpq.itemCount(200, COUPON) + kpq.itemCount(200, PASS));
    }

    @Test
    void extraCouponsAreDroppedBeforeHandingIn() {
        Orchestrator o = orchestrator(1);
        List<RunMember> party = List.of(bot(1, true, MechanicsPersona.PERFECT), bot(2, false, MechanicsPersona.PERFECT),
                bot(3, false, MechanicsPersona.PERFECT));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        FakeKpq kpq = new FakeKpq(run, 1);
        kpq.grid.put(2, 7); // answer 8
        kpq.add(2, COUPON, 11);
        Map<Integer, SolverStep> steps = o.tick(run, kpq, 1_000);
        SolverStep s = steps.get(2);
        assertInstanceOf(SolverStep.DropFor.class, s);
        assertEquals(3, ((SolverStep.DropFor) s).count());
        assertEquals(0, ((SolverStep.DropFor) s).toCharacterId(), "extras go on the floor");
        assertInstanceOf(SolverStep.TalkNpc.class, steps.get(3), "no question yet: ask Cloto");
        assertInstanceOf(SolverStep.Wait.class, steps.get(1), "the leader waits for passes");
    }

    @Test
    void losingMembersReclaimsThenAbandonsBelowTheMinimum() {
        Orchestrator o = orchestrator(2);
        List<RunMember> party = List.of(bot(1, true, MechanicsPersona.PERFECT), bot(2, false, MechanicsPersona.PERFECT),
                bot(3, false, MechanicsPersona.PERFECT), bot(4, false, MechanicsPersona.PERFECT));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        FakeKpq kpq = new FakeKpq(run, 2);
        kpq.present.remove(Integer.valueOf(4)); // logged out
        o.tick(run, kpq, 1_000);
        assertNull(run.endedBecause(), "3 left is still a KPQ party");
        assertEquals(3, run.members().size());
        play(o, run, kpq, 20_000, null);
        assertTrue(run.cleared(), "two passes are enough for three: " + run.events());

        RunState run2 = o.start(kpq(), Tier.NORMAL, party);
        o.memberLost(run2, 4, "left the party");
        o.memberLost(run2, 3, "left the party");
        assertTrue(run2.endedBecause().startsWith("too few members"), run2.endedBecause());

        RunState run3 = o.start(kpq(), Tier.NORMAL, party);
        o.memberLost(run3, 1, "disconnected");
        assertTrue(run3.endedBecause().startsWith("the leader left"), run3.endedBecause());
    }

    @Test
    void theTimeBudgetEndsARunThatDoesNotClear() {
        Orchestrator o = new Orchestrator(Orchestrator.phase1Solvers(), new Random(1), 60_000, 1);
        List<RunMember> party = List.of(bot(1, true, MechanicsPersona.PERFECT), bot(2, false, MechanicsPersona.PERFECT),
                bot(3, false, MechanicsPersona.PERFECT));
        RunState run = o.start(kpq(), Tier.NORMAL, party);
        FakeKpq kpq = new FakeKpq(run, 1);
        o.tick(run, kpq, 1_000);
        o.tick(run, kpq, 61_001);
        assertNotNull(run.endedBecause());
        assertTrue(run.endedBecause().startsWith("time budget"), run.endedBecause());
        assertFalse(run.cleared());
        assertTrue(o.tick(run, kpq, 62_000).isEmpty(), "an ended run gives no steps");
    }

    @Test
    void aRunNeedsBotsOneLeaderAndAKpqSizedParty() {
        Orchestrator o = orchestrator(1);
        assertTrue(o.start(kpq(), Tier.NORMAL, List.of(RunMember.player(1, true), RunMember.player(2, false),
                RunMember.player(3, false))).endedBecause().startsWith("no bots"));
        assertTrue(o.start(kpq(), Tier.NORMAL, List.of(bot(1, true, MechanicsPersona.PERFECT),
                bot(2, false, MechanicsPersona.PERFECT))).endedBecause().contains("takes 3-4 members"));
        assertTrue(o.start(kpq(), Tier.NORMAL, List.of(bot(1, false, MechanicsPersona.PERFECT),
                bot(2, false, MechanicsPersona.PERFECT), bot(3, false, MechanicsPersona.PERFECT)))
                .endedBecause().contains("one leader"));
        assertEquals(1, o.lastPlayable(kpq()), "phase 1 plays stage 1 only");
    }
}
