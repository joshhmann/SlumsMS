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

    /** How long a bot keeps asking the reward NPC for its prize before it gives up (a full bag; bot audit B6). */
    public static final long PRIZE_GIVE_UP_MS = 60_000; // room for the walk to him and a few clicks

    /** The solvers phase 1 has. */
    public static Map<String, StageSolver> phase1Solvers() {
        QuestionCouponsSolver qc = new QuestionCouponsSolver();
        return Map.of(qc.id(), qc);
    }

    /**
     * Every solver there is (1.6.1: KPQ end to end; 1.6.4: Orbis PQ end to end, Ludi PQ's nine stages, and
     * Henesys PQ's seed beds).
     */
    public static Map<String, StageSolver> allSolvers() {
        QuestionCouponsSolver qc = new QuestionCouponsSolver();
        CombinationSolver cb = new CombinationSolver();
        KillCollectHandInSolver kc = new KillCollectHandInSolver();
        BossPhasesSolver bp = new BossPhasesSolver();
        OpqCloudsSolver oc = new OpqCloudsSolver();
        OpqSweepSolver os = new OpqSweepSolver();
        OpqSubRoomSolver sr = new OpqSubRoomSolver();
        OpqLeversSolver ol = new OpqLeversSolver();
        OpqLobbySolver la = new OpqLobbySolver();
        OpqSealedSolver se = new OpqSealedSolver();
        LpqFarmSolver lf = new LpqFarmSolver();
        LpqComboSolver lc = new LpqComboSolver();
        OpqStatueSolver st = new OpqStatueSolver();
        HpqBedsSolver hb = new HpqBedsSolver();
        CafeCouponsSolver cc = new CafeCouponsSolver();
        PirateQuestSolver pq = new PirateQuestSolver();
        TreasureChestSolver tc = new TreasureChestSolver();
        DelliHoldSolver dh = new DelliHoldSolver();
        MagatiaBooksSolver mb = new MagatiaBooksSolver();
        FightTheRoomSolver fr = new FightTheRoomSolver();
        ReactorCountSolver rc = new ReactorCountSolver();
        MagatiaFilesSolver mf = new MagatiaFilesSolver();
        MagatiaClimbSolver mc = new MagatiaClimbSolver();
        SummonedBossSolver sb = new SummonedBossSolver();
        ReactorStateSolver rs = new ReactorStateSolver();
        EllinMarblesSolver em = new EllinMarblesSolver();
        EllinStoneSolver es = new EllinStoneSolver();
        CwkpqSigilSolver cs = new CwkpqSigilSolver();
        CwkpqDoorSolver cd = new CwkpqDoorSolver();
        CwkpqBossSolver cbo = new CwkpqBossSolver();
        HoldSolver ho = new HoldSolver();
        LudiMazeSolver lm = new LudiMazeSolver();
        ZakumMinesSolver zm = new ZakumMinesSolver();
        // Map.of caps at ten pairs; anything past that goes in a second immutable copy
        Map<String, StageSolver> all = new java.util.HashMap<>(Map.of(qc.id(), qc, cb.id(), cb, kc.id(), kc,
                bp.id(), bp, oc.id(), oc, os.id(), os, sr.id(), sr, ol.id(), ol));
        all.put(la.id(), la);
        all.put(se.id(), se);
        all.put(st.id(), st);
        all.put(lf.id(), lf);
        all.put(lc.id(), lc);
        all.put(hb.id(), hb);
        all.put(cc.id(), cc);
        all.put(pq.id(), pq);
        all.put(tc.id(), tc);
        all.put(dh.id(), dh);
        all.put(mb.id(), mb);
        all.put(fr.id(), fr);
        all.put(rc.id(), rc);
        all.put(mf.id(), mf);
        all.put(mc.id(), mc);
        all.put(sb.id(), sb);
        all.put(rs.id(), rs);
        all.put(em.id(), em);
        all.put(es.id(), es);
        all.put(cs.id(), cs);
        all.put(cd.id(), cd);
        all.put(cbo.id(), cbo);
        all.put(ho.id(), ho);
        all.put(lm.id(), lm);
        all.put(zm.id(), zm);
        for (StageSolver gq : List.of(new GqLobbySolver(), new GqEarringSolver(), new GqStatuesSolver(),
                new GqSpearsSolver(), new GqVassalsSolver(), new GqMazeSolver(), new GqBossSolver())) {
            all.put(gq.id(), gq);
        }
        return Map.copyOf(all);
    }

    @Override
    public RunState start(Content content, Tier tier, List<RunMember> members) {
        int last = lastPlayable(content);
        // the clock is the tier's: rules(NORMAL) is the row's own minutes, a named tier may shorten or lengthen it
        long budget = budgetMs > 0 ? budgetMs
                : Math.max(1, content.rules(tier == null ? Tier.NORMAL : tier)
                        .orElse(content.rules(Tier.NORMAL).orElseThrow()).timeLimitMinutes()) * 60_000L;
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
        String unseated = content.compositionProblem(members.stream().map(RunMember::level).toList());
        if (unseated != null) {
            // a row that asks something of the party (Guild Quest: one member of level 30 or below, under30gate.js:25)
            // cannot be played by one that does not have it, and nothing here stands a member in for a missing one
            run.end(unseated);
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
    /**
     * A stage's own clock, in ms, or 0 when the stage declares none ({@code timeLimitMinutes:} on the stage).
     *
     * <p>1.6.4 boss lane: a boss row's budget is per stage. Each room has its own clock - Horntail's body room restarts
     * the script's timer to 60 minutes when its reactor is hit ({@code scripts/reactor/2401000.js:33}) - so a run that
     * spends its time in the head rooms is not cut off in a room it has only just entered. The party quests that time
     * their rooms separately do the same thing with the same timer ({@code scripts/event/PiratePQ.js:241-268}: six
     * minutes a room; {@code scripts/event/CWKPQ.js:262-294}: ten, ten, ten, twenty, sixty), so the clock is a fact of
     * the stage for every kind of row, not a boss rule.
     */
    static long stageClockMs(Stage stage) {
        int minutes = stage == null ? 0 : stage.timeLimitMinutes();
        return minutes > 0 ? minutes * 60_000L : 0L;
    }

    /**
     * The budget the run is held to on this stage: the stage's own clock when it declares one, else the row's. A boss
     * row counts either from the stage's start ({@link #tick}); a party quest counts the row's from the run's start
     * and a stage's own from the stage's.
     */
    static long budgetFor(RunState run, StageView view) {
        long own = stageClockMs(view.stage());
        return own > 0 ? own : run.budgetMs();
    }

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
                memberLost(run, m.characterId(), "no longer in the stage", nowMs);
                if (run.ended()) {
                    return steps;
                }
            }
        }

        // paused for a revive (ticket 130, owner 2026-11-02): nothing advances, nothing warps, every bot says who it
        // is waiting for; the member's return ends the pause, and the window running out ends the run as it always did
        if (run.waitingFor() != null) {
            if (pausedStepsOrResume(run, view, steps, nowMs)) {
                return steps;
            }
        }

        noteStrays(run, view, nowMs);

        if (view.cleared()) {
            long took = nowMs - run.stageStartedMs();
            run.note(nowMs, "stage " + run.currentStage() + " cleared in " + took / 1000 + " s ("
                    + run.wrongShownTotal() + " wrong tries shown)");
            // a conditional way on (Pre-Horntail's lightbulb room picks one of two caves by a reactor's state,
            // hontale_C.js:30-41): the first branch whose condition holds when the stage clears wins. A branch to a
            // stage this run does not play (past lastStage) and `goto: end` both finish the row here.
            MechanicsModel.Branch branch = view.branchTaken();
            boolean rowEndsHere = false;
            if (branch != null) {
                if (branch.end() || branch.stage() > run.lastStage()) {
                    run.note(nowMs, "stage " + run.currentStage() + ": " + branch.describeWhen() + ": the row ends here");
                    rowEndsHere = true;
                } else {
                    run.note(nowMs, "stage " + run.currentStage() + ": " + branch.describeWhen() + ": on to stage "
                            + branch.stage() + (branch.stage() <= run.currentStage() ? " (a room played before)" : ""));
                    goToStage(run, branch.stage(), nowMs);
                    return steps;
                }
            }
            // a looping stage sends the run back instead of forward (BossRush: the same waves until the clock stops
            // them); the budget check below is what ends such a run, and it ends it the way any over-time run ends
            if (!rowEndsHere && run.stageData().loopTo() > 0) {
                int back = run.stageData().loopTo();
                run.note(nowMs, "stage " + run.currentStage() + " cleared in " + took / 1000
                        + " s: looping back to stage " + back);
                run.setStage(back, nowMs);
                run.clearTries();
                run.setClaims(RoleClaims.claim(run.stageData(), run.members()));
                return steps;
            }
            if (rowEndsHere || run.currentStage() >= run.lastStage()) {
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

        long budget = budgetFor(run, view);
        boolean ownClock = stageClockMs(view.stage()) > 0;
        boolean boss = run.content().kind() == MechanicsModel.Kind.BOSS;
        long spent = boss || ownClock ? nowMs - run.stageStartedMs() : elapsed;
        if (spent > budget) {
            if (ownClock && view.stage().onTimeLimit() == MechanicsModel.OnTimeLimit.ADVANCE
                    && run.currentStage() < run.lastStage()) {
                // the row says this room's clock is a way on, not an end (GuildQuest.js:203-222): no clear is claimed
                run.note(nowMs, "stage " + run.currentStage() + ": its own " + budget / 60_000
                        + " min ran out; the row declares onTimeLimit: advance, so the run goes on to stage "
                        + (run.currentStage() + 1) + " without a clear");
                goToStage(run, run.currentStage() + 1, nowMs);
                return steps;
            }
            fail(run, "time budget of " + budget / 60_000 + " min ran out on stage " + run.currentStage());
            return steps;
        }
        if (!boss && ownClock && elapsed > run.budgetMs()) {
            // a room's clock sits inside the run's: the row's whole-run budget still ends a party quest
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
            if (!stage.mapSet().contains(on) && !solver.extraMaps(view).contains(on)) {
                // a boss run's rooms are separate maps with no knowable portal name: the solver says the map and the
                // step warps straight into the instance's own copy of it (SolverStep.WarpTo). Every other content
                // keeps walking through the portal an earlier stage opened.
                SolverStep into = solver.next(view, m);
                if (into instanceof SolverStep.WarpTo) {
                    steps.put(m.characterId(), into);
                    continue;
                }
                // still behind: walk into the portal an earlier stage opened (its script's gate decides).
                // Any earlier stage, not just the last: a run staged at a later stage walks through every one.
                // A row that gives no portal for that transition (Orbis PQ's rooms are entered by map id) cannot be
                // walked at all, so waiting there would pin the member on a map no script will move it from.
                Stage behind = run.content().stageOnMap(on).orElse(null);
                if (behind != null && behind.index() < stage.index() && behind.nextPortal() != null) {
                    steps.put(m.characterId(), new SolverStep.EnterPortal(behind.nextPortal()));
                } else if (run.content().stageOnMap(on).isPresent()) {
                    steps.put(m.characterId(), new SolverStep.WarpTo(stage.mapId(),
                            "stage " + stage.index() + " is in this room"));
                } else {
                    steps.put(m.characterId(), new SolverStep.Wait(1_000,
                            "not on stage " + stage.index() + "'s map yet"));
                }
                continue;
            }
            planTries(run, view, solver, m, factor, nowMs);
        }
        // tries are planned for every bot first: one bot's plan (a combination leader's) shapes the others' steps
        for (RunMember m : run.members()) {
            if (m.bot() && !steps.containsKey(m.characterId())) {
                SolverStep step = solver.next(view, m);
                if (step instanceof SolverStep.Abandon a) {
                    // the content itself says it cannot be finished: end the run on its reason, do not spin
                    fail(run, a.why());
                    return steps;
                }
                steps.put(m.characterId(), step);
            }
        }
        if (run.held()) {
            hold(run, steps, nowMs);
            return steps; // no reminders to a real leader either: the formation is up for a screenshot
        }
        RunMember leader = view.leader().orElse(null);
        if (leader != null && !leader.bot() && onThisStage(view, solver, stage, leader.characterId())) {
            realLeader(run, view, solver, stage, leader, steps, nowMs);
        }
        return steps;
    }

    /**
     * Whether the member stands on a map this stage drives: its own map, one of its {@code maps:}, or one its solver names.
     * A real leader is prompted - and a bot speaks for the party - from anywhere in a multi-map stage, not only on its first
     * map: Guild Quest's maze is done in rooms the leader leaves for 700, and its boss stage ends on 900.
     */
    private static boolean onThisStage(StageView view, StageSolver solver, Stage stage, int characterId) {
        int on = view.mapOf(characterId);
        return on == stage.mapId() || stage.mapSet().contains(on) || solver.extraMaps(view).contains(on);
    }

    /**
     * A held run (QA hold): every step that would clear the stage (a formation check, a hand-in) becomes a wait, so the
     * bots stand on their spots; walking, climbing, opening the stage with the NPC and fighting go on as usual.
     */
    static void hold(RunState run, Map<Integer, SolverStep> steps, long nowMs) {
        boolean waiting = false;
        for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
            SolverStep s = e.getValue();
            if (s instanceof SolverStep.CheckAnswer || s instanceof SolverStep.TalkNpc t && t.itemId() > 0) {
                e.setValue(new SolverStep.Wait(1_000, "held for QA: !mechanics release to go on"));
                waiting = true;
            }
        }
        if (waiting && run.stageMemory().putIfAbsent("heldNoted", nowMs) == null) {
            run.note(nowMs, "stage " + run.currentStage() + " in place and held (!mechanics release to clear it)");
        }
    }

    /** First reminder this long after the leader's turn comes, then one every {@link #LEADER_PROMPT_EVERY_MS}. */
    static final long LEADER_PROMPT_FIRST_MS = 20_000;
    static final long LEADER_PROMPT_EVERY_MS = 40_000;

    /**
     * A real leader (bot audit K4, K5). Only he can do the leader's part (the scripts check {@code isEventLeader}),
     * and he is never steered: when it is his turn (passes in, formation ready) one bot reminds him in party chat,
     * after his turn came and again every little while ({@link StageSolver#leaderPromptTiming}), so a run with a player leader always says what it waits for. When
     * he is dead the bots stop where they are and one asks him to get up (his revive outside the PQ ends the run).
     */
    private void realLeader(RunState run, StageView view, StageSolver solver, Stage stage, RunMember leader,
                            Map<Integer, SolverStep> steps, long nowMs) {
        Map<String, Object> mem = run.stageMemory();
        int lid = leader.characterId();
        Integer speaker = steps.keySet().stream().filter(id -> onThisStage(view, solver, stage, id)).findFirst()
                .orElse(null);
        if (!view.alive(lid)) {
            for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
                e.setValue(new SolverStep.Wait(1_000, "the leader is down"));
            }
            if (mem.putIfAbsent("leaderDown", nowMs) == null) {
                run.note(nowMs, lid + " (the leader) died; the bots wait");
                if (speaker != null) {
                    steps.put(speaker, new SolverStep.Say("@" + lid + " u died, we wait here for u"));
                }
            }
            return;
        }
        if (mem.remove("leaderDown") != null) {
            run.note(nowMs, lid + " (the leader) is up again");
        }
        java.util.Optional<String> prompt = solver.leaderPrompt(view, leader);
        if (prompt.isEmpty()) {
            mem.remove("leaderTurnSince");
            return;
        }
        Object since = mem.putIfAbsent("leaderTurnSince", nowMs);
        long turn = since == null ? nowMs : (Long) since;
        Object last = mem.get("leaderPromptAt");
        long[] timing = solver.leaderPromptTiming();
        boolean due = last == null ? nowMs - turn >= timing[0] : nowMs - (Long) last >= timing[1];
        if (due && speaker != null) {
            mem.put("leaderPromptAt", nowMs);
            steps.put(speaker, new SolverStep.Say(prompt.get()));
            run.note(nowMs, "reminded the leader: " + prompt.get());
        }
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
        if (bots.stream().allMatch(m -> run.stageMemory().containsKey("prizeGaveUp:" + m.characterId())
                && view.mapOf(m.characterId()) == after.rewardMap())) {
            run.note(nowMs, "every bot left has given up on its prize");
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
                // B6 (bot audit): Cloto keeps a bot with a full bag on his map; it clicked him every 2.5 s until
                // the after-clear budget ran out. It gives up after PRIZE_GIVE_UP_MS and waits there.
                Object since = run.stageMemory().putIfAbsent("prizeSince:" + id, nowMs);
                long first = since == null ? nowMs : (Long) since;
                if (nowMs - first > PRIZE_GIVE_UP_MS) {
                    if (run.stageMemory().putIfAbsent("prizeGaveUp:" + id, true) == null) {
                        run.note(nowMs, id + " gives up on the prize (no room for it?)");
                    }
                    steps.put(id, new SolverStep.Wait(5_000, "no room for the prize"));
                    continue;
                }
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

    /** How long a member may sit where the run cannot drive it before one note says so (a walk between stages fits). */
    public static final long STRAY_GRACE_MS = 15_000;

    /**
     * One line for a member the run cannot drive: down, still on another map, or gone from the stage's map. Without
     * it a stage that never finishes reads exactly like a stall with no cause (TEST 10-31: t-mx-2 stage 3 waited
     * 19 minutes in silence, t-mx-5's second alt never asked Cloto and said nothing). Noted once per member and
     * reason, after {@link #STRAY_GRACE_MS}, and again only if the member left and strayed once more.
     */
    /** A stage whose solver drives a second map (Orbis PQ's garden) is not a stray there. */
    private static boolean runsThisMap(RunState run, StageView view, int mapId) {
        StageSolver solver = builtins().get(run.stageData().solver());
        return solver != null && (view.stage().mapSet().contains(mapId) || solver.extraMaps(view).contains(mapId));
    }

    /** The shipped solvers, built once: they are stateless, and the static helpers below cannot see the instance map. */
    private static volatile Map<String, StageSolver> builtins;

    private static Map<String, StageSolver> builtins() {
        Map<String, StageSolver> m = builtins;
        if (m == null) {
            builtins = m = allSolvers();
        }
        return m;
    }

    /** The run moves to {@code stage} and starts it: tries cleared, roles claimed afresh, one history line. */
    private static void goToStage(RunState run, int stage, long nowMs) {
        run.setStage(stage, nowMs);
        run.clearTries();
        run.setClaims(RoleClaims.claim(run.stageData(), run.members()));
        run.note(nowMs, "stage " + run.currentStage() + ": " + describe(run));
    }

    static void noteStrays(RunState run, StageView view, long nowMs) {
        Stage stage = run.stageData();
        Map<String, Object> mem = run.stageMemory();
        for (RunMember m : run.members()) {
            int id = m.characterId();
            int on = view.mapOf(id);
            String why = !view.alive(id) ? "is down"
                    : on != stage.mapId() && !runsThisMap(run, view, on)
                    ? "is on map " + on + (on == -1 ? " (not in the event)" : "") : null;
            String key = "stray:" + id;
            if (why == null) {
                mem.remove(key);
                mem.remove(key + ":noted");
                continue;
            }
            if (mem.putIfAbsent(key, nowMs) == null || nowMs - (Long) mem.get(key) < STRAY_GRACE_MS) {
                continue;
            }
            if (mem.putIfAbsent(key + ":noted", true) == null) {
                run.note(nowMs, id + " " + why + ", not on stage " + stage.index() + "'s map " + stage.mapId()
                        + ": the run cannot drive it");
            }
        }
    }

    @Override
    public void memberLost(Run r, int characterId, String why, long nowMs) {
        RunState run = (RunState) r;
        RunMember lost = run.member(characterId);
        int index = lost == null ? 0 : run.members().indexOf(lost);
        if (lost == null || !run.removeMember(characterId)) {
            return;
        }
        run.note(nowMs, characterId + " lost: " + why);
        if (mayPauseForRevive(run, nowMs)) {
            long window = RevivePause.windowMs(run.content());
            run.waitFor(lost, index, why, nowMs, nowMs + window);
            run.note(nowMs, "run held for " + window / 1_000 + " s: " + characterId + " may revive and walk back in"
                    + " (-D" + RevivePause.FLAG + "=false ends the run instead)");
            return;
        }
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

    /**
     * A boss run holds itself for a member that leaves so it can revive and walk back in (ticket 130, owner 2026-11-02);
     * a party quest does not -- a KPQ or OPQ run whose member leaves ends exactly as it did before this existed.
     */
    static boolean mayPauseForRevive(RunState run, long nowMs) {
        if (!RevivePause.on() || run.content().kind() != MechanicsModel.Kind.BOSS) {
            return false;
        }
        if (RevivePause.windowMs(run.content()) <= 0 || run.waitingFor() != null) {
            return false;
        }
        // a run already past its budget would only pause to end on the same tick: let today's path have it
        int minutes = run.stageData().timeLimitMinutes();
        long budget = minutes > 0 ? minutes * 60_000L : run.budgetMs();
        return nowMs - run.stageStartedMs() <= budget;
    }

    /**
     * While the run holds itself for a member: resume it if the member is back, end it if the window is over, and
     * otherwise put a Wait naming that member in every bot's slot. Returns whether the caller should stop there (steps
     * left as they are).
     */
    private boolean pausedStepsOrResume(RunState run, StageView view, Map<Integer, SolverStep> steps, long nowMs) {
        RunState.Waiter waiter = run.waitingFor();
        int id = waiter.member() == null ? 0 : waiter.member().characterId();
        if (view.registered(id)) {
            run.waiterReturned(nowMs);
            run.setClaims(RoleClaims.claim(run.stageData(), run.members()));
            run.note(nowMs, id + " is back after " + (nowMs - waiter.sinceMs()) / 1_000 + " s: the run resumes on stage "
                    + run.currentStage());
            return false;
        }
        if (nowMs >= waiter.deadlineMs()) {
            abort(run, "the revive window of " + (waiter.deadlineMs() - waiter.sinceMs()) / 1_000 + " s ran out waiting"
                    + " for " + who(view, id) + " (" + waiter.why() + ") - not a solver failure");
            return true;
        }
        String why = "waiting for " + who(view, id) + " to come back from a revive (" + waiter.why() + ", "
                + Math.max(1, (waiter.deadlineMs() - nowMs) / 1_000) + " s left)";
        for (RunMember m : run.members()) {
            if (m.bot()) {
                steps.put(m.characterId(), new SolverStep.Wait(1_000, why));
            }
        }
        return true;
    }

    /** "Zak (12)" for the log, "12" when the instance does not know the name. */
    private static String who(StageView view, int characterId) {
        return view.nameOf(characterId).map(n -> n + " (" + characterId + ")").orElse(String.valueOf(characterId));
    }

    @Override
    public void abort(Run r, String why) {
        RunState run = (RunState) r;
        if (!run.ended()) {
            run.note(run.startedMs(), "abort: " + why);
            run.end(why);
        }
    }

    /**
     * As {@link #abort}, for a run the party played and could not finish: the run ends {@link RunState.Ending#LOST}, which
     * is the one ending the bots say something sad about. Every other abort stays silent in chat.
     */
    public void fail(Run r, String why) {
        RunState run = (RunState) r;
        if (!run.ended()) {
            run.note(run.startedMs(), "abort: " + why);
            run.fail(why);
        }
    }

    static String describe(RunState run) {
        StringBuilder sb = new StringBuilder();
        if (run.tier() != MechanicsModel.Tier.NORMAL) {
            // only the non-stock tiers are named, so a NORMAL run logs exactly the line it always logged
            sb.append("tier ").append(run.tier()).append("; ");
        }
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
