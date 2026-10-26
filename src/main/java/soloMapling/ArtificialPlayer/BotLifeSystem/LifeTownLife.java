package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotCommitments;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.Map;
import java.util.Optional;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/**
 * 1.6.0 S8 (brain reach: all bots): the brain for a town or social bot, a "light host". It runs the same
 * {@link LifeRuntime} (needs, Bartle, goals and plans, CHANGE_CHANNEL) as a training bot, starting from different
 * needs ({@link #lifeNeeds}) and with the activities its type can really do:
 * <ul>
 *   <li>HANG_OUT / SHOW_OFF: stay where it is (its type keeps loitering, chatting, emoting);</li>
 *   <li>MOVE_MAP: the type's own hop to another room of its town ({@link Moves#moveMap}), when it has one;</li>
 *   <li>BREAK: sit down on a chair a while ({@link Moves#sit}), when it can;</li>
 *   <li>CHANGE_CHANNEL: "cc" to the same map on a quieter channel ({@link LifeChannelMove});</li>
 *   <li>LOG_OUT: end the session through the population director once its session time is up.</li>
 * </ul>
 * Grinding, PQs and helping stay with the training bots (the next session can be one: the director draws each
 * session's type from the bot's personality). Only roster members get it; town dressing and set pieces never do.
 * Every activity is a switch, not a trip: the request ends as soon as it starts.
 */
public final class LifeTownLife implements LifeHost {

    /** What the bot type can do for the brain. */
    public interface Moves {
        /** Whether a hop to another room of its town is possible now. */
        default boolean canMoveMap() { return false; }

        /** Starts that hop (the type's own travel); true when it started. */
        default boolean moveMap() { return false; }

        /** Whether it can sit down now. */
        default boolean canSit() { return false; }

        /** Sits down; true when it did. */
        default boolean sit() { return false; }

        /** Whether the bot is free for something of its own (no chatter, scene or conversation). */
        default boolean available() { return true; }
    }

    public enum Kind { SOCIAL, TOWN }

    private final BotSM bot;
    private final Kind kind;
    private final Moves moves;
    private final LifeActivityQueue queue = new LifeActivityQueue();
    private final LifeRuntime runtime;
    private volatile Activity stay = Activity.HANG_OUT;
    private volatile long stayStarted, stayUntil;
    private boolean started;

    private LifeTownLife(BotSM bot, Kind kind, Moves moves) {
        this.bot = bot;
        this.kind = kind;
        this.moves = moves;
        this.runtime = new LifeRuntime(bot, this);
        queue.onEnd(runtime::ended);
    }

    /** The brain for this bot, or null when it shouldn't have one (brain off or training-only, not a roster member). */
    public static LifeTownLife forBot(BotSM bot, Kind kind, Moves moves) {
        if (!LifeRuntime.reachesAllBots() || bot == null || bot.getChr() == null) return null;
        int slot = bot.getChr().getId() - BotStore.BOT_ID_BASE;
        if (slot < 0 || BotRoster.loadedMember(slot) == null) return null;
        return new LifeTownLife(bot, kind, moves);
    }

    /** Test seam: a light brain without the roster check. */
    static LifeTownLife forTest(BotSM bot, Kind kind, Moves moves) {
        return new LifeTownLife(bot, kind, moves);
    }

    public Kind kind() { return kind; }

    /** One brain step from the bot's own update: decide (30 s cadence, signature skip), then start what it chose. */
    public void tick(long now) {
        if (!LifeRuntime.enabled() || !bot.getRunning() || bot.getChr() == null || bot.getChr().getMap() == null) return;
        if (!started) {
            started = true;
            stayStarted = now;
            runtime.phase("LIFE_DWELL", now);
        }
        runtime.tick(now);
        if (!queue.hasWaiting()) return;
        var run = queue.drain(now, TrainingBotGate.executionEnabled(), () -> freeForAutomaticLifeActivity(now),
                new LifeAcceptance.Executor() {
                    public boolean supports(Activity activity) { return canStartLifeActivity(activity, now); }
                    public Optional<LifeAcceptance.Accepted> start(Activity activity) {
                        return startNow(activity, now) ? Optional.of(new LifeAcceptance.Accepted(activity,
                                "started " + activity.name().toLowerCase(java.util.Locale.ROOT))) : Optional.empty();
                    }
                });
        if (run.attempt().isPresent() && run.attempt().get().accepted().isPresent()) {
            Activity activity = run.attempt().get().accepted().get().activity();
            runtime.accepted(activity, now, false);
            queue.finish(run.number(), LifeActivityQueue.End.COMPLETED); // a switch, not a trip
            runtime.phase("LIFE_DWELL", now); // a fresh stay starts after any switch
            note("START activity=" + activity);
        }
    }

