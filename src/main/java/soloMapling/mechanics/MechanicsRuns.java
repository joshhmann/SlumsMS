package soloMapling.mechanics;

import client.Character;
import scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static soloMapling.BotLogger.log;

/**
 * The game-server side of ticket 130 (phase 1): runs the {@link Orchestrator} for each party that a GM command or a
 * QA check started, on a 4 Hz tick of the shared scheduler. Per tick it snapshots the event instance
 * ({@link LiveStageView}), asks the orchestrator for every bot's step and carries the steps out
 * ({@link StepExecutor}). While a run holds a bot, the bot's own brain is paused and {@link MechanicsHold} keeps it
 * from logging out; every end (cleared, timer, too few members, the event closing, an exception, a stop) releases the
 * bots and wakes their brains, so nothing stays held. Nothing here runs unless {@code -Dslums.mechanics=true}.
 */
public final class MechanicsRuns {

    static final long TICK_MS = 250;

    private static final MechanicsRuns INSTANCE = new MechanicsRuns();

    public static MechanicsRuns get() {
        return INSTANCE;
    }

    /** One run on the server: the orchestrator's state plus the event instance and the bots it drives. */
    public static final class Active {
        final RunState run;
        final EventInstanceManager eim;
        final Map<Integer, Character> bots;
        final List<Integer> pausedBrains = new ArrayList<>();
        final Consumer<String> reporter;
        final Consumer<Active> onEnd;
        volatile ScheduledFuture<?> tick;
        volatile boolean finished;

        Active(RunState run, EventInstanceManager eim, Map<Integer, Character> bots, Consumer<String> reporter,
               Consumer<Active> onEnd) {
            this.run = run;
            this.eim = eim;
            this.bots = bots;
            this.reporter = reporter;
            this.onEnd = onEnd;
        }

        public RunState run() {
            return run;
        }

        public String instanceName() {
            return eim.getName();
        }

        public List<Character> bots() {
            return List.copyOf(bots.values());
        }
    }

    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final Orchestrator orchestrator = new Orchestrator(Orchestrator.phase1Solvers(), new Random(), 0, 1);
    private final StepExecutor executor = new StepExecutor();

    private MechanicsRuns() {
    }

    /**
     * Starts a run for the party already registered in {@code eim} (the event script put them on its first map).
     *
     * @param perfect  every bot plays the perfect persona (no wrong tries)
     * @param reporter gets one line per thing that happened (GM chat, QA log); called on the tick thread
     * @param onEnd    called once when the run ends, after the bots are released (cleanup such as despawning)
     * @return the run, or a run already ended with the reason it could not start
     */
    public Active start(EventInstanceManager eim, Content content, boolean perfect, long budgetMs,
                        Consumer<String> reporter, Consumer<Active> onEnd) {
        List<RunMember> members = new ArrayList<>();
        Map<Integer, Character> bots = new ConcurrentHashMap<>();
        for (Character c : eim.getPlayers()) {
            boolean bot = c.isBot();
            boolean leader = c.getId() == eim.getLeaderId();
            members.add(new RunMember(c.getId(), leader, bot,
                    !bot || perfect ? MechanicsPersona.PERFECT : MechanicsPersona.forCharacter(c.getId())));
            if (bot) {
                bots.put(c.getId(), c);
            }
        }
        Orchestrator o = budgetMs > 0 ? new Orchestrator(Orchestrator.phase1Solvers(), new Random(), budgetMs, 1)
                : orchestrator;
        RunState run = o.start(content, Tier.NORMAL, members);
        Active a = new Active(run, eim, bots, reporter == null ? line -> { } : reporter, onEnd);
        if (run.ended()) {
            a.finished = true;
            a.reporter.accept("RUN not started: " + run.endedBecause());
            return a;
        }
        if (active.putIfAbsent(eim.getName(), a) != null) {
            run.end("this event instance already has a run");
            a.finished = true;
            a.reporter.accept("RUN not started: " + run.endedBecause());
            return a;
        }
        for (Character bot : bots.values()) {
            MechanicsHold.hold(bot.getId());
            BotSM brain = CharacterStorage.getBotById(bot.getId());
            if (brain != null && brain.getRunning()) {
                BotTypeManager.manuallyStopBot(bot); // the run drives it now; woken again when the run ends
                a.pausedBrains.add(bot.getId());
            }
            GCMovement.stop(bot);
        }
        a.reporter.accept("RUN start " + content.id() + " stage 1 in " + eim.getName() + ": "
                + Orchestrator.describe(run));
        Orchestrator use = o;
        a.tick = ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(
                () -> safeTick(a, use), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        return a;
    }

    private void safeTick(Active a, Orchestrator o) {
        if (a.finished) {
            return;
        }
        try {
            tick(a, o);
        } catch (Throwable t) {
            log("[Mechanics] run " + a.eim.getName() + " threw: " + t);
            o.abort(a.run, "exception: " + t);
        }
        if (a.run.ended()) {
            finish(a);
        }
    }

    private void tick(Active a, Orchestrator o) {
        if (BotShutdown.isQuiesced()) {
            o.abort(a.run, "the server is shutting down");
            return;
        }
        if (a.eim.isEventDisposed()) {
            o.abort(a.run, "the event ended");
            return;
        }
        long now = System.currentTimeMillis();
        LiveStageView view = new LiveStageView(a.run, a.eim);
        int eventsBefore = a.run.events().size();
        Map<Integer, SolverStep> steps = o.tick(a.run, view, now);
        List<RunState.Event> events = a.run.events();
        for (int i = eventsBefore; i < events.size(); i++) {
            a.reporter.accept("RUN " + events.get(i).line());
        }
        for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
            Character bot = view.character(e.getKey());
            if (bot == null || bot.getMap() == null || bot.getMapId() != a.run.stageData().mapId()) {
                continue; // not on the stage's map (respawned elsewhere, being warped): next tick
            }
            executor.execute(a.run, o, view, bot, e.getValue(), now, a.reporter);
        }
    }

