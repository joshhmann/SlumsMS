package soloMapling.site;

import java.util.Map;

/** Test access to {@link TradeRatings}' pending counts from other packages (the trade code's tests). */
public final class TradeRatingsTestAccess {
    private TradeRatingsTestAccess() {
    }

    public static Map<Integer, long[]> pending() {
        return TradeRatings.pendingForTest();
    }

    public static void reset() {
        TradeRatings.resetForTest(null);
    }
}
