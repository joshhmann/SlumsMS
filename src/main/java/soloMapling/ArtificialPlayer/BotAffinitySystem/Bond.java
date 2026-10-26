package soloMapling.ArtificialPlayer.BotAffinitySystem;

/**
 * 1.6.0 S2: how one bot feels about one other bot. Directional (a KS sours the victim, not the thief). A score in
 * -100..100 as of {@link #updatedAt}, faded toward 0 from there by {@link BondRules}, and how many kind and hostile
 * things built it, like {@link Tie} for real players. Per-event cooldowns live here in memory only (lazily allocated:
 * most bonds only ever see one or two kinds of event). Not thread-safe: {@link BotBonds} guards it with the owner's
 * bond set.
 */
public final class Bond {

    /** The bot that holds the feeling, and the bot it is about (character ids). */
    final int botId;
    final int otherId;
    double score;
    int kind;
    int hostile;
    long updatedAt;
    /** When each {@link BondEvent} last counted for this pair (index = ordinal); null until the first event. */
    long[] lastAt;
    boolean dirty;

    public Bond(int botId, int otherId, double score, int kind, int hostile, long updatedAt) {
        this.botId = botId;
        this.otherId = otherId;
        this.score = score;
        this.kind = kind;
        this.hostile = hostile;
        this.updatedAt = updatedAt;
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
        return new Bond(botId, otherId, score, kind, hostile, updatedAt);
    }
}
