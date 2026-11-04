package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 S2: something two bots shared that moves how one feels about the other ({@link BotBonds}). Every kind comes
 * from a shared event the game already has (a party, a thread, a guild, a PQ, a KS on an observed map, a buff or a
 * trade, a sampled minute on the same observed map), never from a scan over all pairs. Each kind has a weight and a
 * per-pair cooldown, tunable with {@code -Dsolomapling.bonds.<name>=<delta>} and
 * {@code -Dsolomapling.bonds.<name>.cooldownSec=<s>} (name in lower case, e.g. {@code party_time}).
 */
public enum BondEvent {
    // Raised by
    /** One joined the other's party. */
    PARTY(6, 30 * 60),
    /** Still in one party together, counted at most every 5 minutes. */
    PARTY_TIME(3, 5 * 60),
    /** A bot thread (map chatter) between the two; a guild-beef thread is a {@link #SPAT}. */
    THREAD(3, 20 * 60),
    /** Guild mates who meet (in a party or a sampled minute on one map): a small seed, twice a day at most. */
    GUILD(4, 12 * 60 * 60),
    /** A party quest together (S3 calls {@link BotBonds#onPqTogether}). */
    PQ(8, 30 * 60),
    BUFF(2, 10 * 60),
    TRADE(3, 30 * 60),
    GIFT(8, 30 * 60),
    /**
     * The giver's side of a one-sided trade ({@link BotBonds#onTrade}); stage 2. It moves the giver's axes only (a
     * little fonder, the receiver owes it) and never its score, so every score-reading consumer sees what it saw before.
     */
    GIFT_GIVEN(0, 30 * 60),
    /** A sampled minute on the same observed map: a nod, barely anything on its own. */
    NEAR(1, 30 * 60),
    // Lowered by
    /** The other bot took this bot's mob on an observed map. */
    KS(-6, 5 * 60),
    /** A guild-beef thread between the two. */
    SPAT(-5, 20 * 60);

    private final int defaultDelta;
    private final int defaultCooldownSec;

    BondEvent(int defaultDelta, int defaultCooldownSec) {
        this.defaultDelta = defaultDelta;
        this.defaultCooldownSec = defaultCooldownSec;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean hostile() {
        return defaultDelta < 0;
    }

    public int defaultDelta() {
        return defaultDelta;
    }

    /**
     * How far the operator's {@code -Dsolomapling.bonds.<name>} moved this event's score delta from its default (1 when
     * untouched; 0 when tuned to nothing). The axis table ({@link AxisTable}) is scaled by it, so tuning an event tunes
     * its axes with it. An event whose default is 0 (it has no score effect) always weighs 1.
     */
    public double weight() {
        return defaultDelta == 0 ? 1 : delta() / (double) defaultDelta;
    }

    public int delta() {
        return Integer.getInteger("solomapling.bonds." + key(), defaultDelta);
    }

    public long cooldownMs() {
        return TimeUnit.SECONDS.toMillis(Integer.getInteger("solomapling.bonds." + key() + ".cooldownSec",
                defaultCooldownSec));
    }

    /** Whether a crossing into FRIEND through this event pushes the bot's Bartle point toward PARTY (else CHAT). */
    boolean partyLike() {
        return this == PARTY || this == PARTY_TIME || this == PQ || this == GUILD;
    }
}
