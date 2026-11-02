package soloMapling.Casino;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for casino chip items and their NPC shop.
 *
 * <p>Chips are ETC stamp items repurposed as casino currency. The shop is database rows, not code: NPC
 * {@link #CASINO_NPC_ID} into map 100000203 (Henesys Game Park) and shop {@link #CASINO_SHOP_ID} are created by
 * {@code database/migrations/059-casino-chip-shop.sql}, which inserts exactly {@link #getChips()} below; change chip
 * values here and in that migration together, or {@code CasinoChipShopMigrationTest} fails. The NPC's
 * {@code scripts/npc/9000055.js} opens the shop by id.
 *
 * <p>Buying charges the face value (the {@code shops} price {@code Shop} reads, passed through
 * {@code MesoSinks.npcPrice}). Selling pays it back through {@link #sellValue}, because the stock chip WZ
 * {@code info/price} is 250/350/400/350 and {@code Shop.sell} would otherwise pay that instead: a chip bought for
 * 1,000,000 and sold back would return 350. The exchange is lossless mesos for mesos.
 */
public class CasinoChipConfig {

    // The NPC players click in Henesys Game Park (NPC 9000055, "Aramia"; spawnCasinoNpcs in EnvironmentManager).
    public static final int CASINO_NPC_ID = 9000055;

    // Shop ID for the casino chip shop (must not collide with existing shops in DB, and must not be a GM shop:
    // 1337 or 9999992-9999999, which ShopFactory would hide from players).
    public static final int CASINO_SHOP_ID = 9999001;

    // Chip definitions: itemId -> meso value (buy AND sell price)
    private static final Map<Integer, Integer> CHIPS = new LinkedHashMap<>();

    static {
        CHIPS.put(4002000, 10_000);     // Snail Stamp      = 10k   chip
        CHIPS.put(4002001, 50_000);     // Blue Snail Stamp = 50k   chip
        CHIPS.put(4002002, 250_000);    // Stump Stamp      = 250k  chip
        CHIPS.put(4002003, 1_000_000);  // Slime Stamp      = 1m    chip
    }

    /** The chip table in display order (also the order the migration inserts shop items). */
    public static Map<Integer, Integer> getChips() {
        return CHIPS;
    }

    public static boolean isCasinoChip(int itemId) {
        return CHIPS.containsKey(itemId);
    }

    /** Face value of one chip in mesos, or -1 when {@code itemId} is not a chip. */
    public static int faceValue(int itemId) {
        return CHIPS.getOrDefault(itemId, -1);
    }

    /**
     * What the chip shop pays for {@code quantity} chips: face value times quantity, so buy-then-sell returns the same
     * mesos back. -1 for anything that is not a chip ({@code Shop.sell} then falls back to the WZ price), and saturated
     * at {@link Integer#MAX_VALUE} instead of wrapping for stack-sized quantities.
     */
    public static int sellValue(int itemId, int quantity) {
        int face = faceValue(itemId);
        if (face < 0 || quantity <= 0) {
            return -1;
        }
        long total = (long) face * quantity;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
