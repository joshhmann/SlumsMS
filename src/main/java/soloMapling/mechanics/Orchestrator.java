package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;

/**
 * The orchestrator's logic (ticket 130), with no server in it so it can be tested step by step: claims roles per
 * stage, plans each bot's visible wrong tries once its puzzle is known, asks the stage's solver for every bot's next
 * step, notices clears, lost members and the time budget. {@link MechanicsRuns} owns the clock, the event instance and
 * the bots, and carries the steps out.
 */
public final class Orchestrator implements RunOrchestrator {

    /** How long a stage may sit with a required role unfilled before the run is abandoned. */
    public static final long UNFILLED_GRACE_MS = 60_000;

    private final Map<String, StageSolver> solvers;
    private final Random rnd;
    private final long budgetMs;
    private final int lastStage;

    /**
     * @param budgetMs  the run's time budget (0 = the content's own time limit)
     * @param lastStage the last stage to play (phase 1 plays KPQ stage 1 only); 0 = every stage with a solver
     */
    public Orchestrator(Map<String, StageSolver> solvers, Random rnd, long budgetMs, int lastStage) {
        this.solvers = Map.copyOf(solvers);
        this.rnd = rnd;
        this.budgetMs = budgetMs;
        this.lastStage = lastStage;
    }

    /** How long the members may take after the last clear (prize, bonus, way out) before the run lets them go. */
    public static final long AFTER_CLEAR_BUDGET_MS = 10 * 60_000L;

    /** The solvers phase 1 has. */
    public static Map<String, StageSolver> phase1Solvers() {
        QuestionCouponsSolver qc = new QuestionCouponsSolver();
        return Map.of(qc.id(), qc);
    }

    /** Every solver there is (1.6.1: KPQ end to end). */
    public static Map<String, StageSolver> allSolvers() {
        QuestionCouponsSolver qc = new QuestionCouponsSolver();
        CombinationSolver cb = new CombinationSolver();
        KillCollectHandInSolver kc = new KillCollectHandInSolver();
        return Map.of(qc.id(), qc, cb.id(), cb, kc.id(), kc);
    }

    @Override
    public RunState start(Content content, Tier tier, List<RunMember> members) {
        int last = lastPlayable(content);
        long budget = budgetMs > 0 ? budgetMs : Math.max(1, content.timeLimitMinutes()) * 60_000L;
        RunState run = new RunState(content, tier, members, budget, Math.max(1, last));
        if (last < 1) {
            run.end("no stage of " + content.id() + " has a solver yet");
            return run;
        }
        long bots = members.stream().filter(RunMember::bot).count();
        if (bots == 0) {
            run.end("no bots in the party: nothing to orchestrate");
            return run;
        }
        if (members.stream().filter(RunMember::leader).count() != 1) {
            run.end("the party needs exactly one leader");
            return run;
        }
        if (members.size() < content.minPlayers() || members.size() > content.maxPlayers()) {
            run.end(content.id() + " takes " + content.minPlayers() + "-" + content.maxPlayers() + " members, not "
                    + members.size());
            return run;
        }
        run.setClaims(RoleClaims.claim(run.stageData(), run.members()));
        run.note(0, "start " + content.id() + " stage 1 with " + describe(run));
        return run;
    }

    /**
     * QA staging ({@code !mechanics kpq ... stageN}): the run begins on stage {@code first}; the caller has already
     * put the event in the state the earlier clears leave (their clear properties set, their portals open), so the
     * members walk there through those portals as a party would. Returns false (run untouched) when the stage is not
     * one this run can play.
     */
    public boolean startAt(RunState run, int first, long nowMs) {
        if (run.ended() || first <= 1) {
            return !run.ended();
        }
        if (first > run.lastStage()) {
            return false;
        }
        run.setStage(first, nowMs);
        run.clearTries();
        run.setClaims(RoleClaims.claim(run.stageData(), run.members()));
        run.note(nowMs, "staged at stage " + first + ": " + describe(run));
        return true;
    }

    /** The highest stage (in order from 1) that has a solver, capped by {@code lastStage}; 0 when stage 1 has none. */
    int lastPlayable(Content content) {
        int last = 0;
        for (Stage s : content.stages()) {
            if (s.index() != last + 1 || !solvers.containsKey(s.solver())) {
                break;
            }
            last = s.index();
            if (lastStage > 0 && last >= lastStage) {
                break;
            }
        }
        return last;
    }

