package soloMapling.Economy;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotWealth;
import soloMapling.qa.QaLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 1.6.0 (owner 2026-10-26): a TRADE thread between two bots can end in a real trade. Before the talk is written,
 * {@link #plan} picks a real item the seller holds and the price, and decides agree or decline from structured data;
 * the writer is told the outcome (it never decides it). After the lines, {@link #execute} swaps the item for the mesos
 * server-side, both or neither.
 * <ul>
 *   <li><b>Items</b> (owner 2026-10-26, "small and rare"): common things only - an ETC stack, or potions and food out of
 *   the USE bag ({@link ItemConstants#isPotion}, {@link ItemConstants#isFood}); never equips, scrolls, set-up items,
 *   stars or bullets, and never what {@link SaleRules} refuses (cash, quest, untradeable, boss-only). Potions: half the
 *   stack at most and {@code botBotTrade.maxPotionUnits} (100), at least 10, so the seller keeps its kit.</li>
 *   <li><b>Price:</b> where both bots' bands meet: a bot never sells under {@code bands.botSellsLow} (0.95) of fair
 *   value and never pays over {@code bands.botBuysHigh} (0.95), so the price is 0.95 x {@link PriceBook#stackValue}.
 *   Only {@code botBotTrade.minPrice} (1,000) to {@code botBotTrade.maxPrice} (300,000).</li>
 *   <li><b>Money:</b> the buyer keeps {@code directTrade.reserveShareOfCeiling} (0.25) of its wealth ceiling, and the
 *   seller must stay at or under its ceiling after the sale (a sale never pushes it into {@link BotWealth} upkeep).</li>
 *   <li><b>Caps:</b> {@code botBotTrade.perBotPerDay} (3) per bot per UTC day, either side, and
 *   {@code botBotTrade.worldPerHour} (30) world-wide per rolling hour.</li>
 *   <li><b>Who:</b> roster bots only: never an owned alt or a follower (a player's companion), never a bot in a
 *   trade window.</li>
 * </ul>
 * <b>The market never sees these</b> (owner: the Slums Market chart moves on its own, "no artificial"): no price-board
 * sample ({@link PriceBook#record} refuses a sample between two bots as well), so no sales.json, daily prices, NX rate
 * or snapshot item price; no trade rating; no {@code Trade} window, so no {@code SocialHooks.onTradeDone}. The mesos
 * only move between two bots: the economy counters count them as a bot-to-bot transfer
 * ({@code EconomyCounters.recordBotTransfer}), never as money in or out. Bot wallets never feed the player wealth index.
 */
public final class BotBotTrade {

    private static final Logger log = LoggerFactory.getLogger(BotBotTrade.class);

    static final long HOUR_MS = 3_600_000L;
    static final long DAY_MS = 86_400_000L;

    /**
     * A planned trade: {@code seller} offers {@code item} ({@code quantity} of it) to {@code buyer} for {@code price}.
     * {@code agreed}: the buyer takes it; else {@code reason} says why not.
     */
    public record Deal(Character seller, Character buyer, Item item, int itemId, int quantity, long price,
                       boolean agreed, String reason, String itemName) {
        /** The decision for the social trace: "sell Work Gloves for 95,000" or "no deal". */
        public String decision() {
            return agreed ? "sell " + itemName + " for " + String.format(Locale.ROOT, "%,d", price) : "no deal";
        }

        /** The price as bots write it ("95k"). */
        public String priceWords() {
            return PriceBook.shorthand(price);
        }

        /** What the thread is about ("Bob sells Work Gloves for 95k"). */
        public String about() {
            return seller.getName() + " sells " + itemName + (quantity > 1 ? " x" + quantity : "") + " for "
                    + priceWords();
        }
    }

    /** What {@link #execute} did: {@code ok}, or why not. */
    public record Result(boolean ok, String why) {
    }

    private static final Map<Integer, long[]> perBot = new ConcurrentHashMap<>();   // bot id -> {day, count}
    private static final Deque<Long> worldHour = new ArrayDeque<>();                  // guarded by BotBotTrade.class
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private BotBotTrade() {
    }

    public static boolean enabled() {
        return EconomyConfig.getBool("botBotTrade.enabled", true);
    }

    // ── planning (before the talk is written) ──

    /**
     * A deal for two bots about to talk trade, or null when there is none to talk about (switched off, a bot that may
     * not trade, a cap reached, or neither holds an item the other could buy). {@code a} is tried as the seller first.
     * Never throws.
     */
    public static Deal plan(Character a, Character b, Random rng) {
        try {
            if (!enabled() || a == null || b == null || a == b || !mayTrade(a) || !mayTrade(b)
                    || !capsLeft(a.getId(), b.getId(), clock.getAsLong())) {
                return null;
            }
            Deal d = offer(a, b, rng);
            if (d == null) {
                d = offer(b, a, rng);
            }
            return d;
        } catch (RuntimeException | LinkageError e) {
            log.debug("bot-to-bot trade plan for {} and {}", a == null ? null : a.getName(),
                    b == null ? null : b.getName(), e);
            return null;
        }
    }

    /** The item {@code seller} would offer {@code buyer} and the buyer's answer, or null when it has nothing to offer. */
    static Deal offer(Character seller, Character buyer, Random rng) {
        List<Item> candidates = new ArrayList<>();
        for (InventoryType t : new InventoryType[]{InventoryType.ETC, InventoryType.USE}) {
            Inventory inv = seller.getInventory(t);
            List<Item> items;
            inv.lockInventory();
            try {
                items = new ArrayList<>(inv.list());
            } finally {
                inv.unlockInventory();
            }
            for (Item it : items) {
                if (offerable(it) && price(it, units(it)) > 0) {
                    candidates.add(it);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // its better things first: one of its top three lots
        candidates.sort((x, y) -> Long.compare(price(y, units(y)), price(x, units(x))));
        Item item = candidates.get(rng.nextInt(Math.min(3, candidates.size())));
        int units = units(item);
        long price = price(item, units);
        Item lot = lot(item, units);
        String name = BotShops.nameOf(item.getItemId());
        String why = refusal(seller, buyer, lot, price, rng);
        return new Deal(seller, buyer, item, item.getItemId(), units, price, why == null, why, name);
    }

    /**
     * A common item that may change hands bot to bot: in the bag (not worn), an ETC item or a potion or food, and
     * allowed by the sale rules. Never an equip, a scroll, a set-up item or ammo.
     */
    static boolean offerable(Item it) {
        if (it == null || it.getPosition() <= 0 || it.getQuantity() <= 0
                || ItemConstants.isRechargeable(it.getItemId())) {
            return false;
        }
        InventoryType t = it.getInventoryType();
        boolean common = t == InventoryType.ETC || t == InventoryType.USE
                && (ItemConstants.isPotion(it.getItemId()) || ItemConstants.isFood(it.getItemId()));
        return common && SaleRules.botMaySell(it);
    }

    /** How many of the stack go: an ETC stack whole; potions and food half the stack, at most maxPotionUnits; 0: none. */
    static int units(Item it) {
        if (it.getInventoryType() != InventoryType.USE) {
            return it.getQuantity();
        }
        int n = Math.min(it.getQuantity() / 2, Math.max(1, EconomyConfig.getInt("botBotTrade.maxPotionUnits", 100)));
        return n >= 10 ? n : 0;
    }

    /** The lot that changes hands: {@code units} of the item. */
    static Item lot(Item item, int units) {
        Item lot = item.copy();
        lot.setQuantity((short) units);
        return lot;
    }

    static long price(Item item, int units) {
        return units <= 0 ? 0 : price(lot(item, units));
    }

    /**
     * The price two bots settle at: where the seller's band floor and the buyer's band ceiling meet (both 0.95 of fair
     * value by default), rounded down as players write it when that stays inside; 0 when the bands do not meet or the
     * price is outside {@code botBotTrade.minPrice}..{@code maxPrice}.
     */
    static long price(Item item) {
        long fair = PriceBook.stackValue(item);
        if (PriceBook.fairValue(item) <= 0) {
            return 0;   // nobody can price it
        }
        long lo = PriceBand.of(PriceBand.Side.BOT_SELLS, fair, false).low();
        long hi = PriceBand.of(PriceBand.Side.BOT_BUYS, fair, false).high();
        if (lo > hi) {
            return 0;
        }
        long p = PriceBand.nice(hi, true);
        if (p < lo) {
            p = hi;
        }
        long min = Math.max(1, EconomyConfig.getLong("botBotTrade.minPrice", 1_000));
        long max = Math.min(Integer.MAX_VALUE, EconomyConfig.getLong("botBotTrade.maxPrice", 300_000));
        return p < min || p > max ? 0 : p;
    }

    /** Why the buyer says no (null: it agrees). Money first, then the bond, then its mood. */
    static String refusal(Character seller, Character buyer, Item item, long price, Random rng) {
        String money = moneyProblem(seller, buyer, item, price);
        if (money != null) {
            return money;
        }
        if (BotBonds.state(buyer.getId(), seller.getId()).hostile()) {
            return "they do not get along";
        }
        double chance = BotBonds.friend(buyer.getId(), seller.getId())
                ? EconomyConfig.getDouble("botBotTrade.agreeChanceFriend", 0.8)
                : EconomyConfig.getDouble("botBotTrade.agreeChance", 0.55);
        return rng.nextDouble() < chance ? null : "not interested";
    }

    /** Whether the money works for both sides, or why not. */
    static String moneyProblem(Character seller, Character buyer, Item item, long price) {
        if (price <= 0 || price > Integer.MAX_VALUE) {
            return "no price";
        }
        if (spendable(buyer) < price) {
            return "too pricey for them";
        }
        if (!seller.canHoldMeso((int) price)) {
            return "the seller's wallet is full";
        }
        if (BotWealth.enabled() && (long) seller.getMeso() + price > BotWealth.ceiling(seller)) {
            return "the seller has enough mesos";
        }
        if (!BotHoldings.canHold(buyer, item)) {
            return "no room in the buyer's bag";
        }
        return null;
    }

    /** What the buyer may spend: its mesos less the direct-trade reserve (a share of its wealth ceiling). */
    static long spendable(Character buyer) {
        double keep = EconomyConfig.getDouble("directTrade.reserveShareOfCeiling", 0.25);
        long reserve = (long) (BotWealth.ceiling(buyer) * Math.max(0, keep));
        return Math.max(0, (long) buyer.getMeso() - reserve);
    }

    /**
     * A roster bot that is free to trade: not a player's owned alt or follower (companion), not in a trade window, not
     * a merchant type (they run their own trade flow).
     */
    static boolean mayTrade(Character c) {
        if (!BotHelpers.isRosterBot(c) || c.getTrade() != null) {
            return false;
        }
        BotSM sm = CharacterStorage.getBotById(c.getId());
        return !(sm instanceof soloMapling.ArtificialPlayer.BotTypes.FollowerBot)
                && (sm == null || !BotDirectTrade.handlesOwnTrades(sm));
    }

    // ── caps ──

    static boolean capsLeft(int a, int b, long now) {
        return botLeft(a, now) && botLeft(b, now) && worldLeft(now);
    }

    private static boolean botLeft(int botId, long now) {
        long[] c = perBot.get(botId);
        int cap = Math.max(0, EconomyConfig.getInt("botBotTrade.perBotPerDay", 3));
        return c == null || c[0] != now / DAY_MS || c[1] < cap;
    }

    private static synchronized boolean worldLeft(long now) {
        while (!worldHour.isEmpty() && now - worldHour.peekFirst() >= HOUR_MS) {
            worldHour.pollFirst();
        }
        return worldHour.size() < Math.max(0, EconomyConfig.getInt("botBotTrade.worldPerHour", 30));
    }

    /** Takes one trade from both bots' day and the world's hour, or false when a cap is reached. */
    static synchronized boolean claim(int a, int b, long now) {
        if (!capsLeft(a, b, now)) {
            return false;
        }
        long day = now / DAY_MS;
        for (int id : new int[]{a, b}) {
            perBot.compute(id, (k, c) -> c == null || c[0] != day ? new long[]{day, 1} : new long[]{day, c[1] + 1});
        }
        worldHour.addLast(now);
        if (perBot.size() > 20_000) {
            perBot.values().removeIf(c -> c[0] != day);
        }
        return true;
    }

    /** Gives a claim back (the swap did not happen). */
    static synchronized void unclaim(int a, int b, long now) {
        long day = now / DAY_MS;
        for (int id : new int[]{a, b}) {
            perBot.computeIfPresent(id, (k, c) -> c[0] == day && c[1] > 1 ? new long[]{day, c[1] - 1} : null);
        }
        worldHour.removeLastOccurrence(now);
    }

    // ── the swap ──

    /**
     * Carries out an agreed deal: the buyer's mesos to the seller, the item to the buyer, through {@link BotHoldings}
     * (each bot's ledger, so a restart or a failed save never mints or loses either side). Everything is checked again
     * first; any step that fails puts back the steps before it, so both bots end as they started. Never throws.
     */
    public static Result execute(Deal d) {
        if (d == null || !d.agreed()) {
            return new Result(false, "no deal");
        }
        Character seller = d.seller();
        Character buyer = d.buyer();
        try {
            if (!enabled()) {
                return new Result(false, "switched off");
            }
            if (!mayTrade(seller) || !mayTrade(buyer)) {
                return new Result(false, "a bot is busy or may not trade");
            }
            if (seller.getMapId() != buyer.getMapId()) {
                return new Result(false, "they are no longer on one map");
            }
            Item item = d.item();
            if (!held(seller, item) || !offerable(item) || units(item) != d.quantity()) {
                return new Result(false, "the seller no longer has it");
            }
            String money = moneyProblem(seller, buyer, lot(item, d.quantity()), d.price());
            if (money != null) {
                return new Result(false, money);
            }
            long now = clock.getAsLong();
            if (!claim(seller.getId(), buyer.getId(), now)) {
                return new Result(false, "a trade cap is reached");
            }
            String failed = swap(seller, buyer, item, d.quantity(), (int) d.price());
            if (failed != null) {
                unclaim(seller.getId(), buyer.getId(), now);
                qa("BOT TRADE FAILED " + seller.getName() + " -> " + buyer.getName() + " " + d.itemName() + " "
                        + d.price() + ": " + failed);
                return new Result(false, failed);
            }
        } catch (RuntimeException | LinkageError e) {
            log.warn("bot-to-bot trade {} -> {}", seller.getName(), buyer.getName(), e);
            return new Result(false, "error: " + e);
        }
        afterSwap(d);
        return new Result(true, "sold " + d.itemName() + " to " + buyer.getName() + " for "
                + String.format(Locale.ROOT, "%,d", d.price()));
    }

    /**
     * The swap itself, both or neither: the buyer pays, the seller is paid, {@code units} of the item leave the seller
     * (the whole stack, or part of a potion stack), they reach the buyer. Null when it all went through, else why not
     * (and what was done is undone).
     */
    static String swap(Character seller, Character buyer, Item item, int units, int price) {
        if (!BotHoldings.spendMeso(buyer, price)) {
            return "the buyer could not pay";
        }
        if (!BotHoldings.grantMeso(seller, price)) {
            refund(buyer, price);
            return "the seller could not take the mesos";
        }
        boolean whole = units >= item.getQuantity();
        Item moved = lot(item, units);
        Item back;   // what goes back to the seller if the buyer cannot take it
        if (whole) {
            if (!BotHoldings.destroyHeldItem(seller, item)) {
                takeBack(seller, price);
                refund(buyer, price);
                return "the item left the seller's bag";
            }
            back = item;
        } else {
            int taken = BotHoldings.consumeHeld(seller, item, units);
            if (taken != units) {
                if (taken > 0 && !BotHoldings.grantItem(seller, lot(item, taken))) {
                    PendingGrants.giveBack(seller, lot(item, taken), "bot-to-bot trade undone");
                }
                takeBack(seller, price);
                refund(buyer, price);
                return "the item left the seller's bag";
            }
            back = lot(item, units);
        }
        if (!BotHoldings.grantItem(buyer, moved)) {
            if (!BotHoldings.grantItem(seller, back)) {
                PendingGrants.giveBack(seller, back, "bot-to-bot trade undone");
            }
            takeBack(seller, price);
            refund(buyer, price);
            return "no room in the buyer's bag";
        }
        return null;
    }

    private static void refund(Character buyer, int price) {
        if (!BotHoldings.grantMeso(buyer, price)) {
            log.warn("bot-to-bot trade: could not refund {} mesos to {}", price, buyer.getName());
        }
    }

    private static void takeBack(Character seller, int price) {
        if (!BotHoldings.spendMeso(seller, price)) {
            log.warn("bot-to-bot trade: could not take {} mesos back from {}", price, seller.getName());
        }
    }

    private static boolean held(Character bot, Item item) {
        try {
            for (Item i : bot.getInventory(item.getInventoryType()).list()) {
                if (i == item) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return false;
    }

    /** The bookkeeping after a swap: a bot-to-bot transfer, a bond, the QA line. No price sample, no trade rating. */
    private static void afterSwap(Deal d) {
        try {
            soloMapling.Economy.stats.EconomyCounters.recordBotTransfer(d.price());
        } catch (RuntimeException | LinkageError e) {
            log.debug("bot transfer counter", e);
        }
        try {
            BotBonds.onTrade(d.seller(), true, d.buyer(), true);
        } catch (RuntimeException | LinkageError e) {
            log.debug("bot-to-bot trade bond", e);
        }
        qa("BOT TRADE " + d.seller().getName() + " -> " + d.buyer().getName() + " " + d.itemName()
                + (d.quantity() > 1 ? " x" + d.quantity() : "") + " " + d.price());
    }

    private static void qa(String line) {
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, line);
        }
    }

    // ── test seams ──

    static void resetForTest(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
        perBot.clear();
        synchronized (BotBotTrade.class) {
            worldHour.clear();
        }
    }
}
