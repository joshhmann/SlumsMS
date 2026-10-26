package soloMapling.Economy;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.NXMerchantBot;
import soloMapling.ArtificialPlayer.BotWhispers;
import soloMapling.ArtificialPlayer.MerchantStock;
import soloMapling.ArtificialPlayer.PlayerBusy;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 ticket 86: the real game behind {@link MegaBuyDesk.World}. Reads stock from what bots really hold (a bag item
 * a bot trades in person, a merchant bot's item for sale, the shelf of an open bot shop), whispers through
 * {@link BotWhispers}, and records a bag bot's price as a {@link BotDirectTrade} quote. It creates nothing: no item, no
 * meso, no new trade path.
 */
public final class MegaBuyGame implements MegaBuyDesk.World {

    /** Bots a !qa scenario staged without a state machine; the scan counts them too (ticket 86, tr-8). */
    private static final java.util.Set<Integer> QA_BOTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void qaAddBot(int botId) {
        QA_BOTS.add(botId);
    }

    public static void qaRemoveBot(int botId) {
        QA_BOTS.remove(botId);
    }

    /** A bag bot lists at most this many items, so one hoarder cannot fill the scan. */
    private static final int PER_BOT = 40;

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
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // the desk logs its own failures
            }
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private static Character player(MegaBuyDesk.Asker asker) {
        return asker.handle() instanceof Character c ? c : null;
    }

    @Override
    public List<MegaBuyDesk.Stock> stock(MegaBuyDesk.Asker asker) {
        Character player = player(asker);
        List<MegaBuyDesk.Stock> out = new ArrayList<>();
        Map<Integer, String> names = new HashMap<>();
        for (BotSM sm : new ArrayList<>(CharacterStorage.getAllBots().values())) {
            try {
                Character bot = sm.getChr();
                if (bot == null || bot.getMap() == null || bot == player || !sm.getRunning()
                        || BotAffinity.hostile(bot.getId(), asker.id())) {
                    continue;
                }
                int ch = channelOf(bot);
                String where = whereIs(bot, asker, ch);
                if (BotDirectTrade.handlesOwnTrades(sm)) {
                    addMerchant(out, sm, bot, ch, where);
                } else if (BotDirectTrade.takesTradeFrom(bot, player)) {
                    addBag(out, bot, ch, where, names);
                }
            } catch (RuntimeException e) {
                // one half-built bot never blocks the scan
            }
        }
        for (int id : QA_BOTS) {
            Character bot = soloMapling.ArtificialPlayer.BotHelpers.getCharFromChannelStorage(id);
            if (bot != null && bot.getMap() != null && bot != player && BotDirectTrade.takesTradeFrom(bot, player)) {
                addBag(out, bot, channelOf(bot), whereIs(bot, asker, channelOf(bot)), names);
            }
        }
        for (BotShops.ShopStock sh : BotShops.stockSnapshot()) {
            Character owner = soloMapling.ArtificialPlayer.BotHelpers.getCharFromChannelStorage(sh.botId());
            if (owner == null || BotAffinity.hostile(sh.botId(), asker.id())) {
                continue;
            }
            int itemId = sh.item().getItemId();
            out.add(new MegaBuyDesk.Stock(MegaBuyDesk.Source.SHOP, sh.botId(), sh.botName(), sh.channel(), itemId,
                    names.computeIfAbsent(itemId, BotShops::nameOf), Math.max(1, sh.item().getQuantity()),
                    PriceBook.stackValue(sh.item()), sh.price(), "fm room " + (sh.mapId() % 100) + " ch" + sh.channel()
                    + ", just buy it from the shop", sh));
        }
        return out;
    }

    private static void addMerchant(List<MegaBuyDesk.Stock> out, BotSM sm, Character bot, int ch, String where) {
        if (sm instanceof NXMerchantBot || !sm.takesTradesNow()) {
            return;
        }
        MerchantStock.Stock ms = MerchantStock.of(sm);
        Item main = ms != null && ms.selling() && sm.getTradeInventory() != null
                ? sm.getTradeInventory().getMainItemForSale() : null;
        if (main == null || ms.price() <= 0) {
            return;
        }
        out.add(new MegaBuyDesk.Stock(MegaBuyDesk.Source.MERCHANT, bot.getId(), bot.getName(), ch, main.getItemId(),
                ms.item(), Math.max(1, main.getQuantity()), 0, ms.price(), where, main));
    }

    private static void addBag(List<MegaBuyDesk.Stock> out, Character bot, int ch, String where, Map<Integer, String> names) {
        int added = 0;
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
                    continue;   // scrolls only: potions and ammo are the bot's own kit
                }
                if (SaleRules.whyNot(item) != null) {
                    continue;
                }
                out.add(new MegaBuyDesk.Stock(MegaBuyDesk.Source.BAG, bot.getId(), bot.getName(), ch, item.getItemId(),
                        names.computeIfAbsent(item.getItemId(), BotShops::nameOf), Math.max(1, item.getQuantity()), 0, 0,
                        where, item));
                if (++added >= PER_BOT) {
                    return;
                }
            }
        }
    }

    private static String whereIs(Character bot, MegaBuyDesk.Asker asker, int ch) {
        String map;
        try {
            map = bot.getMap().getMapName();
        } catch (RuntimeException e) {
            map = null;
        }
        String at = map == null || map.isBlank() ? "on ch" + ch : "at " + map.toLowerCase(Locale.ROOT) + " ch" + ch;
        return "im " + at;
    }

    public static int channelOfPlayer(Character chr) {
        return channelOf(chr);
    }

    static int channelOf(Character chr) {
        try {
            return chr.getClient().getChannel();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    @Override
    public boolean stillHas(MegaBuyDesk.Stock stock) {
        Character bot = soloMapling.ArtificialPlayer.BotHelpers.getCharFromChannelStorage(stock.botId());
        if (bot == null || !(stock.handle() instanceof Item item)) {
            return false;
        }
        return switch (stock.source()) {
            case BAG -> BotDirectTrade.carries(bot, item);
            case MERCHANT -> {
                BotSM sm = CharacterStorage.getBotById(stock.botId());
                yield sm != null && sm.takesTradesNow() && sm.getTradeInventory() != null
                        && sm.getTradeInventory().getMainItemForSale() == item;
            }
            case SHOP -> BotShops.stockSnapshot().stream().anyMatch(s -> s.item() == item);
        };
    }

    @Override
    public boolean askerOnline(MegaBuyDesk.Asker asker) {
        Character p = player(asker);
        return p != null && p.getClient() != null && p.isLoggedinWorld() && !PlayerBusy.isBusy(p);
    }

    @Override
    public boolean friend(int botId, int playerId) {
        return BotAffinity.friend(botId, playerId);
    }

    @Override
    public long fairOf(MegaBuyDesk.Stock stock) {
        return stock.handle() instanceof Item item ? PriceBook.stackValue(item) : 0;
    }

    @Override
    public boolean whisper(MegaBuyDesk.Stock stock, MegaBuyDesk.Asker asker, String line) {
        Character p = player(asker);
        // 1.6.0 S5: an answer to the player's buy megaphone - through the social gate (busy, no repeated line), but
        // asked for, so @quiet bots does not hold it and it spends no whisper budget
        return p != null && BotWhispers.send(p, stock.botName(), Math.max(0, stock.channel() - 1), line,
                soloMapling.ArtificialPlayer.SocialGate.Act.TRADE_OFFER);
    }

    @Override
    public void quote(MegaBuyDesk.Stock stock, MegaBuyDesk.Asker asker, long price, int rounds) {
        Character bot = soloMapling.ArtificialPlayer.BotHelpers.getCharFromChannelStorage(stock.botId());
        if (bot != null && stock.handle() instanceof Item item) {
            BotDirectTrade.rememberQuote(bot, player(asker), item, price, rounds);
        }
    }
}