    private boolean startNow(Activity activity, long now) {
        Character chr = bot.getChr();
        switch (activity) {
            case HANG_OUT, SHOW_OFF -> {
                stay = activity;
                stayStarted = now;
                return true;
            }
            case MOVE_MAP -> {
                if (!moves.moveMap()) return false;
                stay = Activity.HANG_OUT;
                stayStarted = now;
                return true;
            }
            case BREAK -> {
                return moves.sit();
            }
            case CHANGE_CHANNEL -> {
                int target = runtime.channelTarget();
                if (target <= 0 || LifeChannelMove.why(chr, false, now) != null) return false;
                if (!LifeChannelMove.move(chr, target, runtime.channelForKs(), now)) return false;
                stayStarted = now;
                return true;
            }
            case LOG_OUT -> {
                return BotPopulationDirector.logOut(chr.getId() - BotStore.BOT_ID_BASE);
            }
            default -> {
                return false;
            }
        }
    }

    // ── LifeHost ──

    @Override public Character getChr() { return bot.getChr(); }
    @Override public boolean getRunning() { return bot.getRunning(); }
    @Override public LifeRuntime lifeRuntime() { return runtime; }
    @Override public void nudgeSoon(long delayMs) { bot.nudgeSoon(delayMs); }
    @Override public LifeActivityQueue.State lifeActivityState() { return queue.state(); }
    @Override public LifeActivityQueue.Request prepareLifeActivity(Result decision) {
        return queue.prepare(decision, System.currentTimeMillis(), LifeActivityQueue.MAX_WAIT_MS);
    }
    @Override public LifeActivityQueue.Offer submitLifeActivity(LifeActivityQueue.Request request) {
        if (!TrainingBotGate.executionEnabled()) return LifeActivityQueue.Offer.DISABLED;
        return queue.offer(request, System.currentTimeMillis());
    }
    @Override public void keepLifeActivity(Activity activity, long until) { stayUntil = until; }

    @Override
    public boolean freeForAutomaticLifeActivity(long at) {
        Character chr = bot.getChr();
        return bot.getRunning() && chr != null && chr.getMap() != null && moves.available()
                && !BotCommitments.inFlight(bot) && !BotConversation.hasAnyChat(chr.getId())
                && BotPopulationDirector.mayLogOut(bot);
    }

    @Override
    public boolean canStartLifeActivity(Activity activity, long at) {
        Character chr = bot.getChr();
        if (!LifeRuntime.enabled() || chr == null || chr.getMap() == null) return false;
        return switch (activity) {
            case HANG_OUT -> true;
            case SHOW_OFF -> chr.getLevel() >= 70;
            case MOVE_MAP -> moves.canMoveMap();
            case BREAK -> moves.canSit();
            case CHANGE_CHANNEL -> runtime.channelTarget() > 0 && LifeChannelMove.why(chr, false, at) == null;
            case LOG_OUT -> sessionDone(chr, at);
            default -> false;
        };
    }

    /** The training bot's rule: its session time is up, and guild departures stay with the director's plan. */
    static boolean sessionDone(Character chr, long at) {
        var member = BotRoster.loadedMember(chr.getId() - BotStore.BOT_ID_BASE);
        return member != null && member.lastLoginAt() > 0 && member.sessionMinutes() > 0
                && at - member.lastLoginAt() >= member.sessionMinutes() * 60_000L && chr.getGuildId() == 0;
    }

    @Override
    public Current lifeCurrent(LifeProfile profile, long now) {
        long since = stayStarted > 0 && stayStarted <= now ? stayStarted : now;
        return new Current(stay, since, stayLength(profile), LifePolicy.STICKINESS);
    }

    /** A social bot is settled in: its stays last three town stays; a wanderer's one. */
    long stayLength(LifeProfile profile) {
        return (kind == Kind.SOCIAL ? 3 : 1) * profile.townDurationMs();
    }

    /**
     * Different starting needs: a social bot stands among people, so its SOCIAL need stays low and it is slow to get
     * bored; a wanderer is the restless kind (boredom grows faster, so it moves on).
     */
    @Override
    public void lifeNeeds(Map<Need, Double> needs, long now) {
        if (kind == Kind.SOCIAL) {
            needs.put(Need.SOCIAL, needs.getOrDefault(Need.SOCIAL, 0.0) * .2);
            needs.computeIfPresent(Need.BOREDOM, (n, v) -> v * .7);
        } else {
            needs.put(Need.SOCIAL, needs.getOrDefault(Need.SOCIAL, 0.0) * .6);
            needs.computeIfPresent(Need.BOREDOM, (n, v) -> Math.min(1, v * 1.3));
        }
        needs.remove(Need.SUPPLIES); // no grinding: potions don't matter here
        needs.remove(Need.BAG_SPACE);
    }

    private void note(String text) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
            soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.HABITS, "LIFE " + text + " bot=" + bot.getChr().getName()
                    + " type=" + kind);
        }
    }

    /** Execution is on with the brain flags (as for training bots), or under QA with execute set. */
    static final class TrainingBotGate {
        private TrainingBotGate() {}
        static boolean executionEnabled() {
            return soloMapling.ArtificialPlayer.BotTypes.TrainingBot.lifeExecutionEnabled();
        }
    }
}
