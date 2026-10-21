package soloMapling.ArtificialPlayer.BotScrollSystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.qa.QaLog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Ticket 11: scrolls a training bot wants from the Free Market. Grinding bots never walk the FM rooms, so a bot that
 * wants a scroll for its gear posts it here, and the FM shopper bots (which do walk every shop, players' shops first
 * since 1.4.2) buy it for that bot when they sit in a shop that lists it at or under the wanted price.
 *
 * <p><b>Money:</b> the wanting bot pays, from its own mesos: the exact price moves from it to the shopper just before
 * the purchase and back if the purchase fails, so the shopper's allowance ({@code FMBotWallet}) is never used for
 * scrolls. The scroll then moves from the shopper to the wanting bot. A purchase from a real player's shop still goes
 * through 56A first (1.0x price ceiling, 5m per seller account per day) and counts against the wanting bot's
 * {@link ScrollBudget} (3 a day from players).
 */
public final class ScrollWants {

    static final long WANT_TTL_MS = 30 * 60_000L;
    static final int MAX_WANTS_PER_BOT = 3;

    /** One wanted scroll. The bot is held weakly: a bot that left the world drops its wants. */
    static final class Want {
        final int scrollId;
        final long maxPrice;
        final long postedAt;
        final WeakReference<Character> bot;
        final int botId;
        volatile boolean busy;

        Want(Character bot, int scrollId, long maxPrice, long postedAt) {
            this.bot = new WeakReference<>(bot);
            this.botId = bot.getId();
            this.scrollId = scrollId;
            this.maxPrice = maxPrice;
            this.postedAt = postedAt;
        }
    }

    private static final Map<Integer, List<Want>> byScroll = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private ScrollWants() {
    }

    /** Posts (or refreshes) a bot's want for a scroll at up to {@code maxPrice}. False when it has too many. */
    public static boolean post(Character bot, int scrollId, long maxPrice) {
        if (bot == null || maxPrice <= 0) {
            return false;
        }
        long now = clock.getAsLong();
        prune(now);
        int mine = 0;
        for (List<Want> list : byScroll.values()) {
            synchronized (list) {
                for (Want w : list) {
                    if (w.botId == bot.getId()) {
                        if (w.scrollId == scrollId) {
                            list.remove(w);   // refreshed below at today's price
                            break;
                        }
                        mine++;
                    }
                }
            }
        }
        if (mine >= MAX_WANTS_PER_BOT) {
            return false;
        }
        List<Want> list = byScroll.computeIfAbsent(scrollId, id -> new ArrayList<>());
        synchronized (list) {
            list.add(new Want(bot, scrollId, maxPrice, now));
        }
        return true;
    }

    /** Whether any bot wants this scroll at {@code price} or more right now. */
    public static boolean wanted(int scrollId, long price) {
        return find(scrollId, price, false) != null;
    }

    /** Number of open wants (read-out). */
    public static int open() {
        prune(clock.getAsLong());
        int n = 0;
        for (List<Want> list : byScroll.values()) {
            synchronized (list) {
                n += list.size();
            }
        }
        return n;
    }

    private static Want find(int scrollId, long price, boolean claim) {
        List<Want> list = byScroll.get(scrollId);
        if (list == null) {
            return null;
        }
        long now = clock.getAsLong();
        synchronized (list) {
            Iterator<Want> it = list.iterator();
            Want best = null;
            while (it.hasNext()) {
                Want w = it.next();
                Character bot = w.bot.get();
                if (bot == null || now - w.postedAt > WANT_TTL_MS) {
                    it.remove();
                    continue;
                }
                if (!w.busy && w.maxPrice >= price && (best == null || w.postedAt < best.postedAt)) {
                    best = w;
                }
            }
            if (best != null && claim) {
                best.busy = true;
            }
            return best;
        }
    }

