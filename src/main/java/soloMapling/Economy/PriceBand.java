package soloMapling.Economy;

/**
 * 1.5.0 rc3, owner decision 3: the price band a bot trades inside, and its haggling, enforced here in code. The chat
 * brain only words what this class decided; nothing a player types can move a bot outside the band.
 * <ul>
 *   <li><b>A bot buying</b> from a player pays between {@code bands.botBuysLow} (0.70) and {@code bands.botBuysHigh}
 *   (0.95) of the item's fair value (the board's 7-day median, {@link PriceBook#fairValue}).</li>
 *   <li><b>A bot selling</b> to a player takes between {@code bands.botSellsLow} (0.95) and
 *   {@code bands.botSellsHigh} (1.25) of it.</li>
 *   <li><b>A friend</b> (affinity) gets {@code bands.friendBonus} (0.05) of fair value on the edges bots open from (a
 *   buying bot's low edge, a selling bot's high edge), never on the edges they settle at: a bot's buy ceiling is always
 *   at or under every bot's sell floor, so buying from one friend bot and selling to another cannot turn a profit
 *   (1.5.0 rc3 review). A bot never pays more than fair value, and never sells under half of it.</li>
 *   <li>At most {@code bands.maxRounds} (4) counters, then the bot names its last price.</li>
 * </ul>
 * Invariant (tested): a price this class returns for a bot to pay is never above the band's high edge, and a price
 * for a bot to sell at is never below its low edge.
 */
public final class PriceBand {

    public enum Side { BOT_BUYS, BOT_SELLS }

    public record Band(Side side, long fair, long low, long high) {
        public boolean contains(long price) {
            return price >= low && price <= high;
        }
    }

    public enum Kind { ACCEPT, COUNTER, FINAL }

    public record Decision(Kind kind, long price) {
    }

    private PriceBand() {
    }

    public static Band of(Side side, long fair, boolean friend) {
        long f = Math.max(1, fair);
        double bonus = friend ? Math.max(0, EconomyConfig.getDouble("bands.friendBonus", 0.05)) : 0;
        // every bot's sell floor; a bot never pays more than it (or any other bot) would sell the item for, so
        // buying from one bot and selling to another never pays (1.5.0 rc3 review)
        double sellFloor = Math.max(0.5, EconomyConfig.getDouble("bands.botSellsLow", 0.95));
        double lo, hi;
        if (side == Side.BOT_BUYS) {
            hi = Math.min(Math.min(1.0, sellFloor), EconomyConfig.getDouble("bands.botBuysHigh", 0.95));
            lo = Math.min(hi, EconomyConfig.getDouble("bands.botBuysLow", 0.70) + bonus);   // a friend: a better opening
        } else {
            lo = sellFloor;
            hi = Math.max(lo, EconomyConfig.getDouble("bands.botSellsHigh", 1.25) - bonus);  // a friend: a lower opening
        }
        long low = Math.max(1, Math.round(f * Math.min(lo, hi)));
        long high = Math.max(low, Math.round(f * Math.max(lo, hi)));
        return new Band(side, f, low, high);
    }

    /** The bot's first price: a buyer opens low in its band, a seller high. */
    public static long opening(Band b) {
        long span = b.high() - b.low();
        long p = b.side() == Side.BOT_BUYS ? b.low() + span * 3 / 10 : b.high() - span * 2 / 10;
        return clamp(b, nice(p, b.side() == Side.BOT_BUYS));
    }

    /**
     * The bot answers a player's number. {@code current}: the price the bot stands at now; {@code theirs}: the
     * player's price (what they ask when the bot buys, what they bid when it sells); {@code round}: counters so far.
     */
    public static Decision respond(Band b, long current, long theirs, int round) {
        int maxRounds = Math.max(1, EconomyConfig.getInt("bands.maxRounds", 4));
        boolean last = round + 1 >= maxRounds;
        if (b.side() == Side.BOT_BUYS) {
            // the player asks `theirs` for their item; the bot pays at most b.high
            if (theirs <= current) {
                return new Decision(Kind.ACCEPT, Math.max(1, theirs));
            }
            if (theirs <= b.high()) {
                long halfway = current + (b.high() - current) / 2;
                if (theirs <= halfway || last) {
                    return new Decision(Kind.ACCEPT, theirs);
                }
                return new Decision(Kind.COUNTER, clamp(b, nice((current + theirs) / 2, true)));
            }
            if (last || current >= b.high()) {
                return new Decision(Kind.FINAL, b.high());
            }
            return new Decision(Kind.COUNTER, clamp(b, nice(current + (b.high() - current) / 2, true)));
        }
        // the player bids `theirs` for the bot's item; the bot takes at least b.low
        if (theirs >= current) {
            return new Decision(Kind.ACCEPT, clamp(b, current));
        }
        if (theirs >= b.low()) {
            long halfway = current - (current - b.low()) / 2;
            if (theirs >= halfway || last) {
                return new Decision(Kind.ACCEPT, theirs);
            }
            return new Decision(Kind.COUNTER, clamp(b, nice((current + theirs) / 2, false)));
        }
        if (last || current <= b.low()) {
            return new Decision(Kind.FINAL, b.low());
        }
        return new Decision(Kind.COUNTER, clamp(b, nice(current - (current - b.low()) / 2, false)));
    }

    static long clamp(Band b, long p) {
        return Math.max(b.low(), Math.min(b.high(), p));
    }

    /** A price as a player writes it: 3 significant digits, rounded down when the bot pays, up when it sells. */
    static long nice(long p, boolean down) {
        if (p < 1000) {
            return Math.max(1, p);
        }
        long unit = 1;
        long v = p;
        while (v >= 1000) {
            v /= 10;
            unit *= 10;
        }
        long q = down ? (p / unit) * unit : ((p + unit - 1) / unit) * unit;
        return Math.max(1, q);
    }
}
