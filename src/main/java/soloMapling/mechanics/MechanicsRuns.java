package soloMapling.mechanics;

import client.Character;
import scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Collection;
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

    static {
        // the bots' win and loss lines are a reader of the run's outcome record, like any other (RunOutcomes)
        RunOutcomes.listen(RunChatter::onOutcome);
        RunOutcomes.listen(RunSamples.get()::onOutcome); // the real runs a simulated one is sampled from
    }

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
        /** How the run came to be and whether it holds a place in {@link RealRunPool}; released when the run ends. */
        final RealRunPool.Admission admission;
        /** Unique per run: what its {@link RunOutcome} is keyed by. */
        final String runId = java.util.UUID.randomUUID().toString();
        final long startedAtMs = System.currentTimeMillis();
        /** Who was in the run when it started, by what each is (a player, a bot, a player's owned alt). */
        final List<RunOutcome.Participant> participants;
        /** Set when this run's outcome has been written: a run has exactly one. */
        final java.util.concurrent.atomic.AtomicBoolean recorded = new java.util.concurrent.atomic.AtomicBoolean();
        volatile ScheduledFuture<?> tick;
        volatile boolean finished;

        Active(RunState run, EventInstanceManager eim, Map<Integer, Character> bots, Consumer<String> reporter,
               Consumer<Active> onEnd, boolean ownsInstance, RealRunPool.Admission admission,
               List<RunOutcome.Participant> participants) {
            this.run = run;
            this.eim = eim;
            this.bots = bots;
            this.reporter = reporter;
            this.onEnd = onEnd;
            this.ownsInstance = ownsInstance;
            this.admission = admission;
            this.participants = List.copyOf(participants);
        }

        /** The id this run's {@link RunOutcome} will carry. */
        public String runId() {
            return runId;
        }

        /** How this run came to be (a lobby, a staging, a human's party): what its {@link RealRunPool.Admission} says. */
        public RealRunPool.Origin origin() {
            return admission.origin();
        }

        /** Whether this run holds a place in the real-run pool right now (false for a human's or a staged run). */
        public boolean leased() {
            return admission.leased();
        }

        public RunState run() {
            return run;
        }

        /** The instance and its channel ("Kerning0 ch 2"): every channel's first KPQ is "Kerning0". */
        public String instanceName() {
            return keyOf(eim);
        }

        public List<Character> bots() {
            return List.copyOf(bots.values());
        }

        /** The event instance the run plays in (QA checks look at who is still inside after a run). */
        public EventInstanceManager instance() {
            return eim;
        }
    }

    /**
     * The runs, by the event instance itself (identity: {@link EventInstanceManager} defines no {@code equals}), so a
     * name can never make one instance stand in for another. 1.6.2 (b113) keyed this by {@link #keyOf}, the name with
     * world and channel; that still refused a real run (t-mx-1 on TEST 2026-10-31) because a CLEARED Kerning PQ frees
     * its lobby, hence its name "Kerning0", while the old run is still playing the prizes and bonus: the next instance
     * took the name and was told "this event instance already has a run" for an instance that was gone. Two instances
     * of one event may share a name (that is what freeing the lobby means) and are two different runs.
     */
    private final Map<EventInstanceManager, Active> active = new ConcurrentHashMap<>();

    /** A run's key: the instance name with its world and channel ("Kerning0 ch 2", "Kerning0 w1 ch 2"). */
    public static String keyOf(EventInstanceManager eim) {
        String name = eim.getName();
        try {
            scripting.event.EventManager em = eim.getEm();
            net.server.channel.Channel ch = em == null ? null : em.getChannelServer();
            if (ch != null) {
                return name + " w" + ch.getWorld() + " ch " + ch.getId();
            }
        } catch (RuntimeException e) {
            // a half-disposed instance: its name alone
        }
        return name;
    }
    /** Picks the bots that speak about a run and their lines ({@link RunChatter}). */
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
        return start(eim, content, perfect, budgetMs, 1, lastStage, false, reporter, onEnd);
    }

    /**
     * Ticket 170: every line the run reports also goes to the QA log's MECHANICS topic (the TEST QA event feed),
     * about the run's real players, whoever started it (a GM's !mechanics, a !qa check, a player's party at Lakelis).
     */
    static Consumer<String> toQaLog(List<RunMember> members, Consumer<String> reporter) {
        Consumer<String> out = reporter == null ? line -> { } : reporter;
        int[] players = members.stream().filter(m -> !m.bot()).mapToInt(RunMember::characterId).toArray();
        return line -> {
            out.accept(line);
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.MECHANICS)) {
                soloMapling.qa.QaLog.chars(soloMapling.qa.QaLog.Topic.MECHANICS, 0, players, line);
            }
        };
    }

    /**
     * As above. {@code ownsInstance}: staging or the bot lobby started this instance for the run (a GM's
     * {@code !mechanics kpq}, a bot leader at Lakelis), so a run that ends short of the clear, or cannot start,
     * ends the instance with it (everyone out, disposed); never left for its 30-minute timer. {@code firstStage}
     * (QA): the run begins there; the caller has staged the event's state for it ({@link KpqStaging#stageEventTo}).
     */
    public Active start(EventInstanceManager eim, Content content, boolean perfect, long budgetMs, int firstStage,
                        int lastStage, boolean ownsInstance, Consumer<String> reporter, Consumer<Active> onEnd) {
        return start(eim, content, Tier.NORMAL, perfect, budgetMs, firstStage, lastStage, ownsInstance, reporter, onEnd,
                RealRunPool.exempt(RealRunPool.Origin.STAGED));
    }

    /**
     * As above at a difficulty tier (1.6.4 "PQ difficulties"): the tier is the run's own ({@link RunState#tier()}), so
     * every rule lookup ({@link Content#rules(Tier)}), the {@code RUN start} line and anything the tier scales can read
     * it from the run instead of from a field each row would have to carry. {@link Tier#NORMAL} is the stock PQ and
     * the default of the shorter overload, so a caller that never heard of tiers starts exactly what it started before.
     */
    public Active start(EventInstanceManager eim, Content content, Tier tier, boolean perfect, long budgetMs,
                        int firstStage, int lastStage, boolean ownsInstance, Consumer<String> reporter,
                        Consumer<Active> onEnd) {
        return start(eim, content, tier, perfect, budgetMs, firstStage, lastStage, ownsInstance, reporter, onEnd,
                RealRunPool.exempt(RealRunPool.Origin.STAGED));
    }

    /**
     * As above, for a run admitted by {@link RealRunPool} ({@code admission}): the place it holds is given back when the
     * run ends however it ends, and when this call refuses or fails to start it (a run that ends at its start, a second
     * run on one instance, a throw) - a leaked place is a permanently smaller quota.
     */
    public Active start(EventInstanceManager eim, Content content, boolean perfect, long budgetMs, int firstStage,
                        int lastStage, boolean ownsInstance, Consumer<String> reporter, Consumer<Active> onEnd,
                        RealRunPool.Admission admission) {
        return start(eim, content, Tier.NORMAL, perfect, budgetMs, firstStage, lastStage, ownsInstance, reporter, onEnd,
                admission);
    }

    /**
     * The one every overload goes through: {@code tier} reaches the run, {@code admission} is the place it holds.
     */
    public Active start(EventInstanceManager eim, Content content, Tier tier, boolean perfect, long budgetMs,
                        int firstStage, int lastStage, boolean ownsInstance, Consumer<String> reporter,
                        Consumer<Active> onEnd, RealRunPool.Admission admission) {
        Active a = null;
        try {
            a = begin(eim, content, tier, perfect, budgetMs, firstStage, lastStage, ownsInstance, reporter, onEnd,
                    admission);
            if (!a.finished && !admission.origin().governed()) {
                // a human's or a GM's run took no lease, but that row had a real pass now: it is not the stalest
                RealRunPool.get().notePass(content.id(), a.run.tier());
            }
            return a;
        } finally {
            if (a == null || a.finished) {
                admission.release(); // refused, ended at its start, or threw: no run is left to give it back
            }
        }
    }

    private Active begin(EventInstanceManager eim, Content content, Tier tier, boolean perfect, long budgetMs,
                         int firstStage, int lastStage, boolean ownsInstance, Consumer<String> reporter,
                         Consumer<Active> onEnd, RealRunPool.Admission admission) {
        List<RunMember> members = new ArrayList<>();
        List<RunOutcome.Participant> participants = new ArrayList<>();
        Map<Integer, Character> bots = new ConcurrentHashMap<>();
        for (Character c : eim.getPlayers()) {
            boolean bot = drivable(c);
            boolean leader = c.getId() == eim.getLeaderId();
            members.add(new RunMember(c.getId(), leader, bot,
                    !bot || perfect ? MechanicsPersona.PERFECT : MechanicsPersona.forCharacter(c.getId()),
                    c.getJob() == null ? 0 : c.getJob().getId(), c.getLevel()));
            participants.add(new RunOutcome.Participant(c.getId(), c.isOwnedAlt() ? RunOutcome.ParticipantKind.OWNED_ALT
                    : bot ? RunOutcome.ParticipantKind.BOT : RunOutcome.ParticipantKind.PLAYER, leader));
            if (bot) {
                bots.put(c.getId(), c);
            }
        }
        Orchestrator o = budgetMs > 0 || lastStage > 0
                ? new Orchestrator(Orchestrator.allSolvers(), new Random(), budgetMs, lastStage) : orchestrator;
        RunState run = o.start(content, tier == null ? TierScaling.requested(content) : tier, members);
        if (firstStage > 1 && !o.startAt(run, firstStage, System.currentTimeMillis())) {
            run.end("stage " + firstStage + " is not one this run plays (last " + run.lastStage() + ")");
        }
        Active a = new Active(run, eim, bots, toQaLog(members, reporter), onEnd, ownsInstance, admission, participants);
        if (run.ended()) {
            a.finished = true;
            a.reporter.accept("RUN not started: " + run.endedBecause());
            closeInstance(a);
            recordOutcome(a); // a run that never started is still a run, and an abandoned one
            return a;
        }
        if (active.putIfAbsent(eim, a) != null) {
            run.end("this event instance already has a run");
            a.finished = true;
            a.reporter.accept("RUN not started: " + run.endedBecause());
            recordOutcome(a);
            return a;
        }
        try {
            holdAndSchedule(a, o, eim, content, run);
        } catch (RuntimeException | LinkageError e) {
            // K7 (bot audit): a throw after the holds (the scheduler refusing at shutdown) left the run registered,
            // its bots held for good and no tick to ever finish it
            log("[Mechanics] run " + keyOf(eim) + " could not start: " + e);
            o.abort(run, "could not start: " + e);
            finish(a);
        }
        return a;
    }

    /** The shared scheduler the run ticks on; tests put a refusing one here. */
    static java.util.function.Supplier<java.util.concurrent.ScheduledExecutorService> scheduler =
            ExecutorServiceManager::getScheduledExecutorService;

    private void holdAndSchedule(Active a, Orchestrator o, EventInstanceManager eim, Content content, RunState run) {
        Map<Integer, Character> bots = a.bots;
        for (Character bot : bots.values()) {
            MechanicsHold.hold(bot.getId());
            BotSM brain = CharacterStorage.getBotById(bot.getId());
            if (brain != null && brain.getRunning()) {
                BotTypeManager.manuallyStopBot(bot); // the run drives it now; woken again when the run ends
                a.pausedBrains.add(bot.getId());
            }
            GCMovement.stop(bot);
        }
        a.reporter.accept("RUN start " + content.id() + " stage " + run.currentStage() + " in " + keyOf(eim) + ": "
                + Orchestrator.describe(run));
        tellLeader(eim, content, bots.size());
        for (Character bot : bots.values()) {
            String full = fullBag(bot);
            if (full != null) {
                a.reporter.accept("RUN " + bot.getName() + " " + full);
            }
        }
        Orchestrator use = o;
        a.tick = scheduler.get().scheduleWithFixedDelay(
                () -> safeTick(a, use), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Round 9, item 6: a human in the room has no way to tell "the bots are running this fight" from "the bots are
     * standing there broken" - the owner's own bug report was "they just stand there". One line at run start, to the
     * party leader, behind the boss switch: who is driving and for which boss. Nothing per tick, nothing on a PQ run.
     */
    static void tellLeader(EventInstanceManager eim, Content content, int bots) {
        if (content.kind() != MechanicsModel.Kind.BOSS || !Mechanics.bossesEnabled() || bots <= 0) {
            return;
        }
        Character leader = eim.getPlayerById(eim.getLeaderId());
        if (leader == null) {
            return;
        }
        leader.dropMessage(6, "[Expedition] " + bots + (bots == 1 ? " bot is" : " bots are")
                + " running this fight with you (" + shortName(content.name()) + ").");
    }

    /** "Zakum (expedition)" -> "Zakum": a row's name without its qualifier, for a line a player reads. */
    static String shortName(String name) {
        int cut = name == null ? -1 : name.indexOf(" (");
        return cut > 0 ? name.substring(0, cut) : name;
    }

    private void safeTick(Active a, Orchestrator o) {
        if (a.finished) {
            return;
        }
        try {
            tick(a, o);
        } catch (Throwable t) {
            log("[Mechanics] run " + keyOf(a.eim) + " threw: " + t);
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
        List<Character> driven = new ArrayList<>(steps.size());
        for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
            Character bot = view.character(e.getKey());
            if (bot == null || bot.getMap() == null || bot.getHp() <= 0) {
                continue; // gone, between maps or dead: next tick
            }
            driven.add(bot);
            executor.execute(a.run, o, view, bot, e.getValue(), now, a.reporter);
        }
        keepPhysics(driven, GCMovement::holdFullPhysics);
        upkeep(driven, now);
        scaleRoom(a);
        if (!a.run.ended()) {
            get().releaseLostMembers(a);
        }
    }

    /**
     * 1.6.4: a bot the run has lost - it died, or was walked out - is not driven any more, so its brain goes back to it.
     * Without this the hold would leave a live, revived bot standing in the room for the rest of the instance, which is
     * exactly the "they just stand there" a player reports. The one exception is the member a paused run is waiting
     * for: that one stays held until it is back or the run ends, because the pause is what is keeping it in the run.
     */
    void releaseLostMembers(Active a) {
        RunState.Waiter waiter = a.run.waitingFor();
        int waitedFor = waiter == null || waiter.member() == null ? -1 : waiter.member().characterId();
        for (Integer id : new ArrayList<>(a.bots.keySet())) {
            if (id == waitedFor || a.run.member(id) != null) {
                continue;
            }
            Character bot = a.bots.remove(id);
            a.pausedBrains.remove(id);
            MechanicsHold.release(id);
            executor.forget(id);
            BotSM brain = CharacterStorage.getBotById(id);
            if (bot != null && brain != null && !brain.getRunning()) {
                BotTypeManager.manuallyStartBot(bot);
            }
            if (bot != null) {
                a.reporter.accept("RUN " + bot.getName() + " is out of the run: its own brain has it again");
            }
        }
    }

    /** 1.6.4 difficulties (item C): the tier's numbers reach the room, once per tick, behind their own switch. */
    private static void scaleRoom(Active a) {
        if (TierScaling.on()) {
            TierScaling.applyTo(a.run, a.eim);
        }
    }

    /*
     * R8 (bot audit, items 1/2): what a held bot still does for itself. The run stops the bot's brain
     * (BotTypeManager.manuallyStopBot), so the self-buff loop its own type runs (FollowerBot.supportParty ->
     * BotBuffDriver.selfBuff) stops with it and the bot fights the whole boss unbuffed - a human sees the buff visuals
     * lapse and never come back. Its potions are not in that boat: HP potions are drunk by the damage path
     * (BotVitals.hurt -> topUp, and BotContactDamage.tickMobDamage still runs for a held bot because
     * GCMovement.stop only cancels follow/travel) and MP potions by the cast cost (BotVitals.spend -> topUp), so
     * the only upkeep the run owes a bot is its buffs. Cheap: selfBuff holds its own timer and does nothing until one
     * is due. Behind the boss switch (and the run can only exist with mechanics on), like the rest of the lane.
     */
    static void upkeep(List<Character> bots, long nowMs) {
        if (!Mechanics.bossesEnabled()) {
            return;
        }
        for (Character bot : bots) {
            if (bot == null) {
                continue;
            }
            long next = nextBuffLook.getOrDefault(bot.getId(), 0L);
            nextBuffLook.put(bot.getId(), BotBuffDriver.selfBuff(bot, nowMs, next,
                    () -> BotHabits.of(bot.getId()).rebuffLateNowMs(), BotBuffDriver.Pay.PARTY_BUFFS, upkeepRng));
        }
    }

    /** Per bot, the next time its buff loop may look; the loop itself throttles, this only remembers across ticks. */
    private static final Map<Integer, Long> nextBuffLook = new ConcurrentHashMap<>();
    private static final Random upkeepRng = new Random();

    /*
     * 1.6.2 (b153): the maps a run's bots stand on keep full movement physics while the run drives them. With no real
     * player there (a bot-only lobby or staged run) the movement LOD went coarse, and the coarse planner lands a bot
     * on the floor under a goal it cannot plan to: on TEST (d4f103b0e) t-mx-1/t-mx-2's bots stood at the same few
     * points for minutes (STALL lines, 0-2 swings in 9-15 min) while a player-led run's bots farmed 5-7 coupons a
     * minute. Once per distinct map instance per tick; the hold lapses ~2 s after the run stops calling it.
     */
    static void keepPhysics(List<Character> bots, Consumer<server.maps.MapleMap> hold) {
        List<server.maps.MapleMap> done = new ArrayList<>(2);
        for (Character bot : bots) {
            server.maps.MapleMap map = bot == null ? null : bot.getMap();
            if (map != null && done.stream().noneMatch(m -> m == map)) {
                done.add(map);
                hold.accept(map);
            }
        }
    }

    /**
     * Who a run drives: population bots and the player's owned alts (owner rule: one PQ logic for both; an alt in its
     * owner's party plays the stages like any party bot). Real players are planned around, never driven.
     */
    static boolean drivable(Character c) {
        return c != null && (c.isBot() || c.isOwnedAlt());
    }

    /**
     * 1.6.2 (b153): a member whose etc tab has no room cannot pick up the stage's coupons or passes; the run says so
     * at its start (null when there is room).
     */
    static String fullBag(Character bot) {
        try {
            int free = bot.getInventory(client.inventory.InventoryType.ETC).getNumFreeSlot();
            return KpqLobby.bagRoom(free) ? null : "has " + free + " free etc slot(s): coupons and passes may not fit";
        } catch (RuntimeException e) {
            return null;
        }
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
    /**
     * An instance this server started for a party that holds bots or owned alts: the party quests (KPQ, at Lakelis) and
     * the real boss expeditions (Zakum today, Horntail later).
     *
     * <p>One entry point, because the event script names the content ({@code KerningPQ}, {@code ZakumBattle}) and the
     * data folder decides what it is: a {@code kind: PQ} row arms a run behind {@code -Dslums.mechanics}, a
     * {@code kind: BOSS} row behind {@code -Dslums.mechanics.boss}. {@code expedition} only says which caller this is,
     * so a boss expedition with no data can say so once instead of looking like a silent no-op.
     *
     * <p>The expedition call must come <em>after</em> {@code eim.startEvent()}: the members are warped by the script's
     * {@code playerEntry} during it, so before that the instance holds nobody to drive.
     *
     * <p>{@code GuildBossRuns} never reaches this: it is the roll with the bots logged out, not a real fight.
     *
     * @return the run, or null when nothing was armed
     */
    public static Active onInstanceStarted(EventInstanceManager eim, boolean expedition) {
        if (eim == null || STAGING.get()) {
            return null;
        }
        try {
            String event = eim.getEm() == null ? null : eim.getEm().getName();
            List<Content> rows = rowsFor(event);
            Content content = select(rows, eim.instanceMapIds());
            if (content == null) {
                if (rows.size() > 1) {
                    if (Mechanics.enabled() || Mechanics.bossesEnabled()) {
                        log("[Mechanics] " + keyOf(eim) + " (" + event + "): " + rows.size() + " rows claim this"
                                + " script and none of them is built on this instance's map " + eim.instanceMapIds()
                                + ", so no row is armed rather than the wrong one");
                    }
                } else if (expedition && Mechanics.bossesEnabled()) {
                    log("[Mechanics] no boss content for " + keyOf(eim) + " (" + event + "): the expedition is left to"
                            + " its humans");
                }
                return null;
            }
            // one spelling of "this row may arm": a BOSS row asks the boss switch plus its own flag, anything else asks
            // Mechanics.enabledFor - bossEnabledFor is that first pair, rowFlagOn is the flag half of it
            if (content.kind() == MechanicsModel.Kind.BOSS
                    ? !Mechanics.bossEnabledFor(content)
                    : !Mechanics.enabledFor(content)) {
                return null;
            }
            List<Character> inside = eim.getPlayers();
            // a real player in the run decides it: his run is always real and takes no place in the pool. A party with
            // nobody but bots in it (no production path reaches here: the lobbies stage through KpqStaging) is a bot-only
            // run like a lobby's and asks the pool; a run it refuses is simply not made
            boolean human = inside.stream().anyMatch(c -> !drivable(c));
            RealRunPool.Admission admission = human
                    ? RealRunPool.exempt(expedition ? RealRunPool.Origin.HUMAN_EXPEDITION : RealRunPool.Origin.HUMAN_PARTY)
                    : RealRunPool.get().admit(RealRunPool.Origin.BOT_INSTANCE, content, 0);
            if (!admission.granted()) {
                if (admission.announce()) {
                    log("[Mechanics] " + keyOf(eim) + " (" + event + "): no run: " + admission.why());
                }
                return null;
            }
            // neither a PQ nor a boss room is the run's to end: the party or the expedition script owns, and ends, it
            return get().start(eim, content, false, 0, 1, 0, false,
                    line -> log("[Mechanics] " + keyOf(eim) + " " + line), null, admission);
        } catch (RuntimeException e) {
            log("[Mechanics] could not start a run for " + keyOf(eim) + ": " + e);
            return null;
        }
    }

    /**
     * An instance the world queue just started ({@code EventManager.startInstance(lobby, chr, leader, difficulty)}, the
     * path {@code attemptStartGuildInstance} takes): when the content row says it is entered through the queue
     * ({@code queue:}) and its switch is on, a {@link LobbyWatch} arms the run once the join window closes. A staging
     * call that starts and runs the instance itself holds this off ({@link #staging}); every other instance this
     * server starts through that path - solo events, quests - has no queue row and is left exactly as it was.
     *
     * @return whether a watch was started
     */
    public static boolean onQueueInstanceStarted(EventInstanceManager eim) {
        if (eim == null || STAGING.get()) {
            return false;
        }
        try {
            Content content = contentFor(eim.getEm() == null ? null : eim.getEm().getName());
            if (content == null || content.queue() == null || !Mechanics.enabledFor(content)) {
                return false;
            }
            LobbyWatch.begin(eim, content);
            return true;
        } catch (RuntimeException e) {
            log("[Mechanics] could not watch a queue instance " + keyOf(eim) + ": " + e);
            return false;
        }
    }

    /**
     * The join window of a queue-started instance has closed: the run is armed for the bots and owned alts the guild
     * brought, exactly as {@link #onInstanceStarted} arms one for a party, except that the world owns the instance (the
     * script ends it, not the run). Nothing is armed for a guild of humans only.
     */
    static Active armOnClosedLobby(EventInstanceManager eim, Content content) {
        if (eim.getPlayers().stream().noneMatch(MechanicsRuns::drivable)) {
            log("[Mechanics] " + keyOf(eim) + ": no bot or alt joined the " + content.id() + " guild run, nothing to drive");
            return null;
        }
        return get().start(eim, content, false, 0, 1, 0, false,
                line -> log("[Mechanics] " + keyOf(eim) + " " + line), null);
    }

    private static volatile List<Content> contentCache;
    private static volatile String contentCacheDir;

    /** The stage data for an event script name ("KerningPQ"), or null. Loaded once per data folder. */
    public static Content contentFor(String eventScript) {
        return select(rowsFor(eventScript), List.of());
    }

    /**
     * The stage data for an event script name on an instance built on {@code instanceMaps}, or null.
     *
     * <p>One row per script is the rule and that row wins outright, whatever map the instance is on - every row in the
     * folder but GuardianNex's six. A script that builds one instance per venue carries no mark of the venue other than
     * the map it made ({@code GuardianNex.js:14-15} resets the cave the lobby names, and {@code playerEntry} warps to
     * it), so when several rows claim the name, the one whose stage is that map is the row. No evidence, or evidence
     * that fits none or two of them, is no row: {@link #onInstanceStarted} logs that once and arms nothing, because
     * running the first match there would drive a party through the wrong cave.
     */
    public static Content contentFor(String eventScript, Collection<Integer> instanceMaps) {
        return select(rowsFor(eventScript), instanceMaps);
    }

    /** Every row whose {@code eventScript} is this name, in folder order. */
    static List<Content> rowsFor(String eventScript) {
        List<Content> rows = new ArrayList<>();
        if (eventScript == null) {
            return rows;
        }
        for (Content c : allContent()) {
            if (eventScript.equalsIgnoreCase(c.eventScript())) {
                rows.add(c);
            }
        }
        return rows;
    }

    /** One row means that row; several mean the instance's own map decides; nothing conclusive means no row. */
    private static Content select(List<Content> rows, Collection<Integer> instanceMaps) {
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() == 1) {
            return rows.get(0);
        }
        Content chosen = null;
        for (Content c : rows) {
            if (c.stages().stream().noneMatch(stage -> instanceMaps.contains(stage.mapId()))) {
                continue;
            }
            if (chosen != null) {
                return null;
            }
            chosen = c;
        }
        return chosen;
    }

    /** Every content row the data folder holds, read once and re-read if the folder changes ({@code -Dslums.mechanics.dir}). */
    private static List<Content> allContent() {
        String dir = Mechanics.dataDir();
        List<Content> all = contentCache;
        if (all == null || !dir.equals(contentCacheDir)) {
            all = StageData.load().content();
            contentCacheDir = dir;
            contentCache = all;
        }
        return all;
    }

    /** The run on this instance, or null. */
    public Active runFor(EventInstanceManager eim) {
        return eim == null ? null : active.get(eim);
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
        SimulatedRuns.get().stopAll(why); // a stop ends the simulated runs too, recorded abandoned: nobody reports them
    }

    private synchronized void finish(Active a) {
        if (a.finished) {
            return;
        }
        a.finished = true;
        try {
            endRun(a);
        } finally {
            // the one place a finished run's place in RealRunPool is given back, whatever the end above did or threw
            a.admission.release();
        }
    }

    private void endRun(Active a) {
        ScheduledFuture<?> t = a.tick;
        if (t != null) {
            t.cancel(false);
        }
        active.remove(a.eim, a);
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
            log("[Mechanics] could not end " + keyOf(a.eim) + ": " + e);
        }
        recordOutcome(a);
        RunState r = a.run;
        a.reporter.accept("RUN end " + r.content().id() + " in " + keyOf(a.eim) + ": " + r.endedBecause()
                + (r.cleared() ? " after " + r.clearedAfterMs() / 1000 + " s, " + r.wrongShownTotal()
                + " wrong tries shown" : ""));
        if (a.onEnd != null) {
            try {
                a.onEnd.accept(a);
            } catch (RuntimeException e) {
                log("[Mechanics] cleanup after " + keyOf(a.eim) + " threw: " + e);
            }
        }
    }

    /**
     * Writes this run's outcome ({@link RunOutcome}) once, however the run ended. Everything that has something to say
     * about a run reads that record; the bots' win and loss lines are one of its readers ({@link
     * RunChatter#onOutcome}, registered with {@link RunOutcomes#listen}). A run that cannot be described still ends.
     */
    private void recordOutcome(Active a) {
        if (!a.recorded.compareAndSet(false, true)) {
            return;
        }
        try {
            RunOutcomes.record(RunOutcome.of(a.runId, keyOf(a.eim), a.run, a.admission.origin(), RunOutcome.Mode.REAL,
                    a.startedAtMs, System.currentTimeMillis(), a.participants));
        } catch (RuntimeException e) {
            log("[Mechanics] could not record the outcome of " + keyOf(a.eim) + ": " + e);
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
        if (!eim.isEventDisposed() && a.run.content().kind() == MechanicsModel.Kind.BOSS) {
            // a boss room belongs to its expedition script, not to this run: the chests are still to spawn or to loot
            // when the run ends, and the script ends the instance on its own clock. Releasing the bots is all we do.
            sendStrayAltsOut(a, eim);
            return;
        }
        boolean realInside = eim.getPlayers().stream().anyMatch(c -> !drivable(c));
        if (!shouldEndInstance(a.ownsInstance, a.run.cleared(), eim.isEventDisposed(), realInside)) {
            if (!a.run.cleared() && !eim.isEventDisposed()) {
                sendStrayAltsOut(a, eim);
            }
            return;
        }
        int out = endInstance(eim, a.run.content().exitMap());
        a.reporter.accept("RUN instance " + keyOf(eim) + " ended with the run: " + out + " member(s) sent out");
    }

    /**
     * b118 (1.6.2): after a run aborts in a PQ a real player stays in, an owned alt whose owner is no longer inside
     * is sent out (unregistered, to the exit map) instead of standing in the stage until its owner dismisses it. An
     * alt whose owner is still inside stays with him (its companion brain is awake again).
     */
    private void sendStrayAltsOut(Active a, EventInstanceManager eim) {
        List<Character> inside = eim.getPlayers();
        for (Character c : inside) {
            if (!strayAlt(c, inside)) {
                continue;
            }
            try {
                eim.unregisterPlayer(c);
                int exit = a.run.content().exitMap();
                if (exit > 0) {
                    c.changeMap(exit);
                }
                a.reporter.accept("RUN " + c.getName() + " (an alt whose owner left) sent out of " + keyOf(eim));
            } catch (RuntimeException e) {
                log("[Mechanics] could not send alt " + c.getName() + " out of " + keyOf(eim) + ": " + e);
            }
        }
    }

    /** An owned alt in the instance whose owner is not in it. */
    static boolean strayAlt(Character c, List<Character> inside) {
        if (c == null || !c.isOwnedAlt()) {
            return false;
        }
        Character owner = c.getOwnedAltOwner();
        return owner == null || inside.stream().noneMatch(o -> o.getId() == owner.getId());
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
            log("[Mechanics] " + keyOf(eim) + "'s script end failed: " + e);
        }
        if (!eim.isEventDisposed()) {
            for (Character c : eim.getPlayers()) {
                try {
                    eim.unregisterPlayer(c);
                    if (exitMap > 0) {
                        c.changeMap(exitMap);
                    }
                } catch (RuntimeException e) {
                    log("[Mechanics] could not send " + c.getName() + " out of " + keyOf(eim) + ": " + e);
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

    /** {@code !mechanics release}: every held run goes on (QA hold); returns how many were held. */
    public int release(String by) {
        int n = 0;
        for (Active a : List.copyOf(active.values())) {
            if (a.run.held()) {
                a.run.setHeld(false);
                a.reporter.accept("RUN released by " + by + " on stage " + a.run.currentStage());
                n++;
            }
        }
        return n;
    }

    /** One line per run for {@code !mechanics status}. */
    public List<String> status() {
        List<String> out = new ArrayList<>();
        for (Active a : active.values()) {
            RunState r = a.run;
            long secs = r.startedMs() == 0 ? 0 : (System.currentTimeMillis() - r.startedMs()) / 1000;
            out.add(r.content().id() + " stage " + r.currentStage() + (r.held() ? " (held)" : "") + " in "
                    + keyOf(a.eim) + ", " + secs + " s, "
                    + Orchestrator.describe(r) + ", wrong tries " + r.wrongShownTotal());
        }
        if (out.isEmpty()) {
            out.add("no mechanics runs (bots held: " + MechanicsHold.count() + ")");
        }
        String pool = RealRunPool.get().describe(); // null while the pool is unlimited: status reads as it always did
        if (pool != null) {
            out.add(pool);
        }
        out.addAll(SimulatedRuns.get().describe()); // empty unless the pool refused a run and it was simulated
        return out;
    }
}