    @Override
    public Map<Integer, SolverStep> tick(Run r, StageView view, long nowMs) {
        RunState run = (RunState) r;
        Map<Integer, SolverStep> steps = new LinkedHashMap<>();
        if (run.ended()) {
            return steps;
        }
        run.markStarted(nowMs);
        long elapsed = nowMs - run.startedMs();
        if (run.afterClear()) {
            return afterClearTick(run, view, nowMs);
        }

        // members who are no longer in the stage (logged out, left the party or the event)
        Set<Integer> present = new HashSet<>();
        for (RunMember m : view.members()) {
            present.add(m.characterId());
        }
        for (RunMember m : run.members()) {
            if (!present.contains(m.characterId())) {
                memberLost(run, m.characterId(), "no longer in the stage");
                if (run.ended()) {
                    return steps;
                }
            }
        }

        if (view.cleared()) {
            long took = nowMs - run.stageStartedMs();
            run.note(nowMs, "stage " + run.currentStage() + " cleared in " + took / 1000 + " s ("
                    + run.wrongShownTotal() + " wrong tries shown)");
            if (run.currentStage() >= run.lastStage()) {
                run.markCleared(elapsed);
                MechanicsModel.AfterClear after = run.content().afterClear();
                if (after != null && run.lastStage() == run.content().stages().size()) {
                    run.enterAfterClear(nowMs);
                    run.note(nowMs, run.content().id() + " cleared in " + elapsed / 1000 + " s; prizes, bonus and out");
                    return steps;
                }
                run.end("cleared");
                return steps;
            }
            run.setStage(run.currentStage() + 1, nowMs);
            run.clearTries();
            run.setClaims(RoleClaims.claim(run.stageData(), run.members()));
            run.note(nowMs, "stage " + run.currentStage() + ": " + describe(run));
            return steps; // the next stage's map is entered first; steps start next tick
        }

        if (elapsed > run.budgetMs()) {
            abort(run, "time budget of " + run.budgetMs() / 60_000 + " min ran out on stage " + run.currentStage());
            return steps;
        }

        RoleClaims.Claims claims = run.claims();
        if (claims == null || !claims.complete()) {
            if (run.unfilledSinceMs() == 0) {
                run.setUnfilledSinceMs(nowMs);
            } else if (nowMs - run.unfilledSinceMs() > UNFILLED_GRACE_MS) {
                abort(run, "roles unfilled for " + UNFILLED_GRACE_MS / 1000 + " s: "
                        + (claims == null ? "no claims" : claims.unfilled()));
                return steps;
            }
        } else {
            run.setUnfilledSinceMs(0);
        }

        Stage stage = run.stageData();
        StageSolver solver = solvers.get(stage.solver());
        double factor = run.content().rules(run.tier()).map(MechanicsModel.TierRules::botMistakeFactor)
                .orElse(TryPlanner.tierFactor(run.tier()));
        for (RunMember m : run.members()) {
            if (!m.bot()) {
                continue; // a real player is planned around, never steered
            }
            int on = view.mapOf(m.characterId());
            if (on != stage.mapId()) {
                // still behind: walk into the portal an earlier stage opened (its script's gate decides), else wait.
                // Any earlier stage, not just the last: a run staged at a later stage walks through every one
                Stage behind = run.content().stageOnMap(on).orElse(null);
                steps.put(m.characterId(), behind != null && behind.index() < stage.index()
                        && behind.nextPortal() != null
                        ? new SolverStep.EnterPortal(behind.nextPortal())
                        : new SolverStep.Wait(1_000, "not on stage " + stage.index() + "'s map yet"));
                continue;
            }
            planTries(run, view, solver, m, factor, nowMs);
        }
        // tries are planned for every bot first: one bot's plan (a combination leader's) shapes the others' steps
        for (RunMember m : run.members()) {
            if (m.bot() && !steps.containsKey(m.characterId())) {
                steps.put(m.characterId(), solver.next(view, m));
            }
        }
        return steps;
    }

    private void planTries(RunState run, StageView view, StageSolver solver, RunMember m, double factor, long nowMs) {
        if (run.triesPlanned(m.characterId())) {
            return;
        }
        OptionalInt answer = view.answerFor(m.characterId());
        if (answer.isPresent()) {
            List<Integer> wrong = solver.planWrongTries(view, m, answer.getAsInt(), m.persona(), factor, rnd);
            run.planTries(m.characterId(), wrong);
            if (!wrong.isEmpty() || run.stageData().answerProperty() == null || m.leader()) {
                run.note(nowMs, m.characterId() + " got answer " + answer.getAsInt()
                        + (wrong.isEmpty() ? "" : ", will try " + wrong + " first"));
            }
        }
    }

    /**
     * A member handed {@code count} of {@code itemId} to the stage NPC. A planned wrong try is used up (the next
     * step goes on toward the right answer); returns true when it was one.
     */
    public boolean noteHandIn(RunState run, int characterId, int itemId, int count, long nowMs) {
        boolean wrong = run.useWrongTry(characterId, count);
        run.note(nowMs, characterId + " hands in " + count + " x " + itemId + (wrong ? " (a wrong try)" : ""));
        return wrong;
    }

