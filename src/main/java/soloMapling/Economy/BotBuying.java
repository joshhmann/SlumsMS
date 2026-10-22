package soloMapling.Economy;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.qa.QaLog;

import java.util.List;

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
                // 1.5.0 rc3 review: the instance bought, never just any stack of the id (findById could move the
                // bot's own better piece, or a stack with other flags, into the pool)
                held = findBought(new java.util.ArrayList<>(inv.list()), bought);
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
                PendingGrants.giveBack(bot, copy, "resale hand-off");
            }
            if (QaLog.on(QaLog.Topic.TRADE)) {
                QaLog.any(QaLog.Topic.TRADE, String.format("RESALE %s bought %d from a player for %,d: %s",
                        bot.getName(), bought.getItemId(), price, pooled ? "to the resale pool" : "kept"));
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(BotBuying.class).debug("resale hand-off", e);
        }
    }

    /**
     * The item in {@code held} that is the one bought: that very instance when the bot holds it (a trade hands the
     * window's equip over as is); else, for an equip, one of the same id with exactly its stats, slots, level, flags,
     * owner and expiry (a shop hands over a copy; two such are the same thing); for a stack, a stack of the id with
     * the same flags, owner and expiry (stacks merge on the way in). Null when none matches: the bot keeps it.
     */
    static Item findBought(List<Item> held, Item bought) {
        if (bought == null || held == null) {
            return null;
        }
        for (Item i : held) {
            if (i == bought) {
                return i;
            }
        }
        for (Item i : held) {
            if (i.getItemId() != bought.getItemId() || i.getFlag() != bought.getFlag()
                    || i.getExpiration() != bought.getExpiration()
                    || !java.util.Objects.equals(i.getOwner(), bought.getOwner())) {
                continue;
            }
            if (bought instanceof Equip b) {
                if (i instanceof Equip e && sameEquip(e, b)) {
                    return i;
                }
            } else if (!(i instanceof Equip)) {
                return i;
            }
        }
        return null;
    }

    private static boolean sameEquip(Equip a, Equip b) {
        return a.getUpgradeSlots() == b.getUpgradeSlots() && a.getLevel() == b.getLevel()
                && a.getVicious() == b.getVicious() && a.getStr() == b.getStr() && a.getDex() == b.getDex()
                && a.getInt() == b.getInt() && a.getLuk() == b.getLuk() && a.getHp() == b.getHp()
                && a.getMp() == b.getMp() && a.getWatk() == b.getWatk() && a.getMatk() == b.getMatk()
                && a.getWdef() == b.getWdef() && a.getMdef() == b.getMdef() && a.getAcc() == b.getAcc()
                && a.getAvoid() == b.getAvoid() && a.getHands() == b.getHands() && a.getSpeed() == b.getSpeed()
                && a.getJump() == b.getJump();
    }

    static boolean isEquipTab(Item item) {
        return item.getInventoryType() == InventoryType.EQUIP;
    }
}
