package soloMapling.Economy;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules;
import soloMapling.ArtificialPlayer.BotWealth;
import soloMapling.qa.QaLog;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 1.5.0 rc3, owner decision 4: a friend bot now and then gives a player it likes a small gift.
 * <ul>
 *   <li><b>Who:</b> a bot whose affinity state toward the player is friend ({@link BotAffinity#friend}, the public
 *   API), when it notices them nearby ({@code BotPlayerReaction}), with {@code gifts.chancePerSighting} (3%).</li>
 *   <li><b>How often:</b> at most once per {@code gifts.perBotDays} (7) days per bot and player (kept in
 *   {@code botgifts}, migration 020), at most {@code gifts.perAccountPerDay} (2) a day per player account (alts
 *   share it), and at most {@code gifts.worldPerDay} (20) gifts a day world-wide.</li>
 *   <li><b>What:</b> potions for the player's level (the bot pays the NPC price), a common scroll from its own bag
 *   (60%+ plain scroll worth at most {@code gifts.scrollMaxValue}), or {@code gifts.mesoMin}..{@code gifts.mesoMax}
 *   (100k-500k) of its own mesos when it keeps half its wealth ceiling. Nothing is minted: every gift comes out of the
 *   bot's purse or bag.</li>
 *   <li><b>How:</b> the items drop at the player's feet, theirs alone; mesos go straight to them with a message.</li>
 * </ul>
 */
public final class BotGifts {

    private static final Logger log = LoggerFactory.getLogger(BotGifts.class);

    public enum Kind { POTIONS, SCROLL, MESOS }

    private static final Map<Long, Long> lastGift = new ConcurrentHashMap<>();
    private static volatile EconomyStore store = new EconomyStore.Jdbc();
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private BotGifts() {
    }

    public static boolean enabled() {
        return EconomyConfig.getBool("gifts.enabled", true);
    }

    static long key(int botId, int playerId) {
        return ((long) botId << 32) ^ (playerId & 0xffffffffL);
    }

    static long periodMs() {
        return TimeUnit.DAYS.toMillis(Math.max(0, EconomyConfig.getLong("gifts.perBotDays", 7)));
    }

    /**
     * A bot noticed a player near it: maybe a gift. True when it decided to give one (the gift itself lands a beat
     * later, off this thread). Cheap when nothing happens: a memory read and a roll.
     */
    public static boolean maybeGift(Character bot, Character player) {
        if (!enabled() || bot == null || player == null || soloMapling.ArtificialPlayer.PlayerBusy.isBusy(player) || BotHelpers.isBot(player)
                || !BotAffinity.friend(bot.getId(), player.getId())) {
            return false;
        }
        Random rng = ThreadLocalRandom.current();
        if (rng.nextDouble() >= EconomyConfig.getDouble("gifts.chancePerSighting", 0.03)) {
            return false;
        }
        if (!soloMapling.ArtificialPlayer.SocialGate.allow(bot, player, soloMapling.ArtificialPlayer.SocialGate.Act.GIFT)) {
            return false; // 1.6.0 S5: every unprompted act goes through the social gate (logged)
        }
        long k = key(bot.getId(), player.getId());
        Long last = lastGift.get(k);
        long now = clock.getAsLong();
        if (last != null && now - last < periodMs()) {
            return false;
        }
        int account = player.getAccountID();
        if (!claimAccount(account)) {
            return false;   // this player's account (alts included) had its gifts for today
        }
        if (!EconomyMeters.tryAdd(EconomyMeters.GIFTS, 1, Math.max(0, EconomyConfig.getLong("gifts.worldPerDay", 20)))) {
            releaseAccount(account);
            return false;
        }
        lastGift.put(k, now);   // claim it now, so the next sighting does not race the store read
        // rc6 bug hunt: forget gifts older than the period (the store still has them), so the map stays bounded
        long period = periodMs();
        soloMapling.server.BoundedMaps.prune(lastGift, 5_000, 20_000, at -> now - at >= period);
        ExecutorServiceManager.runAsync(() -> {
            long stored = store.lastGift(bot.getId(), player.getId());
            if (stored > 0 && now - stored < periodMs()) {
                lastGift.put(k, stored);
                EconomyMeters.add(EconomyMeters.GIFTS, -1);
                releaseAccount(account);
                return;
            }
            String line = give(bot, player, null, rng);
            if (line == null) {
                EconomyMeters.add(EconomyMeters.GIFTS, -1);
                releaseAccount(account);
                lastGift.remove(k);
            }
        });
        return true;
    }

    /**
     * 1.5.0 rc3 review: gifts were limited per character only, so one person's alts could take most of the world's
     * {@code gifts.worldPerDay}. An account gets at most {@code gifts.perAccountPerDay} (2) a day, counted in the
     * daily meters (kept across a restart). True when one was claimed for it.
     */
    static boolean claimAccount(int account) {
        long cap = Math.max(0, EconomyConfig.getLong("gifts.perAccountPerDay", 2));
        return EconomyMeters.tryAdd(accountMeter(account), 1, cap);
    }

    static void releaseAccount(int account) {
        EconomyMeters.add(accountMeter(account), -1);
    }

    static String accountMeter(int account) {
        return EconomyMeters.GIFT_ACCOUNT_PREFIX + account;
    }

    /**
     * Gives one gift now. {@code kind} null = picked by weight; a GM's forced gift names one. The line saying what
     * happened, or null when the bot had nothing suitable.
     */
    public static String give(Character bot, Character player, Kind kind, Random rng) {
        if (soloMapling.ArtificialPlayer.PlayerBusy.isBusy(player)
                || bot.getMap() == null || player.getMap() == null || bot.getMapId() != player.getMapId()) {
            return null;
        }
        List<Kind> order = order(kind, pick(rng), dropsAllowed(player.getMap().getFieldLimit()));
        for (Kind k : order) {
            String line = switch (k) {
                case POTIONS -> potions(bot, player);
                case SCROLL -> scroll(bot, player);
                case MESOS -> mesos(bot, player, rng);
            };
            if (line != null) {
                record(bot, player, line);
                return line;
            }
        }
        return null;
    }

    /**
     * A gift was given: this pair's gift time (memory and the {@code botgifts} row, so the 7-day pair rule holds for
     * both kinds of gift and across a restart), the log, say-do and the QA log. Shared with {@link BotGiftAsks}.
     */
    static void record(Character bot, Character player, String line) {
        long now = clock.getAsLong();
        lastGift.put(key(bot.getId(), player.getId()), now);
        EconomyStore st = store;
        ExecutorServiceManager.runAsync(() -> st.saveGift(bot.getId(), player.getId(), now, line));
        log.info("[Gift] {}", line);
        soloMapling.ArtificialPlayer.BotConvo.SayDo.event(bot, soloMapling.ArtificialPlayer.BotConvo.SayDo.Ev.GIFT, bot.getMapId()); // rc6 say-do: the gift was given
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, "GIFT " + line);
        }
    }

    /**
     * When this bot last gave this player any gift (unprompted or asked for), or 0: memory first, else the stored
     * row (a database read: call it off the game threads).
     */
    static long lastGiftAt(int botId, int playerId) {
        Long last = lastGift.get(key(botId, playerId));
        if (last != null) {
            return last;
        }
        long stored = store.lastGift(botId, playerId);
        if (stored > 0) {
            lastGift.put(key(botId, playerId), stored);
        }
        return Math.max(0, stored);
    }

    static long now() {
        return clock.getAsLong();
    }

    /**
     * The one value-and-sale rule for an item a bot gives away from its bag (1.6.2: asked-for gifts; the scroll rule
     * below adds its success floor): never gear, never what bots may not sell ({@link SaleRules}), and the units given
     * worth at most {@code max} in all at the price board's fair value. Null when it may be given, else why not.
     */
    static String giftProblem(Item item, int quantity, long max) {
        if (item == null || quantity <= 0) {
            return "nothing";
        }
        if (item.getInventoryType() == InventoryType.EQUIP || item.getInventoryType() == InventoryType.EQUIPPED) {
            return "gear is never a gift";
        }
        String why = SaleRules.whyNot(item);
        if (why != null) {
            return "bots may not sell it (" + why + ")";
        }
        // a star or bullet stack is priced as one set (PriceBook.units); anything else per unit
        long value = unitValue(item) * (constants.inventory.ItemConstants.isRechargeable(item.getItemId()) ? 1 : quantity);
        if (value > max) {
            return String.format("worth %,d > %,d", value, max);
        }
        return null;
    }

    /** What one unit is worth for the gift rules: the board's fair value, at least 1. */
    static long unitValue(Item item) {
        return Math.max(1, PriceBook.fairValue(item));
    }

    /** How many of this item fit under {@code max} in all (0 when not even one does). */
    static int unitsUnder(Item item, int wanted, long max) {
        long each = unitValue(item);
        return (int) Math.max(0, Math.min(wanted, max / each));
    }

    /**
     * rc6 auto-QA triage (t-gf-1, 2026-10-23): item gifts drop at the player's feet, and a map with
     * {@code FieldLimit.DROP_LIMIT} (every Free Market room) only shows the drop vanish - payOut puts the units back
     * and the gift came to nothing: "!qa do gift scroll" in FM room 1 failed in both runs. Mesos go straight to the
     * player, so they still work there.
     */
    static boolean dropsAllowed(int fieldLimit) {
        return !server.maps.FieldLimit.DROP_LIMIT.check(fieldLimit);
    }

    /** The kinds to try, in order: the forced one only, else the rolled one first; no item gifts where drops vanish. */
    static List<Kind> order(Kind forced, Kind rolled, boolean drops) {
        List<Kind> out = new ArrayList<>();
        Kind first = forced != null ? forced : rolled;
        out.add(first);
        if (forced == null) {
            for (Kind k : Kind.values()) {
                if (k != first) {
                    out.add(k);
                }
            }
        }
        if (!drops) {
            out.removeIf(k -> k != Kind.MESOS);
        }
        return out;
    }

    static Kind pick(Random rng) {
        int p = Math.max(0, EconomyConfig.getInt("gifts.weightPotions", 45));
        int s = Math.max(0, EconomyConfig.getInt("gifts.weightScroll", 30));
        int m = Math.max(0, EconomyConfig.getInt("gifts.weightMesos", 25));
        int roll = rng.nextInt(Math.max(1, p + s + m));
        return roll < p ? Kind.POTIONS : roll < p + s ? Kind.SCROLL : Kind.MESOS;
    }

    /** The potion and count for a player's level: {itemId, quantity}. */
    static int[] potionFor(int level) {
        if (level < 30) {
            return new int[]{2000002, 50};    // White Potion
        }
        if (level < 70) {
            return new int[]{2000004, 20};    // Elixir
        }
        return new int[]{2000005, 10};        // Power Elixir
    }

    private static String potions(Character bot, Character player) {
        int[] p = potionFor(player.getLevel());
        long cost = (long) npcPrice(p[0]) * p[1];
        if (cost <= 0 || cost > Integer.MAX_VALUE || bot.getMeso() < cost * 2) {
            return null;
        }
        Item item = new Item(p[0], (short) 0, (short) p[1]);
        if (!BotHoldings.canHold(bot, item) || !BotHoldings.spendMeso(bot, (int) cost)) {
            return null;   // the bot pays the NPC price: a gift is bought, never minted
        }
        if (!BotHoldings.grantItem(bot, item)) {
            BotHoldings.grantMeso(bot, (int) cost);
            return null;
        }
        int landed = BotHoldings.payOut(bot, p[0], p[1], player, player.getPosition());
        if (landed <= 0) {
            return null;
        }
        greet(bot, player, "have some pots :)");
        return String.format("%s gave %s %d x %s (bought for %,d)", bot.getName(), player.getName(), landed,
                BotShops.nameOf(p[0]), cost);
    }

    private static String scroll(Character bot, Character player) {
        Item pick = giftableScroll(bot, EconomyConfig.getLong("gifts.scrollMaxValue", 500_000));
        if (pick == null || BotHoldings.payOut(bot, pick.getItemId(), 1, player, player.getPosition()) <= 0) {
            return null;
        }
        greet(bot, player, "found this, u can have it");
        return String.format("%s gave %s a %s from its bag", bot.getName(), player.getName(),
                BotShops.nameOf(pick.getItemId()));
    }

    /**
     * The first scroll in the bot's bag it would give: a plain 60%+ scroll bots may sell, worth at most {@code max}
     * ({@link #scrollProblem}). Shared by the unprompted gift and the asked-for one ({@link BotGiftAsks}).
     */
    static Item giftableScroll(Character bot, long max) {
        Inventory inv = bot.getInventory(InventoryType.USE);
        inv.lockInventory();
        try {
            for (Item it : inv.list()) {
                if (ScrollRules.isPlainScroll(it.getItemId())
                        && scrollProblem(SaleRules.whyNot(it), successOf(it.getItemId()), PriceBook.fairValue(it), max) == null) {
                    return it;
                }
            }
        } finally {
            inv.unlockInventory();
        }
        return null;
    }

    /**
     * Why the bot's bag holds no scroll it would give, one reason per plain scroll it holds (GM / !qa read-out). rc6
     * auto-QA triage (t-gf-1): "!qa do gift scroll" found "nothing suitable" in both runs of the day although the bot
     * was given a 60% scroll a moment earlier; this says which rule turned it away (or that the bag has none).
     */
    public static List<String> scrollReasons(Character bot) {
        List<String> out = new ArrayList<>();
        long max = EconomyConfig.getLong("gifts.scrollMaxValue", 500_000);
        Inventory inv = bot.getInventory(InventoryType.USE);
        int held = 0;
        inv.lockInventory();
        try {
            for (Item it : inv.list()) {
                held++;
                if (!ScrollRules.isPlainScroll(it.getItemId())) {
                    continue;
                }
                String why = scrollProblem(SaleRules.whyNot(it), successOf(it.getItemId()), PriceBook.fairValue(it), max);
                out.add(it.getItemId() + " x" + it.getQuantity() + ": " + (why == null ? "giftable" : why));
            }
        } finally {
            inv.unlockInventory();
        }
        if (out.isEmpty()) {
            out.add("no plain scroll in its use tab (" + held + " stacks)");
        }
        return out;
    }

    /** Why a plain scroll is not a gift, or null: the same rules as {@link #scroll}. */
    static String scrollProblem(String saleWhyNot, int success, long value, long max) {
        if (saleWhyNot != null) {
            return "bots may not sell it (" + saleWhyNot + ")";
        }
        if (success < 60) {
            return "success " + success + "% < 60%";
        }
        if (value > max) {
            return String.format("worth %,d > %,d", value, max);
        }
        return null;
    }

    private static String mesos(Character bot, Character player, Random rng) {
        long lo = Math.max(1, EconomyConfig.getLong("gifts.mesoMin", 100_000));
        long hi = Math.max(lo, EconomyConfig.getLong("gifts.mesoMax", 500_000));
        long amount = (lo + (long) (rng.nextDouble() * (hi - lo))) / 10_000 * 10_000;
        amount = Math.max(lo, amount);
        long keep = BotWealth.ceiling(bot) / 2;
        if (bot.getMeso() - amount < keep || amount > Integer.MAX_VALUE || !player.canHoldMeso((int) amount)) {
            return null;
        }
        if (!BotHoldings.spendMeso(bot, (int) amount)) {
            return null;
        }
        player.gainMeso((int) amount, true);
        EconomyMeters.add(EconomyMeters.GIFT_MESOS, amount);
        player.dropMessage(5, String.format("%s gave you %,d mesos.", bot.getName(), amount));
        greet(bot, player, "here, for pots or whatever");
        return String.format("%s gave %s %,d mesos", bot.getName(), player.getName(), amount);
    }

    static void greet(Character bot, Character player, String scripted) {
        BotTiming.afterRandom(300, 900, () -> BotDirectTrade.say(bot, player,
                "You just gave your friend " + player.getName() + " a small gift; say so warmly in a few words.",
                scripted, null, false));
    }

    /** What an NPC store asks for the item: about twice what it pays for it (the WZ price). */
    private static int npcPrice(int itemId) {
        try {
            return Math.max(0, ItemInformationProvider.getInstance().getWholePrice(itemId)) * 2;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    static int successOf(int scrollId) {
        try {
            Map<String, Integer> stats = ItemInformationProvider.getInstance().getEquipStats(scrollId);
            return stats == null ? 0 : stats.getOrDefault("success", 0);
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** Forgets the in-memory gift time of this pair (QA re-tests); the stored row still counts after a restart. */
    public static void forget(int botId, int playerId) {
        lastGift.remove(key(botId, playerId));
    }

    // ---- test seams ----

    static void resetForTest(EconomyStore testStore, LongSupplier testClock) {
        store = testStore == null ? new EconomyStore.Jdbc() : testStore;
        clock = testClock == null ? System::currentTimeMillis : testClock;
        lastGift.clear();
    }
}
