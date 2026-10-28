package soloMapling.Economy.exchange;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.server.Server;
import net.server.world.World;
import server.CashShop;
import server.ItemInformationProvider;
import tools.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Ticket 113 round 2: the {@link LiveWallet} of a logged-in player in the running game. The lock is the character's
 * monitor, which {@link Character#saveCharToDB(boolean)} (synchronized) also takes: every save, the autosave, the
 * channel change's and the logout's, waits while the exchange changes this wallet. Mesos change through
 * {@link Character#takeMesoExact}/{@link Character#giveMesoExact} (atomic with {@code gainMeso}), NX through
 * {@link CashShop#takeNxCredit}/{@link CashShop#giveNxCredit} (atomic with {@code gainCash}).
 * <p>
 * Ticker items (owner 2026-10-28) come out of and go into the character's bags under the bag's own lock; only
 * tradeable copies count ({@link #tradeable}); the store then writes the whole inventory, as memory holds it, in the
 * same transaction as the escrow or payout ({@link #inventoryWriter}), the same write a character save makes.
 */
public final class GameLiveWallet implements LiveWallet {

    private final Character chr;

    private GameLiveWallet(Character chr) {
        this.chr = chr;
    }

    /** The account's playing character (not a bot, not an owned alt), or null when it has none in a world. */
    public static LiveWallet find(long account) {
        try {
            for (World w : Server.getInstance().getWorlds()) {
                for (Character c : w.getPlayerStorage().getAllCharacters()) {
                    if (c.getAccountID() == account && isPlayer(c)) {
                        return new GameLiveWallet(c);
                    }
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** The wallet of a character the caller already has (the in-game command), or null for a bot or an alt. */
    public static LiveWallet of(Character c) {
        return c != null && isPlayer(c) ? new GameLiveWallet(c) : null;
    }

    private static boolean isPlayer(Character c) {
        Client cl = c.getClient();
        return cl != null && !(cl instanceof BotClient) && !c.isOwnedAlt() && cl.getPlayer() == c;
    }

    @Override
    public long account() {
        return chr.getAccountID();
    }

    @Override
    public long character() {
        return chr.getId();
    }

    @Override
    public <T> T locked(Supplier<T> body) {
        synchronized (chr) {
            return body.get();
        }
    }

    @Override
    public boolean usable() {
        Client cl = chr.getClient();
        if (cl == null || !chr.isLoggedin() || cl.isInTransition() || cl.getPlayer() != chr || chr.isOwnedAlt()) {
            return false;
        }
        CashShop cs = chr.getCashShop();
        if (cs == null || cs.isOpened()) {
            return false;
        }
        try {
            return cl.getChannelServer() != null
                    && cl.getChannelServer().getPlayerStorage().getCharacterById(chr.getId()) == chr;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean saveNow() {
        try {
            chr.saveCharToDB(true);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public long have(Ledger.Asset asset) {
        if (asset.item()) {
            Inventory inv = chr.getInventory(ItemConstants.getInventoryType(asset.itemId()));
            inv.lockInventory();
            try {
                long n = 0;
                for (Item it : inv.listById(asset.itemId())) {
                    n += tradeable(it) ? it.getQuantity() : 0;
                }
                return cashItem(asset.itemId()) ? 0 : n;
            } finally {
                inv.unlockInventory();
            }
        }
        return asset == Ledger.Asset.NX ? chr.getCashShop().getCash(1) : chr.getMeso();
    }

    /**
     * A copy the exchange may take: no untradeable or lock flag (an untradeable copy, a sealed one, a trade-once
     * scissors copy), no expiry date. Cash items are refused as a whole ({@link #cashItem}).
     */
    public static boolean tradeable(Item it) {
        return it != null && tradeable(it.getFlag(), it.getExpiration());
    }

    /** The same rule on a copy's flag and expiry (pure: no item data needed). */
    public static boolean tradeable(short flag, long expiration) {
        return (flag & (ItemConstants.UNTRADEABLE | ItemConstants.LOCK | ItemConstants.MERGE_UNTRADEABLE)) == 0
                && expiration <= 0;
    }

    private static boolean cashItem(int itemId) {
        try {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            return ii.isCash(itemId) || ii.isUntradeableRestricted(itemId);
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    public boolean take(Ledger.Asset asset, long amount) {
        if (amount < 0 || amount > Integer.MAX_VALUE) {
            return false;
        }
        if (asset.item()) {
            return takeItems(asset.itemId(), (int) amount);
        }
        return asset == Ledger.Asset.NX ? chr.getCashShop().takeNxCredit((int) amount)
                : chr.takeMesoExact((int) amount);
    }

    /** Removes exactly {@code amount} tradeable copies (smallest stacks first), or nothing. */
    private boolean takeItems(int itemId, int amount) {
        if (amount == 0) {
            return true;
        }
        if (cashItem(itemId)) {
            return false;
        }
        InventoryType type = ItemConstants.getInventoryType(itemId);
        Inventory inv = chr.getInventory(type);
        inv.lockInventory();
        try {
            List<Item> ok = new ArrayList<>();
            long have = 0;
            for (Item it : inv.listById(itemId)) {
                if (tradeable(it)) {
                    ok.add(it);
                    have += it.getQuantity();
                }
            }
            if (have < amount) {
                return false;
            }
            ok.sort(Comparator.comparingInt(Item::getQuantity).thenComparingInt(Item::getPosition));
            int left = amount;
            for (Item it : ok) {
                if (left <= 0) {
                    break;
                }
                short q = (short) Math.min(left, it.getQuantity());
                InventoryManipulator.removeFromSlot(chr.getClient(), type, it.getPosition(), q, false);
                left -= q;
            }
            return true;
        } finally {
            inv.unlockInventory();
        }
    }

    @Override
    public boolean give(Ledger.Asset asset, long amount) {
        if (amount < 0 || amount > Integer.MAX_VALUE) {
            return false;
        }
        if (asset.item()) {
            return giveItems(asset.itemId(), (int) amount);
        }
        return asset == Ledger.Asset.NX ? chr.getCashShop().giveNxCredit((int) amount)
                : chr.giveMesoExact((int) amount);
    }

    /** Adds {@code amount} plain copies (no flags, no expiry), or nothing: a part-way add is taken back out. */
    private boolean giveItems(int itemId, int amount) {
        if (amount == 0) {
            return true;
        }
        if (amount > Short.MAX_VALUE) {
            return false;
        }
        Inventory inv = chr.getInventory(ItemConstants.getInventoryType(itemId));
        inv.lockInventory();
        try {
            if (!InventoryManipulator.checkSpace(chr, itemId, amount, "")) {
                return false;
            }
            long before = tradeableCount(inv, itemId);
            if (InventoryManipulator.addById(chr, itemId, (short) amount)) {
                return true;
            }
            long added = tradeableCount(inv, itemId) - before;
            if (added > 0) {
                takeItems(itemId, (int) added);
            }
            return false;
        } finally {
            inv.unlockInventory();
        }
    }

    private static long tradeableCount(Inventory inv, int itemId) {
        long n = 0;
        for (Item it : inv.listById(itemId)) {
            n += tradeable(it) ? it.getQuantity() : 0;
        }
        return n;
    }

    @Override
    public long room(Ledger.Asset asset, long want) {
        if (!asset.item()) {
            return LiveWallet.super.room(asset, want);
        }
        long lo = 0, hi = Math.min(want, Short.MAX_VALUE);
        Inventory inv = chr.getInventory(ItemConstants.getInventoryType(asset.itemId()));
        inv.lockInventory();
        try {
            while (lo < hi) { // the most that fits: checkSpace only gets harder with more
                long mid = (lo + hi + 1) / 2;
                if (InventoryManipulator.checkSpace(chr, asset.itemId(), (int) mid, "")) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        } finally {
            inv.unlockInventory();
        }
    }

    /** The character's inventory as memory holds it, written the way {@code Character.saveCharToDB} writes it. */
    @Override
    public ExchangeStore.InventoryWriter inventoryWriter() {
        return con -> {
            List<Pair<Item, InventoryType>> items = new ArrayList<>();
            for (InventoryType type : InventoryType.values()) {
                Inventory iv = chr.getInventory(type);
                if (iv == null) {
                    continue;
                }
                for (Item item : iv.list()) {
                    items.add(new Pair<>(item, iv.getType()));
                }
            }
            ItemFactory.INVENTORY.saveItems(items, chr.getId(), con);
        };
    }

    @Override
    public void tell(String line) {
        try {
            chr.yellowMessage(line);
        } catch (RuntimeException ignored) {
            // the player left; the money is already in memory and saves with the character
        }
    }
}
