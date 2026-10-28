package soloMapling.Economy.exchange;

import java.util.List;

/**
 * Ticket 113: the exchange's official NX rate, mesos per 1,000 NX: the volume-weighted average price of the fills in
 * the last window, <b>leaving out every fill a regular took part in</b> (owner 2026-10-28: regulars are excluded from the
 * official rate), so only prices players agreed between themselves count. Null under the minimum number of counted
 * fills. Pure. It will feed {@code NxRate} (the price-board indicator) later; nothing reads it there yet.
 */
public final class OfficialRate {

    private OfficialRate() {
    }

    /** VWAP over fills with {@code now - window < at <= now}, or null with fewer than {@code minFills} counted. */
    public static Long vwap(List<ExchangeStore.FillRow> fills, long now, long windowMs, int minFills) {
        long nx = 0;
        long mesos = 0;
        int n = 0;
        for (ExchangeStore.FillRow f : fills) {
            if (f.at() > now || f.at() <= now - windowMs || f.buyerRegular() || f.sellerRegular() || f.nx() <= 0) {
                continue;
            }
            nx = Math.addExact(nx, f.nx());
            // price x nx, not the rounded mesos, so the rate is exact
            mesos = Math.addExact(mesos, Math.multiplyExact(f.price(), f.nx()));
            n++;
        }
        if (n < minFills || nx == 0) {
            return null;
        }
        return Math.round(mesos / (double) nx);
    }
}
