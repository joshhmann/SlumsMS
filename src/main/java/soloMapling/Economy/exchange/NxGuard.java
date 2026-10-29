package soloMapling.Economy.exchange;

import java.util.concurrent.TimeUnit;

/**
 * 1.6.1 stolen-cookie guard, the game's side (owner 2026-10-29: "both guards"; the website does the fresh sign-in on
 * its side). JVM properties only (config.yaml is strict):
 * <pre>
 *   -Dslums.exchange.nxBand.minPct=50          an NX SELL may not ask less than this % of the official NX rate
 *   -Dslums.exchange.nxBand.maxPct=200         an NX BUY may not bid more than this % of it
 *   -Dslums.exchange.nxBand.floor=100000       with no official rate yet: the lowest NX sell price (mesos per 1,000
 *                                              NX, i.e. 100 mesos per NX); buys then have only the ticker's range
 *   -Dslums.exchange.siteNxSellPerDay=10000    NX an account may sell through the SITE in 24 h (0 = no cap); in-game
 *                                              {@code @nx} sells are not counted (they need the live character)
 *   -Dslums.exchange.siteAuthMaxHours=12       a site request must carry {@code siteAuthAt} (the player's last real
 *                                              site sign-in) no older than this (0 = not checked)
 * </pre>
 * The band is for every player NX order, site or game (the regulars and the QA checks' test regular quote through
 * their own path). A sell may still fill higher and a buy lower than its limit: the band only stops a dump (or an
 * overpay) priced far away from what players have been paying. A 0 turns that part off.
 */
public record NxGuard(int minPct, int maxPct, long floorPrice, long siteSellNxPerDay, long siteAuthMaxMs) {

    /** How far into the future a {@code siteAuthAt} may be (the site's clock against ours). */
    public static final long SKEW_MS = TimeUnit.MINUTES.toMillis(5);

    public NxGuard {
        minPct = Math.max(0, Math.min(100, minPct));
        maxPct = maxPct <= 0 ? 0 : Math.max(100, maxPct);
        floorPrice = Math.max(0, floorPrice);
        siteSellNxPerDay = Math.max(0, siteSellNxPerDay);
        siteAuthMaxMs = Math.max(0, siteAuthMaxMs);
    }

    /** The owner's defaults (2026-10-29). */
    public static NxGuard defaults() {
        return new NxGuard(50, 200, 100_000, 10_000, TimeUnit.HOURS.toMillis(12));
    }

    /** Every part off (tests of other things). */
    public static final NxGuard OFF = new NxGuard(0, 0, 0, 0, 0);

    public static NxGuard fromSystem() {
        NxGuard d = defaults();
        return new NxGuard((int) l("nxBand.minPct", d.minPct), (int) l("nxBand.maxPct", d.maxPct),
                l("nxBand.floor", d.floorPrice), l("siteNxSellPerDay", d.siteSellNxPerDay),
                TimeUnit.HOURS.toMillis(l("siteAuthMaxHours", TimeUnit.MILLISECONDS.toHours(d.siteAuthMaxMs))));
    }

    /** The same with another site sign-in age (0 = off). */
    public NxGuard withSiteAuthMaxMs(long ms) {
        return new NxGuard(minPct, maxPct, floorPrice, siteSellNxPerDay, ms);
    }

    /** The same with another site sell cap (0 = off). */
    public NxGuard withSiteSellNxPerDay(long nx) {
        return new NxGuard(minPct, maxPct, floorPrice, nx, siteAuthMaxMs);
    }

    /**
     * The allowed NX limit prices {lowest sell, highest buy} (mesos per 1,000 NX), inside the ticker's own range
     * {@code [min, max]}: around {@code rate} when there is one, else the fixed floor and the ticker's max.
     */
    public long[] band(Long rate, long min, long max) {
        long lo = min, hi = max;
        if (rate != null && rate > 0) {
            if (minPct > 0) {
                lo = Math.max(min, rate * minPct / 100);
            }
            if (maxPct > 0) {
                hi = Math.min(max, Math.multiplyHigh(rate, maxPct) == 0 ? rate * maxPct / 100 : max);
            }
        } else if (floorPrice > 0) {
            lo = Math.max(min, floorPrice);
        }
        return new long[]{Math.min(lo, max), Math.max(hi, min)};
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
