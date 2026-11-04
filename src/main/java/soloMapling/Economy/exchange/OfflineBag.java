package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.AssetRegistry.Asset;

import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Ticket 270 (owner 2026-11-03: "sell from site item wallet"): the bags of a character who is <b>not</b> in the game,
 * as the site's item sell needs them. This is the same tradeable rule as {@link GameLiveWallet} and the same write: the
 * whole inventory is saved in the exchange's transaction with {@link ItemFactory#INVENTORY}'s save, which is what a
 * character save writes.
 * <p>
 * Why it cannot dupe: the bags are read once, changed in memory under {@link #locked}, and written only by the batch
 * that places the order; the stock goes to the order's escrow in that same batch, so the database never has the items
 * twice. The order's own transaction also locks the account row ({@link ExchangeStore} requires it logged out), so a
 * login that races the sell either waits for the sell to commit (and then loads the reduced bags) or makes the sell
 * refuse (ONLINE/MOVED). Nothing here pays items out: a fill's mesos go to the seller's PAYOUT and are swept into the
 * character row by {@link Exchange#collectQuietly}, and item payouts still wait for a live character.
 * <p>
 * The take is deliberately the only operation: this is a sell from the bag, never a way to move items into it.
 */
final class OfflineBag implements LiveWallet {

    private static final Logger log = LoggerFactory.getLogger(OfflineBag.class);

    private final long account;
    private final int character;
    private final List<Pair<Item, InventoryType>> items;
    private boolean changed;

    private OfflineBag(long account, int character, List<Pair<Item, InventoryType>> items) {
        this.account = account;
        this.character = character;
        this.items = items;
    }

    /**
     * The bags of {@code character} when it belongs to {@code account} and is not in the game, else null. A character
     * that is logged in must go through {@link GameLiveWallet}, so the caller checks the live wallet first.
     */
    static OfflineBag open(long account, long character) {
        if (account <= 0 || character <= 0 || character > Integer.MAX_VALUE || !owned(account, character)) {
            return null;
        }
        try {
            return new OfflineBag(account, (int) character,
                    new ArrayList<>(ItemFactory.INVENTORY.loadItems((int) character, false)));
        } catch (SQLException | RuntimeException e) {
            log.warn("NX exchange: could not read the bags of character {} (account {})", character, account, e);
            return null;
        }
    }

    private static boolean owned(long account, long character) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT 1 FROM characters WHERE id = ? AND accountid = ?")) {
            ps.setLong(1, character);
            ps.setLong(2, account);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public long account() {
        return account;
    }

    @Override
    public long character() {
        return character;
    }

    @Override
    public <T> T locked(Supplier<T> body) {
        synchronized (this) {
            return body.get();
        }
    }

    /** The bags were read from the database a moment ago and the account is locked out of the game by the place. */
    @Override
    public boolean offline() {
        return true;
    }

    @Override
    public boolean usable() {
        return true;
    }

    /** Nothing in memory to save: the database is the truth until the place writes it. */
    @Override
    public boolean saveNow() {
        return true;
    }

    @Override
    public long have(Asset asset) {
        if (!asset.item() || GameLiveWallet.cashItem(asset.itemId())) {
            return 0;
        }
        long n = 0;
        for (Pair<Item, InventoryType> p : items) {
            if (p.getLeft().getItemId() == asset.itemId() && traded(p.getLeft())) {
                n += p.getLeft().getQuantity();
            }
        }
        return n;
    }

    @Override
    public boolean take(Asset asset, long amount) {
        if (!asset.item() || amount < 0 || amount > Integer.MAX_VALUE) {
            return false;
        }
        if (amount == 0) {
            return true;
        }
        if (GameLiveWallet.cashItem(asset.itemId())) {
            return false;
        }
        List<Pair<Item, InventoryType>> ok = new ArrayList<>();
        long sum = 0;
        for (Pair<Item, InventoryType> p : items) {
            if (p.getLeft().getItemId() == asset.itemId() && traded(p.getLeft())) {
                ok.add(p);
                sum += p.getLeft().getQuantity();
            }
        }
        if (sum < amount) {
            return false;
        }
        ok.sort(Comparator.comparingInt((Pair<Item, InventoryType> p) -> p.getLeft().getQuantity())
                .thenComparingInt(p -> p.getLeft().getPosition()));
        long left = amount;
        for (Pair<Item, InventoryType> p : ok) {
            if (left <= 0) {
                break;
            }
            Item it = p.getLeft();
            int q = (int) Math.min(left, it.getQuantity());
            if (q >= it.getQuantity()) {
                items.remove(p);
            } else {
                it.setQuantity((short) (it.getQuantity() - q));
            }
            left -= q;
            changed = true;
        }
        return left == 0;
    }

    /** Items are never paid into bags here: a fill's items wait in PAYOUT for the character to log in. */
    @Override
    public boolean give(Asset asset, long amount) {
        return false;
    }

    @Override
    public long room(Asset asset, long want) {
        return 0;
    }

    /** The bags as {@link #take} left them, written where an order's transaction runs. */
    @Override
    public ExchangeStore.InventoryWriter inventoryWriter() {
        return changed ? con -> ItemFactory.INVENTORY.saveItems(items, character, con) : null;
    }

    @Override
    public void tell(String line) {
    }

    /** Tradeable as {@link GameLiveWallet} means it: no untradeable/lock flag, no expiry. */
    private static boolean traded(Item it) {
        return GameLiveWallet.tradeable(it);
    }
}
