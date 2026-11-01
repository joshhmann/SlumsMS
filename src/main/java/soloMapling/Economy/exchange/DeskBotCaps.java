package soloMapling.Economy.exchange;

import java.util.HashMap;
import java.util.Map;

import soloMapling.Economy.exchange.volatility.MarketConfig;

/**
 * 1.6.3 (b260, the owner's one exception for 1.6.3): how much NX a trader bot may put through the NX desk in one
 * hour, alone and all together.
 *
 * <p>The exchange is the keeper of the rate: every desk fill moves it by a step that scales with the fill against the
 * whole float, so the caps are what stops a thousand bots from walking the rate between two player trades. They count
 * both sides together (a bot that buys 1,000 and sells 1,000 used 2,000) in absolute clock hours, so an hour boundary
 * is the same for every bot and for a restart. The table is memory only: a restart forgives the hour, which is
 * harmless (the worst case is one extra hour of the default allowance) and keeps the ledger clear of rate-limit rows.
 *
 * <p>Default: 2,000 NX an hour for one bot (a bot that traded its whole purse of 5,000 would be one trade every
 * couple of hours, which is what a regular does) and 20,000 for the world (about 1.4% of TEST's 1.4m NX float, a
 * tenth of a percent of drift an hour: the desk's own step cap of 50 bps is never approached by the bots alone).
 */
public final class DeskBotCaps {

    private final long perBot;
    private final long world;
    private long hour = Long.MIN_VALUE;
    private long worldUsed;
    private final Map<Long, Long> used = new HashMap<>();

    public DeskBotCaps(long perBot, long world) {
        this.perBot = Math.max(0, perBot);
        this.world = Math.max(0, world);
    }

    /** The caps from the JVM ({@link MarketConfig#tradersNxPerBotHour()}, {@link MarketConfig#tradersNxWorldHour()}). */
    public static DeskBotCaps fromSystem() {
        return new DeskBotCaps(MarketConfig.tradersNxPerBotHour(), MarketConfig.tradersNxWorldHour());
    }

    /** What this bot may still trade this hour, in NX (both sides together), never more than the world has left. */
    public synchronized long left(long account, long now) {
        roll(now);
        long mine = used.getOrDefault(account, 0L);
        return Math.max(0, Math.min(perBot - mine, world - worldUsed));
    }

    /** What all the trader bots together may still trade this hour, in NX. */
    public synchronized long worldLeft(long now) {
        roll(now);
        return Math.max(0, world - worldUsed);
    }

    /** Books {@code nx} of desk volume against this bot and the world (only after a fill really happened). */
    public synchronized void count(long account, long now, long nx) {
        if (nx <= 0) {
            return;
        }
        roll(now);
        used.merge(account, nx, Long::sum);
        worldUsed += nx;
    }

    /**
     * Moves to the hour {@code now} falls in ({@link Desk#HOUR_MS} buckets since the epoch), forgetting the last one:
     * an hour later everyone's allowance is whole again. The clock going backwards (a system clock step) starts a
     * fresh hour rather than locking the bots out.
     */
    private void roll(long now) {
        long h = Math.floorDiv(now, Desk.HOUR_MS);
        if (h != hour) {
            hour = h;
            used.clear();
            worldUsed = 0;
        }
    }
}
