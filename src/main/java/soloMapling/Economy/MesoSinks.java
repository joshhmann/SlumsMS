package soloMapling.Economy;

import client.Character;
import client.Client;
import client.inventory.Item;
import server.Shop;
import server.ShopItem;
import server.Trade;
import soloMapling.qa.QaLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1.5.0 rc3, owner decision 6: where players' mesos leave the world. Every number is in economy.yaml under
 * {@code sinks.} and {@code cosmeticShop.} with a safe default (docs/release/1.5.0-economy-trade.md has the veto
 * table).
 * <ul>
 *   <li><b>FM shop tax:</b> a sale in a player shop or hired merchant (bot shops too) keeps back
 *   {@code max(v83 trade fee, sinks.shopTaxPercent (3) % of the price)} from the seller. The v83 fee is 0-1.8% under
 *   5m, so the tax lifts small sales to 3% and leaves big ones at v83's 3-6%.</li>
 *   <li><b>NPC price bumps:</b> {@code sinks.shopPrices} replaces the price of an item in every NPC shop that sells it
 *   (pet food, the VIP teleport rock, ...), read when a shop loads.</li>
 *   <li><b>The cosmetics merchant</b> (Hanako, 9209006, placed in the FM entrance by migration 021): hair, face, lens,
 *   skin and dye coupons, a few classic cosmetic items and Gachapon Tickets, for big mesos
 *   ({@code cosmeticShop.items}: itemId: price).</li>
 * </ul>
 */
public final class MesoSinks {

    public static final int COSMETIC_NPC_DEFAULT = 9209006;

    private MesoSinks() {
    }

    // ---- FM shop tax ----

    /** What a shop sale of {@code price} keeps back from the seller: the larger of the v83 fee and the tax. */
    public static int shopFee(long price) {
        if (price <= 0) {
            return 0;
        }
        int v83 = Trade.getFee(price);
        double pct = Math.max(0, Math.min(50, EconomyConfig.getDouble("sinks.shopTaxPercent", 3.0)));
        long tax = Math.round(price * pct / 100.0);
        return (int) Math.min(price, Math.max(v83, tax));
    }

    /**
     * A sale in a player shop or merchant went through: books the fee as a sink and leaves a price sample. Never
     * throws (a hook in the shops' buy paths).
     *
     * @param sold         the item as handed over (its quantity is the units sold)
     * @param totalPrice   what the buyer paid, before the fee
     * @param fee          what the seller did not get
     * @param realSeller   a real player owns the shop
     * @param realBuyer    a real player bought
     * @param botShop      a bot's own FM shop ({@link BotShops}) or an FM bot merchant
     */
    public static void onShopSale(Item sold, long totalPrice, int fee, boolean realSeller, boolean realBuyer,
                                  boolean botShop) {
        onShopSale(sold, totalPrice, fee, realSeller, realBuyer, botShop, null, null);
    }

    /**
     * As above, with the two sides when known: a sale between two real players then leaves a sample only when they
     * are not linked (one account, address or machine) and the pair has not left one for this item today
     * ({@link PriceBook#recordBetween}).
     */
    public static void onShopSale(Item sold, long totalPrice, int fee, boolean realSeller, boolean realBuyer,
                                  boolean botShop, PriceBook.Party seller, PriceBook.Party buyer) {
        try {
            if (fee > 0) {
                EconomyMeters.add(EconomyMeters.TAX, fee);
                soloMapling.Economy.stats.EconomyCounters.recordFmTax(fee);
            }
            if (totalPrice > 0) {
                soloMapling.Economy.stats.EconomyCounters.recordFmSale(totalPrice);
            }
            int units = sold == null ? 0 : PriceBook.units(sold);
            PriceBook.Source src = botShop ? (realBuyer ? PriceBook.Source.BOT_SHOP : PriceBook.Source.BOT_SEED)
                    : realSeller ? PriceBook.Source.SHOP : PriceBook.Source.BOT_SEED;
            if (realSeller && realBuyer && !botShop) {
                // a sale between two players: only between parties that are not one person in two hats
                PriceBook.recordBetween(sold, totalPrice, units, src, seller != null ? seller
                        : new PriceBook.Party(0, null, null), buyer != null ? buyer : new PriceBook.Party(0, null, null));
            } else {
                PriceBook.record(sold, totalPrice, units, src, realSeller || realBuyer);
            }
            if (QaLog.on(QaLog.Topic.TRADE) && sold != null) {
                QaLog.any(QaLog.Topic.TRADE, String.format("SHOP SALE %d x%d for %,d: fee/tax %,d (%s seller, %s buyer%s)",
                        sold.getItemId(), units, totalPrice, fee, realSeller ? "player" : "bot",
                        realBuyer ? "player" : "bot", botShop ? ", bot shop" : ""));
            }
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger(MesoSinks.class).debug("shop sale hook", e);
        }
    }

