package soloMapling.Economy.board;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import database.note.NoteDao;
import net.server.Server;
import net.server.world.World;
import server.ItemInformationProvider;
import service.NoteService;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeProfile;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeCommands;
import soloMapling.ArtificialPlayer.BotTypes.BuyingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.ArtificialPlayer.BotWealth;
import soloMapling.ArtificialPlayer.BotWhispers;
import soloMapling.ArtificialPlayer.SocialGate;
import soloMapling.Economy.BotBuying;
import soloMapling.Economy.BotDirectTrade;
import soloMapling.Economy.BotShops;
import soloMapling.Economy.EconomyConfig;
import soloMapling.Economy.MegaBuyDesk;
import soloMapling.Economy.MegaBuyGame;
import soloMapling.Economy.PriceBand;
import soloMapling.Economy.PriceBook;
import soloMapling.Economy.market.MarketListings;
import soloMapling.Economy.market.PublicMarketExport;
import soloMapling.server.ExecutorServiceManager;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The real game behind {@link BoardWorld}. Reads accounts and characters from the database (cached a few minutes),
 * bots' stock through {@link MegaBuyGame}, FM listings through {@link PublicMarketExport#liveShops}; whispers through
 * {@link BotWhispers} and notes through Cosmic's note service. Creates nothing: no item, no meso.
 * <p>
 * "Merchant and greedy" bots (owner, 2026-10-26): the merchant bot types, bot shops, and bots whose life profile leans
 * merchant ({@link LifeProfile.Archetype#MERCHANT} share at or above {@link #GREEDY_SHARE}, about a third of them).
 */
public class GameBoardWorld implements BoardWorld {

    /** LifeProfile's merchant share runs .04-.12; at or above this a bot counts as greedy. */
    static final double GREEDY_SHARE = 0.09;
    private static final long CACHE_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int BOT_ID_BASE = 1_000_000_000;

    /** Bots a !qa scenario staged without a state machine; they count as merchant/greedy. */
    private static final Set<Integer> QA_BOTS = ConcurrentHashMap.newKeySet();

    public static void qaAddBot(int botId) {
        QA_BOTS.add(botId);
    }

    public static void qaRemoveBot(int botId) {
        QA_BOTS.remove(botId);
    }

    private record Cached<T>(T value, long at) {
    }

    private final Map<String, Cached<Board.Owner>> owners = new ConcurrentHashMap<>();
    /** Posters the site may not name (GM or auto-QA characters), from the owner lookups. */
    private final java.util.Set<Integer> hiddenPosters = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Cached<Boolean>> accounts = new ConcurrentHashMap<>();

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public double roll() {
        return ThreadLocalRandom.current().nextDouble();
    }

    @Override
    public int between(int lo, int hi) {
        return hi <= lo ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    @Override
    public void later(long delayMs, Runnable task) {
        ExecutorServiceManager.getScheduledExecutorService().schedule(task, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    // ---- accounts, characters, items ----

    @Override
    public Board.Owner owner(String character) {
        if (character == null || character.isBlank() || character.length() > 13) {
            return null;
        }
        String key = character.trim().toLowerCase(Locale.ROOT);
        Cached<Board.Owner> c = owners.get(key);
        long now = now();
        if (c != null && now - c.at() < CACHE_MS) {
            return c.value();
        }
        Board.Owner o = null;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, accountid, name, gm FROM characters WHERE name = ?")) {
            ps.setString(1, character.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    o = new Board.Owner(rs.getInt("id"), rs.getInt("accountid"), rs.getString("name"));
                    if (soloMapling.site.SiteFeed.isPublic(o.name(), rs.getInt("gm"), false)) {
                        hiddenPosters.remove(o.characterId());
                    } else {
                        hiddenPosters.add(o.characterId());
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("characters: " + e.getMessage(), e);
        }
        owners.put(key, new Cached<>(o, now));
        return o;
    }

    @Override
    public Map<String, Object> tradeRatings(long now) {
        return soloMapling.site.PublicSiteExport.tradeRatingsFile(now);
    }

    @Override
    public boolean publicCharacter(int characterId) {
        return !hiddenPosters.contains(characterId);
    }

    @Override
    public boolean accountExists(int account) {
        Cached<Boolean> c = accounts.get(account);
        long now = now();
        if (c != null && now - c.at() < CACHE_MS) {
            return c.value();
        }
        boolean found;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT 1 FROM accounts WHERE id = ?")) {
            ps.setInt(1, account);
            try (ResultSet rs = ps.executeQuery()) {
                found = rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("accounts: " + e.getMessage(), e);
        }
        accounts.put(account, new Cached<>(found, now));
        return found;
    }

    @Override
    public boolean itemExists(int itemId) {
        try {
            return ItemInformationProvider.getInstance().getName(itemId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean tradeable(int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        return !ii.isUntradeableRestricted(itemId) && !ii.isAccountRestricted(itemId) && !ii.isQuestItem(itemId);
    }

    @Override
    public boolean equip(int itemId) {
        return ItemConstants.isEquipment(itemId);
    }

    @Override
    public String itemName(int itemId) {
        try {
            String n = ItemInformationProvider.getInstance().getName(itemId);
            return n == null ? "" : n;
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---- the market ----

    @Override
    public Map<Integer, Long> lowestListings() {
        Map<Integer, Long> low = new HashMap<>();
        for (MarketListings.Shop shop : PublicMarketExport.liveShops()) {
            for (MarketListings.Row r : shop.items()) {
                if (r == null || r.bundles() <= 0 || r.bundlePrice() <= 0) {
                    continue;
                }
                low.merge(r.itemId(), r.unitPrice(), Math::min);
            }
        }
        return low;
    }

    // ---- bots ----

    static boolean greedy(int botId) {
        try {
            return LifeProfile.forBot(botId).blend().getOrDefault(LifeProfile.Archetype.MERCHANT, 0.0) >= GREEDY_SHARE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A merchant type, a QA-staged bot, or a greedy one. */
    static boolean trader(int botId) {
        if (QA_BOTS.contains(botId)) {
            return true;
        }
        BotSM sm = CharacterStorage.getBotById(botId);
        return sm instanceof SellingMerchantBot || sm instanceof BuyingMerchantBot || greedy(botId);
    }

    private static Character onlineChar(int characterId) {
        try {
            for (World w : Server.getInstance().getWorlds()) {
                Character c = w.getPlayerStorage().getCharacterById(characterId);
                if (c != null) {
                    return c;
                }
            }
        } catch (RuntimeException e) {
            // no world yet
        }
        return null;
    }

    @Override
    public List<MegaBuyDesk.Stock> sellers(int itemId, int posterCharacterId) {
        Character poster = onlineChar(posterCharacterId);
        MegaBuyGame game = new MegaBuyGame();
        List<MegaBuyDesk.Stock> out = new ArrayList<>();
        for (MegaBuyDesk.Stock s : game.stock(new MegaBuyDesk.Asker(posterCharacterId, "", 0, poster))) {
            if (s.itemId() != itemId || (s.source() == MegaBuyDesk.Source.BAG && !trader(s.botId()))) {
                continue;   // bot shops and merchant bots always count; a bag bot only when it is greedy
            }
            out.add(s.source() == MegaBuyDesk.Source.BAG
                    ? new MegaBuyDesk.Stock(s.source(), s.botId(), s.botName(), s.channel(), s.itemId(), s.itemName(),
                    s.qty(), game.fairOf(s), s.ask(), s.where(), s.handle())
                    : s);
        }
        return out;
    }

    /** One unit of the item, to price and check the way a bot buying it in a trade would. */
    private static Item unit(int itemId) {
        return new Item(itemId, (short) 0, (short) 1);
    }

    @Override
    public List<BoardOffers.Buyer> buyers(int itemId, int qty, int posterCharacterId) {
        List<BoardOffers.Buyer> out = new ArrayList<>();
        if (ItemConstants.isEquipment(itemId)) {
            return out;   // bots buy only above-average gear, which a post cannot show
        }
        Item probe = unit(itemId);
        if (BotBuying.whyNotBuyFromPlayer(probe) != null) {
            return out;
        }
        long unitValue = PriceBook.botBuyValue(probe);
        if (unitValue <= 0) {
            return out;
        }
        Character poster = onlineChar(posterCharacterId);
        List<Character> bots = new ArrayList<>();
        for (BotSM sm : new ArrayList<>(CharacterStorage.getAllBots().values())) {
            Character bot = sm == null ? null : sm.getChr();
            if (bot != null && sm.getRunning() && !BotDirectTrade.handlesOwnTrades(sm)) {
                bots.add(bot);
            }
        }
        for (int id : QA_BOTS) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot != null && !bots.contains(bot)) {
                bots.add(bot);
            }
        }
        for (Character bot : bots) {
            try {
                if (bot.getMap() == null || !trader(bot.getId()) || BotAffinity.hostile(bot.getId(), posterCharacterId)
                        || !BotDirectTrade.takesTradeFrom(bot, poster)) {
                    continue;
                }
                long spend = spendable(bot);
                if (spend > 0) {
                    out.add(new BoardOffers.Buyer(bot.getId(), bot.getName(), MegaBuyGame.channelOfPlayer(bot), spend,
                            unitValue));
                }
            } catch (RuntimeException e) {
                // one half-built bot never blocks the scan
            }
        }
        return out;
    }

    /** As a bot's trade wallet counts it: its mesos above the reserve it keeps. */
    static long spendable(Character bot) {
        double keep = EconomyConfig.getDouble("directTrade.reserveShareOfCeiling", 0.25);
        long reserve = (long) (BotWealth.ceiling(bot) * Math.max(0, keep));
        return Math.max(0, (long) bot.getMeso() + BotTradeCommands.readMeso(bot) - reserve);
    }

    @Override
    public boolean stillHas(MegaBuyDesk.Stock stock) {
        return stock != null && new MegaBuyGame().stillHas(stock);
    }

    @Override
    public long spendable(int botId) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        return bot == null ? 0 : spendable(bot);
    }

    // ---- people ----

    @Override
    public Recipient online(int account, String preferred) {
        Recipient any = null;
        try {
            for (World w : Server.getInstance().getWorlds()) {
                for (Character c : w.getPlayerStorage().getAllCharacters()) {
                    if (c == null || c.getId() >= BOT_ID_BASE || c.getAccountID() != account
                            || c.getClient() instanceof BotClient || !c.isLoggedinWorld()) {
                        continue;
                    }
                    Recipient r = new Recipient(c.getId(), c.getName(), c);
                    if (preferred != null && preferred.equalsIgnoreCase(c.getName())) {
                        return r;
                    }
                    if (any == null) {
                        any = r;
                    }
                }
            }
        } catch (RuntimeException e) {
            // no world yet
        }
        return any;
    }

    @Override
    public String lastPlayed(int account) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT name FROM characters WHERE accountid = ? AND id < ?"
                     + " ORDER BY lastLogoutTime DESC, id DESC LIMIT 1")) {
            ps.setInt(1, account);
            ps.setInt(2, BOT_ID_BASE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("characters: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean whisper(Recipient to, String botName, int channel, String line) {
        // an answer to the player's own post: through the social gate (busy, repeats) but asked for, like the megaphone
        return to.handle() instanceof Character p
                && BotWhispers.send(p, botName, Math.max(0, channel - 1), line, SocialGate.Act.TRADE_OFFER);
    }

    @Override
    public boolean note(String from, String toCharacter, String text) {
        String t = text.length() > 200 ? text.substring(0, 200) : text;
        return new NoteService(new NoteDao()).sendNormal(t, from, toCharacter);
    }

    @Override
    public void yellow(Recipient to, String text) {
        if (to.handle() instanceof Character p) {
            p.yellowMessage(text);
        }
    }

    @Override
    public void quote(MegaBuyDesk.Stock stock, Recipient to, long price, int rounds) {
        Character bot = BotHelpers.getCharFromChannelStorage(stock.botId());
        if (bot != null && to.handle() instanceof Character p && stock.handle() instanceof Item item) {
            BotDirectTrade.rememberQuote(bot, p, item, price, rounds);
        }
    }

    // ---- bots' own posts ----

    /** Item kinds a buying bot asks for: scrolls and etc (ores, materials), the things players farm and sell. */
    static boolean wantable(int itemId) {
        return itemId / 10000 == 204 || itemId / 1000000 == 4;
    }

    @Override
    public List<BotBoardPosts.Candidate> botPostCandidates() {
        List<BotBoardPosts.Candidate> out = new ArrayList<>();
        MegaBuyGame game = new MegaBuyGame();
        Map<Integer, Boolean> traders = new HashMap<>();
        Map<Integer, Long> spend = new HashMap<>();
        for (MegaBuyDesk.Stock s : game.stock(new MegaBuyDesk.Asker(0, "", 0, null))) {
            // a merchant bot's sale item is not in its bag, so its post could not be checked later: bag and shop only
            if (s.source() == MegaBuyDesk.Source.MERCHANT || !traders.computeIfAbsent(s.botId(), GameBoardWorld::trader)) {
                continue;
            }
            long unit;
            if (s.source() == MegaBuyDesk.Source.BAG) {
                long fair = game.fairOf(s);
                if (fair <= 0) {
                    continue;
                }
                unit = PriceBand.opening(PriceBand.of(PriceBand.Side.BOT_SELLS, fair, false)) / Math.max(1, s.qty());
            } else {
                unit = s.ask() / Math.max(1, s.qty());
            }
            if (unit > 0) {
                out.add(new BotBoardPosts.Candidate(s.botId(), s.botName(), Board.Kind.WTS, s.itemId(), s.qty(), unit));
            }
            if (s.source() == MegaBuyDesk.Source.BAG && !spend.containsKey(s.botId())) {
                Character bot = BotHelpers.getCharFromChannelStorage(s.botId());
                spend.put(s.botId(), bot == null ? 0 : spendable(bot));
            }
        }
        // buyers: greedy bag bots with mesos ask for something players farm and sell
        List<Integer> wanted = new ArrayList<>();
        for (int id : PriceBook.trackedItemIds()) {
            if (wantable(id)) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return out;
        }
        int asked = 0;
        for (Map.Entry<Integer, Long> e : spend.entrySet()) {
            if (asked >= 40 || e.getValue() <= 0) {
                continue;
            }
            int itemId = wanted.get(between(0, wanted.size() - 1));
            Item probe = unit(itemId);
            if (BotBuying.whyNotBuyFromPlayer(probe) != null) {
                continue;
            }
            long value = PriceBook.botBuyValue(probe);
            if (value <= 0) {
                continue;
            }
            int qty = itemId / 10000 == 204 ? 1 : between(1, 10) * 10;
            long total = PriceBand.opening(PriceBand.of(PriceBand.Side.BOT_BUYS, value * qty, false));
            long unitPrice = total / qty;
            if (unitPrice <= 0 || unitPrice * qty > e.getValue()) {
                continue;
            }
            Character bot = BotHelpers.getCharFromChannelStorage(e.getKey());
            if (bot != null) {
                out.add(new BotBoardPosts.Candidate(bot.getId(), bot.getName(), Board.Kind.WTB, itemId, qty, unitPrice));
                asked++;
            }
        }
        return out;
    }

    @Override
    public boolean stillBacks(Board.Post p) {
        Character bot = BotHelpers.getCharFromChannelStorage(p.characterId());
        if (bot == null || bot.getMap() == null) {
            return false;
        }
        if (p.kind() == Board.Kind.WTB) {
            return spendable(bot) >= p.total();
        }
        if (bot.getItemQuantity(p.itemId(), false) >= p.qty()) {
            return true;
        }
        for (BotShops.ShopStock s : BotShops.stockSnapshot()) {
            if (s.botId() == p.characterId() && s.item().getItemId() == p.itemId()) {
                return true;
            }
        }
        return false;
    }
}
