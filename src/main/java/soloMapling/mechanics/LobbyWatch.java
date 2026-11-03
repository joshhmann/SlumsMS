package soloMapling.mechanics;

import scripting.event.EventInstanceManager;
import soloMapling.mechanics.MechanicsModel.Content;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static soloMapling.BotLogger.log;

/**
 * Arms a run on an instance the world queue started, once its join window has closed.
 *
 * <p>Guild Quest starts empty: {@code EventManager.attemptStartGuildInstance} makes the instance with the guild's master
 * alone in it, and the rest of the guild talks to the door NPC over the next three minutes ({@code scripts/npc/9040000.js:
 * 54-65}), after which {@code scheduledTimeout} sets {@code canJoin} to 0 and starts the real clock
 * ({@code scripts/event/GuildQuest.js:203-222}). A run armed at the instance's start would see one member; one armed
 * when the window closes sees the party the guild actually brought, which is the party the script's own gates
 * ({@code isEventTeamLackingNow}) count from then on. The row's {@code queue:} says which property closes the window
 * and how long it takes ({@link MechanicsModel.Queue}).
 *
 * <p>Nothing here starts an instance, registers a member or touches the queue: it only watches an instance somebody
 * else started and, when it is ready, hands it to {@link MechanicsRuns} as any other started instance would be.
 */
final class LobbyWatch implements Runnable {

    /** How often the watch looks at the instance. */
    static final long POLL_MS = 5_000;
    /** How much longer than the join window it keeps looking before it gives up on the instance. */
    static final long GRACE_MS = 2 * 60_000L;

    enum Verdict { WAIT, ARM, GIVE_UP }

    private final EventInstanceManager eim;
    private final Content content;
    private final long startedMs;
    private volatile ScheduledFuture<?> task;

    private LobbyWatch(EventInstanceManager eim, Content content, long now) {
        this.eim = eim;
        this.content = content;
        this.startedMs = now;
    }

    /** Starts watching {@code eim} for {@code content}; the schedule is the run scheduler's. */
    static void begin(EventInstanceManager eim, Content content) {
        LobbyWatch watch = new LobbyWatch(eim, content, System.currentTimeMillis());
        watch.task = MechanicsRuns.scheduler.get().scheduleWithFixedDelay(watch, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        log("[Mechanics] " + MechanicsRuns.keyOf(eim) + " came from the " + content.id() + " queue: a run is armed when "
                + content.queue().closedProperty() + " = " + content.queue().closedEquals());
    }

    /** What to do with the instance: ended or too old, give up; window closed, arm; otherwise keep waiting. */
    static Verdict verdict(boolean disposed, boolean closed, long elapsedMs, long patienceMs) {
        if (disposed) {
            return Verdict.GIVE_UP;
        }
        if (closed) {
            return Verdict.ARM;
        }
        return elapsedMs > patienceMs ? Verdict.GIVE_UP : Verdict.WAIT;
    }

    @Override
    public void run() {
        Verdict verdict;
        try {
            long patience = content.queue().waitMinutes() * 60_000L + GRACE_MS;
            verdict = verdict(eim.isEventDisposed(), content.queue().closed(eim.getProperty(content.queue().closedProperty())),
                    System.currentTimeMillis() - startedMs, patience);
        } catch (RuntimeException e) {
            verdict = Verdict.GIVE_UP;
            log("[Mechanics] " + MechanicsRuns.keyOf(eim) + " lobby watch threw: " + e);
        }
        if (verdict == Verdict.WAIT) {
            return;
        }
        ScheduledFuture<?> t = task;
        if (t != null) {
            t.cancel(false);
        }
        if (verdict == Verdict.ARM) {
            MechanicsRuns.armOnClosedLobby(eim, content);
        } else {
            log("[Mechanics] " + MechanicsRuns.keyOf(eim) + " never closed its join window: no run");
        }
    }
}
