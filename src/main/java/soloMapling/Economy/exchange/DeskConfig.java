package soloMapling.Economy.exchange;

/**
 * Ticket 162: the NX desk's knobs. JVM properties only (config.yaml is strict), the owner's set B of 2026-11-01
 * 14:31 UTC as the defaults: a 2% spread each side, 7 bps of drift per 1% of the float turned over, at most 50 bps a
 * hour, at most 20% a day, no fee on top of the spread, whole NX from 100.
 * <pre>
 *   -Dslums.exchange.desk=true                 the switch (default off: the NX order book runs as it does today)
 *   -Dslums.exchange.desk.spreadBps=200        the spread each side of R in basis points (the desk's only charge)
 *   -Dslums.exchange.desk.driftBpsPerPct=7     drift bps per 1% of the NX float the desk net moved in the hour
 *   -Dslums.exchange.desk.maxStepBps=50        the most R may move in one hourly step
 *   -Dslums.exchange.desk.dayCapPct=20         the most R may move in a UTC day, either way, from the day's first R
 *   -Dslums.exchange.desk.feeBps=0             a fee on a desk fill's mesos (the owner's set B: none, the spread is
 *                                              the charge); both sides pay it when it is not 0
 *   -Dslums.exchange.desk.minQty=100           whole NX: the least a desk fill trades, and the most that is not
 * </pre>
 * The hard window ({@link NxGuard#NX_LOWEST}..{@link NxGuard#NX_HIGHEST}) is not a knob: R and every quote stay
 * inside it, whatever these say.
 */
public record DeskConfig(boolean enabled, int spreadBps, int driftBpsPerPct, int maxStepBps, int dayCapPct, int feeBps,
                         long minQty) {

    public static final String ENABLED = "slums.exchange.desk";

    public DeskConfig {
        spreadBps = Math.max(0, Math.min(5_000, spreadBps));
        driftBpsPerPct = Math.max(0, driftBpsPerPct);
        maxStepBps = Math.max(0, maxStepBps);
        dayCapPct = Math.max(0, Math.min(100, dayCapPct));
        feeBps = Math.max(0, Math.min(5_000, feeBps));
        minQty = Math.max(1, minQty);
    }

    /** The owner's set B (2026-11-01): 2% a side, 7 bps per 1% of float, 50 bps an hour, 20% a day, no fee, 100 NX. */
    public static DeskConfig defaults() {
        return new DeskConfig(false, 200, 7, 50, 20, 0, 100);
    }

    /** The same numbers, switched on (tests, the QA staging). */
    public DeskConfig on() {
        return new DeskConfig(true, spreadBps, driftBpsPerPct, maxStepBps, dayCapPct, feeBps, minQty);
    }

    /** The same numbers with another spread (tests). */
    public DeskConfig withSpread(int bps) {
        return new DeskConfig(enabled, bps, driftBpsPerPct, maxStepBps, dayCapPct, feeBps, minQty);
    }

    public static boolean switchedOn() {
        return Boolean.getBoolean(ENABLED);
    }

    /** From the JVM properties, each falling back to {@link #defaults()}. */
    public static DeskConfig fromSystem() {
        DeskConfig d = defaults();
        return new DeskConfig(switchedOn(), (int) l("spreadBps", d.spreadBps()),
                (int) l("driftBpsPerPct", d.driftBpsPerPct()), (int) l("maxStepBps", d.maxStepBps()),
                (int) l("dayCapPct", d.dayCapPct()), (int) l("feeBps", d.feeBps()), l("minQty", d.minQty()));
    }

    /** What the house pays for 1,000 NX: {@code floor(R * (1 - spread))}, inside the hard window. */
    public long buy(long mid) {
        return NxGuard.clamp(Math.floorDiv(Math.multiplyExact(mid, 10_000 - spreadBps), 10_000));
    }

    /** What the house charges for 1,000 NX: {@code ceil(R * (1 + spread))}, inside the hard window. */
    public long sell(long mid) {
        long raw = Math.multiplyExact(mid, 10_000 + spreadBps);
        return NxGuard.clamp(-Math.floorDiv(-raw, 10_000));
    }

    /** One side's fee on a fill's mesos, rounded down (0 under the owner's set B). */
    public long fee(long mesos) {
        return feeBps == 0 ? 0 : Math.multiplyExact(mesos, feeBps) / 10_000;
    }

    private static long l(String key, long dflt) {
        String v = System.getProperty(ENABLED.replace("desk", "desk." + key));
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
