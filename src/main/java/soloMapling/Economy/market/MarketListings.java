package soloMapling.Economy.market;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * {@code listings.json}: every item on sale in an open Free Market shop right now (hired merchants and player shops,
 * bot shops included). The owner's Track D decision: seller names are shown for bots and players alike (they are
 * IGNs). Pure: {@link PublicMarketExport} reads the world and hands the shops in.
 */
public final class MarketListings {

    /** The FM rooms: 910000001..910000022 are rooms 1..22. */
    static final int FM_ROOM_BASE = 910_000_000;
    static final int FM_ROOMS = 22;

    private MarketListings() {
    }

    /**
     * One slot of a shop: {@code perBundle} units sold together for {@code bundlePrice} mesos, {@code bundles} of
     * them left. A star or bullet stack ({@code rechargeable}) is one priced unit, as on the price board.
     */
    public record Row(int itemId, int perBundle, int bundles, long bundlePrice, boolean rechargeable) {
        public long unitPrice() {
            return rechargeable ? bundlePrice : bundlePrice / Math.max(1, perBundle);
        }
    }

    /** One open shop. {@code merchant}: a hired merchant (a bot's FM stand is one too); false: a player shop. */
    public record Shop(int channel, int mapId, String title, String seller, boolean bot, boolean merchant,
                       List<Row> items) {
    }

    /** The FM room number for a map, or null when the shop is not in an FM room. */
    static Integer room(int mapId) {
        int r = mapId - FM_ROOM_BASE;
        return r >= 1 && r <= FM_ROOMS ? r : null;
    }

    public static Map<String, Object> build(List<Shop> shops, IntFunction<String> names, long now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int botShops = 0, playerShops = 0;
        for (Shop shop : shops) {
            if (shop == null) {
                continue;
            }
            int listed = 0;
            for (Row r : shop.items()) {
                if (r == null || r.bundles() <= 0 || r.bundlePrice() <= 0) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("item_id", r.itemId());
                m.put("name", MarketNames.of(names, r.itemId()));
                m.put("qty", Math.max(1, r.perBundle()));
                m.put("bundles", r.bundles());
                m.put("price", r.unitPrice());
                m.put("bundle_price", r.bundlePrice());
                m.put("channel", shop.channel());
                m.put("map_id", shop.mapId());
                m.put("room", room(shop.mapId()));
                m.put("shop_title", shop.title() == null ? "" : shop.title());
                m.put("seller_name", shop.seller() == null ? "" : shop.seller());
                // no seller_kind: the site's build refuses bot/player flags (one server; site session 2026-10-26)
                m.put("shop_type", shop.merchant() ? "merchant" : "player_shop");
                rows.add(m);
                listed++;
            }
            if (listed > 0) {
                if (shop.bot()) {
                    botShops++;
                } else {
                    playerShops++;
                }
            }
        }
        rows.sort(Comparator.<Map<String, Object>>comparingInt(m -> (Integer) m.get("item_id"))
                .thenComparingLong(m -> (Long) m.get("price")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", PublicMarketExport.SCHEMA);
        out.put("generated_at", Instant.ofEpochMilli(now).toString());
        out.put("shops", botShops + playerShops);
        out.put("bot_shops", botShops);
        out.put("player_shops", playerShops);
        out.put("count", rows.size());
        out.put("listings", rows);
        return out;
    }
}
