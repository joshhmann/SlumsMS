package soloMapling.Economy.exchange.volatility;

import client.Character;
import client.inventory.Inventory;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.GameLiveWallet;
import soloMapling.Economy.exchange.Ledger;
import soloMapling.Economy.exchange.LiveWallet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 1.6.1 volatility: a trader bot's own mesos and bags as an exchange wallet ({@link Exchange#placeBot}). Bots have no
 * {@code characters} row and save through {@link BotStore}, so the exchange never writes them: it takes the escrow out
 * of the bot here (through {@link BotHoldings}, the accounting path every bot gain and spend goes through), saves the
 * bot, and only then records the order; payouts come back the same way. Its account and character are both the bot's
 * character id ({@link Exchange#botAccount}). Never NX: bots trade the item tickers only.
 */
public final class BotTraderWallet implements LiveWallet {

    private final Character bot;

    private BotTraderWallet(Character bot) {
        this.bot = bot;
    }

    /** The wallet of a roster bot in the game, or null (a player, an alt, a bot not logged in). */
    public static BotTraderWallet of(Character c) {
        return c != null && BotHelpers.isRosterBot(c) && Exchange.botAccount(c.getId()) ? new BotTraderWallet(c) : null;
    }

    public Character character0() {
        return bot;
    }

    @Override
    public long account() {
        return bot.getId();
    }

    @Override
    public long character() {
        return bot.getId();
    }

    @Override
    public <T> T locked(Supplier<T> body) {
        synchronized (bot) {
            return body.get();
        }
    }

    @Override
    public boolean usable() {
        try {
            return CharacterStorage.botLoggedIn(bot.getId()) && CharacterStorage.getBotById(bot.getId()) != null
                    && CharacterStorage.getBotById(bot.getId()).getChr() == bot && bot.getMap() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Saves the bot's row and bags (the same sweep the hourly bot autosave makes). */
    @Override
    public boolean saveNow() {
        try {
            return BotStore.saveAll(List.of(bot)) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public long have(Ledger.Asset asset) {
        if (asset == Ledger.Asset.NX) {
            return 0;
        }
        if (!asset.item()) {
            return bot.getMeso();
        }
        Inventory inv = bot.getInventory(ItemConstants.getInventoryType(asset.itemId()));
        inv.lockInventory();
        try {
            long n = 0;
            for (Item it : inv.listById(asset.itemId())) {
                n += GameLiveWallet.tradeable(it) ? it.getQuantity() : 0;
            }
            return n;
        } finally {
            inv.unlockInventory();
        }
    }

    @Override
    public boolean take(Ledger.Asset asset, long amount) {
        if (amount < 0 || amount > Integer.MAX_VALUE || asset == Ledger.Asset.NX) {
            return false;
        }
        if (amount == 0) {
            return true;
        }
        if (!asset.item()) {
            return BotHoldings.spendMeso(bot, (int) amount);
        }
        Inventory inv = bot.getInventory(ItemConstants.getInventoryType(asset.itemId()));
        List<Item> ok = new ArrayList<>();
        inv.lockInventory();
        try {
            long have = 0;
            for (Item it : inv.listById(asset.itemId())) {
                if (GameLiveWallet.tradeable(it)) {
                    ok.add(it);
                    have += it.getQuantity();
                }
            }
            if (have < amount) {
                return false;
            }
        } finally {
            inv.unlockInventory();
        }
        ok.sort(Comparator.comparingInt(Item::getQuantity).thenComparingInt(Item::getPosition));
        long left = amount;
        for (Item it : ok) {
            if (left <= 0) {
                break;
            }
            left -= BotHoldings.consumeHeld(bot, it, (int) Math.min(left, it.getQuantity()));
        }
        if (left > 0) {
            // a stack moved under us: put back what was taken, report nothing taken
            long took = amount - left;
            if (took > 0) {
                give(asset, took);
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean give(Ledger.Asset asset, long amount) {
        if (amount < 0 || amount > Integer.MAX_VALUE || asset == Ledger.Asset.NX) {
            return false;
        }
        if (amount == 0) {
            return true;
        }
        if (!asset.item()) {
            return BotHoldings.grantMeso(bot, (int) amount);
        }
        if (amount > Short.MAX_VALUE) {
            return false;
        }
        return BotHoldings.grantItem(bot, new Item(asset.itemId(), (short) 0, (short) amount));
    }

    @Override
    public long room(Ledger.Asset asset, long want) {
        if (!asset.item()) {
            return LiveWallet.super.room(asset, want);
        }
        long lo = 0, hi = Math.min(want, Short.MAX_VALUE);
        while (lo < hi) {
            long mid = (lo + hi + 1) / 2;
            if (BotHoldings.canHold(bot, new Item(asset.itemId(), (short) 0, (short) mid))) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