    /** A member checked a puzzle as {@code value} (a combination's index): a planned wrong try is used up. */
    public boolean noteTry(RunState run, int characterId, int value, long nowMs) {
        boolean wrong = run.useWrongTry(characterId, value);
        run.note(nowMs, characterId + " checks " + value + (wrong ? " (a wrong try)" : ""));
        return wrong;
    }

    /**
     * After the last clear (content with {@code afterClear}): every bot claims its prize from the reward NPC, which
     * sends it to the bonus map; there it hunts for a while (planned per bot) and leaves through the exit NPC. A bot
     * never leaves a real player alone in the bonus (the event would end and throw him out). The run ends, cleared,
     * once no bot is left in the instance or after {@link #AFTER_CLEAR_BUDGET_MS}.
     */
    Map<Integer, SolverStep> afterClearTick(RunState run, StageView view, long nowMs) {
        Map<Integer, SolverStep> steps = new LinkedHashMap<>();
        MechanicsModel.AfterClear after = run.content().afterClear();
        Set<Integer> present = new HashSet<>();
        boolean playerPresent = false;
        for (RunMember m : view.members()) {
            present.add(m.characterId());
            playerPresent |= !m.bot();
        }
        List<RunMember> bots = run.members().stream().filter(m -> m.bot() && present.contains(m.characterId())).toList();
        if (bots.isEmpty()) {
            run.note(nowMs, "every bot has left " + run.content().id());
            run.end("cleared");
            return steps;
        }
        if (nowMs - run.afterClearSinceMs() > AFTER_CLEAR_BUDGET_MS) {
            run.note(nowMs, "after-clear time is up: " + bots.size() + " bot(s) let go where they stand");
            run.end("cleared");
            return steps;
        }
        int count = present.size();
        for (RunMember m : bots) {
            int id = m.characterId();
            int on = view.mapOf(id);
            if (on == after.rewardMap()) {
                steps.put(id, new SolverStep.Converse(after.rewardNpc(), List.of(0), "claim the prize"));
            } else if (after.bonusMap() > 0 && on == after.bonusMap()) {
                Long until = run.bonusUntil(id);
                if (until == null) {
                    long dwell = bonusDwellMs(after, m.persona(), rnd);
                    run.planBonus(id, nowMs + dwell);
                    run.note(nowMs, id + " hunts the bonus for " + dwell / 1000 + " s");
                    until = run.bonusUntil(id);
                }
                if (nowMs >= until && (!playerPresent || count - 1 >= 2)) {
                    steps.put(id, new SolverStep.Converse(after.exitNpc(), List.of(0), "leave the bonus"));
                    count--;
                } else {
                    steps.put(id, new SolverStep.Fight(0, 0, 0));
                }
            } else {
                steps.put(id, new SolverStep.Wait(1_000, "after the clear"));
            }
        }
        return steps;
    }

    /** How long a bot hunts the bonus: between the content's min and max, a chaotic bot nearer the max. */
    static long bonusDwellMs(MechanicsModel.AfterClear after, MechanicsPersona persona, Random rnd) {
        int lo = Math.max(0, after.bonusMinSeconds());
        int hi = Math.max(lo, after.bonusMaxSeconds());
        double f = Math.min(1.0, 0.6 * rnd.nextDouble() + 0.4 * persona.chaos());
        return (long) ((lo + (hi - lo) * f) * 1000);
    }

    @Override
    public void memberLost(Run r, int characterId, String why) {
        RunState run = (RunState) r;
        RunMember lost = run.member(characterId);
        if (lost == null || !run.removeMember(characterId)) {
            return;
        }
        run.note(run.startedMs(), characterId + " lost: " + why);
        if (lost.leader()) {
            abort(run, "the leader left (" + why + ")");
            return;
        }
        if (run.members().size() < run.content().minPlayers()) {
            abort(run, "too few members left (" + run.members().size() + " < " + run.content().minPlayers() + ")");
            return;
        }
        if (run.members().stream().noneMatch(RunMember::bot)) {
            abort(run, "no bots left in the run");
            return;
        }
        run.setClaims(RoleClaims.claim(run.stageData(), run.members()));
    }

    @Override
    public void abort(Run r, String why) {
        RunState run = (RunState) r;
        if (!run.ended()) {
            run.note(run.startedMs(), "abort: " + why);
            run.end(why);
        }
    }

    static String describe(RunState run) {
        StringBuilder sb = new StringBuilder();
        for (RunMember m : run.members()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(m.characterId()).append(m.bot() ? "" : " (player)").append(m.leader() ? " leader" : "")
                    .append(" = ").append(run.roleOf(m.characterId()));
        }
        RoleClaims.Claims c = run.claims();
        if (c != null && !c.complete()) {
            sb.append("; unfilled ").append(c.unfilled());
        }
        return sb.toString();
    }
}
