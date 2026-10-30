package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import soloMapling.itemPool.EquipMetadataCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * Ticket 162 part 4: junk sorting and reserve rules for a bot's equip bag, on top of SaleRules (which this never
 * loosens: it only keeps more out of the town junk sale). Off unless -Dslums.junk.sort=true; then BotBagRoom's town
 * sale skips what this keeps.
 *
 * After nutnnut/Cosmic (GreenCatMS, https://github.com/nutnnut/Cosmic, AGPL-3.0, dev):
 *  - the self-reserve (BotEquipManager.selectOwnedItemsForSelfReserve, relevantStatsFor, paretoDominates,
 *    effectiveStatValue, SELF_RESERVE_TRACK_CAP, @ 2771d9ea1): per slot track (a weapon per weapon class), the own-
 *    class equips no other owned piece beats in every stat its job uses with a level need no higher, at most three
 *    per track by ceiling (stats now plus what its open slots could still take);
 *  - the valuables shelf (BotInventoryManager.shouldKeepForSellTrash / tradeValueScore / KEEP_VALUABLE_EQUIP_SLOTS /
 *    NEVER_SELL_TRADE_SCORE, @ f19fed568): an equip scrolled or rolled above its clean stats is kept for the market,
 *    the best SHELF_SLOTS by trade value, and one far above its base is kept regardless.
 * Ours: upstream keeps reserves for any future level; ours only up to RESERVE_HORIZON levels ahead, and the absolute
 * keep is bounded (MAX_ABSOLUTE) so a bag can always sell down (a bot that cannot free a slot strands loot on the
 * floor, the 1.6.0 bag report). Everything else in the bag is junk, sold cheapest first as before.
 */
public final class JunkSort {

    private JunkSort() {}

    public static boolean enabled() {
        return Boolean.getBoolean("slums.junk.sort");
    }

    static final int RESERVE_HORIZON = 15;
    static final int TRACK_CAP = 3;            // upstream SELF_RESERVE_TRACK_CAP
    static final int SHELF_SLOTS = 16;         // upstream keeps 24; our sale empties a 96-slot bag to half
    static final double NEVER_SELL_SCORE = 25; // upstream NEVER_SELL_TRADE_SCORE
    static final int MAX_ABSOLUTE = 8;
    static final double SHELF_MIN_SCORE = 6;   // upstream's "6 above base" stat protection

    public enum Kind { RESERVE, SHELF, JUNK }

    /*
     * One owned equip as the sort sees it: its slot track, whether its job may wear it, its level need, the stats its
     * job uses (upstream effective values), open slots, trade score (above its clean stats, attack x5) and whether
     * it has been scrolled. {@code worn}: on the body (rivals only, never sorted).
     */
    public record Owned(Object item, String track, boolean ownClass, int reqLevel, int[] rel, int slotsLeft,
                        double tradeScore, boolean scrolled, boolean worn) {
    }

    /* Every bag equip's kind (worn ones are left out). */
    public static Map<Object, Kind> sort(int level, List<Owned> owned) {
        Map<Object, Kind> out = new IdentityHashMap<>();
        Set<Object> reserved = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, List<Owned>> byTrack = new LinkedHashMap<>();
        for (Owned o : owned) {
            if (o.track() == null || !o.ownClass() || o.reqLevel() > level + RESERVE_HORIZON
                    || (!positive(o.rel()) && o.slotsLeft() <= 0)) {
                continue;
            }
            byTrack.computeIfAbsent(o.track(), t -> new ArrayList<>()).add(o);
        }
        for (List<Owned> track : byTrack.values()) {
            List<Owned> survivors = new ArrayList<>();
            for (Owned c : track) {
                boolean dominated = false;
                for (Owned other : track) {
                    if (other != c && other.reqLevel() <= c.reqLevel() && dominates(other.rel(), c.rel())
                            && ceiling(other) >= ceiling(c)) {
                        dominated = true;
                        break;
                    }
                }
                if (!dominated) {
                    survivors.add(c);
                }
            }
            survivors.sort(Comparator.comparingDouble(JunkSort::ceiling).reversed());
            for (Owned o : survivors.subList(0, Math.min(TRACK_CAP, survivors.size()))) {
                reserved.add(o.item());
            }
        }
        List<Owned> valuables = new ArrayList<>();
        for (Owned o : owned) {
            if (o.worn()) {
                continue;
            }
            if (reserved.contains(o.item())) {
                out.put(o.item(), Kind.RESERVE);
            } else if (o.scrolled() || o.tradeScore() >= SHELF_MIN_SCORE) {
                valuables.add(o);
            } else {
                out.put(o.item(), Kind.JUNK);
            }
        }
        valuables.sort(Comparator.comparingDouble(Owned::tradeScore).reversed());
        int absolute = 0;
        for (int i = 0; i < valuables.size(); i++) {
            Owned o = valuables.get(i);
            boolean keep = i < SHELF_SLOTS
                    || (o.tradeScore() >= NEVER_SELL_SCORE && absolute++ < MAX_ABSOLUTE);
            out.put(o.item(), keep ? Kind.SHELF : Kind.JUNK);
        }
        return out;
    }

    private static boolean positive(int[] rel) {
        for (int v : rel) {
            if (v > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean dominates(int[] a, int[] b) {
        boolean strict = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] < b[i]) {
                return false;
            }
            if (a[i] > b[i]) {
                strict = true;
            }
        }
        return strict;
    }

    // Upstream selfReserveCeiling: what it gives now plus what its open slots could still take (2 a slot).
    static double ceiling(Owned o) {
        double sum = 0;
        for (int v : o.rel()) {
            sum += v;
        }
        return sum + 2.0 * Math.max(0, o.slotsLeft());
    }

    // ------------------------------------------------------------------ the live bag

    /* The bag equips the town sale must keep (reserve and shelf); empty when off or without item data. */
    public static Set<Item> keeps(Character chr) {
        Set<Item> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!enabled() || chr == null || !EquipMetadataCache.isInitialized()) {
            return keep;
        }
        try {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            List<Owned> owned = new ArrayList<>();
            for (Item it : chr.getInventory(InventoryType.EQUIP).list()) {
                if (it instanceof Equip e) {
                    Owned o = owned(chr, ii, e, false);
                    if (o != null) {
                        owned.add(o);
                    }
                }
            }
            for (Item it : chr.getInventory(InventoryType.EQUIPPED).list()) {
                if (it instanceof Equip e && it.getPosition() > -100) {
                    Owned o = owned(chr, ii, e, true);
                    if (o != null) {
                        owned.add(o);
                    }
                }
            }
            for (Map.Entry<Object, Kind> e : sort(chr.getLevel(), owned).entrySet()) {
                if (e.getValue() != Kind.JUNK) {
                    keep.add((Item) e.getKey());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            keep.clear();   // the sale goes on as before
        }
        return keep;
    }

    private static Owned owned(Character chr, ItemInformationProvider ii, Equip e, boolean worn) {
        int id = e.getItemId();
        if (ii.isCash(id)) {
            return null;
        }
        EquipMetadataCache.EquipEntry entry = EquipMetadataCache.get().find(id);
        Map<String, Integer> base = ii.getEquipStats(id);
        int slotType = ItemConstants.getEquipSlotType(id);
        String track = slotType <= 0 ? null : slotType == 11 ? "weapon:" + id / 10000 : "slot:" + slotType;
        int reqJob = entry != null ? entry.reqJob : 0;
        int niche = chr.getJob().getJobNiche();
        boolean ownClass = reqJob == 0 || (niche >= 1 && niche <= 5 && (reqJob & (1 << (niche - 1))) != 0);
        int reqLevel = entry != null ? entry.reqLevel : 0;
        return new Owned(e, track, ownClass, reqLevel, relevant(chr.getJob().getId(), e), e.getUpgradeSlots(),
                tradeScore(e, base), e.getLevel() > 0, worn);
    }

    /* Upstream relevantStatsFor + effectiveStatValue, as a vector for the job. */
    static int[] relevant(int jobId, Equip e) {
        int branch = jobId / 100;
        boolean mage = branch == 2 || branch == 12;
        if (mage) {
            return new int[]{e.getInt(), e.getLuk(), e.getMatk()};
        }
        if (branch == 3 || branch == 13 || jobId / 10 == 52) {          // bowman, gunslinger: DEX primary
            return new int[]{e.getDex() + 2 * e.getWatk(), e.getStr(), e.getWatk() + e.getDex() / 5};
        }
        if (branch == 4 || branch == 14) {                              // thief: LUK primary
            return new int[]{e.getLuk() + 2 * e.getWatk(), e.getDex(), e.getWatk() + e.getLuk() / 5};
        }
        if (branch == 1 || branch == 11 || branch == 21 || jobId == 500 || jobId / 10 == 51 || branch == 15) {
            return new int[]{e.getStr() + 2 * e.getWatk(), e.getDex(), e.getWatk() + e.getStr() / 5,
                    e.getAcc() + e.getDex()};
        }
        return new int[]{e.getStr(), e.getDex(), e.getInt(), e.getLuk(), e.getWatk(), e.getMatk(), e.getAcc()};
    }

    /* Upstream tradeValueScore: how far it beats its clean stats, weapon attack x5. */
    static double tradeScore(Equip e, Map<String, Integer> base) {
        return 5.0 * above(e.getWatk(), base, "PAD") + above(e.getMatk(), base, "MAD") + above(e.getStr(), base, "STR")
                + above(e.getDex(), base, "DEX") + above(e.getInt(), base, "INT") + above(e.getLuk(), base, "LUK");
    }

    private static int above(int v, Map<String, Integer> base, String key) {
        return Math.max(0, v - (base == null ? 0 : base.getOrDefault(key, 0)));
    }
}