    /**
     * An FM shopper fills a want: when a bot wants {@code scrollId} at {@code price}, that bot pays the shopper the
     * price, {@code buy} makes the purchase (the shopper's own shop-buy path, which spends what it was just paid),
     * and the scroll moves to the wanting bot. Every failure puts the mesos back where they were.
     *
     * @return the wanting bot's name when the scroll was bought for it, else null (nothing changed hands)
     */
    public static String fill(Character shopper, int scrollId, int price, boolean fromPlayer, BooleanSupplier buy) {
        Want w = find(scrollId, price, true);
        if (w == null) {
            return null;
        }
        Character wanter = w.bot.get();
        boolean done = false;
        boolean reserved = false;
        try {
            if (wanter == null || wanter == shopper
                    || !BotHoldings.canHold(wanter, new Item(scrollId, (short) 0, (short) 1))) {
                return null;
            }
            if (!ScrollBudget.tryBuy(wanter.getId(), wanter.getLevel(), wanter.getMeso(), price, fromPlayer)) {
                qa(shopper, "skips " + scrollId + " @ " + price + " for " + wanter.getName() + ": its scroll budget"
                        + " says no (" + ScrollBudget.status(wanter.getId(), wanter.getLevel()) + ")");
                return null;
            }
            reserved = true;
            if (!BotHoldings.spendMeso(wanter, price)) {
                return null;
            }
            if (!BotHoldings.grantMeso(shopper, price)) {
                BotHoldings.grantMeso(wanter, price);
                return null;
            }
            int before = shopper.getInventory(InventoryType.USE).countById(scrollId);
            boolean bought = buy.getAsBoolean()
                    && shopper.getInventory(InventoryType.USE).countById(scrollId) > before;
            if (!bought) {
                if (BotHoldings.spendMeso(shopper, price)) {
                    BotHoldings.grantMeso(wanter, price);
                }
                return null;
            }
            Item held = shopper.getInventory(InventoryType.USE).findById(scrollId);
            if (held == null || BotHoldings.consumeHeld(shopper, held, 1) != 1) {
                return null;   // the shopper keeps it; the wanting bot paid for nothing - should not happen
            }
            if (!BotHoldings.grantItem(wanter, new Item(scrollId, (short) 0, (short) 1))) {
                BotHoldings.grantItem(shopper, new Item(scrollId, (short) 0, (short) 1));
                if (BotHoldings.spendMeso(shopper, price)) {
                    BotHoldings.grantMeso(wanter, price);
                }
                return null;
            }
            done = true;
            ScrollLedger.bought(fromPlayer ? ScrollLedger.Source.PLAYER_SHOP : ScrollLedger.Source.BOT_SHOP, price);
            remove(w);
            BotScrolling.noteBought(wanter, scrollId, price, fromPlayer ? "a player's FM shop (via "
                    + shopper.getName() + ")" : "a bot FM shop (via " + shopper.getName() + ")");
            return wanter.getName();
        } finally {
            if (!done) {
                if (reserved) {
                    ScrollBudget.refund(w.botId, price, fromPlayer);
                }
                w.busy = false;
            }
        }
    }

    private static void remove(Want w) {
        List<Want> list = byScroll.get(w.scrollId);
        if (list != null) {
            synchronized (list) {
                list.remove(w);
            }
        }
    }

    private static void prune(long now) {
        for (List<Want> list : byScroll.values()) {
            synchronized (list) {
                list.removeIf(w -> w.bot.get() == null || now - w.postedAt > WANT_TTL_MS);
            }
        }
    }

    private static void qa(Character shopper, String what) {
        if (QaLog.on(QaLog.Topic.FM_BUY) && shopper.getMap() != null) {
            QaLog.map(QaLog.Topic.FM_BUY, shopper.getMapId(), shopper.getName() + " " + what);
        }
    }

    public static void forget(int botId) {
        for (List<Want> list : byScroll.values()) {
            synchronized (list) {
                list.removeIf(w -> w.botId == botId);
            }
        }
    }

    // ---- test seams ----

    static void useClockForTest(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }

    static void clearForTest() {
        byScroll.clear();
    }
}
