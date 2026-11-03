package soloMapling.mechanics;

import client.Character;
import soloMapling.ArtificialPlayer.BotLogout;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.RealRunPool.Origin;
import soloMapling.mechanics.RealRunPool.RunKey;
import soloMapling.mechanics.RunOutcome.Mode;
import soloMapling.mechanics.RunOutcome.Participant;
import soloMapling.mechanics.RunOutcome.ParticipantKind;
import soloMapling.mechanics.RunOutcome.Result;
import soloMapling.server.BotShutdown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static soloMapling.BotLogger.log;

/**
 * Ticket 164, phase 2 of {@code SIMULATED-RUNS-PLAN.md}: the run a full {@link RealRunPool} makes instead of no run.
 *
 * <p>A bot-only run the pool refused (the lobbies ask, {@link KpqLobby}, {@link ContentLobby}) is not made for real - no
 * event instance, no party, no spawned bot, no held bot, no mobs, no tick - but it is not simply dropped either: it is
 * <em>simulated</em>. Its result is drawn from the last real bot-only runs of the same content and tier
 * ({@link RunSamples}): win or lose with the share of wins they had, and the duration, stage and reason of one of them.
 * One scheduled task waits that duration and then writes the run's {@link RunOutcome} with {@link Mode#SIMULATED}, which
 * the bots' win and loss lines read like any other ({@link RunChatter#onOutcome}). That line, and the record, are the only
 * trace a simulated run leaves.
 *
 * <p><b>It waits rather than fakes.</b> A key with fewer real runs than {@link Mechanics#simulatedFloor()} is not simulated:
 * {@link Verdict#WAITING_FOR_SAMPLES}, and the lobby tries again next tick (when a place frees, rotation hands it to the
 * stalest content, so a cold content is the first to be played for real).
 *
 * <p><b>What it never does.</b> It never touches the world: no item, no mesos, no EXP, no quest point, no achievement,
 * no boss-log attempt or clear (the daily and weekly entry counters and the public boss leaderboard are a player's), no
 * social memory, no trend vote. It never has a human in it ({@link Verdict#NOT_ALLOWED}), is never promoted to a real run
 * and never demoted from one: it is simulated from its start to its end.
 *
 * <p><b>Its bots.</b> A simulated run <em>holds</em> nobody: no brain is stopped, no physics is kept. It only marks the
 * bots it names ({@link #busy}) so a lobby does not put them in a second run meanwhile, and names the bots that report
 * when it ends. A reporter that has logged out by then simply says nothing. {@link ContentLobby} rows have no bots standing
 * at their door, so the run borrows idle population bots of the row's level band ({@link #population}); none to borrow is
 * {@link Verdict#NO_PARTY} and the run waits.
 */
public final class SimulatedRuns {

    public enum Verdict {
        /** The run exists and will end with an outcome. */
        STARTED,
        /** The content has too few real runs to be sampled from. */
        WAITING_FOR_SAMPLES,
        /** The same content and tier already has a simulated run in flight. */
        ALREADY_RUNNING,
        /** {@code -Dslums.mechanics.simruns} simulated runs are in flight (or that switch is 0). */
        AT_CAPACITY,
        /** Nobody to report: no bot of the party is available. */
        NO_PARTY,
        /** A human is in it, or the origin is not a bot-only one: never simulated. */
        NOT_ALLOWED,
        /** The scheduler refused the completion. */
        COULD_NOT_SCHEDULE
    }

    /** What a {@link #tryStart} did, and why when it did not. */
    public record Attempt(Verdict verdict, String why, String runId) {
        public boolean started() {
            return verdict == Verdict.STARTED;
        }
    }

    private static final class Sim {
        final String runId = UUID.randomUUID().toString();
        final RunKey key;
        final Content content;
        final Origin origin;
        final List<Participant> participants;
        final RunSamples.Drawn drawn;
        final long startedAtMs;
        final AtomicBoolean ended = new AtomicBoolean();
        volatile ScheduledFuture<?> task;

        Sim(RunKey key, Content content, Origin origin, List<Participant> participants, RunSamples.Drawn drawn,
            long startedAtMs) {
            this.key = key;
            this.content = content;
            this.origin = origin;
            this.participants = participants;
            this.drawn = drawn;
            this.startedAtMs = startedAtMs;
        }
    }

    private static final SimulatedRuns INSTANCE = new SimulatedRuns(RunSamples.get(), Mechanics::simulatedRunCapacity,
            Mechanics::simulatedFloor, new Random(), System::currentTimeMillis);

    public static SimulatedRuns get() {
        return INSTANCE;
    }

    private final RunSamples samples;
    private final IntSupplier capacity;
    private final IntSupplier floor;
    private final Random rng;
    private final LongSupplier clock;
    private final Map<String, Sim> inFlight = new LinkedHashMap<>();
    private final Set<Integer> busy = new HashSet<>();
    private final Map<String, Long> lastTold = new LinkedHashMap<>();

