package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Kind;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.RealRunPool.Origin;
import soloMapling.mechanics.RunState.Ending;

import java.util.List;

/**
 * Ticket 164, phase 2 of {@code SIMULATED-RUNS-PLAN.md}: what a run <b>was</b>, as one immutable fact written when it
 * ends, not a log line. Every run that ends - cleared, failed, stopped, refused at its start - produces exactly one,
 * written by {@link RunOutcomes}, and everything that wants to say or show something about a run reads this and
 * nothing else: the bots' win and loss lines ({@link RunChatter}) today, a spectator view or a wager later.
 *
 * <p>Result, in the three words the chatter already used ({@link Ending}): {@link Result#WON} the last stage to play
 * cleared, {@link Result#LOST} the party played it and failed ({@link RunState#fail}), {@link Result#ABANDONED}
 * everything else (a GM stop, a yield to a player, too few members, the leader leaving, the event closing, the server
 * stopping, a run that never started): not an outcome of the content.
 *
 * <p>{@link Mode} says whether the run was played. Every run is {@link Mode#REAL} today; {@link Mode#SIMULATED} is
 * reserved for phase 2's simulated runs and nothing makes one yet. The two must never be told apart by a log line.
 *
 * <p>Participants are taken when the run starts, by what each character <em>is</em>: a real player, a population bot,
 * or a player's owned alt (which is driven like a bot and belongs to a person).
 *
 * @param runId         unique per run; what a reader joins on (and what a wager would settle on)
 * @param instanceKey   the event instance and its channel as {@link MechanicsRuns#keyOf} names it ("Kerning0 w1 ch 2")
 * @param contentName   the row's own {@code name:}, for a line a player reads
 * @param stageReached  the stage the run was on when it ended
 * @param stagesToPlay  the last stage the run was to play
 * @param humanAtStart  a real player was in the run when it started
 * @param humanAtEnd    a real player was still a member when it ended
 * @param simulated     for a simulated run, the real runs its result was sampled from; null for a played one
 */
public record RunOutcome(String runId, String instanceKey, String contentId, String contentName, Kind kind, Tier tier,
                         Result result, String reason, Mode mode, Origin origin, long startedAtMs, long endedAtMs,
                         int stageReached, int stagesToPlay, List<Participant> participants, boolean humanAtStart,
                         boolean humanAtEnd, Basis simulated) {

    /** How the run ended, as far as anyone has something to say about it. */
    public enum Result {
        WON, LOST, ABANDONED
    }

    /** Whether the run was played. */
    public enum Mode {
        REAL, SIMULATED
    }

    /** What a participant is. An owned alt is driven by a run like a bot but is a person's character. */
    public enum ParticipantKind {
        PLAYER, BOT, OWNED_ALT
    }

    /** One member of the run, as it was when the run started. */
    public record Participant(int characterId, ParticipantKind kind, boolean leader) {
    }

    public RunOutcome {
        participants = List.copyOf(participants);
        reason = reason == null ? "" : reason;
    }

    /** A played run's outcome: nothing was sampled. */
    public RunOutcome(String runId, String instanceKey, String contentId, String contentName, Kind kind, Tier tier,
                      Result result, String reason, Mode mode, Origin origin, long startedAtMs, long endedAtMs,
                      int stageReached, int stagesToPlay, List<Participant> participants, boolean humanAtStart,
                      boolean humanAtEnd) {
        this(runId, instanceKey, contentId, contentName, kind, tier, result, reason, mode, origin, startedAtMs,
                endedAtMs, stageReached, stagesToPlay, participants, humanAtStart, humanAtEnd, null);
    }

    /**
     * What a {@link Mode#SIMULATED} result was drawn from: how many real runs of the same content and tier, and the share of
     * them that were won (the odds the win-or-lose draw was made with). Null for a played run.
     */
    public record Basis(int samples, double pWin) {
    }

    /** How long the run lasted, from its start to its end. */
    public long durationMs() {
        return Math.max(0, endedAtMs - startedAtMs);
    }

    /** The party leader as the run started, or -1 when no member was the leader. */
    public int leaderId() {
        for (Participant p : participants) {
            if (p.leader()) {
                return p.characterId();
            }
        }
        return -1;
    }

    /** The members of one kind, in the order they were registered. */
    public List<Participant> ofKind(ParticipantKind kind) {
        return participants.stream().filter(p -> p.kind() == kind).toList();
    }

    /** The run's ending in the outcome's own words: a silent end is {@link Result#ABANDONED}. */
    static Result resultOf(Ending ending) {
        return switch (ending) {
            case WON -> Result.WON;
            case LOST -> Result.LOST;
            case SILENT -> Result.ABANDONED;
        };
    }

    /**
     * The outcome of a run that has just ended.
     *
     * @param participants the members when the run started
     */
    static RunOutcome of(String runId, String instanceKey, RunState run, Origin origin, Mode mode, long startedAtMs,
                         long endedAtMs, List<Participant> participants) {
        boolean humanAtStart = participants.stream().anyMatch(p -> p.kind() == ParticipantKind.PLAYER);
        boolean humanAtEnd = run.members().stream().anyMatch(m -> !m.bot());
        return new RunOutcome(runId, instanceKey, run.content().id(), run.content().name(), run.content().kind(),
                run.tier(), resultOf(run.ending()), run.endedBecause(), mode, origin, startedAtMs, endedAtMs,
                run.currentStage(), run.lastStage(), participants, humanAtStart, humanAtEnd);
    }

    /** One line for the bot log; a reader of that log can tell a played run from a simulated one by {@code mode}. */
    public String line() {
        return "outcome " + contentId + " " + result + " " + mode.name().toLowerCase(java.util.Locale.ROOT) + " "
                + origin + (tier == Tier.NORMAL ? "" : " tier " + tier) + " in " + instanceKey + " after "
                + durationMs() / 1000 + " s (stage " + stageReached + " of " + stagesToPlay + "), "
                + ofKind(ParticipantKind.PLAYER).size() + " player(s), " + ofKind(ParticipantKind.BOT).size() + " bot(s), "
                + ofKind(ParticipantKind.OWNED_ALT).size() + " owned alt(s): " + reason
                + (simulated == null ? "" : " (sampled from " + simulated.samples() + " real runs, pWin "
                + String.format(java.util.Locale.ROOT, "%.2f", simulated.pWin()) + ")")
                + " [" + runId + "]";
    }
}
