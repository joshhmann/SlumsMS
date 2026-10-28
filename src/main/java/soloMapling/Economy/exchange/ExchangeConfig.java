package soloMapling.Economy.exchange;

import java.util.concurrent.TimeUnit;

/**
 * Ticket 113: the exchange's knobs. JVM properties only (config.yaml is strict; no new keys). The defaults are the
 * owner's decisions of 2026-10-27/28 (docs/tickets/113-nx-exchange.md, "Decisions"): off until switched on, 1% fee
 * from each side, 100-50,000 NX in lots of 100, 5 open / 20 a day / 10 s apart, 72 h expiry, 3 regulars at
 * 3/6/9% with 500 NX each but idle until a house account is named.
 * <pre>
 *   -Dslums.exchange=false                     the switch (default off: nothing starts, no command)
 *   -Dslums.exchange.lotNx=100                 orders are whole multiples of this many NX
 *   -Dslums.exchange.minNx=100 / maxNx=50000   order size in NX
 *   -Dslums.exchange.minPrice=1000             mesos per 1,000 NX
 *   -Dslums.exchange.maxPrice=100000000        mesos per 1,000 NX (100m per 1k NX)
 *   -Dslums.exchange.feeBps=100                fee in basis points of a fill's mesos, paid by EACH side (the seller gets
 *                                              that much less, the buyer pays that much more): 2 x 1% into FEES
 *   -Dslums.exchange.maxOpen=5                 open orders per account
 *   -Dslums.exchange.perDay=20                 orders placed per account per 24 h
 *   -Dslums.exchange.cooldownSec=10            between two orders of one account
 *   -Dslums.exchange.expiryHours=72            an order's life (0 = good till cancelled)
 *   -Dslums.exchange.regulars=3                regulars quoting (0 = none; they also need a house account and funding)
 *   -Dslums.exchange.houseAccount=0            the staff account whose wallet funds the regulars (0 = none: idle)
 *   -Dslums.exchange.regularSpreadBps=300      each regular quotes this far either side of the reference rate
 *   -Dslums.exchange.regularNx=500             NX per regular order
 *   -Dslums.exchange.rateWindowHours=24        official rate: VWAP window
 *   -Dslums.exchange.rateMinFills=3            official rate: fewest counted fills to publish one
 *   -Dslums.exchange.pollSec=15                site inbox poll
 *   -Dslums.exchange.confirmSec=60             how long a preview's confirm token lives
 * </pre>
 */
public record ExchangeConfig(boolean enabled, long lotNx, long minNx, long maxNx, long minPrice, long maxPrice,
                             int feeBps, int maxOpen, int perDay, long cooldownMs, long expiryMs, int regulars,
                             int regularSpreadBps, long regularNx, long rateWindowMs, int rateMinFills,
                             long pollSeconds, long houseAccount, long confirmMs) {

    public static final String ENABLED = "slums.exchange";

    public ExchangeConfig {
        if (lotNx <= 0 || minNx <= 0 || maxNx < minNx || minPrice <= 0 || maxPrice < minPrice || feeBps < 0
                || feeBps >= 10_000 || maxOpen <= 0 || perDay <= 0 || cooldownMs < 0 || expiryMs < 0 || regulars < 0
                || regularSpreadBps < 0 || regularSpreadBps >= 10_000 || regularNx <= 0 || rateWindowMs <= 0
                || rateMinFills <= 0 || pollSeconds <= 0 || houseAccount < 0 || confirmMs <= 0) {
            throw new IllegalArgumentException("bad exchange config");
        }
        // the largest buy hold must fit a long with room to spare
        Math.multiplyExact(maxNx, maxPrice);
    }

    /** The scaffold's shape (no house account, 60 s confirm tokens). */
    public ExchangeConfig(boolean enabled, long lotNx, long minNx, long maxNx, long minPrice, long maxPrice, int feeBps,
                          int maxOpen, int perDay, long cooldownMs, long expiryMs, int regulars, int regularSpreadBps,
                          long regularNx, long rateWindowMs, int rateMinFills, long pollSeconds) {
        this(enabled, lotNx, minNx, maxNx, minPrice, maxPrice, feeBps, maxOpen, perDay, cooldownMs, expiryMs, regulars,
                regularSpreadBps, regularNx, rateWindowMs, rateMinFills, pollSeconds, 0, 60_000);
    }

    public static ExchangeConfig defaults() {
        return new ExchangeConfig(false, 100, 100, 50_000, 1_000, 100_000_000, 100, 5, 20,
                TimeUnit.SECONDS.toMillis(10), TimeUnit.HOURS.toMillis(72), 3, 300, 500, TimeUnit.HOURS.toMillis(24),
                3, 15, 0, 60_000);
    }

    /** The same config with another house account (tests, the QA check). */
    public ExchangeConfig withHouse(long account) {
        return new ExchangeConfig(enabled, lotNx, minNx, maxNx, minPrice, maxPrice, feeBps, maxOpen, perDay, cooldownMs,
                expiryMs, regulars, regularSpreadBps, regularNx, rateWindowMs, rateMinFills, pollSeconds, account,
                confirmMs);
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
                (int) l("rateMinFills", d.rateMinFills), l("pollSec", d.pollSeconds),
                l("houseAccount", d.houseAccount),
                TimeUnit.SECONDS.toMillis(l("confirmSec", TimeUnit.MILLISECONDS.toSeconds(d.confirmMs))));
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

    /** One side's fee on a fill's mesos, rounded down (each side pays it). */
    public long fee(long mesos) {
        return feeBps == 0 ? 0 : Math.multiplyExact(mesos, feeBps) / 10_000;
    }

    /** What a buy order takes from the wallet: the hold at its limit plus the reserve for the buyer's fee. */
    public long buyTotal(long nx, long price) {
        long cost = OrderBook.cost(nx, price);
        return Math.addExact(cost, fee(cost));
    }
}
