package soloMapling.itemPool;

import client.inventory.Equip;
import constants.inventory.EquipType;
import server.ItemInformationProvider;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import static soloMapling.FreeMarket.EquipListGenerator.generateCleanItemEquip;
import static soloMapling.itemPool.UniqueStatBonusList.applySellablesDeduction;
import static soloMapling.itemPool.UniqueStatBonusList.correctAnomalies;
import static soloMapling.server.MapleVersionManager.getItemPoolVersion;
import static soloMapling.itemPool.ItemSelector.getScrollNodeData;
import static soloMapling.itemPool.ScrollInfoManager.getScrollInfo;

public class UpgradeSimulator {
    // Define Scroll parameters
    static double sellables_offset_multiplier = 0.6;
//    static double sellables_offset_multiplier_no_dark_scrolls = 0.6;
//    static double sellables_offset_multiplier_with_dark_scrolls = 0.8;

    static class Scroll {
        long price;
        int successRate; // as percentage (10, 60, etc.)
        int statBonus;

        Scroll(long price, int successRate, int statBonus) {
            this.price = price;
            this.successRate = successRate;
            this.statBonus = statBonus;
        }

        /* 162: a scroll of the item pool, priced at its learned market price (ScrollMarketPrice; the yaml price
         * {@code poolPrice} when -Dslums.scroll.marketPrice is off or nothing sold yet). */
        static Scroll market(int scrollId, long poolPrice, int successRate, int statBonus) {
            return new Scroll(soloMapling.Economy.ScrollMarketPrice.price(scrollId, poolPrice), successRate, statBonus);
        }

        public int getStatBonus() {
            return this.statBonus;
        }
    }

    public static String formatWithCommas(long number) {
        NumberFormat formatter = NumberFormat.getNumberInstance(Locale.US);
        return formatter.format(number);
    }

    public static double[][] minimumCostToCreateEquipCalculator(ArrayList<Scroll> scrolls, long baseItemCost, int slots, int maxBonus, int maxIncrement) {
        // Initialize the cost array (slots+1) x (maxBonus+1)
        double[][] costArray = new double[slots + 1][maxBonus + 1];
        for (int i = 0; i <= slots; i++) {
            for (int j = 0; j <= maxBonus; j++) {
                costArray[i][j] = Double.POSITIVE_INFINITY; // Use infinity to represent uninitialized
            }
        }
        costArray[0][0] = baseItemCost; // Set base item cost at (0,0)

        // Compute minimum costs
        for (int i = 0; i < slots; i++) {
            for (int j = 0; j <= maxBonus - maxIncrement; j++) {
                for (Scroll scroll : scrolls) {
                    if (scroll.successRate > 0) { // Prevent division by zero
                        double newCost = (costArray[i][j] + scroll.price) / (scroll.successRate / 100.0);
                        int newBonus = j + scroll.statBonus;
                        if (newBonus <= maxBonus) {
                            costArray[i + 1][newBonus] = Math.min(costArray[i + 1][newBonus], newCost);
                        }
                    }
                }
            }
        }
        return costArray;
    }

    public static UniqueStatBonusList cleanUpMinCostData(double[][] costArray, int slots, int maxBonus) {
        UniqueStatBonusList list = new UniqueStatBonusList();

        for (int i = 0; i <= slots; i++) { // Slots Used (+N Scrolls hit)
            for (int j = 0; j <= maxBonus; j++) { // Stat Bonus
                if (costArray[i][j] < Double.POSITIVE_INFINITY) {
                    list.addStatBonus(j, (long) costArray[i][j]);
                }
            }
        }

        // Cleaned up list
        correctAnomalies(list.getStatBonusList());
        applySellablesDeduction(list.getStatBonusList(), sellables_offset_multiplier);
//        printPrices(list.getStatBonusList());

        return list;
    }

    // Utility method to print the stat prices
    public static void printPrices(Map<Integer, Long> statPriceMap) {
        for (Map.Entry<Integer, Long> entry : statPriceMap.entrySet()) {
            long price_value = entry.getValue();
            System.out.println("Stat Bonus: " + entry.getKey() + ", Price: " + formatWithCommas(price_value));
        }
    }

    // Method to get the price for a given stat bonus key
    public static Long getPriceForStatBonus(UniqueStatBonusList statPriceMap, int statBonus) {
        Long price = statPriceMap.getStatBonusList().get(statBonus);
        if (price != null) {
            return price;
        }
        return 1L;
    }

    public static Equip ScrollGivenItem(Equip sellItem) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        ScrollInfoManager.ScrollInfo scrollIds = getScrollInfo(sellItem);
        if (scrollIds == null) { // scrolls don't exist, return clean item
            return sellItem;
        }

