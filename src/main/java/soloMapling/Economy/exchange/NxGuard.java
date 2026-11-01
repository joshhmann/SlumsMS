package soloMapling.Economy.exchange;

import java.util.concurrent.TimeUnit;

/**
 * 1.6.1 stolen-cookie guard, the game's side (owner 2026-10-29: "both guards"; the website does the fresh sign-in on
 * its side; no daily caps, owner 2026-10-29 13:25 UTC). JVM properties only (config.yaml is strict):
 * <pre>
 *   -Dslums.exchange.nxBand.minPct=50          an NX SELL may not ask less than this % of the official NX rate
 *   -Dslums.exchange.nxBand.maxPct=200         an NX BUY may not bid more than this % of it
 *   -Dslums.exchange.nxStartPrice=5000000      what NX counts as until players set an official rate, mesos per 1,000
 *                                              NX (owner 2026-10-29: 5,000 mesos per NX, the NX merchant bots' price,
 *                                              a 10k NX code for 50m); the band is then 50%..200% of it: sells from
 *                                              2,500,000, buys up to 10,000,000 per 1,000 NX
 *   -Dslums.exchange.siteAuthMaxHours=12       a site request must carry {@code siteAuthAt} (the player's last real
 *                                              site sign-in) no older than this (0 = not checked)
 * </pre>
 * The band is for every player NX order, site or game (the regulars and the QA checks' test regular quote through
 * their own path). A sell may still fill higher and a buy lower than its limit: the band only stops a dump (or an
 * overpay) priced far away from what players have been paying. A 0 turns that relative part off.
 *
 * <p><b>Inside a hard window (owner 2026-10-31 ~19:50 UTC).</b> Whatever the official rate does, no NX order and no
 * fill may leave {@link #NX_LOWEST}..{@link #NX_HIGHEST} mesos per 1,000 NX (2,500..10,000 mesos per NX). The relative
 * band above is intersected with it, so the tighter of the two wins, and {@link #OFF} drops only the relative band,
 * never the window. A fill follows: it trades at the resting order's price, and no resting order is outside.
 */
public record NxGuard(int minPct, int maxPct, long startPrice, long siteAuthMaxMs) {

    /** How far into the future a {@code siteAuthAt} may be (the site's clock against ours). */
    public static final long SKEW_MS = TimeUnit.MINUTES.toMillis(5);

    public NxGuard {
        minPct = Math.max(0, Math.min(100, minPct));
        maxPct = maxPct <= 0 ? 0 : Math.max(100, maxPct);
        startPrice = Math.max(0, startPrice);
        siteAuthMaxMs = Math.max(0, siteAuthMaxMs);
    }

    /** The owner's defaults (2026-10-29). */
    public static NxGuard defaults() {
        return new NxGuard(50, 200, START_PRICE, TimeUnit.HOURS.toMillis(12));
    }

    /** NX's starting price, mesos per 1,000 NX: 5,000 mesos per NX (owner 2026-10-29). */
    public static final long START_PRICE = 5_000_000;

    /**
     * The hard window every NX order and fill stays inside, mesos per 1,000 NX (owner 2026-10-31 ~19:50 UTC: "no NX
     * order or fill, player or bot, ever outside 2,500..10,000 mesos per NX, whatever the official rate does"). It is
     * not a switch and no property moves it.
     */
    public static final long NX_LOWEST = 2_500_000, NX_HIGHEST = 10_000_000;

    /** Every part off (tests of other things). */
    public static final NxGuard OFF = new NxGuard(0, 0, 0, 0);

    public static NxGuard fromSystem() {
        NxGuard d = defaults();
        return new NxGuard((int) l("nxBand.minPct", d.minPct), (int) l("nxBand.maxPct", d.maxPct),
                l("nxStartPrice", d.startPrice),
                TimeUnit.HOURS.toMillis(l("siteAuthMaxHours", TimeUnit.MILLISECONDS.toHours(d.siteAuthMaxMs))));
    }

    /** The same with another site sign-in age (0 = off). */
    public NxGuard withSiteAuthMaxMs(long ms) {
        return new NxGuard(minPct, maxPct, startPrice, ms);
    }

    /**
     * The allowed NX limit prices {lowest sell, highest buy} (mesos per 1,000 NX), inside the ticker's own range
     * {@code [min, max]}: the relative band around {@code rate} when there is one, else around the starting price, and
     * always inside {@link #NX_LOWEST}..{@link #NX_HIGHEST}: the tighter of the two applies.
     */
    public long[] band(Long rate, long min, long max) {
        long lo = min, hi = max;
        if (rate == null || rate <= 0) {
            rate = startPrice > 0 ? startPrice : null;
        }
        if (rate != null) {
            if (minPct > 0) {
                lo = Math.max(min, rate * minPct / 100);
            }
            if (maxPct > 0) {
                hi = Math.min(max, Math.multiplyHigh(rate, maxPct) == 0 ? rate * maxPct / 100 : max);
            }
        }
        lo = Math.max(lo, NX_LOWEST);
        hi = Math.min(hi, NX_HIGHEST);
        return new long[]{Math.min(lo, max), Math.max(hi, min)};
    }

    /** One NX price (mesos per 1,000 NX) inside the hard window: {@link #NX_LOWEST}..{@link #NX_HIGHEST}. */
    public static long clamp(long price) {
        return Math.max(NX_LOWEST, Math.min(NX_HIGHEST, price));
    }

    /** The window alone: the hard limits on an NX order whatever the guard says (see {@link #NX_LOWEST}). */
    public static long[] window(long min, long max) {
        return new long[]{Math.min(Math.max(min, NX_LOWEST), max), Math.max(Math.min(max, NX_HIGHEST), min)};
    }

    private static long l(String key, long dflt) {
        String v = System.getProperty("slums.exchange." + key);
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