    /** Tests build their own: the samples, the limits, the dice and the clock are theirs. */
    SimulatedRuns(RunSamples samples, IntSupplier capacity, IntSupplier floor, Random rng, LongSupplier clock) {
        this.samples = samples;
        this.capacity = capacity;
        this.floor = floor;
        this.rng = rng;
        this.clock = clock;
    }

    /**
     * Simulates a bot-only run the pool refused, or says why it cannot.
     *
     * @param who         the bots that report; asked only after every other check passed (picking them costs a scan)
     * @param refusedWhy  why the pool refused, for the log
     */
    public synchronized Attempt tryStart(Origin origin, Content content, Tier tier, Supplier<List<Participant>> who,
                                         String refusedWhy) {
        if (!origin.governed()) {
            return refuse(Verdict.NOT_ALLOWED, "a " + origin + " run is always real");
        }
        RunKey key = new RunKey(content.id(), tier);
        if (inFlight.containsKey(key.toString())) {
            return refuse(Verdict.ALREADY_RUNNING, key + " already has a simulated run in flight");
        }
        int cap = capacity.getAsInt();
        if (inFlight.size() >= cap) {
            return refuse(Verdict.AT_CAPACITY, cap == 0 ? "simulated runs are off (-D" + Mechanics.SIM_RUNS_FLAG + "=0)"
                    : inFlight.size() + " of " + cap + " simulated runs are in flight");
        }
        int need = floor.getAsInt();
        Optional<RunSamples.Drawn> drawn = samples.draw(key, need, rng);
        if (drawn.isEmpty()) {
            return refuse(Verdict.WAITING_FOR_SAMPLES, key + " has " + samples.count(key) + " of " + need
                    + " real runs to be sampled from: it waits, it is not faked");
        }
        List<Participant> party = who.get();
        if (party == null || party.isEmpty()) {
            return refuse(Verdict.NO_PARTY, "no idle bot to report " + key);
        }
        if (party.stream().anyMatch(p -> p.kind() == ParticipantKind.PLAYER)) {
            return refuse(Verdict.NOT_ALLOWED, "a run with a real player in it is never simulated");
        }
        long now = clock.getAsLong();
        Sim sim = new Sim(key, content, origin, List.copyOf(party), drawn.get(), now);
        long wait = Math.max(1_000L, sim.drawn.sample().durationMs());
        try {
            sim.task = MechanicsRuns.scheduler.get().schedule(() -> complete(sim), wait, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            return refuse(Verdict.COULD_NOT_SCHEDULE, "the scheduler refused: " + e);
        }
        inFlight.put(key.toString(), sim);
        for (Participant p : sim.participants) {
            busy.add(p.characterId());
        }
        log("[Mechanics] simulated run " + sim.runId + " of " + key + " (" + origin + "): " + party.size()
                + " bot(s) will report " + sim.drawn.sample().result() + " in " + wait / 1000 + " s, sampled from "
                + sim.drawn.basis().samples() + " real runs (pWin "
                + String.format(java.util.Locale.ROOT, "%.2f", sim.drawn.basis().pWin()) + "); the real-run pool refused: "
                + refusedWhy);
        return new Attempt(Verdict.STARTED, null, sim.runId);
    }

    private static Attempt refuse(Verdict verdict, String why) {
        return new Attempt(verdict, why, null);
    }

    /** The timer: the run's duration has passed, so its outcome is the sampled one. */
    private void complete(Sim sim) {
        // the server stopping: nobody is left to hear it, and a record of it would be a guess
        end(sim, !BotShutdown.isQuiesced());
    }

    private void end(Sim sim, boolean write) {
        if (!sim.ended.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            inFlight.remove(sim.key.toString(), sim);
            for (Participant p : sim.participants) {
                busy.remove(p.characterId());
            }
        }
        if (write) {
            RunSamples.Sample s = sim.drawn.sample();
            record(sim, s.result(), "simulated: " + s.reason(), s.stageReached(), s.stagesToPlay(), clock.getAsLong());
        }
    }

    private void record(Sim sim, Result result, String reason, int stage, int stagesToPlay, long endedAtMs) {
        try {
            RunOutcomes.record(new RunOutcome(sim.runId, "simulated " + sim.key, sim.content.id(), sim.content.name(),
                    sim.content.kind(), sim.key.tier(), result, reason, Mode.SIMULATED, sim.origin, sim.startedAtMs,
                    endedAtMs, stage, stagesToPlay, sim.participants, false, false, sim.drawn.basis()));
        } catch (RuntimeException e) {
            log("[Mechanics] could not record the simulated run " + sim.runId + ": " + e);
        }
    }

    /**
     * Ends every simulated run now ({@code !mechanics stop}, QA): each is recorded {@link Result#ABANDONED}, which no bot
     * says anything about, so a stop never makes a bot report a result nobody drew. Returns how many were stopped.
     */
    public int stopAll(String why) {
        List<Sim> all;
        synchronized (this) {
            all = new ArrayList<>(inFlight.values());
        }
        int n = 0;
        for (Sim sim : all) {
            ScheduledFuture<?> t = sim.task;
            if (t != null) {
                t.cancel(false);
            }
            if (sim.ended.compareAndSet(false, true)) {
                synchronized (this) {
                    inFlight.remove(sim.key.toString(), sim);
                    for (Participant p : sim.participants) {
                        busy.remove(p.characterId());
                    }
                }
                record(sim, Result.ABANDONED, why, 0, sim.drawn.sample().stagesToPlay(), clock.getAsLong());
                n++;
            }
        }
        return n;
    }

    /** Whether this bot is named by a simulated run in flight: a lobby must not put it in another run meanwhile. */
    public synchronized boolean busy(int botId) {
        return busy.contains(botId);
    }

    /** How many simulated runs are in flight. */
    public synchronized int inFlight() {
        return inFlight.size();
    }

    /** One line per simulated run for {@code !mechanics status}. */
    public synchronized List<String> describe() {
        List<String> out = new ArrayList<>();
        long now = clock.getAsLong();
        for (Sim sim : inFlight.values()) {
            out.add("simulated " + sim.key + " (" + sim.origin + "): " + sim.participants.size() + " bot(s), "
                    + sim.drawn.sample().result() + " in "
                    + Math.max(0, (sim.startedAtMs + Math.max(1_000L, sim.drawn.sample().durationMs()) - now) / 1000)
                    + " s, from " + sim.drawn.basis().samples() + " real runs");
        }
        return out;
    }

    /**
     * Whether a refusal to simulate is worth a line now: once per reason-and-key per {@link RealRunPool#LOG_GAP_MS}. The
     * lobbies ask every minute and the answer is usually the same.
     */
    public synchronized boolean tellDue(String key) {
        long now = clock.getAsLong();
        Long last = lastTold.get(key);
        if (last != null && now - last < RealRunPool.LOG_GAP_MS) {
            return false;
        }
        lastTold.put(key, now);
        return true;
    }

    // ---- the party of a run that has none -----------------------------------------------------------------------

    /** One idle population bot as the picker sees it. */
    record Candidate(int id, int level) {
    }

    /**
     * {@code count} candidates in random order, the first of them the leader; fewer when there are not that many. Seeded
     * dice give the same party every time. Never an owned alt, never a player: only what {@link #population} offers.
     */
    static List<Participant> pick(List<Candidate> idle, int count, Random rng) {
        List<Candidate> shuffled = new ArrayList<>(idle);
        shuffled.sort(java.util.Comparator.comparingInt(Candidate::id));
        Collections.shuffle(shuffled, rng);
        List<Participant> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, shuffled.size()); i++) {
            out.add(new Participant(shuffled.get(i).id(), ParticipantKind.BOT, i == 0));
        }
        return out;
    }

    /**
     * The population bots of {@code content}'s level band that are idle now: online, in the registry with a running brain, not
     * an owned alt, in no party and no event, alive, not logging out, not driven by a run and not named by another
     * simulated run. Borrowed only to report; nothing is done to them.
     */
    List<Participant> population(Content content) {
        List<Candidate> idle = new ArrayList<>();
        for (BotSM sm : List.copyOf(CharacterStorage.getAllBots().values())) {
            try {
                Character c = sm == null ? null : sm.getChr();
                if (c != null && idle(c, sm, content) && !busy(c.getId())) {
                    idle.add(new Candidate(c.getId(), c.getLevel()));
                }
            } catch (RuntimeException | LinkageError e) {
                // a bot half-way through logging in or out: not idle
            }
        }
        return pick(idle, content.minPlayers(), rng);
    }

    private static boolean idle(Character c, BotSM sm, Content content) {
        return c.isBot() && !c.isOwnedAlt() && c.getLevel() >= content.minLevel() && c.getLevel() <= content.maxLevel()
                && c.getParty() == null && c.getEventInstance() == null && c.getHp() > 0 && !MechanicsHold.committed(c)
                && sm.getRunning() && !BotLogout.isLeaving(c.getId());
    }

    /** The bots a lobby already picked, as a simulated run's party: the first leads. */
    static List<Participant> party(List<Integer> ids) {
        List<Participant> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            out.add(new Participant(ids.get(i), ParticipantKind.BOT, i == 0));
        }
        return out;
    }

    /** Test hook: cancel everything without a record and forget what was told. */
    synchronized void clearForTest() {
        for (Sim sim : inFlight.values()) {
            sim.ended.set(true);
            ScheduledFuture<?> t = sim.task;
            if (t != null) {
                t.cancel(false);
            }
        }
        inFlight.clear();
        busy.clear();
        lastTold.clear();
    }
}
