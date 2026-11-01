package soloMapling.Economy.exchange.volatility;

import server.life.MonsterDropEntry;
import server.life.MonsterGlobalDropEntry;
import server.life.MonsterInformationProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * b261: the scroll chances a mob and a map give a trader's off-screen finds, worked out once and kept. A trader's hunt
 * asks for them on every loot tick (thousands of bots on hundreds of maps), and working them out meant a mob build and
 * a walk of every {@code drop_data} row of every mob on the map, per scroll, per tick. The rows themselves, and a mob's
 * boss flag, do not change between drop-data reloads, so the summed chance per scroll slot is kept here; only what
 * changes per call - the world's drop rates and the map's mob count - is applied by the caller.
 * <p>
 * Rebuilt when {@link MonsterInformationProvider#dropDataVersion()} moves (a drop reload). Nothing it holds depends on
 * config: {@code slums.exchange.scrollFindRate} and the world's rates are read per call.
 */
final class ScrollDropChances {

    /** One mob: whether the boss drop rate applies to it, and its summed chance per scroll slot. */
    static final class Mob {
        final boolean boss;
        final double[] chance;

        private Mob(boolean boss, double[] chance) {
            this.boss = boss;
            this.chance = chance;
        }
    }

    /** One look: the mobs asked about, in that order, and the map's global chances. */
    static final class Look {
        final Mob[] mobs;
        final double[] global;

        private Look(Mob[] mobs, double[] global) {
            this.mobs = mobs;
            this.global = global;
        }
    }

    private final int[] slotItems;
    private final Map<Integer, Mob> mobs = new ConcurrentHashMap<>();
    private final Map<Integer, double[]> maps = new ConcurrentHashMap<>();
    private volatile long builtFrom = -1;

    /** @param slotItems the scroll item id of each slot ({@link MarketTraders#SCROLL_IDS} order) */
    ScrollDropChances(int[] slotItems) {
        this.slotItems = slotItems.clone();
    }

    /** How many slots this table answers for. */
    int slots() {
        return slotItems.length;
    }

    /** The chances for one tick on a map with these mobs, from the table in use. */
    Look look(int mapId, List<Integer> mobIds) {
        checkDropData();
        Mob[] found = new Mob[mobIds.size()];
        for (int i = 0; i < found.length; i++) {
            int mobId = mobIds.get(i);
            Mob cached = mobs.get(mobId);
            if (cached == null) {
                cached = buildMob(mobId);
                mobs.put(mobId, cached);
            }
            found[i] = cached;
        }

        double[] global = maps.get(mapId);
        if (global == null) {
            global = buildGlobal(mapId);
            maps.put(mapId, global);
        }
        return new Look(found, global);
    }

    /** Forgets everything: the next look is worked out again (tests, and a drop-data reload). */
    void clear() {
        mobs.clear();
        maps.clear();
        builtFrom = -1;
    }

    private void checkDropData() {
        long version = MonsterInformationProvider.dropDataVersion();
        if (version != builtFrom) {
            synchronized (this) {
                if (version != builtFrom) {
                    mobs.clear();
                    maps.clear();
                    builtFrom = version;
                }
            }
        }
    }

    private Mob buildMob(int mobId) {
        MonsterInformationProvider provider = MonsterInformationProvider.getInstance();
        boolean boss;
        try {
            // the provider keeps this answer itself: LifeFactory.getMonster(id).isBoss()
            boss = provider.isBoss(mobId);
        } catch (RuntimeException e) {
            boss = false;       // no mob data: the normal drop rate
        }
        double[] chance = new double[slotItems.length];
        for (MonsterDropEntry drop : provider.retrieveDrop(mobId)) {
            if (drop.chance <= 0 || drop.questid != 0) {
                continue;
            }
            int slot = slotOf(drop.itemId);
            if (slot >= 0) {
                chance[slot] += drop.chance;
            }
        }
        return new Mob(boss, chance);
    }

    private double[] buildGlobal(int mapId) {
        double[] chance = new double[slotItems.length];
        for (MonsterGlobalDropEntry drop : MonsterInformationProvider.getInstance().getRelevantGlobalDrops(mapId)) {
            if (drop.chance <= 0) {
                continue;
            }
            int slot = slotOf(drop.itemId);
            if (slot >= 0) {
                chance[slot] += drop.chance;
            }
        }
        return chance;
    }

    private int slotOf(int itemId) {
        for (int slot = 0; slot < slotItems.length; slot++) {
            if (slotItems[slot] == itemId) {
                return slot;
            }
        }
        return -1;
    }
}
