package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 (TEST 2026-10-30, the client lane's real-leader recheck): two bots dropped their KPQ stage 1 passes "by" the
 * GM leader, said so in party chat, and neither pass ever reached his Etc; the leader had moved on and the passes lay
 * where they fell until the run was stopped. Now a bot walks up to a real leader (within 60 px) before it drops, and
 * takes its own pass back when he has left it lying for 20 s, then tries again. No pass ever goes straight into a
 * player's inventory, and a pass dropped for the leader is never another bot's to pick up.
 */
class KpqPassHandoverTest {

    static final int PASS = KpqFullRunTest.PASS, LEADER = 100, A = 21, B = 22;
    static final long TICK = KpqFullRunTest.TICK;

    /** One drop on the floor: who dropped it, whose it is, where and when. */
    record Drop(int itemId, int dropper, int owner, int x, int y, long at) {
    }

    /** KPQ stage 1 with the passes earned: what the solver reads, and a floor that knows where each drop lies. */
    static final class View implements StageView {
        final RunState run;
        long now = 1_000;
        final Map<Integer, int[]> pos = new HashMap<>();
        final Map<Integer, Integer> passes = new HashMap<>();
        final List<Drop> floor = new ArrayList<>();

        View(RunState run) {
            this.run = run;
        }

        @Override
        public Stage stage() {
            return run.stageData();
        }

        @Override
        public List<RunMember> members() {
            return run.members();
        }

        @Override
        public String roleOf(int id) {
            return run.roleOf(id);
        }

        @Override
        public OptionalInt answerFor(int id) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int id, int itemId) {
            return itemId == PASS ? passes.getOrDefault(id, 0) : 0;
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override
        public int[] positionOf(int id) {
            return pos.getOrDefault(id, new int[]{0, 0}).clone();
        }

        @Override
        public int memberStatus(int id) {
            return 0; // every answerer has had its coupons taken: each holds (or held) its pass
        }

        @Override
        public int floorItems(int itemId, int forId) {
            return (int) floor.stream().filter(d -> d.itemId() == itemId
                    && PassHandover.mayTake(forId, d.owner(), d.dropper(), (byte) 0)).count();
        }

        @Override
        public Optional<FloorDrop> dropBy(int dropperId, int itemId) {
            return floor.stream().filter(d -> d.dropper() == dropperId && d.itemId() == itemId)
                    .reduce((a, b) -> b).map(d -> new FloorDrop(d.x(), d.y(), d.owner(), d.at()));
        }

        @Override
        public Optional<int[]> npcPosition(int npcId) {
            return Optional.of(new int[]{2_000, 0});
        }

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return run.stageMemory();
        }
    }

    static Orchestrator orchestrator() {
        return new Orchestrator(Orchestrator.allSolvers(), new Random(7), 30 * 60_000L, 1);
    }

    static RunState gmAndTwoBots(Orchestrator o) {
        return o.start(KpqFullRunTest.kpq(), Tier.NORMAL, List.of(RunMember.player(LEADER, true),
                KpqFullRunTest.bot(A, false, MechanicsPersona.PERFECT), KpqFullRunTest.bot(B, false,
                        MechanicsPersona.PERFECT)));
    }

    static SolverStep stepOf(StageView view, int id) {
        return new QuestionCouponsSolver().next(view, view.members().stream().filter(m -> m.characterId() == id)
                .findFirst().orElseThrow());
    }

    @Test
    void aBotWalksUpToARealLeaderBeforeItDropsItsPass() {
        RunState run = gmAndTwoBots(orchestrator());
        View v = new View(run);
        v.pos.put(LEADER, new int[]{1_500, 0});
        v.pos.put(A, new int[]{-900, 0});
        v.passes.put(A, 1);
        SolverStep far = stepOf(v, A);
        SolverStep.MoveTo walk = assertInstanceOf(SolverStep.MoveTo.class, far, "walks to him first: " + far);
        assertEquals(1_500, walk.x());

        v.pos.put(A, new int[]{1_500 - PassHandover.REACH_PX + 5, 0});
        SolverStep.DropFor drop = assertInstanceOf(SolverStep.DropFor.class, stepOf(v, A), "drops by his feet");
        assertEquals(LEADER, drop.toCharacterId());

        v.pos.put(A, new int[]{1_500, 200}); // under him, a platform below: not within reach
        assertInstanceOf(SolverStep.MoveTo.class, stepOf(v, A));
    }

    @Test
    void aBotLeaderStillGetsTheDropStraightAway() {
        Orchestrator o = orchestrator();
        RunState run = o.start(KpqFullRunTest.kpq(), Tier.NORMAL, List.of(
                KpqFullRunTest.bot(LEADER, true, MechanicsPersona.PERFECT),
                KpqFullRunTest.bot(A, false, MechanicsPersona.PERFECT),
                KpqFullRunTest.bot(B, false, MechanicsPersona.PERFECT)));
        View v = new View(run);
        v.pos.put(LEADER, new int[]{1_500, 0});
        v.pos.put(A, new int[]{-900, 0});
        v.passes.put(A, 1);
        assertInstanceOf(SolverStep.DropFor.class, stepOf(v, A), "its executor walks it there, as before");
    }

    @Test
    void aPassLeftLyingTwentySecondsWhileTheLeaderIsAwayIsTakenBackAndNeverByAnotherBot() {
        RunState run = gmAndTwoBots(orchestrator());
        View v = new View(run);
        v.pos.put(LEADER, new int[]{1_500, 0});
        v.pos.put(A, new int[]{0, 0});
        v.floor.add(new Drop(PASS, A, LEADER, 0, 0, 1_000));

        v.now = 1_000 + PassHandover.TAKE_BACK_AFTER_MS - TICK;
        assertNull(PassHandover.takeBack(v, A, PASS), "his for 20 s");
        v.now = 1_000 + PassHandover.TAKE_BACK_AFTER_MS;
        assertInstanceOf(SolverStep.TakeBack.class, stepOf(v, A));

        v.pos.put(LEADER, new int[]{40, 0}); // he came back and stands on it
        assertNull(PassHandover.takeBack(v, A, PASS), "about to pick it up: left for him");

        v.pos.put(LEADER, new int[]{1_500, 0});
        assertNull(PassHandover.takeBack(v, B, PASS), "only the bot that dropped it takes it back");
        assertEquals(0, v.floorItems(PASS, B), "another bot's floor scan never sees A's hand-over");
        assertEquals(1, v.floorItems(PASS, LEADER), "the leader's does");
    }

    @Test
    void floorScansNeverTakeAHandOverMeantForSomeoneElse() {
        assertTrue(PassHandover.mayTake(LEADER, LEADER, A, (byte) 0), "the owner");
        assertFalse(PassHandover.mayTake(B, LEADER, A, (byte) 0), "another bot");
        assertFalse(PassHandover.mayTake(B, LEADER, A, (byte) 2), "not even when the drop is free for all");
        assertTrue(PassHandover.mayTake(B, 0, 0, (byte) 2), "a monster's free-for-all pass (stage 5)");
        assertTrue(PassHandover.mayTake(B, B, 0, (byte) 0), "its own kill");
        assertFalse(PassHandover.mayTake(B, A, 0, (byte) 0), "another's kill");
    }

    /**
     * The TEST run: the leader walks off right after each bot drops its pass, then stands still and picks up what
     * lands at his feet. Before: both passes lay where they fell for the rest of the run (0 of 2 in his bag). Now each
     * bot takes its pass back after 20 s, walks to where he stands and drops it again.
     */
    @Test
    void aLeaderWhoWalksOffAfterTheDropStillEndsUpWithBothPasses() {
        Orchestrator o = orchestrator();
        RunState run = gmAndTwoBots(o);
        View v = new View(run);
        v.pos.put(LEADER, new int[]{0, 0});
        v.pos.put(A, new int[]{-800, 0});
        v.pos.put(B, new int[]{800, 0});
        v.passes.put(A, 1);
        v.passes.put(B, 1);
        int walkedOff = 0;
        int takenBack = 0;
        for (; v.now < 3 * 60_000; v.now += TICK) {
            for (Map.Entry<Integer, SolverStep> e : o.tick(run, v, v.now).entrySet()) {
                int id = e.getKey();
                switch (e.getValue()) {
                    case SolverStep.MoveTo m -> v.pos.put(id, new int[]{m.x(), m.y()});
                    case SolverStep.DropFor d -> {
                        int[] to = v.positionOf(d.toCharacterId());
                        if (!PassHandover.near(v.positionOf(id), to)) {
                            v.pos.put(id, to); // he moved since the step was planned: the executor walks first
                            continue;
                        }
                        v.passes.merge(id, -d.count(), Integer::sum);
                        int[] at = v.positionOf(d.toCharacterId()); // at his feet (StepExecutor.dropFor)
                        v.floor.add(new Drop(d.itemId(), id, d.toCharacterId(), at[0], at[1], v.now));
                        if (walkedOff < 2) {
                            walkedOff++;
                            v.pos.put(LEADER, new int[]{v.positionOf(LEADER)[0] + 700, 0}); // and off he goes
                        }
                    }
                    case SolverStep.TakeBack t -> {
                        Drop mine = v.floor.stream().filter(d -> d.dropper() == id).findFirst().orElseThrow();
                        v.floor.remove(mine);
                        v.passes.merge(id, 1, Integer::sum);
                        takenBack++;
                    }
                    default -> {
                    }
                }
            }
            // the leader picks up what lies at his feet (a player's Z), and only that
            int[] me = v.positionOf(LEADER);
            v.floor.removeIf(d -> {
                boolean mine = d.owner() == LEADER && Math.abs(d.x() - me[0]) <= 40 && Math.abs(d.y() - me[1]) <= 40;
                if (mine) {
                    v.passes.merge(LEADER, 1, Integer::sum);
                }
                return mine;
            });
            if (v.passes.getOrDefault(LEADER, 0) == 2) {
                break;
            }
        }
        assertEquals(2, v.passes.getOrDefault(LEADER, 0), "both passes reached him; floor " + v.floor);
        assertTrue(takenBack >= 1, "a pass he walked away from was taken back");
        assertTrue(v.now < 90_000, "within a minute and a half: " + v.now);
    }
}