    // ---- NPC shop prices ----

    /** The price an NPC shop asks for this item: the yaml's bump, or the shop's own price. */
    public static int npcPrice(int itemId, int shopPrice) {
        if (shopPrice <= 0) {
            return shopPrice;   // pitch / token items keep their own currency
        }
        Long bump = EconomyConfig.map("sinks.shopPrices").get(itemId);
        return bump == null || bump <= 0 ? shopPrice : (int) Math.min(Integer.MAX_VALUE, bump);
    }

    // ---- the cosmetics merchant ----

    /** A purchase from the cosmetics merchant: booked as a sink. */
    public static void onSinkPurchase(Character chr, int itemId, int quantity, long mesos) {
        EconomyMeters.add(EconomyMeters.SINK_SHOP, mesos);
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, String.format("SINK %s bought %d x%d from the cosmetics merchant for %,d",
                    chr == null ? "?" : chr.getName(), itemId, quantity, mesos));
        }
    }

    public static int cosmeticNpc() {
        return EconomyConfig.getInt("cosmeticShop.npcId", COSMETIC_NPC_DEFAULT);
    }

    public static boolean isCosmeticNpc(int npcId) {
        return EconomyConfig.getBool("cosmeticShop.enabled", true) && npcId == cosmeticNpc();
    }

    /** What the merchant sells: {itemId, price} in file order (defaults when the yaml has none). */
    public static List<int[]> cosmeticStock() {
        Map<Integer, Long> m = EconomyConfig.map("cosmeticShop.items");
        if (m.isEmpty()) {
            m = DEFAULT_COSMETICS;
        }
        List<int[]> out = new ArrayList<>();
        m.forEach((id, price) -> {
            if (price != null && price > 0) {
                out.add(new int[]{id, (int) Math.min(Integer.MAX_VALUE, price)});
            }
        });
        return out;
    }

    static final Map<Integer, Long> DEFAULT_COSMETICS = defaults();

    private static Map<Integer, Long> defaults() {
        Map<Integer, Long> m = new java.util.LinkedHashMap<>();
        m.put(5150001, 5_000_000L);    // Henesys Hair Style Coupon (VIP)
        m.put(5151001, 2_000_000L);    // Henesys Hair Color Coupon (VIP)
        m.put(5152001, 5_000_000L);    // Henesys Face Coupon (VIP)
        m.put(5152013, 3_000_000L);    // Henesys Cosmetic Lens Coupon (VIP)
        m.put(5153000, 2_000_000L);    // Henesys Skin Coupon
        m.put(5150040, 15_000_000L);   // Royal Hair Coupon
        m.put(5220000, 3_000_000L);    // Gachapon Ticket
        m.put(1002186, 10_000_000L);   // Transparent Hat
        m.put(1102039, 10_000_000L);   // Transparent Cape
        m.put(1102041, 8_000_000L);    // Pink Adventurer Cape
        m.put(1102042, 8_000_000L);    // Purple Adventurer Cape
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Opens the merchant's shop for the player (NPCTalkHandler). False when there is nothing to sell. */
    public static boolean openCosmeticShop(Client c, int npcId) {
        List<int[]> stock = cosmeticStock();
        if (stock.isEmpty() || c == null || c.getPlayer() == null) {
            return false;
        }
        List<ShopItem> items = new ArrayList<>();
        for (int[] s : stock) {
            items.add(new ShopItem((short) 1000, s[0], s[1], 0));
        }
        Shop shop = Shop.custom(EconomyConfig.getInt("cosmeticShop.shopId", 9_209_006), npcId, items);
        shop.sendShop(c);
        return true;
    }
}
