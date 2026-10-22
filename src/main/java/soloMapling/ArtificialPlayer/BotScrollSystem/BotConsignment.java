package soloMapling.ArtificialPlayer.BotScrollSystem;

import client.Character;
import client.inventory.Equip;
import net.server.channel.Channel;
import server.maps.HiredMerchant;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.FreeMarket.HiredMerchantArtificial;
import soloMapling.qa.QaLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Ticket 11: a scrolled equip a bot does not wear goes on sale in a bot FM shop, priced by {@code EquipValuation}.
 * Never what {@code SaleRules} keeps from bot sales (godly gear, 1.5.0 rc3 review): that stays with the bot.
 *
 * <p>Grinding bots have no shop of their own, so the item is put in one of the bot merchants already open in the FM
 * rooms (channel 1), the way a player hands stock to a friend's shop. The item leaves the bot's holdings when it is
 * listed. When a real player buys it, the price after the shop fee is owed to the bot and paid into its wallet the
 * next time it scrolls or comes to town (a transfer from that player, not a mint: bots' mesos from players are not
 * metered by ticket 15). FM shopper bots never buy a listed item (their allowance would turn it into minted
 * mesos). An item unsold after {@link #LISTING_TTL_MS} comes back to the bot. A listing whose shop closed is lost
 * with the shop; what is owed lives in memory and a restart loses it (both only ever cost the bot).
 */
public final class BotConsignment {

    static final long LISTING_TTL_MS = 6 * 60 * 60_000L;
    static final int MAX_PER_BOT = 2;
    static final int MAX_TOTAL = 300;
    static final int FM_FIRST_ROOM = 910000001, FM_LAST_ROOM = 910000022;

    record Listing(int botId, String botName, HiredMerchant shop, PlayerShopItem item, int price, long listedAt) {
    }

    private static final Map<PlayerShopItem, Listing> listings = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> owed = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private BotConsignment() {
    }

    /** True for an item a bot put on sale here: FM shopper bots leave it to players. */
    public static boolean isConsigned(PlayerShopItem item) {
        return item != null && listings.containsKey(item);
    }

    public static int listedBy(int botId) {
        int n = 0;
        for (Listing l : listings.values()) {
            if (l.botId() == botId) {
                n++;
            }
        }
        return n;
    }

    /**
     * Puts the bot's equip on sale at {@code price} in a bot shop of the FM. Returns where it went, or null when it
     * stays with the bot (no shop open, limits, or the item is not the bot's to sell).
     */
    public static String list(Character bot, Equip equip, int price) {
        if (bot == null || bot.getMap() == null || equip == null || price <= 0 || equip.getPosition() < 0
                || listedBy(bot.getId()) >= MAX_PER_BOT || listings.size() >= MAX_TOTAL
                || (equip.getFlag() & constants.inventory.ItemConstants.UNTRADEABLE) != 0) {
            return null;
        }
        String rule = whyNotList(equip);
        if (rule != null) {
            if (QaLog.on(QaLog.Topic.SCROLL) && bot.getMap() != null) {
                QaLog.map(QaLog.Topic.SCROLL, bot.getMapId(), bot.getName() + " keeps " + equip.getItemId()
                        + " (bots never sell: " + rule + ")");
            }
            return null;
        }
        HiredMerchant shop = pickShop(bot);
        if (shop == null) {
            return null;
        }
        if (!BotHoldings.destroyHeldItem(bot, equip)) {   // out of the bot's holdings and ledger: it is in the shop now
            return null;
        }
        PlayerShopItem item = new PlayerShopItem(equip, (short) 1, price);
        if (!shop.addItem(item)) {
            soloMapling.Economy.PendingGrants.giveBack(bot, equip, "the shop refused the listing");
            return null;
        }
        listings.put(item, new Listing(bot.getId(), bot.getName(), shop, item, price, clock.getAsLong()));
        ScrollLedger.listed.incrementAndGet();
        return shop.getOwner() + "'s shop (FM room " + (shop.getMapId() - 910000000) + ")";
    }

    /**
     * 1.5.0 rc3 review: why a bot may not put this equip on sale, or null when it may. What bots never sell
     * ({@link soloMapling.Economy.SaleRules}: godly gear above all) stays with the bot here too; it used to be listed.
     */
    static String whyNotList(Equip equip) {
        return soloMapling.Economy.SaleRules.whyNot(equip);
    }

    private static HiredMerchant pickShop(Character bot) {
        Channel ch;
        try {
            ch = bot.getWorldServer().getChannel(1);
        } catch (RuntimeException e) {
            return null;
        }
        if (ch == null) {
            return null;
        }
        List<HiredMerchant> open = new ArrayList<>();
        try {
            Collection<HiredMerchant> all = new ArrayList<>(ch.getHiredMerchants().values());
            for (HiredMerchant m : all) {
                if (m instanceof HiredMerchantArtificial && !soloMapling.Economy.BotShops.isBotShop(m) && m.isOpen()
                        && m.getItems().size() < 16
                        && m.getMapId() >= FM_FIRST_ROOM && m.getMapId() <= FM_LAST_ROOM) {
                    open.add(m);
                }
            }
        } catch (RuntimeException raced) {
            return null;
        }
        return open.isEmpty() ? null : open.get(ThreadLocalRandom.current().nextInt(open.size()));
    }

    /** HiredMerchant.buy: a player bought {@code item} from a bot shop for {@code netPrice} after the fee. */
    public static void onSold(PlayerShopItem item, int netPrice) {
        Listing l = item == null ? null : listings.get(item);
        if (l == null || item.getBundles() > 0) {
            return;
        }
        listings.remove(item);
        owed.merge(l.botId(), (long) Math.max(0, netPrice), Long::sum);
        ScrollLedger.listedSold.incrementAndGet();
        ScrollLedger.listedSoldMesos.addAndGet(Math.max(0, netPrice));
    }

    /**
     * Pays the bot what its sold listings earned and takes back what did not sell in time. Returns a line for the
     * log, or null when nothing happened.
     */
    public static String collect(Character bot) {
        if (bot == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        Long due = owed.remove(bot.getId());
        if (due != null && due > 0) {
            if (BotHoldings.grantMeso(bot, (int) Math.min(due, Integer.MAX_VALUE))) {
                out.append("collected ").append(due).append(" from FM sales");
            } else {
                owed.merge(bot.getId(), due, Long::sum);   // wallet full: next time
            }
        }
        long now = clock.getAsLong();
        for (Listing l : new ArrayList<>(listings.values())) {
            if (l.botId() != bot.getId()) {
                continue;
            }
            boolean shopGone = !l.shop().isOpen();
            if (!shopGone && now - l.listedAt() < LISTING_TTL_MS) {
                continue;
            }
            if (shopGone) {
                listings.remove(l.item());
                out.append(out.length() > 0 ? "; " : "").append("lost a listing with a closed shop");
                continue;
            }
            if (BotHoldings.canHold(bot, l.item().getItem()) && l.shop().withdrawListing(l.item())) {
                listings.remove(l.item());
                l.item().getItem().setPosition((short) 0);
                if (soloMapling.Economy.PendingGrants.giveBack(bot, l.item().getItem(), "unsold listing")) {
                    out.append(out.length() > 0 ? "; " : "").append("took back unsold ")
                            .append(l.item().getItem().getItemId());
                }
            }
        }
        if (out.length() > 0 && QaLog.on(QaLog.Topic.SCROLL) && bot.getMap() != null) {
            QaLog.map(QaLog.Topic.SCROLL, bot.getMapId(), bot.getName() + " " + out);
        }
        return out.length() > 0 ? out.toString() : null;
    }

    public static int open() {
        return listings.size();
    }

    // ---- test seams ----

    static void useClockForTest(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }

    static void clearForTest() {
        listings.clear();
        owed.clear();
    }

    static long owedForTest(int botId) {
        return owed.getOrDefault(botId, 0L);
    }

    static void putForTest(int botId, HiredMerchant shop, PlayerShopItem item, int price) {
        listings.put(item, new Listing(botId, "bot" + botId, shop, item, price, clock.getAsLong()));
    }
}
