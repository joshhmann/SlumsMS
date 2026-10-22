package server;

/**
 * 1.5.0 rc3 review: what a shop purchase costs in all. The old {@code (int) Math.min((float) price * quantity,
 * Integer.MAX_VALUE)} rounded big totals to a float (undercharging up to tens of mesos per million) and capped a
 * bulk buy at the most mesos a character can hold, so it cost less than it should. A total is now exact (long), and a
 * purchase over the most mesos a character can carry is refused.
 */
public final class ShopMath {

    /** The most mesos a character can hold, so the most one purchase can cost. */
    public static final long MAX_MESO = Integer.MAX_VALUE;

    private ShopMath() {
    }

    /** {@code unitPrice} x {@code quantity}, exactly; -1 when it is over {@link #MAX_MESO} or the input is negative. */
    public static long total(long unitPrice, long quantity) {
        if (unitPrice < 0 || quantity < 0) {
            return -1;
        }
        long total;
        try {
            total = Math.multiplyExact(unitPrice, quantity);
        } catch (ArithmeticException e) {
            return -1;
        }
        return total > MAX_MESO ? -1 : total;
    }
}
