package soloMapling.Economy;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.Server;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.maps.HiredMerchant;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.FreeMarket.FMShopInfoManager;
import soloMapling.FreeMarket.HiredMerchantArtificial;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 1.5.0 rc3, owner decision 1: some bots open hired merchants in the Free Market.
 * <ul>
 *   <li><b>Who:</b> about {@code botShops.ownerPercent} (10%) of grinding bots are "merchant minded" (fixed per bot),
 *   level {@code botShops.minLevel} (15)+. At a town stop ({@code TrainingBot}) such a bot with at least
 *   {@code botShops.minItems} (3) things to sell opens a shop in an FM room on channel 1 under its own name, while the
 *   world has fewer than {@code botShops.maxShops} (8) bot shops. It stays open {@code botShops.hours} (6).</li>
 *   <li><b>Where:</b> FM rooms 1-12 on channels 1-3 (owner decision, rc3 review): each new shop goes to the channel
 *   furthest under its soft share of the world cap ({@code botShops.channelTargets}, "4,2,2": channel 1 busiest).
 *   A bot has one shop at most, on one channel. Payouts and the price board do not depend on the channel.</li>
 *   <li><b>What:</b> drops and scrolls it earned (its bag, never what it wears) and items bots bought from players
 *   (the resale pool: FM shopper purchases and direct trades), so items circulate. Never: what {@link SaleRules}
 *   refuses (godly gear, Chaos/White scrolls, boss-only drops, cash items).</li>
 *   <li><b>Prices:</b> the board's fair value ({@link PriceBook}, the 7-day median of real trades) times
 *   {@code botShops.lootMarkup} (1.0) for its own loot and {@code botShops.resaleMarkup} (1.15) for resale (of the
 *   higher of what was paid and fair value), never under twice the NPC price.</li>
 *   <li><b>Money:</b> a player's purchase pays the bot the price after the FM shop tax ({@link MesoSinks#shopFee}),
 *   collected at its next town stop (a transfer from that player). FM shopper bots never buy from these shops
 *   (their allowance would turn the item into minted mesos).</li>
 *   <li><b>Closing:</b> when it sells out or its time is up; what did not sell goes back to the bot if it is online,
 *   else to the resale pool. Shops and what is owed live in memory: a restart costs only the bots.</li>
 * </ul>
 */
public final class BotShops {

    private static final Logger log = LoggerFactory.getLogger(BotShops.class);

    static final int[] ROOMS_DEFAULT = {910000001, 910000002, 910000003, 910000004, 910000005, 910000006,
            910000007, 910000008, 910000009, 910000010, 910000011, 910000012};
    static final int MERCHANT_SKIN = 5030000;
    static final int SPOT_CLEARANCE = 70;

    /** Why an item is on sale. */
    public enum Kind { LOOT, RESALE }

    record Listing(int botId, String botName, Kind kind, int price) {
    }

    static final class Shop {
        final int botId;
        final String botName;
        final int channel;
        final HiredMerchantArtificial merchant;
        final long openedAt;
        final long closesAt;
        final List<PlayerShopItem> items = new ArrayList<>();

        Shop(int botId, String botName, int channel, HiredMerchantArtificial merchant, long openedAt, long closesAt) {
            this.botId = botId;
            this.botName = botName;
            this.channel = channel;
            this.merchant = merchant;
            this.openedAt = openedAt;
            this.closesAt = closesAt;
        }
    }

    /** An item bots bought from a player, waiting for a bot shop to resell it. */
    record Resale(Item item, long paid) {
    }

    private static final Map<Integer, Shop> shops = new ConcurrentHashMap<>();
    private static final java.util.Set<Integer> opening = ConcurrentHashMap.newKeySet();   // bots opening right now
    private static final Map<PlayerShopItem, Listing> listings = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> owed = new ConcurrentHashMap<>();
    private static final Deque<Resale> resale = new ArrayDeque<>();
    private static final Map<Integer, Long> nextTryAt = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile boolean sweeperStarted;

    private BotShops() {
    }

    // ---- config ----

    public static boolean enabled() {
        return EconomyConfig.getBool("botShops.enabled", true);
    }

    static int maxShops() {
        return Math.max(0, EconomyConfig.getInt("botShops.maxShops", 8));
    }

