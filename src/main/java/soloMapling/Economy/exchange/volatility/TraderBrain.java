package soloMapling.Economy.exchange.volatility;

import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 1.6.1 volatility: what one trader bot does on one ticker when its turn comes. Pure (no game, no exchange): the
 * runtime ({@link MarketTraders}) builds the {@link View} and the bot's {@link Holdings}, and places what this returns
 * through the exchange's normal limits. Five archetypes; momentum and panic herd, the whale moves the book several
 * levels at once, value and the maker pull it back, so prices overshoot both ways. Volatility is the point: the only
 * guard ({@link #guard}) is against obvious bugs (no price 0, nothing beyond the bot's own holdings, prices within 10x
 * of the reference in one order).
 */
public final class TraderBrain {

    private TraderBrain() {
    }

    public enum Archetype {
        /** Buys what rises, sells what falls (last hour's move plus the events and rumours it knows). */
        MOMENTUM,
        /** Dumps most of its stack at a discount after a sharp drop; otherwise restocks quietly. */
        PANIC,
        /** Rarely acts, then one big order that walks several levels of the book. */
        WHALE,
        /** Buys under its fair value, sells well above it (leaves: near the shop's 500k). */
        VALUE,
        /** Quotes both sides around the middle with a spread, re-prices stale quotes. */
        MAKER
    }

    /**
     * What the bot sees of one ticker. Prices in mesos per item; 0 = none.
     *
     * @param reference   the last trade, else the book's middle, else the ticker's fallback price (never 0)
     * @param hourAgo     the last trade an hour or more ago (0: none)
     * @param high2h      the highest trade of the last 2 hours (0: none)
     * @param fair        the bot's notion of fair value
     * @param pressure    where the events and rumours the bot knows push the price (+ up, - down; about -2..2)
     */
    public record View(String ticker, long reference, long bestBid, long bestAsk, long hourAgo, long high2h,
                       long fair, double pressure, long minPrice, long maxPrice, long maxQty, int feeBps) {
        long mid() {
            return bestBid > 0 && bestAsk > 0 ? (bestBid + bestAsk) / 2 : reference;
        }

        /** The last hour's move as a fraction (0 without history). */
        double trend() {
            return hourAgo > 0 ? (double) (reference - hourAgo) / hourAgo : 0;
        }
    }

    /** One of the bot's resting orders on this ticker. */
    public record Open(long id, Side side, long price, long qtyLeft, long placedAt) {
    }

    /** What the bot holds: its own mesos and tradeable items of this ticker, its open orders, places left today. */
    public record Holdings(long mesos, long items, List<Open> open, int placesLeft, int openLimit) {
    }

    public sealed interface Action permits Place, Cancel {
    }

    public record Place(Side side, long price, long qty) implements Action {
    }

    public record Cancel(long orderId) implements Action {
    }

    static final long STALE_MS = 30 * 60_000L;

    /** The actions for one turn, already through {@link #guard}. */
    public static List<Action> decide(Archetype a, View v, Holdings h, long now, Random rng) {
        List<Action> out = new ArrayList<>();
        if (v.reference() <= 0) {
            return out;
        }
        switch (a) {
            case MOMENTUM -> momentum(v, h, now, rng, out);
            case PANIC -> panic(v, h, now, rng, out);
            case WHALE -> whale(v, h, rng, out);
            case VALUE -> value(v, h, now, rng, out);
            case MAKER -> maker(v, h, now, rng, out);
        }
        return guard(out, v, h);
    }

    private static void momentum(View v, Holdings h, long now, Random rng, List<Action> out) {
        cancelStale(h, now, STALE_MS, out);
        double signal = v.trend() + 0.03 * v.pressure();
        if (signal > 0.02) {
            double heat = Math.min(2, signal * 5);
            long price = up(v.bestAsk() > 0 ? v.bestAsk() : v.reference(), 0.02 + 0.04 * rng.nextDouble());
            out.add(new Place(Side.BUY, price, afford(h.mesos() * (0.15 + 0.2 * rng.nextDouble()) * (1 + heat),
                    price, v)));
        } else if (signal < -0.02 && h.items() > 0) {
            long price = down(v.bestBid() > 0 ? v.bestBid() : v.reference(), 0.02 + 0.04 * rng.nextDouble());
            out.add(new Place(Side.SELL, price, part(h.items(), 0.3 + 0.4 * rng.nextDouble())));
        } else if (rng.nextInt(5) == 0) {
            // drifting: a small passive order leaning with the last move
            if (signal >= 0) {
                long price = down(v.mid(), 0.01 * rng.nextDouble());
                out.add(new Place(Side.BUY, price, afford(h.mesos() * 0.05, price, v)));
            } else if (h.items() > 0) {
                out.add(new Place(Side.SELL, up(v.mid(), 0.01 * rng.nextDouble()), part(h.items(), 0.1)));
            }
        }
    }

    private static void panic(View v, Holdings h, long now, Random rng, List<Action> out) {
        cancelStale(h, now, STALE_MS, out);
        double drop = v.high2h() > 0 ? (double) (v.high2h() - v.reference()) / v.high2h() : 0;
        boolean scared = drop > 0.08 || (drop > 0.04 && v.pressure() < -0.5) || v.pressure() < -1.5;
        if (scared && h.items() > 0) {
            long price = down(v.bestBid() > 0 ? v.bestBid() : v.reference(), 0.05 + 0.10 * rng.nextDouble());
            out.add(new Place(Side.SELL, price, part(h.items(), 0.6 + 0.4 * rng.nextDouble())));
        } else if (!scared && v.reference() <= v.fair() * 1.05 && rng.nextInt(10) < 3) {
            long price = down(v.mid(), 0.03 * rng.nextDouble());
            out.add(new Place(Side.BUY, price, afford(h.mesos() * 0.15, price, v)));
        }
    }

    private static void whale(View v, Holdings h, Random rng, List<Action> out) {
        if (rng.nextInt(100) >= 12) {
            return;
        }
        double lean = v.trend() + 0.05 * v.pressure();
        boolean buy = lean > 0.01 || (Math.abs(lean) <= 0.01 && rng.nextBoolean()) || h.items() <= 0;
        if (buy) {
            long price = up(v.bestAsk() > 0 ? v.bestAsk() : v.reference(), 0.10 + 0.15 * rng.nextDouble());
            out.add(new Place(Side.BUY, price, afford(h.mesos() * (0.5 + 0.3 * rng.nextDouble()), price, v)));
        } else {
            long price = down(v.bestBid() > 0 ? v.bestBid() : v.reference(), 0.10 + 0.15 * rng.nextDouble());
            out.add(new Place(Side.SELL, price, part(h.items(), 0.6 + 0.4 * rng.nextDouble())));
        }
    }

    private static void value(View v, Holdings h, long now, Random rng, List<Action> out) {
        cancelStale(h, now, 4 * STALE_MS, out);
        long fair = v.fair();
        boolean hasBuy = h.open().stream().anyMatch(o -> o.side() == Side.BUY);
        boolean hasSell = h.open().stream().anyMatch(o -> o.side() == Side.SELL);
        if (v.bestAsk() > 0 && v.bestAsk() < fair * 0.92) {
            long price = up(v.bestAsk(), 0.01);
            out.add(new Place(Side.BUY, price, afford(h.mesos() * 0.3, price, v)));
        } else if (!hasBuy && rng.nextInt(2) == 0) {
            long price = down(fair, 0.10 + 0.05 * rng.nextDouble());
            out.add(new Place(Side.BUY, price, afford(h.mesos() * 0.2, price, v)));
        }
        if (h.items() > 0) {
            if (v.bestBid() > fair * 1.35) {
                out.add(new Place(Side.SELL, v.bestBid(), part(h.items(), 0.5)));
            } else if (!hasSell && rng.nextInt(2) == 0) {
                out.add(new Place(Side.SELL, up(fair, 0.4 + 0.2 * rng.nextDouble()), part(h.items(), 0.3)));
            }
        }
    }

    private static void maker(View v, Holdings h, long now, Random rng, List<Action> out) {
        long mid = v.mid();
        boolean hasBuy = false, hasSell = false;
        for (Open o : h.open()) {
            boolean stale = now - o.placedAt() > 20 * 60_000L || Math.abs(o.price() - mid) > mid * 0.10;
            if (stale) {
                out.add(new Cancel(o.id()));
            } else if (o.side() == Side.BUY) {
                hasBuy = true;
            } else {
                hasSell = true;
            }
        }
        double spread = 0.02 + 0.04 * rng.nextDouble() + Math.min(0.08, Math.abs(v.trend()) / 2);
        // inventory skew: few items -> quote higher (wants to buy), many -> lower
        double skew = h.items() < 5 ? 0.01 : h.items() > 50 ? -0.01 : 0;
        if (!hasBuy) {
            long price = Math.round(mid * (1 - spread + skew));
            out.add(new Place(Side.BUY, price, afford(h.mesos() * 0.1, price, v)));
        }
        if (!hasSell && h.items() > 0) {
            out.add(new Place(Side.SELL, Math.round(mid * (1 + spread + skew)), part(h.items(), 0.2 + 0.2
                    * rng.nextDouble())));
        }
    }

    private static void cancelStale(Holdings h, long now, long age, List<Action> out) {
        for (Open o : h.open()) {
            if (now - o.placedAt() > age) {
                out.add(new Cancel(o.id()));
            }
        }
    }

    // ---------------------------------------------------------------- the guard (bugs only, not volatility)

    /**
     * Keeps only sane actions: cancels of the bot's own orders; places with a price inside the ticker's range and
     * within 10x of the reference (others are dropped, not clamped), a positive quantity within the ticker's largest order, sells covered by the
     * items the bot holds (all its sells this turn together), buys covered by its mesos with the fee (together), and
     * no more places than its open slots and day's count allow. Prices are rounded to a tidy number.
     */
    public static List<Action> guard(List<Action> in, View v, Holdings h) {
        List<Action> out = new ArrayList<>();
        java.util.Set<Long> mine = new java.util.HashSet<>();
        for (Open o : h.open()) {
            mine.add(o.id());
        }
        int cancels = 0;
        for (Action a : in) {
            if (a instanceof Cancel c && mine.remove(c.orderId())) {
                out.add(c);
                cancels++;
            }
        }
        int slots = Math.min(h.placesLeft(), h.openLimit() - (h.open().size() - cancels));
        long items = h.items(), mesos = h.mesos();
        long lo = Math.max(v.minPrice(), Math.max(1, v.reference() / 10));
        long hi = Math.min(v.maxPrice(), Math.multiplyHigh(v.reference(), 10) == 0 ? v.reference() * 10 : v.maxPrice());
        for (Action a : in) {
            if (!(a instanceof Place p) || slots <= 0) {
                continue;
            }
            if (p.price() < lo || p.price() > hi) {
                continue; // price 0, or 10x off the reference in one order: a bug, not a trade
            }
            long price = tidy(p.price());
            long qty = Math.min(p.qty(), v.maxQty());
            if (price <= 0 || qty <= 0) {
                continue;
            }
            if (p.side() == Side.SELL) {
                qty = Math.min(qty, items);
                if (qty <= 0) {
                    continue;
                }
                items -= qty;
            } else {
                qty = Math.min(qty, maxAfford(mesos, price, v.feeBps()));
                if (qty <= 0) {
                    continue;
                }
                mesos -= total(qty, price, v.feeBps());
            }
            out.add(new Place(p.side(), price, qty));
            slots--;
        }
        return out;
    }

    /** What a buy of {@code qty} at {@code price} takes: the price plus the buyer's fee reserve. */
    static long total(long qty, long price, int feeBps) {
        long cost = Math.multiplyExact(qty, price);
        return cost + Math.multiplyExact(cost, feeBps) / 10_000;
    }

    static long maxAfford(long mesos, long price, int feeBps) {
        if (price <= 0 || mesos <= 0) {
            return 0;
        }
        long q = mesos / price;
        while (q > 0 && total(q, price, feeBps) > Math.min(mesos, Integer.MAX_VALUE)) {
            q--;
        }
        return q;
    }

    private static long afford(double budget, long price, View v) {
        return price <= 0 ? 0 : Math.min(v.maxQty(), (long) Math.floor(budget / (price * (1 + v.feeBps() / 10_000.0))));
    }

    private static long part(long items, double frac) {
        return items <= 0 ? 0 : Math.max(1, Math.round(items * Math.min(1, frac)));
    }

    static long up(long p, double frac) {
        return Math.max(p + 1, Math.round(p * (1 + frac)));
    }

    static long down(long p, double frac) {
        return Math.max(1, Math.round(p * (1 - frac)));
    }

    /** Rounds a price to 3 significant figures (600,123 -> 600,000; 6,123 -> 6,120), never to 0. */
    static long tidy(long p) {
        if (p < 1000) {
            return Math.max(1, p);
        }
        long step = 1;
        while (p / step >= 1000) {
            step *= 10;
        }
        return Math.max(step, Math.round((double) p / step) * step);
    }
}
