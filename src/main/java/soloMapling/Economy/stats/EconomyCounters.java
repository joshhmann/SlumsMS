package soloMapling.Economy.stats;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight in-memory counters for economy flows (sources, sinks, transfers, special currencies).
 * Updates are O(1) thread-safe atomic operations (using {@link LongAdder}) with zero lock contention
 * and zero DB writes on event paths.
 */
public final class EconomyCounters {

    // Faucets / Sources
    private static final LongAdder mobMesoDropsHuman = new LongAdder();
    private static final LongAdder mobMesoDropsBot = new LongAdder();
    private static final LongAdder npcSellMesos = new LongAdder();
    private static final LongAdder questRewardMesos = new LongAdder();

    // Sinks / Drains
    private static final LongAdder npcBuyMesos = new LongAdder();
    private static final LongAdder jobMasterFees = new LongAdder();
    private static final LongAdder upkeepMesos = new LongAdder();
    private static final LongAdder fmTaxMesos = new LongAdder();

    // Transfers
    private static final LongAdder tradeMesos = new LongAdder();
    private static final LongAdder fmSaleMesos = new LongAdder();

    // Special Currencies
    private static final LongAdder mapleLeafGained = new LongAdder();
    private static final LongAdder mapleLeafSpent = new LongAdder();
    private static final LongAdder nxEarned = new LongAdder();
    private static final LongAdder nxSpent = new LongAdder();

    private EconomyCounters() {
    }

    // ---- Event Recording Hooks (O(1)) ----

    public static void recordMobMesoDrop(boolean isBot, long amount) {
        if (amount <= 0) return;
        if (isBot) {
            mobMesoDropsBot.add(amount);
        } else {
            mobMesoDropsHuman.add(amount);
        }
    }

    /**
     * A meso pickup off the floor. Only a mob/reactor drop is new money: a player-dropped meso bag was already
     * counted when it was earned, so it is a transfer and is not counted here.
     */
    public static void recordMesoPickup(boolean playerDrop, boolean isBot, long amount) {
        if (!playerDrop) {
            recordMobMesoDrop(isBot, amount);
        }
    }

    public static void recordNpcSell(long amount) {
        if (amount > 0) {
            npcSellMesos.add(amount);
        }
    }

    public static void recordQuestReward(long amount) {
        if (amount > 0) {
            questRewardMesos.add(amount);
        }
    }

    public static void recordNpcBuy(long amount) {
        if (amount > 0) {
            npcBuyMesos.add(amount);
        }
    }

    public static void recordJobMasterFee(long amount) {
        if (amount > 0) {
            jobMasterFees.add(amount);
        }
    }

    public static void recordUpkeep(long amount) {
        if (amount > 0) {
            upkeepMesos.add(amount);
        }
    }

    public static void recordFmTax(long amount) {
        if (amount > 0) {
            fmTaxMesos.add(amount);
        }
    }

    public static void recordTrade(long amount) {
        if (amount > 0) {
            tradeMesos.add(amount);
        }
    }

    public static void recordFmSale(long amount) {
        if (amount > 0) {
            fmSaleMesos.add(amount);
        }
    }

    public static void recordMapleLeafGained(long amount) {
        if (amount > 0) {
            mapleLeafGained.add(amount);
        }
    }

    public static void recordMapleLeafSpent(long amount) {
        if (amount > 0) {
            mapleLeafSpent.add(amount);
        }
    }

    public static void recordNxEarned(long amount) {
        if (amount > 0) {
            nxEarned.add(amount);
        }
    }

    public static void recordNxSpent(long amount) {
        if (amount > 0) {
            nxSpent.add(amount);
        }
    }

    // ---- Snapshot & Reset ----

    public record Snapshot(
            long mobMesoDropsHuman,
            long mobMesoDropsBot,
            long npcSellMesos,
            long questRewardMesos,
            long npcBuyMesos,
            long jobMasterFees,
            long upkeepMesos,
            long fmTaxMesos,
            long tradeMesos,
            long fmSaleMesos,
            long mapleLeafGained,
            long mapleLeafSpent,
            long nxEarned,
            long nxSpent
    ) {
        public long totalMobMesoDrops() {
            return mobMesoDropsHuman + mobMesoDropsBot;
        }

        public long totalSources() {
            return mobMesoDropsHuman + mobMesoDropsBot + npcSellMesos + questRewardMesos;
        }

        public long totalSinks() {
            return npcBuyMesos + jobMasterFees + upkeepMesos + fmTaxMesos;
        }

        public long netMesoDelta() {
            return totalSources() - totalSinks();
        }

        public long mapleLeafNet() {
            return mapleLeafGained - mapleLeafSpent;
        }

        public long nxNet() {
            return nxEarned - nxSpent;
        }
    }

    /** Reads the current counts without resetting them (on-demand views must not eat the nightly window). */
    public static Snapshot peek() {
        return new Snapshot(
                mobMesoDropsHuman.sum(), mobMesoDropsBot.sum(), npcSellMesos.sum(), questRewardMesos.sum(),
                npcBuyMesos.sum(), jobMasterFees.sum(), upkeepMesos.sum(), fmTaxMesos.sum(),
                tradeMesos.sum(), fmSaleMesos.sum(),
                mapleLeafGained.sum(), mapleLeafSpent.sum(), nxEarned.sum(), nxSpent.sum()
        );
    }

    /**
     * Atomically reads the current counts and resets them to zero.
     */
    public static synchronized Snapshot snapshotAndReset() {
        long dropsHuman = mobMesoDropsHuman.sumThenReset();
        long dropsBot = mobMesoDropsBot.sumThenReset();
        long sells = npcSellMesos.sumThenReset();
        long quests = questRewardMesos.sumThenReset();
        long buys = npcBuyMesos.sumThenReset();
        long jobFees = jobMasterFees.sumThenReset();
        long upkeep = upkeepMesos.sumThenReset();
        long tax = fmTaxMesos.sumThenReset();
        long trades = tradeMesos.sumThenReset();
        long fmSales = fmSaleMesos.sumThenReset();
        long leafGain = mapleLeafGained.sumThenReset();
        long leafSpend = mapleLeafSpent.sumThenReset();
        long nxGain = nxEarned.sumThenReset();
        long nxSpend = nxSpent.sumThenReset();

        return new Snapshot(
                dropsHuman, dropsBot, sells, quests,
                buys, jobFees, upkeep, tax,
                trades, fmSales,
                leafGain, leafSpend, nxGain, nxSpend
        );
    }

    /** Test seam: resets all counters to 0. */
    public static synchronized void resetForTest() {
        mobMesoDropsHuman.reset();
        mobMesoDropsBot.reset();
        npcSellMesos.reset();
        questRewardMesos.reset();
        npcBuyMesos.reset();
        jobMasterFees.reset();
        upkeepMesos.reset();
        fmTaxMesos.reset();
        tradeMesos.reset();
        fmSaleMesos.reset();
        mapleLeafGained.reset();
        mapleLeafSpent.reset();
        nxEarned.reset();
        nxSpent.reset();
    }
}
