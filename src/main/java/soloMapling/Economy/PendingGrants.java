package soloMapling.Economy;

import client.Character;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.qa.QaLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * 1.5.0 rc3 review: an item owed back to a bot that it cannot take right now (its bag is full) is never dropped.
 * Direct trades, bot shops and the resale hand-off used to call {@link BotHoldings#grantItem} and ignore a false, so
 * the item was simply gone. Now it waits here for the bot, with a warning in the log naming the item, and is handed
 * over at the bot's next town stop or trade ({@link #deliver}) once it has room. The holding store is in memory: the
 * log line is the record if the server restarts first.
 */
public final class PendingGrants {

    private static final Logger log = LoggerFactory.getLogger(PendingGrants.class);

    private static final Map<Integer, List<Item>> pending = new ConcurrentHashMap<>();
    private static volatile BiPredicate<Character, Item> granter = BotHoldings::grantItem;

    private PendingGrants() {
    }

    /** Gives {@code item} back to the bot; when it cannot take it now, keeps it for the bot. True when it took it. */
    public static boolean giveBack(Character bot, Item item, String why) {
        if (item == null) {
            return true;
        }
        if (bot == null) {
            log.error("[PendingGrants] {} x{} has no bot to go back to ({}); it is lost", item.getItemId(),
                    item.getQuantity(), why);
            return false;
        }
        try {
            if (granter.test(bot, item)) {
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("[PendingGrants] granting {} to {} failed", item.getItemId(), bot.getName(), e);
        }
        hold(bot.getId(), bot.getName(), item, why);
        return false;
    }

    /** Keeps {@code item} for the bot until it has room (the bot may be offline). */
    public static void hold(int botId, String botName, Item item, String why) {
        if (item == null) {
            return;
        }
        List<Item> list = pending.computeIfAbsent(botId, k -> new ArrayList<>());
        int n;
        synchronized (list) {
            list.add(item);
            n = list.size();
        }
        log.warn("[PendingGrants] {} (id {}) could not take back item {} x{} ({}); kept for it ({} waiting)",
                botName, botId, item.getItemId(), item.getQuantity(), why, n);
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, "PENDING " + botName + " owed item " + item.getItemId() + " x"
                    + item.getQuantity() + " (" + why + ")");
        }
    }

    /** Hands the bot what waits for it, as far as it has room. The number handed over. Never throws. */
    public static int deliver(Character bot) {
        if (bot == null) {
            return 0;
        }
        List<Item> list = pending.get(bot.getId());
        if (list == null) {
            return 0;
        }
        int given = 0;
        synchronized (list) {
            for (int i = 0; i < list.size(); ) {
                Item item = list.get(i);
                boolean took;
                try {
                    took = granter.test(bot, item);
                } catch (RuntimeException e) {
                    took = false;
                }
                if (took) {
                    list.remove(i);
                    given++;
                } else {
                    i++;
                }
            }
            if (list.isEmpty()) {
                pending.remove(bot.getId(), list);
            }
        }
        if (given > 0) {
            log.info("[PendingGrants] {} took back {} waiting item(s)", bot.getName(), given);
        }
        return given;
    }

    /** Items waiting for this bot. */
    public static int waiting(int botId) {
        List<Item> list = pending.get(botId);
        if (list == null) {
            return 0;
        }
        synchronized (list) {
            return list.size();
        }
    }

    // ---- test seams ----

    static void useForTest(BiPredicate<Character, Item> testGranter) {
        granter = testGranter == null ? BotHoldings::grantItem : testGranter;
        pending.clear();
    }
}
