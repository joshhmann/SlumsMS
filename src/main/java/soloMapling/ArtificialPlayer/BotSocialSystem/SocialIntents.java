package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotEpisodes;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotSocialSystem.IntentBook.End;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ticket 271 stage 5: persisted intentions. A bot that agrees to meet, help, come along, party or hang out has made a
 * promise; this makes the promise a thing the server remembers and checks, instead of dialogue that meant nothing.
 *
 * <p>The model proposes nothing here. An intent is created only by server code that has already validated the
 * agreement (the bot-talk plan's yes, or a player's request that passed {@code ConvoGates}); the text of a line, a
 * {@code [do:x]} tag or a claim ({@code SayDo}) cannot create one. Each bot keeps at most {@link IntentBook#MAX_LIVE}
 * live intents inside its {@code LifeMemory} (key {@code "intents"}); nothing here reads a table.
 *
 * <p>Life of an intent:
 * <ol>
 *   <li>{@link #create}: validated (flag, a bot actor, a loaded memory, a real player target only if
 *       {@code SocialGate} and the opt-outs allow, room in the book, no duplicate for the same target and kind);</li>
 *   <li>within {@link #DUE_SOON_MS} of its due time the actor counts as committed ({@link #dueSoon}, read by
 *       {@code BotCommitments.inFlight}), so the population director does not log it out and
 *       {@code ModeSwitch} does not swap its mode;</li>
 *   <li>{@link #tick} (from the bot's own tick): at its due time, if the target is online and reachable, the actor is
 *       free and {@link SocialBudget} allows, the {@link Performer} carries it out; a success ends it KEPT;</li>
 *   <li>failing that, the tick retries every {@link #RETRY_MS} until the deadline; then the intent ends BROKEN (the
 *       promised-to bot's trust falls, it remembers, the actor feels anxious and embarrassed), or CANCELLED when the
 *       target has gone offline or opted out, or EXPIRED when the deadline passed more than {@link #STALE_MS} ago
 *       (the bot was away): no consequence, the world moved on.</li>
 * </ol>
 * Kept: the promised-to bot trusts a little more and feels it owes a little, the actor feels proud.
 *
 * <p>{@code -Dslums.intents=false} creates nothing and changes nothing (existing intents are not ticked or counted as
 * commitments). Time is an argument; callers pass {@link SocialSim#now()}.
 *
 * <p><b>The one integration point:</b> {@link #performer} starts as "not wired" (every try fails with reason
 * {@code no performer}); the integrator sets it to {@code SocialActions.perform}.
 */
public final class SocialIntents {

    private static final Logger log = LoggerFactory.getLogger(SocialIntents.class);

    public static final String SWITCH = "slums.intents";
    public static final int MAX_LIVE = IntentBook.MAX_LIVE;
    /** Within this of its due time an intent holds its actor (a commitment). */
    public static final long DUE_SOON_MS = TimeUnit.MINUTES.toMillis(2);
    /** A failed or refused try waits this long before the next (longer than {@link SocialBudget}'s default pair gap). */
    public static final long RETRY_MS = TimeUnit.SECONDS.toMillis(45);
    /** A deadline passed this long ago is not a broken promise any more, only a forgotten one. */
    public static final long STALE_MS = TimeUnit.MINUTES.toMillis(10);
    /** The default time from due to deadline. */
    public static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(3);
    /** No intent is due further out than this, and none lives longer than {@link #MAX_WINDOW_MS} after its due time. */
    public static final long MAX_HORIZON_MS = TimeUnit.HOURS.toMillis(2);
    public static final long MAX_WINDOW_MS = TimeUnit.HOURS.toMillis(1);

    private SocialIntents() {
    }

    /** On unless {@code -Dslums.intents=false}. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(SWITCH));
    }

    // ── the seams ──

    /** What a carried-out act came to. The default wiring maps {@code SocialActions.Outcome} onto this. */
    public record Result(boolean happened, String reason) {
    }

    /** Carries one act out now ({@code SocialActions.perform}); must never throw. */
    @FunctionalInterface
    public interface Performer {
        Result perform(int actorId, int targetId, SocialActKind kind, long now);
    }

    /** Where a character is: its map and channel. */
    public record Presence(int mapId, int channel) {
    }

    /** What the tick needs to know about the game, behind a seam for the tests. */
    public interface World {
        /** Where the character is, or null when it is offline or unknown. */
        Presence presence(int charId);

        /** Whether the actor can walk to the map. */
        boolean reachable(int actorId, int mapId);

        /** Whether the actor is free of every other commitment (the brain's free-phase check and the log-out gate). */
        boolean free(int actorId, long now);

        /** Whether the actor may aim this act at the target: always for a bot, {@code SocialGate} and opt-outs for a player. */
        boolean mayAim(int actorId, int targetId, SocialActKind kind);

        /** A display name for the character, or null. */
        String name(int charId);
    }

    /** What keeping or breaking a promise does to the characters involved. */
    public interface Consequences {
        void kept(SocialIntent intent, long now);

        void broken(SocialIntent intent, long now);
    }

    private static final Performer NOT_WIRED = (actor, target, kind, now) -> new Result(false, "no performer");

    /** The one integration point: the integrator sets this to {@code SocialActions.perform} (mapped to a {@link Result}). */
    public static volatile Performer performer = NOT_WIRED;
    private static final java.util.function.IntFunction<Character> WORLD_STORAGE = id -> {
        net.server.world.World w = soloMapling.server.SoloMaplingUtilities.world();
        return w == null ? null : w.getPlayerStorage().getCharacterById(id);
    };
    static volatile World world = new GameWorld();
    /** Where {@link GameWorld} finds an online character (bot or player) by id; a test swaps it. */
    static volatile java.util.function.IntFunction<Character> characters = WORLD_STORAGE;
    static volatile Consequences consequences = new GameConsequences();

    // ── counters ──

    private static final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

    private static void count(String metric, SocialActKind kind) {
        counts.computeIfAbsent(metric, k -> new LongAdder()).increment();
        if (kind != null) {
            counts.computeIfAbsent(metric + " " + kind.name(), k -> new LongAdder()).increment();
        }
    }

    /**
     * For the dashboard and the QA log: {@code created}, {@code kept}, {@code broken}, {@code expired},
     * {@code cancelled} (always present), {@code refused} (creations turned down), {@code deferred} (due tries that had to
     * wait), and each of those by kind as {@code kept INVITE}; a kind with none is left out.
     */
    public static Map<String, Long> counters() {
        Map<String, Long> out = new TreeMap<>();
        for (String k : List.of("created", "kept", "broken", "expired", "cancelled", "refused", "deferred")) {
            out.put(k, 0L);
        }
        counts.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    // ── reading (memory only) ──

    private static LifeMemory memory(int actorId) {
        return LifeMemoryStore.peek(actorId);
    }

    /** The actor's live intents, earliest due first; empty when it has none or its memory is not loaded. */
    public static List<SocialIntent> live(int actorId) {
        LifeMemory m = memory(actorId);
        return m == null ? List.of() : m.intents(IntentBook::live);
    }

    /** The live intents whose due time has come (some may be past their deadline). */
    public static List<SocialIntent> due(int actorId, long now) {
        LifeMemory m = memory(actorId);
        return m == null ? List.of() : m.intents(b -> b.due(now));
    }

    private static final ThreadLocal<Integer> EXEMPT = new ThreadLocal<>();

    /**
     * Whether the actor has an intent due within {@link #DUE_SOON_MS} (or due already and not past its deadline): it
     * counts as committed. Memory only and cheap (one map lookup, at most three rows). False with the flag off, with no
     * memory loaded, and for the actor's own free-check while its intent is being carried out ({@link GameWorld#free}).
     */
    public static boolean dueSoon(int actorId, long now) {
        if (!enabled() || actorId <= 0) {
            return false;
        }
        Integer exempt = EXEMPT.get();
        if (exempt != null && exempt == actorId) {
            return false;
        }
        LifeMemory m = memory(actorId);
        return m != null && m.intents(b -> !b.isEmpty() && b.dueSoon(now, DUE_SOON_MS));
    }

    /**
     * Runs {@code body} with this actor's own intents left out of {@link #dueSoon} on this thread: the check "is the
     * actor free to carry its promise out" must not be failed by the very promise it is about.
     */
    public static <T> T ignoringOwnIntents(int actorId, java.util.function.Supplier<T> body) {
        Integer outer = EXEMPT.get();
        EXEMPT.set(actorId);
        try {
            return body.get();
        } finally {
            if (outer == null) {
                EXEMPT.remove();
            } else {
                EXEMPT.set(outer);
            }
        }
    }

    /** {@link #dueSoon(int, long)} at {@link SocialSim#now()}. */
    public static boolean dueSoon(int actorId) {
        return dueSoon(actorId, SocialSim.now());
    }

    // ── creating ──

    /** What {@link #create} did: the intent (new or the one already live), whether it is new, and why not. */
    public record Created(SocialIntent intent, boolean fresh, String reason) {
        public boolean ok() {
            return intent != null;
        }
    }

    private static Created refuse(String reason, SocialActKind kind) {
        count("refused", kind);
        count("refused: " + reason, null);
        return new Created(null, false, reason);
    }

    /** {@link #create(int, int, SocialActKind, long, long, int, String, long)} due in {@code delayMs}, {@link #WINDOW_MS} to keep it. */
    public static Created promise(int actorId, int targetId, SocialActKind kind, long delayMs, int mapId, String origin,
                                  long now) {
        return create(actorId, targetId, kind, now + delayMs, now + delayMs + WINDOW_MS, mapId, origin, now);
    }

    /**
     * Records that {@code actorId} (a bot) promised {@code targetId} to do {@code kind} at {@code dueAt}, and must have
     * by {@code expiresAt}. Server code calls this only after it validated the agreement. Refused (counted under
     * {@code refused}, nothing stored) with the flag off, a non-bot actor, no loaded memory, the same character twice, a
     * real-player target that {@code SocialGate} or an opt-out turns away, or a full book. A live intent for the same
     * target and kind is returned as it is (not new). Times are clamped: due no earlier than now and no later than
     * {@link #MAX_HORIZON_MS}, a deadline after the due time and within {@link #MAX_WINDOW_MS} of it.
     */
    public static Created create(int actorId, int targetId, SocialActKind kind, long dueAt, long expiresAt, int mapId,
                                 String origin, long now) {
        if (!enabled()) {
            return new Created(null, false, "off");
        }
        if (kind == null || actorId <= 0 || targetId <= 0 || actorId == targetId) {
            return refuse("bad request", kind);
        }
        if (!BotHelpers.isArtificial(actorId)) {
            return refuse("actor is not a bot", kind);
        }
        LifeMemory m = memory(actorId);
        if (m == null) {
            return refuse("no memory loaded", kind);
        }
        long due = Math.max(now, Math.min(dueAt, now + MAX_HORIZON_MS));
        long deadline = expiresAt <= due ? due + WINDOW_MS : Math.min(expiresAt, due + MAX_WINDOW_MS);
        if (!BotHelpers.isArtificial(targetId) && !safeAim(actorId, targetId, kind)) {
            return refuse("opted out or gated", kind);
        }
        Created[] result = new Created[1];
        m.intents(book -> {
            SocialIntent known = book.find(targetId, kind);
            if (known != null) {
                result[0] = new Created(known, false, "already live");
            } else {
                SocialIntent made = book.add(targetId, kind, now, due, deadline, mapId, origin);
                result[0] = made == null ? new Created(null, false, "book full") : new Created(made, true, "created");
            }
            return null;
        });
        Created r = result[0];
        if (r.fresh()) {
            count("created", kind);
        } else if (!r.ok()) {
            return refuse(r.reason(), kind);
        }
        return r;
    }

    private static boolean safeAim(int actorId, int targetId, SocialActKind kind) {
        try {
            return world.mayAim(actorId, targetId, kind);
        } catch (RuntimeException | LinkageError e) {
            return false; // a gate that cannot answer is a no
        }
    }

    // ── ending ──

    /**
     * Ends the intent as carried out: the promised-to bot trusts a little more, the actor is proud. False when it was
     * not live (already ended by someone else).
     */
    public static boolean complete(int actorId, long intentId, long now) {
        SocialIntent in = endIntent(actorId, intentId, End.KEPT, now);
        if (in == null) {
            return false;
        }
        retryAt.remove(intentId);
        count("kept", in.kind());
        SocialPilot.onIntent(in.actor(), true);
        safe(() -> consequences.kept(in, now));
        return true;
    }

    /** The live intent for this target and kind was carried out by the flow that was already there: {@link #complete} it. */
    public static boolean fulfil(int actorId, int targetId, SocialActKind kind, long now) {
        if (!enabled()) {
            return false;
        }
        LifeMemory m = memory(actorId);
        if (m == null) {
            return false;
        }
        SocialIntent in = m.intents(b -> b.find(targetId, kind));
        return in != null && complete(actorId, in.id(), now);
    }

    /** Ends the intent with no consequence ({@code why} is for the log). False when it was not live. */
    public static boolean cancel(int actorId, long intentId, String why, long now) {
        SocialIntent in = endIntent(actorId, intentId, End.CANCELLED, now);
        if (in == null) {
            return false;
        }
        retryAt.remove(intentId);
        count("cancelled", in.kind());
        log.debug("Intent {} of bot {} cancelled: {}", intentId, actorId, why);
        return true;
    }

    private static SocialIntent endIntent(int actorId, long intentId, End how, long now) {
        LifeMemory m = memory(actorId);
        return m == null ? null : m.intents(b -> b.end(intentId, how, now));
    }

    // ── the tick ──

    /** What the tick did with one intent: {@code what} is kept, broken, expired, cancelled or deferred. */
    public record Step(long intentId, SocialActKind kind, String what, String reason) {
    }

    private static final Map<Long, Long> retryAt = new ConcurrentHashMap<>();

    /**
     * From the bot's own tick: deals with the intents whose time has come. Past their deadline they end (broken,
     * cancelled or expired, see the class doc); of the others at most one is tried per call, the earliest due. Cheap
     * and memory only when nothing is due (one map lookup and a short list). Never throws.
     */
    public static List<Step> tick(int actorId, long now) {
        try {
            return tickChecked(actorId, now);
        } catch (RuntimeException | LinkageError e) {
            log.debug("Intent tick failed for bot {}", actorId, e);
            return List.of();
        }
    }

    /** {@link #tick} for the bot's own character at {@link SocialSim#now()}; a bot with no character does nothing. */
    public static void onTick(Character chr) {
        if (chr != null) {
            tick(chr.getId(), SocialSim.now());
        }
    }

    private static List<Step> tickChecked(int actorId, long now) {
        if (!enabled()) {
            return List.of();
        }
        LifeMemory m = memory(actorId);
        if (m == null) {
            return List.of();
        }
        List<SocialIntent> due = m.intents(b -> b.isEmpty() ? List.of() : b.due(now));
        if (due.isEmpty()) {
            return List.of();
        }
        List<Step> steps = new ArrayList<>(due.size());
        boolean tried = false;
        for (SocialIntent in : due) {
            if (in.expiredBy(now)) {
                steps.add(expireOne(in, now));
            } else if (!tried) {
                Step step = attempt(in, now);
                steps.add(step);
                tried = !"deferred".equals(step.what()) || step.reason().startsWith("not done");
            }
        }
        return steps;
    }

    /** Ends the intents of this actor that are past their deadline now (what {@link #tick} does first); the steps taken. */
    public static List<Step> expire(int actorId, long now) {
        List<Step> steps = new ArrayList<>();
        for (SocialIntent in : due(actorId, now)) {
            if (in.expiredBy(now)) {
                steps.add(expireOne(in, now));
            }
        }
        return steps;
    }

    private static Step expireOne(SocialIntent in, long now) {
        if (now - in.expiresAt() > STALE_MS) {
            if (endIntent(in.actor(), in.id(), End.EXPIRED, now) == null) {
                return new Step(in.id(), in.kind(), "deferred", "already ended");
            }
            retryAt.remove(in.id());
            count("expired", in.kind());
            return new Step(in.id(), in.kind(), "expired", "deadline long past");
        }
        if (safePresence(in.target()) == null) {
            cancel(in.actor(), in.id(), "target offline at the deadline", now);
            return new Step(in.id(), in.kind(), "cancelled", "target offline");
        }
        SocialIntent ended = endIntent(in.actor(), in.id(), End.BROKEN, now);
        if (ended == null) {
            return new Step(in.id(), in.kind(), "deferred", "already ended");
        }
        retryAt.remove(in.id());
        count("broken", in.kind());
        SocialPilot.onIntent(in.actor(), false);
        safe(() -> consequences.broken(ended, now));
        return new Step(in.id(), in.kind(), "broken", "deadline passed");
    }

    private static Step attempt(SocialIntent in, long now) {
        Long retry = retryAt.get(in.id());
        if (retry != null && now < retry) {
            return deferred(in, "retry gap");
        }
        World w = world;
        Presence me = safePresence(in.actor());
        Presence them = safePresence(in.target());
        if (me == null) {
            return deferred(in, "actor offline");
        }
        if (them == null) {
            return deferred(in, "target offline");
        }
        if (me.mapId() != them.mapId() && !reach(w, in.actor(), them.mapId())) {
            return deferred(in, "not reachable");
        }
        if (!free(w, in.actor(), now)) {
            return deferred(in, "busy");
        }
        boolean botTarget = BotHelpers.isArtificial(in.target());
        if (!botTarget && !safeAim(in.actor(), in.target(), in.kind())) {
            cancel(in.actor(), in.id(), "opted out or gated", now);
            return new Step(in.id(), in.kind(), "cancelled", "opted out or gated");
        }
        String act = "intent:" + in.kind().name();
        long mapKey = ((long) me.channel() << 32) | (me.mapId() & 0xffffffffL);
        if (botTarget) {
            SocialBudget.Verdict v = SocialBudget.allow(in.actor(), in.target(), mapKey, act, now);
            if (!v.allowed()) {
                retryAt.put(in.id(), Math.max(now + RETRY_MS, v.retryAtMs()));
                return deferred(in, "budget " + v.reason().label());
            }
        }
        Result r;
        try {
            r = performer.perform(in.actor(), in.target(), in.kind(), now);
        } catch (RuntimeException | LinkageError e) {
            r = new Result(false, "performer threw");
        }
        if (r == null) {
            r = new Result(false, "no result");
        }
        if (botTarget) {
            SocialBudget.record(new SocialBudget.Outcome(in.actor(), in.target(), mapKey, act, now, r.happened()));
        }
        if (r.happened()) {
            complete(in.actor(), in.id(), now);
            return new Step(in.id(), in.kind(), "kept", r.reason() == null ? "done" : r.reason());
        }
        retryAt.put(in.id(), now + RETRY_MS);
        if (retryAt.size() > 4_096) {
            retryAt.clear();
        }
        return deferred(in, "not done: " + (r.reason() == null ? "?" : r.reason()));
    }

    private static Step deferred(SocialIntent in, String why) {
        count("deferred", in.kind());
        return new Step(in.id(), in.kind(), "deferred", why);
    }

    private static Presence safePresence(int charId) {
        try {
            return world.presence(charId);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean reach(World w, int actorId, int mapId) {
        try {
            return w.reachable(actorId, mapId);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean free(World w, int actorId, long now) {
        try {
            return w.free(actorId, now);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | LinkageError e) {
            log.debug("Intent consequence failed", e);
        }
    }

    // ── for !botwhy ──

    /**
     * One line for {@code !botwhy}: the live intents (kind, target, when it is due or how late, origin) and how the last
     * ones ended. Memory only; never empty.
     */
    public static String whyLine(int actorId, long now) {
        if (!enabled()) {
            return "Intents: off (-Dslums.intents=false)";
        }
        LifeMemory m = memory(actorId);
        if (m == null) {
            return "Intents: not loaded (no social state in memory for this bot yet)";
        }
        List<SocialIntent> live = m.intents(IntentBook::live);
        List<IntentBook.Done> recent = m.intents(IntentBook::recent);
        StringBuilder b = new StringBuilder("Intents: ");
        if (live.isEmpty()) {
            b.append("none live");
        } else {
            b.append(live.size()).append(" live: ");
            for (int i = 0; i < live.size(); i++) {
                SocialIntent in = live.get(i);
                if (i > 0) {
                    b.append("; ");
                }
                String who = nameOf(in.target());
                b.append(in.kind().name()).append(" -> ").append(who == null ? "#" + in.target() : who);
                long toDue = in.dueAt() - now;
                b.append(toDue > 0 ? " due in " + secs(toDue) : " due " + secs(-toDue) + " ago");
                b.append(", deadline in ").append(secs(in.expiresAt() - now));
                if (!in.origin().isEmpty()) {
                    b.append(" (").append(in.origin()).append(')');
                }
            }
        }
        if (!recent.isEmpty()) {
            b.append(" | last: ");
            for (int i = 0; i < Math.min(3, recent.size()); i++) {
                IntentBook.Done d = recent.get(i);
                if (i > 0) {
                    b.append(", ");
                }
                b.append(d.end().label()).append(' ').append(d.kind().name().toLowerCase(Locale.ROOT));
            }
        }
        return b.toString();
    }

    private static String nameOf(int charId) {
        try {
            return world.name(charId);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String secs(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 120 ? (s / 60) + " min" : s + " s";
    }

    // ── the game behind the seams ──

    /** The real game: characters from the world's storage, the brain's free check, {@code SocialGate} for players. */
    static final class GameWorld implements World {

        private static Character lookup(int id) {
            return characters.apply(id);
        }

        @Override
        public Presence presence(int charId) {
            Character c = lookup(charId);
            if (c == null || c.getMap() == null) {
                return null;
            }
            return new Presence(c.getMapId(), c.getMap().getChannelId());
        }

        @Override
        public boolean reachable(int actorId, int mapId) {
            Character c = lookup(actorId);
            return c != null && soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.walkable(c, mapId);
        }

        @Override
        public boolean free(int actorId, long now) {
            var sm = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(actorId);
            if (sm == null || sm.getChr() == null || sm.getChr().getMap() == null) {
                return false;
            }
            return ignoringOwnIntents(actorId, () -> { // its own intent is not another commitment
                if (!soloMapling.ArtificialPlayer.BotPopulationDirector.mayLogOut(sm)
                        || soloMapling.ArtificialPlayer.BotConversation.hasAnyChat(actorId)) {
                    return false;
                }
                var runtime = client.command.commands.gm3.BotWhyCommand.runtimeOf(sm);
                return runtime == null || !soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled()
                        || runtime.commitBlocker(now) == null;
            });
        }

        @Override
        public boolean mayAim(int actorId, int targetId, SocialActKind kind) {
            if (BotHelpers.isArtificial(targetId)) {
                return true;
            }
            Character bot = lookup(actorId);
            Character player = lookup(targetId);
            if (bot == null || player == null || BotAffinity.optedOut(targetId)) {
                return false;
            }
            return soloMapling.ArtificialPlayer.SocialGate.check(bot, player, actOf(kind)).ok();
        }

        private static soloMapling.ArtificialPlayer.SocialGate.Act actOf(SocialActKind kind) {
            return switch (kind) {
                case OFFER_HELP, ASK_HELP -> soloMapling.ArtificialPlayer.SocialGate.Act.HELP;
                case INVITE -> soloMapling.ArtificialPlayer.SocialGate.Act.PARTY_INVITE;
                case GIVE_GIFT -> soloMapling.ArtificialPlayer.SocialGate.Act.GIFT;
                case GREET -> soloMapling.ArtificialPlayer.SocialGate.Act.GREET;
                default -> soloMapling.ArtificialPlayer.SocialGate.Act.FOLLOW_UP;
            };
        }

        @Override
        public String name(int charId) {
            Character c = lookup(charId);
            return c == null ? null : c.getName();
        }
    }

    /** The real consequences: bond axes and a memory note for the promised-to bot, the actor's feelings. */
    static final class GameConsequences implements Consequences {

        @Override
        public void kept(SocialIntent in, long now) {
            if (BotHelpers.isArtificial(in.target())) {
                BotBonds.promise(in.target(), in.actor(), true, now);
            }
            LifeMemory m = memory(in.actor());
            if (m != null) {
                m.mind(mind -> {
                    mind.feel(SocialMind.Emotion.PRIDE, 0.12, now);
                    mind.feel(SocialMind.Emotion.HAPPINESS, 0.05, now);
                    return null;
                });
            }
        }

        @Override
        public void broken(SocialIntent in, long now) {
            if (BotHelpers.isArtificial(in.target())) {
                BotBonds.promise(in.target(), in.actor(), false, now);
                BotEpisodes.botEvent(in.target(), in.actor(), BotMemory.Kind.BROKEN,
                        "said they would " + phrase(in.kind()) + " but never did", -0.6, in.mapId(), now);
            }
            LifeMemory m = memory(in.actor());
            if (m != null) {
                m.mind(mind -> {
                    mind.feel(SocialMind.Emotion.ANXIETY, 0.15, now);
                    mind.feel(SocialMind.Emotion.EMBARRASSMENT, 0.2, now);
                    return null;
                });
            }
        }

        static String phrase(SocialActKind kind) {
            return switch (kind) {
                case INVITE -> "party up";
                case OFFER_HELP -> "help out";
                case SPEND_TIME -> "hang out";
                case GIVE_GIFT -> "bring a gift";
                case APOLOGIZE -> "apologize";
                default -> kind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            };
        }
    }

    // ── test seams ──

    /** Test seam: the characters the real {@link GameWorld} sees online (null: the world's storage). */
    public static void useCharactersForTest(java.util.function.IntFunction<Character> testCharacters) {
        characters = testCharacters != null ? testCharacters : WORLD_STORAGE;
    }

    /** Test seam: the game, the consequences and the performer (null: the real ones / not wired). Pair with {@link #resetForTest}. */
    public static void useForTest(World testWorld, Consequences testConsequences, Performer testPerformer) {
        world = testWorld == null ? new GameWorld() : testWorld;
        consequences = testConsequences == null ? new GameConsequences() : testConsequences;
        performer = testPerformer == null ? NOT_WIRED : testPerformer;
    }

    /** Forget every counter and retry time, and put the real game and the not-wired performer back. */
    public static void resetForTest() {
        characters = WORLD_STORAGE;
        counts.clear();
        retryAt.clear();
        EXEMPT.remove();
        world = new GameWorld();
        consequences = new GameConsequences();
        performer = NOT_WIRED;
    }
}