    /** About ownerPercent of bots are merchant minded; fixed per bot id. */
    public static boolean merchantMinded(int botId) {
        int pct = Math.max(0, Math.min(100, EconomyConfig.getInt("botShops.ownerPercent", 10)));
        return Math.floorMod(Integer.rotateLeft(botId * 0x61C88647, 11) ^ (botId >>> 3), 100) < pct;
    }

    // ---- the town stop ----

    /** A grinding bot's town stop: collect what its shop earned, and maybe open one. Never throws. */
    public static void townTick(Character bot) {
        if (bot == null || !BotHelpers.isBot(bot)) {
            return;
        }
        try {
            long now = clock.getAsLong();
            Long next = nextTryAt.get(bot.getId());
            if (next != null && now < next) {
                return;
            }
            nextTryAt.put(bot.getId(), now + TimeUnit.MINUTES.toMillis(2)
                    + ThreadLocalRandom.current().nextLong(TimeUnit.MINUTES.toMillis(2)));
            collect(bot);
            PendingGrants.deliver(bot);   // items it could not take back earlier
            if (enabled() && merchantMinded(bot.getId()) && !shops.containsKey(bot.getId())
                    && bot.getLevel() >= EconomyConfig.getInt("botShops.minLevel", 15) && shops.size() < maxShops()) {
                open(bot, 0, null, false);
            }
        } catch (RuntimeException e) {
            log.warn("Bot shop step failed for {}", bot.getName(), e);
        }
    }

    /** Pays the bot what its shop sold. A line, or null when nothing was owed. */
    public static String collect(Character bot) {
        Long due = owed.remove(bot.getId());
        if (due == null || due <= 0) {
            return null;
        }
        if (BotHoldings.grantMeso(bot, (int) Math.min(due, Integer.MAX_VALUE))) {
            String line = bot.getName() + " collected " + String.format("%,d", due) + " from its FM shop";
            if (QaLog.on(QaLog.Topic.TRADE)) {
                QaLog.any(QaLog.Topic.TRADE, "SHOP " + line);
            }
            return line;
        }
        owed.merge(bot.getId(), due, Long::sum);   // wallet full: next time
        return null;
    }

    // ---- opening ----

    /**
     * Opens a shop for the bot. {@code mapId}/{@code at}: where (0/null = a free spot in a random FM room);
     * {@code force}: a GM's staging, outside the merchant-minded, level and world limits. The lines saying what
     * happened.
     */
    public static List<String> open(Character bot, int mapId, Point at, boolean force) {
        return open(bot, 0, mapId, at, force);
    }

    /**
     * As above on {@code channel} (0 = the channel {@link #pickChannel} chooses; a GM's staging passes its own). A
     * bot has at most one shop in the world: a second open, on any channel, even at the same moment, is refused.
     */
    public static List<String> open(Character bot, int channel, int mapId, Point at, boolean force) {
        if (!opening.add(bot.getId())) {
            return new ArrayList<>(List.of(bot.getName() + " is opening a shop already"));
        }
        try {
            return openOnce(bot, channel, mapId, at, force);
        } finally {
            opening.remove(bot.getId());
        }
    }

