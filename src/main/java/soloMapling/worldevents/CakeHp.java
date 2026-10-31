package soloMapling.worldevents;

/**
 * 1.6.2 (owner b236): the Giant Cake's HP scales with attendance. At spawn the cake gets its base HP (the template's
 * {@code boss_hp}, or the {@code !qa} override) plus {@link #perHead()} for everyone on the event map, players and
 * bots; while it stands, each newcomer above the most heads seen so far tops it up by the same amount (max and current
 * HP alike). The total never goes past {@link #cap()}, and never below the base (a base above the cap is left alone).
 * Bots can still jump in at once; there is no hold-back window. Rewards and top damage are unchanged.
 *
 * <p>Numbers: on TEST (2026-10-31 02:14 UTC, {@code !qa ev-1}) the bots took a 60,000 HP cake down in ~9.8 s, about
 * 6,100 DPS for the crowd, ~150 DPS per bot spread over 40. Aiming at ~4 minutes for a typical crowd: 150 DPS x 240 s
 * = 36,000 HP per head, so 40 heads = 60,000 + 1,440,000 = 1.5 M HP, ~4.1 min at 6,100 DPS. The cap of 1,800,000
 * (~4.9 min at that DPS, ~48 heads) keeps a packed town from making it a 20-minute slog.
 *
 * <p>Switches: {@code -Dslums.cake.hpPerHead} (default {@value #DEFAULT_PER_HEAD}; 0 = no scaling, the old fixed HP),
 * {@code -Dslums.cake.hpCap} (default {@value #DEFAULT_CAP}).
 */
final class CakeHp {

    static final long DEFAULT_PER_HEAD = 36_000L;
    static final long DEFAULT_CAP = 1_800_000L;
    /** How often the event looks for newcomers while the cake stands. */
    static final long BEAT_MS = 2_000L;

    private CakeHp() {
    }

    static long perHead() {
        return Math.max(0L, Long.getLong("slums.cake.hpPerHead", DEFAULT_PER_HEAD));
    }

    static long cap() {
        return Math.max(0L, Long.getLong("slums.cake.hpCap", DEFAULT_CAP));
    }

    /** Pure: the cake's max HP for {@code heads} people on the map; base when no one is there. */
    static long hpFor(long base, int heads, long perHead, long cap) {
        long b = Math.max(0L, base);
        long extra = Math.max(0, heads) * Math.max(0L, perHead);
        long ceiling = Math.max(b, Math.min(cap, EventTemplates.MAX_BOSS_HP));
        return Math.min(ceiling, b + extra);
    }

    /**
     * Pure: how much to add to a standing cake now that {@code heads} people are on the map, when its HP was last set
     * for {@code countedHeads} ({@code currentMax} now). 0 when no one new came or the cap is reached; never negative
     * (people leaving never shrink it).
     */
    static long topUp(long base, long currentMax, int countedHeads, int heads, long perHead, long cap) {
        if (heads <= countedHeads) {
            return 0L;
        }
        return Math.max(0L, hpFor(base, heads, perHead, cap) - currentMax);
    }
}
