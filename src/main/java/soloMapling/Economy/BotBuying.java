package soloMapling.Economy;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.qa.QaLog;

/**
 * 1.5.0 rc3, owner decision 2: what bots buy from real players, on top of ticket 56A (1.0x fair value and 5m per
 * seller account a day) and the world's daily budget ({@link EconomyMeters#tryBotBuy}, {@code
 * botBuying.worldDailyBudget}, 40m).
 * <ul>
 *   <li>Gear only when it is above average ({@link GearGrade}): bots pull the good pieces off the market at a fair
 *   price and leave the average ones to players. Godly gear stays with players (bots could not resell it).</li>
 *   <li>Never what a bot may not sell again ({@link SaleRules}), so everything bought can circulate.</li>
 *   <li>What was bought goes to the resale pool ({@link BotShops#offerResale}).</li>
 * </ul>
 */
public final class BotBuying {

    private BotBuying() {
    }

    /** Why a bot will not buy this from a player, or null when it may. */
    public static String whyNotBuyFromPlayer(Item item) {
        if (item == null) {
            return "no item";
        }
        String rule = SaleRules.whyNot(item);
        if (rule != null) {
            return "bots do not buy: " + rule;
        }
        if (item instanceof Equip eq && EconomyConfig.getBool("botBuying.equipsAboveAverageOnly", true)) {
            GearGrade.Grade g = GearGrade.grade(eq);
            if (g != GearGrade.Grade.ABOVE) {
                return "gear is " + g.name().toLowerCase() + ", bots buy only above-average gear from players";
            }
        }
        return null;
    }

    /**
     * The FM shopper just bought {@code bought} from a player for {@code price}: the item it now holds moves to the
     * resale pool (it would otherwise be thrown away when its bag fills). Never throws.
     */
    public static void toResale(Character bot, Item bought, long price) {
        try {
            Inventory inv = bot.getInventory(bought.getInventoryType());
            Item held;
            inv.lockInventory();
            try {
                held = inv.findById(bought.getItemId());
            } finally {
                inv.unlockInventory();
            }
            if (held == null) {
                return;
            }
            Item copy = held.copy();
            if (!(held instanceof Equip)) {
                copy.setQuantity((short) Math.min(held.getQuantity(), Math.max(1, bought.getQuantity())));
            }
            if (BotShops.resalePoolSize() >= EconomyConfig.getInt("botShops.resalePoolMax", 200)) {
                return;   // pool full: the shopper keeps it
            }
            // out of the bot first, then into the pool: a failure in between loses the item, never copies it
            boolean out = held instanceof Equip || held.getQuantity() <= copy.getQuantity()
                    ? BotHoldings.destroyHeldItem(bot, held)
                    : BotHoldings.consumeHeld(bot, held, copy.getQuantity()) > 0;
            if (!out) {
                return;
            }
            boolean pooled = BotShops.offerResale(copy, price);
            if (!pooled) {
                BotHoldings.grantItem(bot, copy);
            }
            if (QaLog.on(QaLog.Topic.TRADE)) {
                QaLog.any(QaLog.Topic.TRADE, String.format("RESALE %s bought %d from a player for %,d: %s",
                        bot.getName(), bought.getItemId(), price, pooled ? "to the resale pool" : "kept"));
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(BotBuying.class).debug("resale hand-off", e);
        }
    }

    static boolean isEquipTab(Item item) {
        return item.getInventoryType() == InventoryType.EQUIP;
    }
}
