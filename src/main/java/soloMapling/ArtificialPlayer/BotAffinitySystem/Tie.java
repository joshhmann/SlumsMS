package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.EnumMap;
import java.util.Map;

/**
 * One bot's feelings about one real player (ticket 58): a score in -100..100 as of {@link #updatedAt}, faded toward 0
 * from there by {@link AffinityRules}, and how many kind and hostile things built it. Per-event cooldowns live here too,
 * in memory only. Not thread-safe: {@link BotAffinity} guards it with the player's lock.
 */
public final class Tie {

    final int botId;
    final int charId;
    double score;
    int kind;
    int hostile;
    long updatedAt;
    /** The last time each kind of event counted for this pair (cooldowns; not stored). */
    final Map<AffinityEvent, Long> lastAt = new EnumMap<>(AffinityEvent.class);
    /** Something changed since the last write. */
    boolean dirty;

    public Tie(int botId, int charId, double score, int kind, int hostile, long updatedAt) {
        this.botId = botId;
        this.charId = charId;
        this.score = score;
        this.kind = kind;
        this.hostile = hostile;
        this.updatedAt = updatedAt;
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

    /** A copy for readers outside the lock (GM display, the store). */
    public Tie snapshot() {
        Tie copy = new Tie(botId, charId, score, kind, hostile, updatedAt);
        copy.lastAt.putAll(lastAt);
        return copy;
    }
}
