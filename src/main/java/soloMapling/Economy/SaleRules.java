package soloMapling.Economy;

import client.inventory.Equip;
import client.inventory.Item;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotDbGate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * 1.5.0 rc3, owner decision 1: what a bot never sells, in its FM shop or in the trade window.
 * <ul>
 *   <li>godly scrolled gear ({@link GearGrade}): bot-sold gear stays average;</li>
 *   <li>Chaos Scrolls (2049100-2049103) and the White Scroll (2340000);</li>
 *   <li>boss-only drops: items that only bosses drop (every monster in {@code drop_data} that drops it is a boss)
 *   and no regular NPC shop sells, plus {@code saleRules.bossOnly} in economy.yaml (always);</li>
 *   <li>cash items; and what cannot change hands anyway (untradeable, account-bound, quest items, expiring items,
 *   pets, rings).</li>
 * </ul>
 * {@link #whyNot} says which rule refused, for the QA log and {@code !economy}.
 */
public final class SaleRules {

    private static final Logger log = LoggerFactory.getLogger(SaleRules.class);

    private static volatile Set<Integer> bossOnly;
    private static volatile IntPredicate cashCheck = SaleRules::isCashItem;
    private static volatile IntPredicate tradeBlocked = SaleRules::isTradeBlocked;

    private SaleRules() {
    }

    /** True when a bot may sell this item. */
    public static boolean botMaySell(Item item) {
        return whyNot(item) == null;
    }

    /** Why a bot may not sell this item, or null when it may. */
    public static String whyNot(Item item) {
        return refusal(item, true);
    }

    /**
     * Why a bot may not list gear it owns in its own shop or consignment, or null when it may: {@link #whyNot} minus
     * the godly-gear rule (owner 2026-10-29: a bot may list its own scrolled godly gear). Everything else (gifts,
     * Mega Buy, bot-to-bot trades, buying) keeps {@link #whyNot}.
     */
    public static String whyNotListOwn(Item item) {
        return refusal(item, false);
    }

    /** True for godly scrolled gear ({@link GearGrade}). */
    public static boolean isGodlyGear(Item item) {
        return item instanceof Equip eq && GearGrade.godly(eq);
    }

    /** {@link #whyNot} for a bare item id (generated stock that has no item yet), or null when allowed. */
    public static String whyNotId(int itemId) {
        return whyNot(new Item(itemId, (short) 0, (short) 1));
    }

    private static String refusal(Item item, boolean godlyRule) {
        if (item == null) {
            return "no item";
        }
        int id = item.getItemId();
        if (cashCheck.test(id)) {
            return "cash item";
        }
        if (ItemConstants.isChaosScroll(id) || id == ItemId.WHITE_SCROLL) {
            return "Chaos or White Scroll";
        }
        if (id == ItemId.FISHING_CHAIR) {
            return "fishing chair";   // 1.6.0: a fishing bot keeps the chair BotFishingChairs gave it
        }
        if (ItemConstants.isPet(id) || ItemId.isWeddingRing(id) || id / 10000 == 111) {
            return "pet or ring";
        }
        if (item.getExpiration() > 0) {
            return "expires";
        }
        if ((item.getFlag() & ItemConstants.UNTRADEABLE) != 0 || tradeBlocked.test(id)) {
            return "untradeable";
        }
        if (bossOnly().contains(id)) {
            return "boss-only drop";
        }
        if (godlyRule && isGodlyGear(item)) {
            return "godly gear";
        }
        return null;
    }

    // ---- boss-only drops ----

    /** Items only bosses drop. Read from drop_data once, on first use; the yaml list is added. */
    public static Set<Integer> bossOnly() {
        Set<Integer> b = bossOnly;
        if (b == null) {
            synchronized (SaleRules.class) {
                b = bossOnly;
                if (b == null) {
                    b = loadBossOnly();
                    bossOnly = b;
                }
            }
        }
        return b;
    }

    /**
     * NPCs whose shops are GM/test shops (everything at 1 meso), never "a regular NPC sells it": 9001002 (the GM
     * shops 9999992-9999999) and 11000 (shop 1337). Owner 2026-10-30.
     */
    static final Set<Integer> GM_SHOP_NPCS = Set.of(9001002, 11000);

    private static Set<Integer> loadBossOnly() {
        Set<Integer> fromDrops = new HashSet<>();
        Set<Integer> npcSold = new HashSet<>();
        Map<Integer, Boolean> allBoss = new HashMap<>();   // item -> every dropper so far is a boss
        Map<Integer, Boolean> isBoss = new HashMap<>();
        try (Connection con = BotDbGate.connection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT itemid, dropperid FROM drop_data WHERE chance > 0");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int item = rs.getInt(1);
                    int mob = rs.getInt(2);
                    boolean boss = isBoss.computeIfAbsent(mob, SaleRules::mobIsBoss);
                    allBoss.merge(item, boss, Boolean::logicalAnd);
                }
            }
            allBoss.forEach((item, boss) -> {
                if (boss) {
                    fromDrops.add(item);
                }
            });
            npcSold.addAll(loadNpcSold(con));
        } catch (Exception | LinkageError e) {
            log.warn("[Economy] boss-only drops not read from drop_data ({}); only economy.yaml's list applies",
                    e.toString());
        }
        Set<Integer> out = combineBossOnly(EconomyConfig.map("saleRules.bossOnly").keySet(), fromDrops, npcSold);
        long exempt = fromDrops.stream().filter(npcSold::contains).count();
        log.info("[Economy] {} boss-only items bots will never sell ({} only bosses drop, {} of those exempt: a "
                + "regular NPC shop sells them)", out.size(), fromDrops.size(), exempt);
        return out;
    }

    /** Items a regular NPC shop sells ({@link #regularSale}), read once with the drop table at first use. */
    private static Set<Integer> loadNpcSold(Connection con) {
        Set<Integer> out = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT i.itemid, s.npcid, i.price FROM shopitems i "
                + "JOIN shops s ON s.shopid = i.shopid");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (regularSale(rs.getInt(2), rs.getLong(3))) {
                    out.add(rs.getInt(1));
                }
            }
        } catch (Exception | LinkageError e) {
            log.warn("[Economy] NPC shop items not read ({}); no boss-only exemption for NPC-sold items", e.toString());
        }
        return out;
    }

    /** A shop row is a regular NPC sale: more than 1 meso, and not a GM/test shop NPC ({@link #GM_SHOP_NPCS}). */
    static boolean regularSale(int npcId, long price) {
        return price > 1 && !GM_SHOP_NPCS.contains(npcId);
    }

    /**
     * The boss-only set: items only bosses drop, minus what a regular NPC shop sells (owner 2026-10-30: Red Bean
     * Sundae is a 4,000-meso shop potion that only the Balrog drops), plus economy.yaml's explicit list, which wins
     * over the exemption.
     */
    static Set<Integer> combineBossOnly(Set<Integer> yamlList, Set<Integer> onlyBossesDrop, Set<Integer> npcSold) {
        Set<Integer> out = new HashSet<>();
        for (int id : onlyBossesDrop) {
            if (!npcSold.contains(id)) {
                out.add(id);
            }
        }
        out.addAll(yamlList);
        return Set.copyOf(out);
    }

    private static boolean mobIsBoss(int mobId) {
        try {
            // drop_data names droppers this WZ lacks or has only in part (event/other-version mobs); getMonster would
            // log each as SEVERE ("failed to load"), so read only the boss flag, quietly
            return server.life.LifeFactory.monsterIsBoss(mobId);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean isCashItem(int id) {
        try {
            return ItemInformationProvider.getInstance().isCash(id) || id / 1000000 == 5;
        } catch (RuntimeException | LinkageError e) {
            return id / 1000000 == 5;
        }
    }

    private static boolean isTradeBlocked(int id) {
        try {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            return ii.isUntradeableRestricted(id) || ii.isAccountRestricted(id) || ii.isQuestItem(id);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ---- test seams ----

    public static void useForTest(Set<Integer> testBossOnly, IntPredicate testCash, IntPredicate testBlocked) {
        bossOnly = testBossOnly;
        cashCheck = testCash == null ? SaleRules::isCashItem : testCash;
        tradeBlocked = testBlocked == null ? SaleRules::isTradeBlocked : testBlocked;
    }

    static List<String> describeRules() {
        return List.of("godly gear", "Chaos or White Scroll", "boss-only drop (" + bossOnly().size() + " items)",
                "cash item", "untradeable / expiring / pet / ring");
    }
}
