package soloMapling.ArtificialPlayer.BotAffinitySystem;

/**
 * 1.6.0 S2: how one bot feels about one other bot. Directional (a KS sours the victim, not the thief). A score in
 * -100..100 as of {@link #updatedAt}, faded toward 0 from there by {@link BondRules}, and how many kind and hostile
 * things built it, like {@link Tie} for real players. Per-event cooldowns live here in memory only (lazily allocated:
 * most bonds only ever see one or two kinds of event). Not thread-safe: {@link BotBonds} guards it with the owner's
 * bond set.
 *
 * <p>Ticket 181 stage 2 adds the eight {@link Axis} values (as of the same {@link #updatedAt}) and the {@link Stage}
 * the edge was last in (the memory the stage hysteresis needs). {@code score} stays the number every older consumer
 * reads: it is still moved by the event's own delta, exactly as before; the axes ride along and never change it.
 */
public final class Bond {

    /** The bot that holds the feeling, and the bot it is about (character ids). */
    final int botId;
    final int otherId;
    double score;
    int kind;
    int hostile;
    long updatedAt;
    /** The {@link Axis} values as of {@link #updatedAt} (length {@link Axis#COUNT}); never null. */
    double[] axes;
    /** The stage as of the last event or read that moved it (see {@link Stage}); never null. */
    Stage stage;
    /** When each {@link BondEvent} last counted for this pair (index = ordinal); null until the first event. */
    long[] lastAt;
    boolean dirty;

    /** A row without axes (older rows, GM staging): the axes are derived from {@code score}, {@code kind}, {@code hostile}. */
    public Bond(int botId, int otherId, double score, int kind, int hostile, long updatedAt) {
        this(botId, otherId, score, kind, hostile, updatedAt, Axes.legacy(score, kind, hostile), null);
    }

    /** A row with its axes; a null {@code stage} is derived from them (as a legacy row's is). */
    public Bond(int botId, int otherId, double score, int kind, int hostile, long updatedAt, double[] axes,
                Stage stage) {
        this.botId = botId;
        this.otherId = otherId;
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

    public int otherId() {
        return otherId;
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

    long lastAt(BondEvent event) {
        return lastAt == null ? 0 : lastAt[event.ordinal()];
    }

    void stamp(BondEvent event, long now) {
        if (lastAt == null) {
            lastAt = new long[BondEvent.values().length];
        }
        lastAt[event.ordinal()] = now;
    }

    /** A copy for readers outside the lock (the store, GM display). Cooldowns are not copied. */
    public Bond snapshot() {
        return new Bond(botId, otherId, score, kind, hostile, updatedAt, axes, stage);
    }
}