    private static List<String> openOnce(Character bot, int channelWanted, int mapId, Point at, boolean force) {
        List<String> out = new ArrayList<>();
        if (shops.containsKey(bot.getId())) {
            Shop open = shops.get(bot.getId());
            out.add(bot.getName() + " already has a shop open" + (open != null ? " on channel " + open.channel : ""));
            return out;
        }
        if (!force && shops.size() >= maxShops()) {
            out.add("the world already has " + shops.size() + " bot shops");
            return out;
        }
        List<PlayerShopItem> stock = new ArrayList<>();
        List<Item> taken = new ArrayList<>();
        int maxItems = Math.max(1, Math.min(16, EconomyConfig.getInt("botShops.maxItems", 12)));
        int ownMax = Math.max(0, EconomyConfig.getInt("botShops.maxOwnItems", 10));
        List<String> refused = new ArrayList<>();
        for (Item item : sellable(bot, refused)) {
            if (stock.size() >= Math.min(maxItems, ownMax)) {
                break;
            }
            long ask = askFor(item, Kind.LOOT, 0);
            if (ask <= 0) {
                continue;
            }
            stock.add(new PlayerShopItem(item.copy(), (short) 1, (int) Math.min(Integer.MAX_VALUE, ask)));
            taken.add(item);
        }
        List<Resale> fromPool = new ArrayList<>();
        synchronized (resale) {
            while (stock.size() < maxItems && !resale.isEmpty()) {
                Resale r = resale.pollFirst();
                long ask = askFor(r.item(), Kind.RESALE, r.paid());
                if (ask <= 0 || !SaleRules.botMaySell(r.item())) {
                    continue;
                }
                stock.add(new PlayerShopItem(r.item(), (short) 1, (int) Math.min(Integer.MAX_VALUE, ask)));
                fromPool.add(r);
            }
        }
        int minItems = Math.max(1, EconomyConfig.getInt("botShops.minItems", 3));
        if (stock.size() < (force ? 1 : minItems)) {
            giveBackToPool(fromPool);
            out.add(bot.getName() + " has too little to sell (" + stock.size() + " items" + (refused.isEmpty() ? ""
                    : "; kept back: " + String.join(", ", refused.subList(0, Math.min(5, refused.size())))) + ")");
            return out;
        }
        int channel = channelWanted > 0 ? channelWanted : pickChannel(openPerChannel(), channelTargets(),
                availableChannels());
        Placement where = mapId > 0 && at != null ? new Placement(mapId, at) : findSpot(channel);
        if (where == null) {
            giveBackToPool(fromPool);
            out.add("no free spot in the FM rooms");
            return out;
        }
        HiredMerchantArtificial merchant;
        try {
            merchant = spawnMerchant(bot, channel, where);
        } catch (RuntimeException e) {
            giveBackToPool(fromPool);
            out.add("could not open a merchant: " + e);
            return out;
        }
        long now = clock.getAsLong();
        long hours = Math.max(1, EconomyConfig.getLong("botShops.hours", 6));
        Shop shop = new Shop(bot.getId(), bot.getName(), channel, merchant, now, now + TimeUnit.HOURS.toMillis(hours));
        // the bot's own items leave its holdings only now that the shop exists
        for (int i = 0; i < stock.size(); i++) {
            PlayerShopItem p = stock.get(i);
            boolean own = i < taken.size();
            if (own && !BotHoldings.destroyHeldItem(bot, taken.get(i))) {
                continue;   // gone from the bag in the meantime
            }
            if (merchant.addItem(p)) {
                shop.items.add(p);
                listings.put(p, new Listing(bot.getId(), bot.getName(), own ? Kind.LOOT : Kind.RESALE, p.getPrice()));
                out.add(String.format("  %s %s x%d at %,d (%s)", own ? "loot" : "resale", nameOf(p.getItem().getItemId()),
                        p.getItem().getQuantity(), p.getPrice(), quoteNote(p.getItem())));
            } else if (own) {
                PendingGrants.giveBack(bot, taken.get(i), "shop refused the listing");
            }
        }
        shops.put(bot.getId(), shop);
        startSweeper();
        String head = String.format("%s opened a shop in FM room %d on channel %d (%d items, closes in %d h)",
                bot.getName(), where.mapId() % 100, channel, shop.items.size(), hours);
        out.add(0, head);
        log.info("[BotShops] {}", head);
        if (QaLog.on(QaLog.Topic.TRADE)) {
            for (String l : out) {
                QaLog.any(QaLog.Topic.TRADE, "SHOP " + l.trim());
            }
        }
        return out;
    }

    record Placement(int mapId, Point at) {
    }

    /** The bot's bag items it may sell and can price, best first; {@code refused} gets what the rules kept back. */
    static List<Item> sellable(Character bot, List<String> refused) {
        List<Item> out = new ArrayList<>();
        for (InventoryType t : new InventoryType[]{InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
                InventoryType.ETC}) {
            Inventory inv = bot.getInventory(t);
            List<Item> items;
            inv.lockInventory();
            try {
                items = new ArrayList<>(inv.list());
            } finally {
                inv.unlockInventory();
            }
            for (Item item : items) {
                if (t == InventoryType.USE && item.getItemId() / 10000 != 204) {
                    continue;   // potions and ammo are the bot's own kit; scrolls only
                }
                String why = SaleRules.whyNot(item);
                if (why != null) {
                    refused.add(nameOf(item.getItemId()) + " (" + why + ")");
                    continue;
                }
                out.add(item);
            }
        }
        out.sort((a, b) -> Long.compare(PriceBook.stackValue(b), PriceBook.stackValue(a)));
        return out;
    }

