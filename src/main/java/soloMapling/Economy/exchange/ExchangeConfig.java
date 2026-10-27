package soloMapling.Economy.exchange;

import java.util.concurrent.TimeUnit;

/**
 * Ticket 113: the exchange's knobs. JVM properties only (config.yaml is strict; no new keys). Every default is the
 * safe choice while the owner's open questions (docs/tickets/113-nx-exchange.md) are unanswered: off, no fee, small
 * orders, few orders a day, orders expire, no regulars.
 * <pre>
 *   -Dslums.exchange=false                     the switch (default off: nothing starts, no command)
 *   -Dslums.exchange.lotNx=100                 orders are whole multiples of this many NX
 *   -Dslums.exchange.minNx=100 / maxNx=50000   order size in NX
 *   -Dslums.exchange.minPrice=1000             mesos per 1,000 NX
 *   -Dslums.exchange.maxPrice=100000000        mesos per 1,000 NX (100m per 1k NX)
 *   -Dslums.exchange.feeBps=0                  fee in basis points of the mesos side, paid by the meso receiver
 *   -Dslums.exchange.maxOpen=5                 open orders per account
 *   -Dslums.exchange.perDay=20                 orders placed per account per 24 h
 *   -Dslums.exchange.cooldownSec=10            between two orders of one account
 *   -Dslums.exchange.expiryHours=72            an order's life (0 = good till cancelled)
 *   -Dslums.exchange.regulars=0                regulars quoting (0 = none; they also need a funded house)
 *   -Dslums.exchange.regularSpreadBps=300      each regular quotes this far either side of the reference rate
 *   -Dslums.exchange.regularNx=500             NX per regular order
 *   -Dslums.exchange.rateWindowHours=24        official rate: VWAP window
 *   -Dslums.exchange.rateMinFills=3            official rate: fewest counted fills to publish one
 *   -Dslums.exchange.pollSec=15                site inbox poll
 * </pre>
 */
public record ExchangeConfig(boolean enabled, long lotNx, long minNx, long maxNx, long minPrice, long maxPrice,
                             int feeBps, int maxOpen, int perDay, long cooldownMs, long expiryMs, int regulars,
                             int regularSpreadBps, long regularNx, long rateWindowMs, int rateMinFills,
                             long pollSeconds) {

    public static final String ENABLED = "slums.exchange";

    public ExchangeConfig {
        if (lotNx <= 0 || minNx <= 0 || maxNx < minNx || minPrice <= 0 || maxPrice < minPrice || feeBps < 0
                || feeBps >= 10_000 || maxOpen <= 0 || perDay <= 0 || cooldownMs < 0 || expiryMs < 0 || regulars < 0
                || regularSpreadBps < 0 || regularSpreadBps >= 10_000 || regularNx <= 0 || rateWindowMs <= 0
                || rateMinFills <= 0 || pollSeconds <= 0) {
            throw new IllegalArgumentException("bad exchange config");
        }
        // the largest buy hold must fit a long with room to spare
        Math.multiplyExact(maxNx, maxPrice);
    }

    public static ExchangeConfig defaults() {
        return new ExchangeConfig(false, 100, 100, 50_000, 1_000, 100_000_000, 0, 5, 20, TimeUnit.SECONDS.toMillis(10),
                TimeUnit.HOURS.toMillis(72), 0, 300, 500, TimeUnit.HOURS.toMillis(24), 3, 15);
    }

    public static boolean switchedOn() {
        return Boolean.getBoolean(ENABLED);
    }

    /** From the JVM properties, each falling back to {@link #defaults()}. */
    public static ExchangeConfig fromSystem() {
        ExchangeConfig d = defaults();
        return new ExchangeConfig(switchedOn(),
                l("lotNx", d.lotNx), l("minNx", d.minNx), l("maxNx", d.maxNx),
                l("minPrice", d.minPrice), l("maxPrice", d.maxPrice), (int) l("feeBps", d.feeBps),
                (int) l("maxOpen", d.maxOpen), (int) l("perDay", d.perDay),
                TimeUnit.SECONDS.toMillis(l("cooldownSec", TimeUnit.MILLISECONDS.toSeconds(d.cooldownMs))),
                TimeUnit.HOURS.toMillis(l("expiryHours", TimeUnit.MILLISECONDS.toHours(d.expiryMs))),
                (int) l("regulars", d.regulars), (int) l("regularSpreadBps", d.regularSpreadBps),
                l("regularNx", d.regularNx),
                TimeUnit.HOURS.toMillis(l("rateWindowHours", TimeUnit.MILLISECONDS.toHours(d.rateWindowMs))),
                (int) l("rateMinFills", d.rateMinFills), l("pollSec", d.pollSeconds));
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

    /** The fee on a fill's mesos, rounded down. */
    public long fee(long mesos) {
        return feeBps == 0 ? 0 : Math.multiplyExact(mesos, feeBps) / 10_000;
    }
}