        ScrollPatternGenerator spg = ScrollPatternGenerator.getInstance();
        int[] numScrolls = spg.getScrollPattern(sellItem.getUpgradeSlots());

        for (int x = 0; x < numScrolls[0]; x++) {
            ii.scrollEquipWithId(sellItem, scrollIds.getPercent_10_or_30(), false, 0, true);
        }
        for (int y = 0; y < numScrolls[1]; y++) {
            ii.scrollEquipWithId(sellItem, scrollIds.getPercent_60_or_70(), false, 0, true);
        }
        sellItem.setUpgradeSlots(0);
        return sellItem;
    }

    public static boolean checkIfEquipIsScrollable(Equip sellItem) {
        ScrollInfoManager.ScrollInfo scrollIds = getScrollInfo(sellItem);
        if (scrollIds == null) {
            return false;
        }
        return true;
    }


    public static int limitToIntRange(long value) {
        return (value > 2147483647L) ? 2147483647 : (int) value;
    }

    public static int getHighestStatBonus(ArrayList<Scroll> scrolls) {
        int highestStatBonus = Integer.MIN_VALUE; // Start with the lowest possible value

        for (Scroll scroll : scrolls) {
            if (scroll.getStatBonus() > highestStatBonus) {
                highestStatBonus = scroll.getStatBonus();
            }
        }

        return highestStatBonus;
    }

    public static int getEquipMarketValue(Equip equip) {

        EquipType equipType = EquipType.getEquipTypeById(equip.getItemId());

        // most dominant stat bonus from comparison
        ScrolledItemComparator comp = new ScrolledItemComparator(equip);
        int scrolledStatBonus = comp.getHighestStatValueDifference();

        long baseItemCost;
        try {
            baseItemCost = ItemDatabase.getInstance().getItemPrice(equip.getItemId());
        } catch (Exception e) {
            baseItemCost = 100;
//            throw new RuntimeException(e);
        }
        ScrollInfoManager.ScrollInfo scrollInfo = getScrollInfo(equip, comp);
        if (scrollInfo == null) {
            return (int) baseItemCost;
        }

        ArrayList<Scroll> scrolls2 = scrollsFor(scrollInfo);

        // slots based on clean's number of slots
        Equip clean = (Equip) generateCleanItemEquip(equip.getItemId());
        int slots = clean.getUpgradeSlots();

        long item_price = evModel() ? evPrice(scrolls2, baseItemCost, slots, scrolledStatBonus)
                : naivePrice(scrolls2, baseItemCost, slots, scrolledStatBonus);
        return limitToIntRange(item_price);
    }

    /* The item pool's scrolls for this kind of equip: [price, success rate, stat bonus] (10%/60%, and 30%/70%), each at
     * its learned market price (ScrollMarketPrice) when that is on. */
    static ArrayList<Scroll> scrollsFor(ScrollInfoManager.ScrollInfo scrollInfo) {
        ArrayList<Scroll> scrolls2 = new ArrayList<>();
        if (scrollInfo.hasPercent_10_60()) {
            ScrollNode tenPc = getScrollNodeData(scrollInfo.getPercent_10());
            ScrollNode sixtyPc = getScrollNodeData(scrollInfo.getPercent_60());
            scrolls2.add(Scroll.market(scrollInfo.getPercent_10(), tenPc.getCurrentPrice(),
                    tenPc.getSuccessRate(), tenPc.getStatBonus()));
            scrolls2.add(Scroll.market(scrollInfo.getPercent_60(), sixtyPc.getCurrentPrice(),
                    sixtyPc.getSuccessRate(), sixtyPc.getStatBonus()));
        }
        if (getItemPoolVersion() > 40) {
            if (scrollInfo.hasPercent_30_70()) {
                ScrollNode thirtyPc = getScrollNodeData(scrollInfo.getPercent_30());
                ScrollNode seventyPc = getScrollNodeData(scrollInfo.getPercent_70());
                scrolls2.add(Scroll.market(scrollInfo.getPercent_30(), thirtyPc.getCurrentPrice(),
                        thirtyPc.getSuccessRate(), thirtyPc.getStatBonus()));
                scrolls2.add(Scroll.market(scrollInfo.getPercent_70(), seventyPc.getCurrentPrice(),
                        seventyPc.getSuccessRate(), seventyPc.getStatBonus()));
            }
        }
        return scrolls2;
    }

    /*
     * Ticket 162 part 5: which model prices a scrolled equip. Off (the default, live): the naive chain below. On
     * (-Dslums.scroll.ev=true): ScrollValuer's reproduction cost, nutnnut's expected meso to make one this good from
     * clean bases (a failed scroll burns a slot, a base that runs out of slots below the target is replaced).
     */
    public static boolean evModel() {
        return Boolean.getBoolean("slums.scroll.ev");
    }

    /*
     * The naive model: the cheapest chain of successes reaching exactly this bonus, each step "(cost so far +
     * scroll) / success rate" (a failure is priced as losing the whole item), smoothed, then the sellables cut.
     * A bonus the chain cannot reach exactly is priced 1 meso.
     */
    static long naivePrice(java.util.List<Scroll> scrolls, long baseItemCost, int slots, int scrolledStatBonus) {
        ArrayList<Scroll> list = new ArrayList<>(scrolls);
        int maxIncrement = getHighestStatBonus(list);
        int maxBonus = maxIncrement * slots;
        double[][] minCostArray = minimumCostToCreateEquipCalculator(list, baseItemCost, slots, maxBonus, maxIncrement);
        UniqueStatBonusList costList = cleanUpMinCostData(minCostArray, slots, maxBonus);
        return getPriceForStatBonus(costList, scrolledStatBonus);
    }

    /*
     * The reproduction-cost model (ScrollValuer): expected meso to reach at least this bonus, with the naive
     * model's sellables cut (0.6) on the premium above the clean base.
     */
    static long evPrice(java.util.List<Scroll> scrolls, long baseItemCost, int slots, int scrolledStatBonus) {
        java.util.List<ScrollValuer.ScrollSpec> specs = new ArrayList<>();
        for (Scroll sc : scrolls) {
            if (sc.successRate > 0 && sc.statBonus > 0) {
                specs.add(new ScrollValuer.ScrollSpec(sc.successRate / 100.0, sc.statBonus, Math.max(0, sc.price)));
            }
        }
        int maxGain = 0;
        for (ScrollValuer.ScrollSpec sp : specs) {
            maxGain = Math.max(maxGain, (int) sp.statGain());
        }
        // A bonus no clean base can reach with these scrolls (chaos, hammers, another source) is priced at the most
        // the scrolls can make, not at an unbounded restart loop.
        int target = Math.min(scrolledStatBonus, Math.max(0, slots) * maxGain);
        double value = ScrollValuer.reproductionValue(0, slots, specs, baseItemCost).applyAsDouble(target);
        // The sellables cut on the premium over the clean base only (the naive chain cuts the whole price, which
        // its own "a failure loses the item" overcount more than makes up for; here it would price +2 under clean).
        if (scrolledStatBonus > 0 && value > baseItemCost) {
            value = baseItemCost + (value - baseItemCost) * sellables_offset_multiplier;
        }
        return Math.max(1, Math.round(Math.min(value, Long.MAX_VALUE / 2.0)));
    }


    public static void test() {
        // Define scrolls with [price, rate, stat bonus]
        // Need to get the list of scrolls associated with the type of equipment
        ArrayList<Scroll> scrolls2 = new ArrayList<>();

        scrolls2.add(new Scroll(1500000, 30, 3));
//        scrolls2.add(new Scroll(5000000, 30, 5));
        scrolls2.add(new Scroll(1900000, 70, 2));
//        scrolls2.add(new Scroll(1200000, 70, 2));


//        Scroll[] scrolls = {
//                new Scroll(1200000, 10, 3),
////                new Scroll(5000000, 30, 5),
//                new Scroll(3000000, 60, 2),
////                new Scroll(1200000, 70, 2)
//                // Add more as needed
//        };

        // Add more as needed


        // need to get base cost via .yaml's price node
        long baseItemCost = 400000;

        // slots based on clean's number of slots
        int slots = 7;

        // max bonus based on (highest statBonus * slots)
        int maxBonus = 21;

        // max value of scroll's statBonus's
        int maxIncrement = 3;

        // most dominant stat bonus from comparison
        int scrolledStatBonus = 14;

        double[][] minCostArray = minimumCostToCreateEquipCalculator(scrolls2, baseItemCost, slots, maxBonus, maxIncrement);
        UniqueStatBonusList costList = cleanUpMinCostData(minCostArray, slots, maxBonus);
        printPrices(costList.getStatBonusList());
        long item_price = getPriceForStatBonus(costList, scrolledStatBonus);
        System.out.println(item_price);
    }

    public static void test2() {
        ItemDatabase db = ItemDatabase.getInstance();
        int x = db.getItemPrice(1472026);
        System.out.println(x);
    }

    public static void main(String[] args) {
        test2();
//        ScrollGivenItem();
//        getEquipMarketValue();
    }
}
