package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.EnumMap;
import java.util.Map;

/**
 * One bot's feelings about one real player (ticket 58): a score in -100..100 as of {@link #updatedAt}, faded toward 0
 * from there by {@link AffinityRules}, and how many kind and hostile things built it. Per-event cooldowns live here too,
 * in memory only. Not thread-safe: {@link BotAffinity} guards it with the player's lock.
 *
 * <p>Ticket 271 stage 2 adds the eight {@link Axis} values and the {@link Stage}, exactly as {@link Bond} has them; the
 * score is still moved by the event's own delta and read by every older consumer.
 */
public final class Tie {

    final int botId;
    final int charId;
    double score;
    int kind;
    int hostile;
    long updatedAt;
    /** The {@link Axis} values as of {@link #updatedAt}; never null. */
    double[] axes;
    /** The stage as of the last event or read that moved it; never null. */
    Stage stage;
    /** The last time each kind of event counted for this pair (cooldowns; not stored). */
    final Map<AffinityEvent, Long> lastAt = new EnumMap<>(AffinityEvent.class);
    /** Something changed since the last write. */
    boolean dirty;

    /** A row without axes (older rows, GM staging): the axes are derived from {@code score}, {@code kind}, {@code hostile}. */
    public Tie(int botId, int charId, double score, int kind, int hostile, long updatedAt) {
        this(botId, charId, score, kind, hostile, updatedAt, Axes.legacy(score, kind, hostile), null);
    }

    /** A row with its axes; a null {@code stage} is derived from them. */
    public Tie(int botId, int charId, double score, int kind, int hostile, long updatedAt, double[] axes,
               Stage stage) {
        this.botId = botId;
        this.charId = charId;
        this.score = score;
        this.kind = kind;
        this.hostile = hostile;
        this.updatedAt = updatedAt;
        this.axes = axes.clone();
        this.stage = stage != null ? stage : Stage.settle(Stage.STRANGER, this.axes, true);
    }

    public int botId() {
        return botId;
    }

    public int charId() {
        return charId;
    }

    public double rawScore() {
        return score;
    }

    public int kind() {
        return kind;
    }

    public int hostile() {
        return hostile;
    }

    public long updatedAt() {
        return updatedAt;
    }

    /** The axes as stored (as of {@link #updatedAt}, not faded). */
    public Axes rawAxes() {
        return Axes.of(axes);
    }

    public Stage rawStage() {
        return stage;
    }

    /** A copy for readers outside the lock (GM display, the store). */
    public Tie snapshot() {
        Tie copy = new Tie(botId, charId, score, kind, hostile, updatedAt, axes, stage);
        copy.lastAt.putAll(lastAt);
        return copy;
    }
}
