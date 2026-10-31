package soloMapling.worldevents;

/**
 * 1.6.2 (owner b236): the Giant Cake's HP is aimed at a fight length, not at a fixed pool. At spawn the cake gets its
 * base HP (the template's {@code boss_hp}, or the {@code !qa} override) plus {@link #perHead()} for everyone on the
 * event map, players and bots; every {@link #BEAT_MS} while it stands, the crowd's damage so far is turned into
 * damage per second and the cake's max and current HP alike are raised to what {@link #targetSeconds()} of that pace
 * needs (once {@link #PROBE_MS} of the fight has gone by). Newcomers still top it up by {@link #perHead()} as before.
 * Nothing ever shrinks, and nothing goes past {@link #cap()} (when one is set) or {@link EventTemplates#MAX_BOSS_HP}.
 * Bots can still jump in at once; there is no hold-back window. Rewards and top damage are unchanged.
 *
 * <p>Why aiming rather than a bigger per-head number: on TEST (2026-10-31 02:14 UTC, {@code !qa ev-1}) a 40-bot crowd
 * did ~6,100 DPS and 36,000 HP per head was sized for that; in the owner's 10-31 playtest 49 attendees took the
 * 1,800,000 HP cake down in 43 s, i.e. ~42,000 DPS - about seven times the estimate. A static per-head number can
 * only be wrong in one direction at a time as bots get stronger, so the fight measures its own crowd instead.
 *
 * <p>Switches: {@code -Dslums.cake.targetSeconds} (default {@value #DEFAULT_TARGET_SECONDS}; 0 = aim at nothing, HP
 * per head only), {@code -Dslums.cake.hpPerHead} (default {@value #DEFAULT_PER_HEAD}; 0 = no scaling, the old fixed
 * HP), {@code -Dslums.cake.hpCap} (default {@value #DEFAULT_CAP} = no ceiling of its own; the old ceiling was
 * 1,800,000, which any crowd past ~50 heads outgrew).
 */
final class CakeHp {

    static final long DEFAULT_PER_HEAD = 36_000L;
    static final long DEFAULT_CAP = 0L;
    static final long DEFAULT_TARGET_SECONDS = 300L; // owner 2026-10-31: about five minutes a fight
    /** How long the crowd's damage is measured before the first aim. */
    static final long PROBE_MS = 5_000L;
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

    static long targetSeconds() {
        return Math.max(0L, Long.getLong("slums.cake.targetSeconds", DEFAULT_TARGET_SECONDS));
    }

    /** The most HP a cake may have: its base, a cap when one is set, and never more than the boss limit. */
    static long ceiling(long base, long cap) {
        long b = Math.max(0L, base);
        if (cap <= 0) {
            return EventTemplates.MAX_BOSS_HP;
        }
        return Math.max(b, Math.min(cap, EventTemplates.MAX_BOSS_HP));
    }

    /** Pure: the cake's max HP for {@code heads} people on the map; base when no one is there. */
    static long hpFor(long base, int heads, long perHead, long cap) {
        long b = Math.max(0L, base);
        long extra = Math.max(0, heads) * Math.max(0L, perHead);
        return Math.min(ceiling(b, cap), b + extra);
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

    /** The crowd's damage per second: what it has taken off the cake over the time it has been standing. */
    static long dps(long damageTaken, long elapsedMs) {
        return damageTaken <= 0 || elapsedMs <= 0 ? 0L : Math.round(damageTaken * 1000.0 / elapsedMs);
    }

    /**
     * Pure: the max HP a standing cake should have to last {@code targetSeconds} at the crowd's pace, from the damage
     * it has taken over {@code elapsedMs}. 0 when there is nothing to aim at yet (no aiming, the probe time not over,
     * no damage) - the caller leaves the cake alone. Never below the base, never past the ceiling.
     */
    static long aimFor(long base, long damageTaken, long elapsedMs, long targetSeconds, long cap) {
        if (targetSeconds <= 0 || elapsedMs < PROBE_MS || damageTaken <= 0) {
            return 0L;
        }
        long aim = Math.round(dps(damageTaken, elapsedMs) * (double) targetSeconds);
        return Math.min(ceiling(base, cap), Math.max(Math.max(0L, base), aim));
    }

    /** Pure: what the aim adds to a standing cake now, 0 when it is already at least as big as the aim needs. */
    static long aimTopUp(long base, long currentMax, long damageTaken, long elapsedMs, long targetSeconds, long cap) {
        long aim = aimFor(base, damageTaken, elapsedMs, targetSeconds, cap);
        return aim <= currentMax ? 0L : aim - currentMax;
    }
}
