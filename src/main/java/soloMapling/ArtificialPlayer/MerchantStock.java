package soloMapling.ArtificialPlayer;

import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeSM;
import soloMapling.ArtificialPlayer.BotTradeSystem.ItemQuantity;
import soloMapling.ArtificialPlayer.BotTypes.NXMerchantBot;
import soloMapling.itemPool.ItemInformationProviderUtilities;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * What a merchant bot has on offer right now (ticket 26), read from its live trade state: the item it sells and
 * its asking price, or the item it buys and what it pays. It goes into the chat prompt so the model names the real
 * item, and into the scripted line for "what are you selling?" when the model does not answer. A bot that is not
 * trading anything has no stock.
 */
public final class MerchantStock {

    /** The NX merchant trades a filler item; what it really sells is a code. */
    static final Stock NX_CODE = new Stock(true, "10k nx code", 50_000_000, "nx");

    static final List<String> SELLING_LINES = List.of("selling %s, %s", "just %s rn, %s", "%s for %s, trade me",
            "got %s, %s", "only %s atm, %s");
    static final List<String> BUYING_LINES = List.of("buying %s, paying %s", "lf %s, %s", "b> %s for %s, trade me",
            "need %s, paying %s");

    private static final Set<String> NOT_KEY = Set.of("scroll", "for", "the", "and", "with", "dark", "of", "att", "acc",
            "def", "int", "dex", "luk", "str");

    private static volatile IntFunction<String> itemNames = ItemInformationProviderUtilities::getItemName;

    /**
     * @param selling true when the bot sells the item, false when it buys it
     * @param price   the asking price, or what it pays
     * @param keyWord a word of the item name a reply must contain to count as naming it
     */
    public record Stock(boolean selling, String item, int price, String keyWord) {

        /** For the prompt: what the bot trades, as a fact its reply must keep to. */
        public String fact() {
            return (selling ? "You are selling " + item + " for " + mesos(price)
                    + " right now; to buy it they trade you and put in the mesos."
                    : "You are buying " + item + " right now and pay " + mesos(price)
                    + "; to sell it to you they trade you the item.")
                    + " If they ask what you sell or buy, name that item and price. Do not make up other items or"
                    + " prices, and never give anything for free.";
        }

        /** For a "what are you selling?": the answer the reply must give. */
        public String answer() {
            return selling ? "Tell them you are selling " + item + " for " + mesos(price) + " and they can trade you for it."
                    : "Tell them you are buying " + item + " and pay " + mesos(price) + ".";
        }

        /** A line naming the stock, for when the model does not answer. */
        public String scripted(Random rng) {
            List<String> lines = selling ? SELLING_LINES : BUYING_LINES;
            return String.format(lines.get(rng.nextInt(lines.size())), item.toLowerCase(Locale.ROOT), mesos(price));
        }
    }

    private MerchantStock() {
    }

    /** The bot's current stock, or null when it is not selling or buying anything. */
    public static Stock of(BotSM bot) {
        if (bot == null) {
            return null;
        }
        if (bot instanceof NXMerchantBot) {
            return NX_CODE;
        }
        try {
            BotTradeSM.TradeMode mode = bot.getTradeMode();
            if (mode == BotTradeSM.TradeMode.SELLING && bot.getTradeInventory() != null && bot.getTradeWants() != null) {
                Item item = bot.getTradeInventory().getMainItemForSale();
                return item == null ? null : stock(true, item.getItemId(), bot.getTradeWants().getMesoWanted());
            }
            if (mode == BotTradeSM.TradeMode.BUYING && bot.getTradeWants() != null) {
                ItemQuantity wanted = bot.getTradeWants().getItemQuantityAt(0);
                return wanted == null ? null : stock(false, wanted.getItemId(), bot.getTradeWants().getMesoOffering());
            }
        } catch (RuntimeException e) {
            return null; // a half-built trade state: no stock to talk about
        }
        return null;
    }

    private static Stock stock(boolean selling, int itemId, int price) {
        String name = itemNames.apply(itemId);
        if (name == null || name.isBlank() || price <= 0) {
            return null;
        }
        name = name.replace("[", "").replace("]", "").trim();
        return new Stock(selling, name, price, keyWord(name));
    }

    /** The word of an item name a reply must contain ("gloves" for "Scroll for Gloves for ATT 60%"). */
    static String keyWord(String item) {
        String fallback = null;
        for (String word : item.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").split("\\s+")) {
            if (word.length() < 3) {
                continue;
            }
            if (!NOT_KEY.contains(word)) {
                return word;
            }
            if (fallback == null) {
                fallback = word;
            }
        }
        return fallback;
    }

    /** A price the way players type it: 900, 250k, 1.5m, 12m. */
    static String mesos(int price) {
        if (price < 1_000) {
            return String.valueOf(price);
        }
        String[] units = {"k", "m", "b"};
        double value = price;
        int unit = -1;
        while (value >= 1_000 && unit < units.length - 1) {
            value /= 1_000;
            unit++;
        }
        String text = value >= 100 ? String.valueOf(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value).replaceAll("\\.0$", "");
        return text + units[unit];
    }

    /** Test seam: item names without the WZ files (null restores the real lookup). */
    static void useItemNamesForTest(IntFunction<String> names) {
        itemNames = names != null ? names : ItemInformationProviderUtilities::getItemName;
    }
}
