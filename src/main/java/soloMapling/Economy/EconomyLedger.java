package soloMapling.Economy;

/**
 * Ticket 181 (market#F-07, "Better systems #9"): the mint ledger - the one place that counts value a process creates
 * rather than moves. A meter per unit and source per server day, kept by {@link EconomyMeters} like every other daily
 * number, under {@code mint:<unit>:<source>}.
 *
 * <p>It exists so an item that enters the world through a bot can never be invisible again: the selling merchant bot
 * generated a fresh item per stocked trade and nothing counted it. The ledger is instrumentation only - it changes no
 * price, rate, drop or cap.
 */
public final class EconomyLedger {

    /** An item created out of nothing (a merchant bot's generated stock, before ticket 181). */
    public static final String ITEM = "item";
    /** Mesos created out of nothing. */
    public static final String MESOS = "mesos";

    private static final String PREFIX = "mint:";

    private EconomyLedger() {
    }

    /** The meter this unit and source count under: {@code mint:item:merchant-bot}. */
    public static String meter(String unit, String source) {
        return PREFIX + unit + ":" + source;
    }

    /**
     * Counts one {@code unit} of value created today by {@code source}.
     *
     * @param unit   what was created ({@link #ITEM}, {@link #MESOS})
     * @param source who created it: a stable, greppable name such as {@code merchant-bot}
     */
    public static void minted(String unit, String source) {
        EconomyMeters.add(meter(unit, source), 1);
    }

    /** How many {@code unit}s {@code source} created today. */
    public static long mintedToday(String unit, String source) {
        return EconomyMeters.get(meter(unit, source));
    }
}