    /** The asking price for a whole stack: fair value x markup, never under twice the NPC price; 0 = not for sale. */
    static long askFor(Item item, Kind kind, long paid) {
        long fair = PriceBook.fairValue(item);
        int qty = Math.max(1, PriceBook.units(item));   // a star stack is one set
        long base = kind == Kind.RESALE ? Math.max(fair * qty, paid) : fair * qty;
        if (base <= 0) {
            return 0;
        }
        double markup = kind == Kind.RESALE ? EconomyConfig.getDouble("botShops.resaleMarkup", 1.15)
                : EconomyConfig.getDouble("botShops.lootMarkup", 1.0);
        long ask = Math.round(base * Math.max(0.5, markup));
        ask = Math.max(ask, 2L * npcPrice(item.getItemId()) * qty);
        return PriceBand.nice(ask, false);
    }

    private static long npcPrice(int itemId) {
        try {
            return Math.max(0, ItemInformationProvider.getInstance().getWholePrice(itemId));
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static String quoteNote(Item item) {
        PriceBook.Quote q = PriceBook.quote(item.getItemId(), -1);
        return q.fromSamples() ? "median " + PriceBook.shorthand(q.median()) + " over " + q.samples() + " sales"
                : "no recent sales: usual value";
    }

    // ---- channels ----

    /** The channels bot shops use: 1..{@code botShops.channels} (3). */
    static int channelCount() {
        return Math.max(1, Math.min(20, EconomyConfig.getInt("botShops.channels", 3)));
    }

    /** Each channel's soft share of the world cap, channel 1 first ({@code botShops.channelTargets}, "4,2,2"). */
    static int[] channelTargets() {
        int n = channelCount();
        int[] out = new int[n];
        String raw = String.valueOf(EconomyConfig.raw("botShops.channelTargets") == null ? "4,2,2"
                : EconomyConfig.raw("botShops.channelTargets"));
        String[] parts = raw.replaceAll("[\\[\\]\\s]", "").split(",");
        for (int i = 0; i < n; i++) {
            try {
                out[i] = i < parts.length ? Math.max(0, Integer.parseInt(parts[i])) : 0;
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Open bot shops per channel (index 0 = channel 1). */
    static int[] openPerChannel() {
        int[] out = new int[channelCount()];
        for (Shop s : shops.values()) {
            if (s.channel >= 1 && s.channel <= out.length) {
                out[s.channel - 1]++;
            }
        }
        return out;
    }

    /** Which of channels 1..n run in this world (index 0 = channel 1). */
    static boolean[] availableChannels() {
        boolean[] out = new boolean[channelCount()];
        for (int i = 0; i < out.length; i++) {
            out[i] = channel(i + 1) != null;
        }
        return out;
    }

    /**
     * The channel a new shop goes to: the running channel furthest under its soft target (ties to the lower
     * channel), or, with every channel at its target, the one with fewest shops for its target. Channel 1 when none
     * of the others runs.
     */
    static int pickChannel(int[] open, int[] targets, boolean[] available) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < open.length; i++) {
            if (available != null && i < available.length && !available[i]) {
                continue;
            }
            int target = i < targets.length ? targets[i] : 0;
            // room under the target first; past every target, the lowest load per share of the cap
            double score = target - open[i] > 0 ? 1_000 + (target - open[i])
                    : -(open[i] + 1.0) / Math.max(0.5, target);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best < 0 ? 1 : best + 1;
    }

    /** A free merchant spot in one of the FM rooms of {@code channel}, away from the shops already there. */
    static Placement findSpot(int channel) {
        Channel ch = channel(channel);
        if (ch == null) {
            return null;
        }
        List<Integer> rooms = new ArrayList<>();
        for (int r : ROOMS_DEFAULT) {
            rooms.add(r);
        }
        Collections.shuffle(rooms);
        for (int mapId : rooms) {
            MapleMap map = ch.getMapFactory().getMap(mapId);
            if (map == null) {
                continue;
            }
            if (!soloMapling.ArtificialPlayer.BotMapCap.hasRoom(map)) {
                soloMapling.ArtificialPlayer.BotMapCap.noteTurnedAway(); // 1.5.0: room at its per-map bot cap
                continue;
            }
            List<Point> spots = new ArrayList<>(new FMShopInfoManager().getRegionFMSpots(
                    FMShopInfoManager.getRegionByMapId(mapId)));
            Collections.shuffle(spots);
            List<Point> taken = new ArrayList<>();
            for (MapObject o : map.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY,
                    List.of(MapObjectType.HIRED_MERCHANT, MapObjectType.PLAYER, MapObjectType.SHOP))) {
                taken.add(o.getPosition());
            }
            for (Point p : spots) {
                if (taken.stream().noneMatch(t -> Math.abs(t.x - p.x) < SPOT_CLEARANCE && Math.abs(t.y - p.y) < 40)) {
                    return new Placement(mapId, new Point(p));
                }
            }
        }
        return null;
    }

    private static Channel channel(int channel) {
        try {
            int world = soloMapling.server.SoloMaplingConstants.GameConstants.homeWorld();
            return Server.getInstance().getChannel(world, channel);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static HiredMerchantArtificial spawnMerchant(Character bot, int channel, Placement where) {
        Channel ch = channel(channel);
        if (ch == null) {
            throw new IllegalStateException("no channel " + channel);
        }
        Client cm = soloMapling.ArtificialPlayer.BotClientHandler.getBotClient(channel);
        Character stand = Character.getDefault(cm);
        stand.setWorld(soloMapling.server.SoloMaplingConstants.GameConstants.homeWorld());
        stand.setPosition(where.at());
        stand.setMap(ch.getMapFactory().getMap(where.mapId()));
        HiredMerchantArtificial m = new HiredMerchantArtificial(stand, description(bot), MERCHANT_SKIN, bot.getId(),
                bot.getName());
        m.setMapId(where.mapId());
        m.setTier("bot");
        stand.getWorldServer().registerHiredMerchant(m);
        ch.addHiredMerchant(bot.getId(), m);
        m.setOpen(true);
        m.getMap().addMapObject(m);
        m.getMap().broadcastMessage(PacketCreator.spawnHiredMerchantBox(m));
        return m;
    }

    private static String description(Character bot) {
        String[] lines = {"loot from training, cheap", "selling my drops", "scrolls + stuff, fair prices",
                "cleaning out my inv", "s> drops and scrolls"};
        return lines[Math.floorMod(bot.getId(), lines.length)];
    }

    // ---- selling ----

    /** True for a merchant that is a bot's own shop (BotConsignment and FM shopper bots leave it alone). */
    public static boolean isBotShop(HiredMerchant m) {
        if (m == null) {
            return false;
        }
        Shop s = shops.get(m.getOwnerId());
        return s != null && s.merchant == m;
    }

    /** True for an item a bot shop lists: FM shopper bots never buy it. */
    public static boolean isListed(PlayerShopItem item) {
        return item != null && listings.containsKey(item);
    }

    /** HiredMerchant.buy: a player bought from a bot shop; the bot is owed the price after the tax. */
    public static void onSold(PlayerShopItem item, int netPrice) {
        Listing l = item == null ? null : listings.get(item);
        if (l == null || item.getBundles() > 0) {
            return;
        }
        listings.remove(item);
        owed.merge(l.botId(), (long) Math.max(0, netPrice), Long::sum);
        EconomyMeters.add(EconomyMeters.BOT_SHOP_SALES, Math.max(0, netPrice));
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, String.format("SHOP SOLD %s's %s for %,d after tax (%s); owed to the bot",
                    l.botName(), nameOf(item.getItem().getItemId()), netPrice, l.kind().name().toLowerCase()));
        }
        Shop s = shops.get(l.botId());
        if (s != null && s.items.stream().noneMatch(p -> p.isExist() && p.getBundles() > 0)) {
            ExecutorServiceManager.runAsync(() -> close(l.botId(), "sold out"));
        }
    }

    // ---- the resale pool ----

    /** A bot bought this from a player: it goes to the pool a bot shop restocks from. False when the pool is full. */
    public static boolean offerResale(Item item, long paid) {
        if (item == null || !enabled() || !SaleRules.botMaySell(item)) {
            return false;
        }
        synchronized (resale) {
            if (resale.size() >= Math.max(0, EconomyConfig.getInt("botShops.resalePoolMax", 200))) {
                return false;
            }
            resale.addLast(new Resale(item.copy(), paid));
            return true;
        }
    }

    private static void giveBackToPool(List<Resale> rs) {
        synchronized (resale) {
            for (int i = rs.size() - 1; i >= 0; i--) {
                resale.addFirst(rs.get(i));
            }
        }
    }

    public static int resalePoolSize() {
        synchronized (resale) {
            return resale.size();
        }
    }

    // ---- closing ----

    /** Closes a bot's shop; unsold items go back to it (online) or to the resale pool. What happened. */
    public static String close(int botId, String why) {
        Shop s = shops.remove(botId);
        if (s == null) {
            return "no shop";
        }
        Character bot = onlineBot(botId);
        int back = 0, pooled = 0, kept = 0;
        for (PlayerShopItem p : s.items) {
            Listing l = listings.remove(p);
            if (l == null || !s.merchant.withdrawListing(p)) {
                continue;   // sold
            }
            Item item = p.getItem().copy();
            item.setPosition((short) 0);
            if (bot != null && BotHoldings.grantItem(bot, item)) {
                back++;
            } else if ((l.kind() == Kind.RESALE || bot == null) && offerResale(item, l.price())) {
                pooled++;
            } else {
                // 1.5.0 rc3 review: never lost; it waits for the bot (PendingGrants)
                PendingGrants.hold(botId, s.botName, item, "its shop closed and it had no room");
                kept++;
            }
        }
        try {
            s.merchant.forceClose();
            Channel ch = channel(s.channel);
            if (ch != null) {
                ch.removeHiredMerchant(botId);
            }
        } catch (RuntimeException e) {
            log.debug("closing bot shop", e);
        }
        String line = String.format("%s's shop closed (%s): %d back to the bot, %d to the resale pool, %d kept for"
                + " the bot until it has room", s.botName, why, back, pooled, kept);
        log.info("[BotShops] {}", line);
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, "SHOP " + line);
        }
        return line;
    }

    private static synchronized void startSweeper() {
        if (sweeperStarted) {
            return;
        }
        sweeperStarted = true;
        try {
            ExecutorServiceManager.scheduleAtFixedRate(BotShops::sweep, 5, 5, TimeUnit.MINUTES);
        } catch (RuntimeException | LinkageError e) {
            sweeperStarted = false;
        }
    }

    /** Closes shops whose time is up or whose merchant is gone. */
    static void sweep() {
        long now = clock.getAsLong();
        for (Shop s : new ArrayList<>(shops.values())) {
            try {
                if (now >= s.closesAt) {
                    close(s.botId, "time is up");
                } else if (!s.merchant.isOpen()) {
                    close(s.botId, "merchant closed");
                }
            } catch (RuntimeException e) {
                log.warn("bot shop sweep", e);
            }
        }
    }

    // ---- read-outs ----

    public static List<String> status() {
        List<String> out = new ArrayList<>();
        long now = clock.getAsLong();
        out.add(String.format("Bot shops: %d open (max %d), %d listings, resale pool %d, %,d mesos owed to bots",
                shops.size(), maxShops(), listings.size(), resalePoolSize(),
                owed.values().stream().mapToLong(Long::longValue).sum())
                + " (per channel " + java.util.Arrays.toString(openPerChannel()) + ", targets "
                + java.util.Arrays.toString(channelTargets()) + ")");
        for (Shop s : shops.values()) {
            long left = s.items.stream().filter(p -> p.isExist() && p.getBundles() > 0).count();
            out.add(String.format("  %s: ch %d FM room %d, %d of %d items left, closes in %d min", s.botName,
                    s.channel, s.merchant.getMapId() % 100, left, s.items.size(), Math.max(0, (s.closesAt - now) / 60_000)));
        }
        return out;
    }

    public static boolean hasShop(int botId) {
        return shops.containsKey(botId);
    }

    static String nameOf(int itemId) {
        try {
            String n = ItemInformationProvider.getInstance().getName(itemId);
            return n == null ? String.valueOf(itemId) : n;
        } catch (RuntimeException | LinkageError e) {
            return String.valueOf(itemId);
        }
    }

    // ---- test seams ----

    static void resetForTest(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
        shops.clear();
        opening.clear();
        listings.clear();
        owed.clear();
        nextTryAt.clear();
        synchronized (resale) {
            resale.clear();
        }
    }

    static long owedForTest(int botId) {
        return owed.getOrDefault(botId, 0L);
    }

    /** A shop on {@code channel} for the channel spread tests (no merchant behind it). */
    static void putShopForTest(int botId, int channel) {
        shops.put(botId, new Shop(botId, "bot" + botId, channel, null, clock.getAsLong(), Long.MAX_VALUE));
    }

    static boolean tryOpeningForTest(int botId) {
        return opening.add(botId);
    }

    static void listForTest(int botId, PlayerShopItem item, Kind kind) {
        listings.put(item, new Listing(botId, "bot" + botId, kind, item.getPrice()));
    }

    /** The bot if it is in the world now, else null. */
    static Character onlineBot(int botId) {
        try {
            return BotHelpers.getCharFromChannelStorage(botId);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
