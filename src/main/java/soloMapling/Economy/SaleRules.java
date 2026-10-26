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
 *   <li>boss-only drops: items that only bosses drop (every monster in {@code drop_data} that drops it is a boss),
 *   plus {@code saleRules.bossOnly} in economy.yaml;</li>
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
        if (item instanceof Equip eq && GearGrade.godly(eq)) {
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

    private static Set<Integer> loadBossOnly() {
        Set<Integer> out = new HashSet<>(EconomyConfig.map("saleRules.bossOnly").keySet());
        Map<Integer, Boolean> allBoss = new HashMap<>();   // item -> every dropper so far is a boss
        Map<Integer, Boolean> isBoss = new HashMap<>();
        try (Connection con = BotDbGate.connection();
             PreparedStatement ps = con.prepareStatement("SELECT itemid, dropperid FROM drop_data WHERE chance > 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int item = rs.getInt(1);
                int mob = rs.getInt(2);
                boolean boss = isBoss.computeIfAbsent(mob, SaleRules::mobIsBoss);
                allBoss.merge(item, boss, Boolean::logicalAnd);
            }
        } catch (Exception | LinkageError e) {
            log.warn("[Economy] boss-only drops not read from drop_data ({}); only economy.yaml's list applies",
                    e.toString());
        }
        allBoss.forEach((item, boss) -> {
            if (boss) {
                out.add(item);
            }
        });
        log.info("[Economy] {} boss-only items bots will never sell", out.size());
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