    /** Ends a run now (GM stop, QA stop). */
    public void stop(Active a, String why) {
        if (a == null || a.finished) {
            return;
        }
        orchestrator.abort(a.run, why);
        finish(a);
    }

    public void stopAll(String why) {
        for (Active a : List.copyOf(active.values())) {
            stop(a, why);
        }
    }

    private synchronized void finish(Active a) {
        if (a.finished) {
            return;
        }
        a.finished = true;
        ScheduledFuture<?> t = a.tick;
        if (t != null) {
            t.cancel(false);
        }
        active.remove(a.eim.getName(), a);
        for (Character bot : a.bots.values()) {
            MechanicsHold.release(bot.getId());
            executor.forget(bot.getId());
            GCMovement.stop(bot);
        }
        for (int id : a.pausedBrains) {
            Character bot = a.bots.get(id);
            BotSM brain = bot == null ? null : CharacterStorage.getBotById(id);
            if (brain != null && !brain.getRunning()) {
                BotTypeManager.manuallyStartBot(bot);
            }
        }
        RunState r = a.run;
        a.reporter.accept("RUN end " + r.content().id() + " in " + a.eim.getName() + ": " + r.endedBecause()
                + (r.cleared() ? " after " + r.clearedAfterMs() / 1000 + " s, " + r.wrongShownTotal()
                + " wrong tries shown" : ""));
        if (a.onEnd != null) {
            try {
                a.onEnd.accept(a);
            } catch (RuntimeException e) {
                log("[Mechanics] cleanup after " + a.eim.getName() + " threw: " + e);
            }
        }
    }

    public List<Active> active() {
        return List.copyOf(active.values());
    }

    /** One line per run for {@code !mechanics status}. */
    public List<String> status() {
        List<String> out = new ArrayList<>();
        for (Active a : active.values()) {
            RunState r = a.run;
            long secs = r.startedMs() == 0 ? 0 : (System.currentTimeMillis() - r.startedMs()) / 1000;
            out.add(r.content().id() + " stage " + r.currentStage() + " in " + a.eim.getName() + ", " + secs + " s, "
                    + Orchestrator.describe(r) + ", wrong tries " + r.wrongShownTotal());
        }
        if (out.isEmpty()) {
            out.add("no mechanics runs (bots held: " + MechanicsHold.count() + ")");
        }
        return out;
    }
}
