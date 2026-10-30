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
        /** The run's own staging or lobby started this instance: a run that ends short of the clear ends it too. */
        final boolean ownsInstance;
        volatile ScheduledFuture<?> tick;
        volatile boolean finished;

        Active(RunState run, EventInstanceManager eim, Map<Integer, Character> bots, Consumer<String> reporter,
               Consumer<Active> onEnd, boolean ownsInstance) {
            this.run = run;
            this.eim = eim;
            this.bots = bots;
            this.reporter = reporter;
            this.onEnd = onEnd;
            this.ownsInstance = ownsInstance;
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
    private final Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new Random(), 0, 0);
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
        return start(eim, content, perfect, budgetMs, 0, reporter, onEnd);
    }

    /** As above, playing stages up to {@code lastStage} only (0 = all of them, then the prize and the bonus). */
    public Active start(EventInstanceManager eim, Content content, boolean perfect, long budgetMs, int lastStage,
                        Consumer<String> reporter, Consumer<Active> onEnd) {
        return start(eim, content, perfect, budgetMs, lastStage, false, reporter, onEnd);
    }

    /**
     * As above. {@code ownsInstance}: staging or the bot lobby started this instance for the run (a GM's
     * {@code !mechanics kpq}, a bot leader at Lakelis), so a run that ends short of the clear, or cannot start,
     * ends the instance with it (everyone out, disposed); never left for its 30-minute timer.
     */
    public Active start(EventInstanceManager eim, Content content, boolean perfect, long budgetMs, int lastStage,
                        boolean ownsInstance, Consumer<String> reporter, Consumer<Active> onEnd) {
        List<RunMember> members = new ArrayList<>();
        Map<Integer, Character> bots = new ConcurrentHashMap<>();
        for (Character c : eim.getPlayers()) {
            boolean bot = drivable(c);
            boolean leader = c.getId() == eim.getLeaderId();
            members.add(new RunMember(c.getId(), leader, bot,
                    !bot || perfect ? MechanicsPersona.PERFECT : MechanicsPersona.forCharacter(c.getId())));
            if (bot) {
                bots.put(c.getId(), c);
            }
        }
        Orchestrator o = budgetMs > 0 || lastStage > 0
                ? new Orchestrator(Orchestrator.allSolvers(), new Random(), budgetMs, lastStage) : orchestrator;
        RunState run = o.start(content, Tier.NORMAL, members);
        Active a = new Active(run, eim, bots, reporter == null ? line -> { } : reporter, onEnd, ownsInstance);
        if (run.ended()) {
            a.finished = true;
            a.reporter.accept("RUN not started: " + run.endedBecause());
            closeInstance(a);
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
        LiveStageView view = new LiveStageView(a.run, a.eim, now);
        int eventsBefore = a.run.events().size();
        Map<Integer, SolverStep> steps = o.tick(a.run, view, now);
        List<RunState.Event> events = a.run.events();
        for (int i = eventsBefore; i < events.size(); i++) {
            a.reporter.accept("RUN " + events.get(i).line());
        }
        for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
            Character bot = view.character(e.getKey());
            if (bot == null || bot.getMap() == null || bot.getHp() <= 0) {
                continue; // gone, between maps or dead: next tick
            }
            executor.execute(a.run, o, view, bot, e.getValue(), now, a.reporter);
        }
    }

    /**
     * Who a run drives: population bots and the player's owned alts (owner rule: one PQ logic for both; an alt in its
     * owner's party plays the stages like any party bot). Real players are planned around, never driven.
     */
    static boolean drivable(Character c) {
        return c != null && (c.isBot() || c.isOwnedAlt());
    }

    /** Set while staging starts an instance that it then runs itself (no second, automatic run). */
    private static final ThreadLocal<Boolean> STAGING = ThreadLocal.withInitial(() -> false);

    /** Runs {@code start} with automatic runs held off (KpqStaging, the bot leader's Lakelis talk). */
    public static <T> T staging(java.util.function.Supplier<T> start) {
        boolean was = STAGING.get();
        STAGING.set(true);
        try {
            return start.get();
        } finally {
            STAGING.set(was);
        }
    }

    /**
     * 1.6.1 (KPQ automation): a party with bots or owned alts in it just entered an event this package has stage data
     * for (a player talked to Lakelis with bots in his party): the run takes the bots through it. Called by
     * {@code EventManager} after a party instance starts; nothing happens with {@code -Dslums.mechanics} off, for an
     * event without stage data, a party without bots, or while staging starts its own run.
     */
    public static void onPartyInstanceStarted(EventInstanceManager eim) {
        if (!Mechanics.enabled() || eim == null || STAGING.get()) {
            return;
        }
        try {
            String event = eim.getEm() == null ? null : eim.getEm().getName();
            Content content = contentFor(event);
            if (content == null || eim.getPlayers().stream().noneMatch(MechanicsRuns::drivable)) {
                return;
            }
            get().start(eim, content, false, 0, line -> log("[Mechanics] " + eim.getName() + " " + line), null);
        } catch (RuntimeException e) {
            log("[Mechanics] could not start a run for " + eim.getName() + ": " + e);
        }
    }

    private static volatile List<Content> contentCache;

    /** The stage data for an event script name ("KerningPQ"), or null. Loaded once. */
    public static Content contentFor(String eventScript) {
        if (eventScript == null) {
            return null;
        }
        List<Content> all = contentCache;
        if (all == null) {
            all = StageData.load().content();
            contentCache = all;
        }
        for (Content c : all) {
            if (eventScript.equalsIgnoreCase(c.eventScript())) {
                return c;
            }
        }
        return null;
    }

    /** The run on this instance, or null. */
    public Active runFor(EventInstanceManager eim) {
        return eim == null ? null : active.get(eim.getName());
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
        try {
            closeInstance(a);
        } catch (RuntimeException e) {
            log("[Mechanics] could not end " + a.eim.getName() + ": " + e);
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

    /**
     * Whether a run that just ended takes its event instance down with it (1.6.2, TEST 2026-10-30: a stopped run
     * left its KPQ alive for 30 minutes and Lakelis refused the next party). Never while a real player plays on in
     * an instance he started himself; otherwise an instance the run started that did not clear, or one with nobody
     * but bots left in it, ends now.
     */
    static boolean shouldEndInstance(boolean ownsInstance, boolean cleared, boolean disposed, boolean realPlayerInside) {
        if (disposed) {
            return false;
        }
        return !realPlayerInside || ownsInstance && !cleared;
    }

    /** Ends the run's instance when {@link #shouldEndInstance} says so; reports what it did. */
    private void closeInstance(Active a) {
        EventInstanceManager eim = a.eim;
        boolean realInside = eim.getPlayers().stream().anyMatch(c -> !drivable(c));
        if (!shouldEndInstance(a.ownsInstance, a.run.cleared(), eim.isEventDisposed(), realInside)) {
            return;
        }
        int out = endInstance(eim, a.run.content().exitMap());
        a.reporter.accept("RUN instance " + eim.getName() + " ended with the run: " + out + " member(s) sent out");
    }

    /**
     * Ends an event instance the way its script does ({@code end}: every member exits to the exit map, then
     * {@code eim.dispose()}); if the script fails or leaves it alive, exits whoever is left and disposes it here.
     *
     * @return how many members were inside
     */
    public static int endInstance(EventInstanceManager eim, int exitMap) {
        if (eim == null || eim.isEventDisposed()) {
            return 0;
        }
        List<Character> inside = eim.getPlayers();
        try {
            eim.invokeScriptFunction("end", eim);
        } catch (Exception e) {
            log("[Mechanics] " + eim.getName() + "'s script end failed: " + e);
        }
        if (!eim.isEventDisposed()) {
            for (Character c : eim.getPlayers()) {
                try {
                    eim.unregisterPlayer(c);
                    if (exitMap > 0) {
                        c.changeMap(exitMap);
                    }
                } catch (RuntimeException e) {
                    log("[Mechanics] could not send " + c.getName() + " out of " + eim.getName() + ": " + e);
                }
            }
            eim.dispose();
        }
        return inside.size();
    }

    /**
     * {@code !mechanics dispose kpq [channel]}: ends a stuck instance of this content's event on a channel (its run
     * stopped, everyone sent out) when no real non-GM player is inside. One line per instance looked at.
     */
    public List<String> disposeStuck(Content content, net.server.channel.Channel ch, String byGm) {
        List<String> out = new ArrayList<>();
        scripting.event.EventManager em = ch == null ? null : ch.getEventSM().getEventManager(content.eventScript());
        if (em == null) {
            out.add("no " + content.eventScript() + " event on that channel");
            return out;
        }
        for (EventInstanceManager eim : em.getInstances()) {
            if (eim.isEventDisposed()) {
                continue;
            }
            List<Character> inside = eim.getPlayers();
            List<String> players = inside.stream().filter(c -> !drivable(c) && !c.isGM()).map(Character::getName)
                    .toList();
            if (!players.isEmpty()) {
                out.add(eim.getName() + ": not disposed, player(s) inside: " + String.join(", ", players));
                continue;
            }
            Active a = runFor(eim);
            if (a != null) {
                stop(a, "disposed by " + byGm);
            }
            int n = eim.isEventDisposed() ? inside.size() : endInstance(eim, content.exitMap());
            String line = eim.getName() + " on channel " + ch.getId() + ": disposed, " + n + " member(s) sent out";
            log("[Mechanics] " + byGm + " disposed " + line);
            out.add(line);
        }
        if (out.isEmpty()) {
            out.add("no " + content.eventScript() + " instance on channel " + ch.getId());
        }
        return out;
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
