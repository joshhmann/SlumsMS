package soloMapling.Economy.stats;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight in-memory counters for economy flows (sources, sinks, transfers, special currencies).
 * Updates are O(1) thread-safe atomic operations (using {@link LongAdder}) with zero lock contention
 * and zero DB writes on event paths. {@link EconomyCounterPersistence} copies them to the database every few minutes
 * (off the game thread) and back at boot, so a restart keeps the day's totals.
 */
public final class EconomyCounters {

    /** One counter. {@link #dbKey} is its row key in {@code economycounters} (migration 032): never rename one. */
    public enum Key {
        // Faucets / Sources
        MOB_DROPS_HUMAN("mob_drops_human"),
        MOB_DROPS_BOT("mob_drops_bot"),
        NPC_SELL("npc_sell"),
        QUEST_REWARD("quest_reward"),
        // Sinks / Drains
        NPC_BUY("npc_buy"),
        JOB_MASTER_FEE("job_master_fee"),
        NPC_FEE("npc_fee"),
        UPKEEP("upkeep"),
        FM_TAX("fm_tax"),
        // Transfers
        TRADE("trade"),
        FM_SALE("fm_sale"),
        // Special Currencies
        MAPLE_LEAF_GAINED("maple_leaf_gained"),
        MAPLE_LEAF_SPENT("maple_leaf_spent"),
        NX_EARNED("nx_earned"),
        NX_SPENT("nx_spent");

        public final String dbKey;

        Key(String dbKey) {
            this.dbKey = dbKey;
        }

        public static Key byDbKey(String dbKey) {
            for (Key k : values()) {
                if (k.dbKey.equals(dbKey)) {
                    return k;
                }
            }
            return null;
        }
    }

    private static final LongAdder[] ADDERS = new LongAdder[Key.values().length];

    static {
        for (int i = 0; i < ADDERS.length; i++) {
            ADDERS[i] = new LongAdder();
        }
    }

    private EconomyCounters() {
    }

    private static void add(Key key, long amount) {
        if (amount > 0) {
            ADDERS[key.ordinal()].add(amount);
        }
    }

    // ---- Event Recording Hooks (O(1)) ----

    public static void recordMobMesoDrop(boolean isBot, long amount) {
        add(isBot ? Key.MOB_DROPS_BOT : Key.MOB_DROPS_HUMAN, amount);
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
        add(Key.NPC_SELL, amount);
    }

    public static void recordQuestReward(long amount) {
        add(Key.QUEST_REWARD, amount);
    }

    /**
     * A meso change made by an NPC, quest or item script: a gain is a reward (a source), a loss is a fee (taxis,
     * Spinel, job-advance fees: mesos leaving the game, a sink).
     */
    public static void recordScriptMeso(long delta) {
        if (delta > 0) {
            add(Key.QUEST_REWARD, delta);
        } else if (delta < 0) {
            add(Key.NPC_FEE, -delta);
        }
    }

    public static void recordNpcFee(long amount) {
        add(Key.NPC_FEE, amount);
    }

    public static void recordNpcBuy(long amount) {
        add(Key.NPC_BUY, amount);
    }

    public static void recordJobMasterFee(long amount) {
        add(Key.JOB_MASTER_FEE, amount);
    }

    public static void recordUpkeep(long amount) {
        add(Key.UPKEEP, amount);
    }

    public static void recordFmTax(long amount) {
        add(Key.FM_TAX, amount);
    }

    public static void recordTrade(long amount) {
        add(Key.TRADE, amount);
    }

    public static void recordFmSale(long amount) {
        add(Key.FM_SALE, amount);
    }

    public static void recordMapleLeafGained(long amount) {
        add(Key.MAPLE_LEAF_GAINED, amount);
    }

    public static void recordMapleLeafSpent(long amount) {
        add(Key.MAPLE_LEAF_SPENT, amount);
    }

    public static void recordNxEarned(long amount) {
        add(Key.NX_EARNED, amount);
    }

    public static void recordNxSpent(long amount) {
        add(Key.NX_SPENT, amount);
    }

    // ---- Snapshot & Reset ----

    public record Snapshot(
            long mobMesoDropsHuman,
            long mobMesoDropsBot,
            long npcSellMesos,
            long questRewardMesos,
            long npcBuyMesos,
            long jobMasterFees,
            long npcFeeMesos,
            long upkeepMesos,
            long fmTaxMesos,
            long tradeMesos,
            long fmSaleMesos,
            long mapleLeafGained,
            long mapleLeafSpent,
            long nxEarned,
            long nxSpent
    ) {
        static Snapshot of(long[] v) {
            return new Snapshot(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12],
                    v[13], v[14]);
        }

        /** Every counter by key, in {@link Key} order (what the persistence writes). */
        public Map<Key, Long> byKey() {
            long[] v = {mobMesoDropsHuman, mobMesoDropsBot, npcSellMesos, questRewardMesos, npcBuyMesos,
                    jobMasterFees, npcFeeMesos, upkeepMesos, fmTaxMesos, tradeMesos, fmSaleMesos, mapleLeafGained,
                    mapleLeafSpent, nxEarned, nxSpent};
            Map<Key, Long> out = new EnumMap<>(Key.class);
            for (Key k : Key.values()) {
                out.put(k, v[k.ordinal()]);
            }
            return out;
        }

        public long totalMobMesoDrops() {
            return mobMesoDropsHuman + mobMesoDropsBot;
        }

        public long totalSources() {
            return mobMesoDropsHuman + mobMesoDropsBot + npcSellMesos + questRewardMesos;
        }

        public long totalSinks() {
            return npcBuyMesos + jobMasterFees + npcFeeMesos + upkeepMesos + fmTaxMesos;
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
        long[] v = new long[ADDERS.length];
        for (int i = 0; i < v.length; i++) {
            v[i] = ADDERS[i].sum();
        }
        return Snapshot.of(v);
    }

    /**
     * Atomically reads the current counts and resets them to zero.
     */
    public static synchronized Snapshot snapshotAndReset() {
        long[] v = new long[ADDERS.length];
        for (int i = 0; i < v.length; i++) {
            v[i] = ADDERS[i].sumThenReset();
        }
        return Snapshot.of(v);
    }

    /**
     * Adds stored totals back (at boot, from {@code economycounters}): the day's counts so far before the restart.
     * Unknown keys and non-positive values are skipped.
     */
    public static synchronized void restore(Map<String, Long> stored) {
        for (var e : stored.entrySet()) {
            Key k = Key.byDbKey(e.getKey());
            if (k != null && e.getValue() != null) {
                add(k, e.getValue());
            }
        }
    }

    /** Test seam: resets all counters to 0. */
    public static synchronized void resetForTest() {
        for (LongAdder a : ADDERS) {
            a.reset();
        }
    }
}
