package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.ExchangeStore.Failure;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.Ledger.Kind;
import soloMapling.Economy.exchange.Ledger.Posting;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Ticket 113: the exchange's MySQL store (tables from migration 038-nx-exchange). Every write is one transaction:
 * <ol>
 *   <li>each account whose wallet a posting touches is locked ({@code SELECT loggedin ... FOR UPDATE}, in id order so
 *   two batches never deadlock) and must be logged out ({@code loggedin = 0}). The game's login writes that column
 *   with an UPDATE, which waits for this lock, and loads the character only after: so a character can never be
 *   loaded with a wallet value this transaction is about to change, and a logged-in wallet is never written;</li>
 *   <li>each posting debits with a guard ({@code ... AND amount >= ?}; zero rows = refused), credits, and appends its
 *   {@code exchange_ledger} row (the ledger is only ever INSERTed);</li>
 *   <li>order and fill rows are written; then COMMIT. Any failure rolls the whole thing back.</li>
 * </ol>
 * Ticker items (migration 044): an item WALLET leg is only accepted for the batch's live character together with its
 * {@link ExchangeStore.InventoryWriter}, which writes that character's whole inventory in this same transaction.
 */
public final class JdbcExchangeStore implements ExchangeStore {

    /** Where connections come from (the game's pool, or a test database). */
    public interface Connections {
        Connection get() throws SQLException;
    }

    static final String LOCK_ACCOUNT = "SELECT loggedin, COALESCE(nxCredit, 0) FROM accounts WHERE id = ? FOR UPDATE";
    static final String DEBIT_NX = "UPDATE accounts SET nxCredit = COALESCE(nxCredit, 0) - ? WHERE id = ? AND COALESCE(nxCredit, 0) >= ?";
    static final String CREDIT_NX = "UPDATE accounts SET nxCredit = COALESCE(nxCredit, 0) + ? WHERE id = ? AND COALESCE(nxCredit, 0) <= 2147483647 - ?";
    static final String DEBIT_MESO = "UPDATE characters SET meso = meso - ? WHERE id = ? AND accountid = ? AND meso >= ?";
    static final String CREDIT_MESO = "UPDATE characters SET meso = meso + ? WHERE id = ? AND accountid = ? AND meso <= 2147483647 - ?";
    static final String CHAR_ACCOUNT = "SELECT accountid FROM characters WHERE id = ?";
    static final String DEBIT_BUCKET = "UPDATE exchange_escrow SET amount = amount - ?, updated_at = ? WHERE kind = ? AND owner_id = ? AND asset = ? AND amount >= ?";
    static final String CREDIT_BUCKET = "INSERT INTO exchange_escrow (kind, owner_id, asset, account_id, amount, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount), updated_at = VALUES(updated_at)";
    static final String LEDGER = "INSERT INTO exchange_ledger (batch_id, at, why, from_kind, from_id, to_kind, to_id, asset, amount, account_id, order_id, fill_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String ORDER_UPDATE = "UPDATE exchange_orders SET nx_left = ?, mesos_held = ?, status = ?, closed_at = ? WHERE id = ?";
    static final String ORDER_INSERT = "INSERT INTO exchange_orders (id, request_id, account_id, character_id, regular_id, side, price, nx_total, nx_left, mesos_held, placed_at, expires_at, seq, status, closed_at, ticker) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String FILL_INSERT = "INSERT INTO exchange_fills (id, at, buy_order, sell_order, nx, price, mesos, buyer_regular, seller_regular, ticker) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String ORDER_COLUMNS = "id, request_id, account_id, character_id, regular_id, side, price, nx_total, nx_left, mesos_held, placed_at, expires_at, seq, status, closed_at, ticker";
    static final String FILL_COLUMNS = "id, at, buy_order, sell_order, nx, price, mesos, buyer_regular, seller_regular, ticker";

    private final Connections connections;

    public JdbcExchangeStore(Connections connections) {
        this.connections = connections;
    }

    public static JdbcExchangeStore forGame() {
        return new JdbcExchangeStore(tools.DatabaseConnection::getConnection);
    }

    private interface Work<T> {
        T run(Connection con) throws SQLException, StoreException;
    }

    private <T> T tx(Work<T> work) throws StoreException {
        try (Connection con = connections.get()) {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                T out = work.run(con);
                con.commit();
                return out;
            } catch (StoreException | SQLException | RuntimeException e) {
                try {
                    con.rollback();
                } catch (SQLException ignored) {
                    // the connection is gone; the server rolls back for us
                }
                if (e instanceof StoreException se) {
                    throw se;
                }
                if (e instanceof SQLIntegrityConstraintViolationException) {
                    throw new StoreException(Failure.DUPLICATE, e.getMessage(), e);
                }
                throw new StoreException(Failure.ERROR, String.valueOf(e.getMessage()), e);
            } finally {
                try {
                    con.setAutoCommit(auto);
                } catch (SQLException ignored) {
                    // closing anyway
                }
            }
        } catch (SQLException e) {
            throw new StoreException(Failure.ERROR, "no connection: " + e.getMessage(), e);
        }
    }

    /** Locks each account (id order) and checks it is logged out. */
    private static void lockOffline(Connection con, Iterable<Long> accounts) throws SQLException, StoreException {
        lock(con, accounts, 0);
    }

    /**
     * Locks each account (id order): {@code live} must be fully logged in ({@code loggedin = 2}: not mid channel change
     * or logout, which write that column and so wait for this lock), every other one logged out.
     */
    private static void lock(Connection con, Iterable<Long> accounts, long live) throws SQLException, StoreException {
        for (long account : accounts) {
            try (PreparedStatement ps = con.prepareStatement(LOCK_ACCOUNT)) {
                ps.setLong(1, account);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new StoreException(Failure.NOT_OWNER, "no account " + account);
                    }
                    int state = rs.getInt(1);
                    if (account == live) {
                        if (state != LOGGED_IN) {
                            throw new StoreException(Failure.MOVED, "account " + account + " loggedin=" + state);
                        }
                    } else if (state != 0) {
                        throw new StoreException(Failure.ONLINE, "account " + account);
                    }
                }
            }
        }
    }

    /** {@code accounts.loggedin} of a fully logged-in account ({@code Client.LOGIN_LOGGEDIN}). */
    static final int LOGGED_IN = 2;

    @Override
    public void apply(Batch batch) throws StoreException {
        tx(con -> {
            TreeSet<Long> accounts = new TreeSet<>();
            for (Posting p : batch.postings()) {
                if (p.from().kind() == Kind.WALLET || p.to().kind() == Kind.WALLET) {
                    accounts.add(p.account());
                }
            }
            boolean items = false;
            for (Posting p : batch.postings()) {
                for (Bucket b : new Bucket[]{p.from(), p.to()}) {
                    if (b.kind() != Kind.WALLET) {
                        continue;
                    }
                    if (b.asset().item()) {
                        // ticker items: only the live character's bags, written whole below in this transaction
                        if (batch.liveAccount() <= 0 || p.account() != batch.liveAccount()
                                || b.id() != batch.liveCharacter() || batch.inventory() == null) {
                            throw new StoreException(Failure.NOT_OWNER, b + ": items move only through the live "
                                    + "character");
                        }
                        items = true;
                    }
                    if (batch.liveAccount() > 0 && p.account() == batch.liveAccount() && b.asset().perCharacter()
                            && b.id() != batch.liveCharacter()) {
                        throw new StoreException(Failure.NOT_OWNER, b + " is not the live character "
                                + batch.liveCharacter());
                    }
                }
            }
            lock(con, accounts, batch.liveAccount());
            for (Posting p : batch.postings()) {
                post(con, batch.id(), batch.at(), p);
            }
            if (items) {
                if (!Long.valueOf(batch.liveAccount()).equals(characterAccount(con, batch.liveCharacter()))) {
                    throw new StoreException(Failure.NOT_OWNER, "character " + batch.liveCharacter()
                            + " is not on account " + batch.liveAccount());
                }
                batch.inventory().write(con);
            }
            for (OrderRow o : batch.orders()) {
                writeOrder(con, o);
            }
            for (FillRow f : batch.fills()) {
                try (PreparedStatement ps = con.prepareStatement(FILL_INSERT)) {
                    ps.setLong(1, f.id());
                    ps.setLong(2, f.at());
                    ps.setLong(3, f.buyOrder());
                    ps.setLong(4, f.sellOrder());
                    ps.setLong(5, f.nx());
                    ps.setLong(6, f.price());
                    ps.setLong(7, f.mesos());
                    ps.setBoolean(8, f.buyerRegular());
                    ps.setBoolean(9, f.sellerRegular());
                    ps.setString(10, f.ticker());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    private static void writeOrder(Connection con, OrderRow o) throws SQLException, StoreException {
        try (PreparedStatement ps = con.prepareStatement(ORDER_UPDATE)) {
            ps.setLong(1, o.nxLeft());
            ps.setLong(2, o.mesosHeld());
            ps.setString(3, o.status());
            ps.setLong(4, o.closedAt());
            ps.setLong(5, o.id());
            if (ps.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement ps = con.prepareStatement(ORDER_INSERT)) {
            ps.setLong(1, o.id());
            ps.setString(2, o.requestId());
            ps.setLong(3, o.account());
            ps.setLong(4, o.character());
            ps.setLong(5, o.regular());
            ps.setString(6, o.side().name());
            ps.setLong(7, o.price());
            ps.setLong(8, o.nxTotal());
            ps.setLong(9, o.nxLeft());
            ps.setLong(10, o.mesosHeld());
            ps.setLong(11, o.placedAt());
            ps.setLong(12, o.expiresAt());
            ps.setLong(13, o.seq());
            ps.setString(14, o.status());
            ps.setLong(15, o.closedAt());
            ps.setString(16, o.ticker());
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new StoreException(Failure.DUPLICATE, "order " + o.id() + " / request " + o.requestId(), e);
        }
    }

    private static void post(Connection con, long batch, long at, Posting p) throws SQLException, StoreException {
        move(con, at, p.from(), p.amount(), p.account(), true);
        move(con, at, p.to(), p.amount(), p.account(), false);
        try (PreparedStatement ps = con.prepareStatement(LEDGER)) {
            ps.setLong(1, batch);
            ps.setLong(2, at);
            ps.setString(3, p.why().name());
            ps.setString(4, p.from().kind().name());
            ps.setLong(5, p.from().id());
            ps.setString(6, p.to().kind().name());
            ps.setLong(7, p.to().id());
            ps.setString(8, p.asset().name());
            ps.setLong(9, p.amount());
            ps.setLong(10, p.account());
            ps.setLong(11, p.orderId());
            ps.setLong(12, p.fillId());
            ps.executeUpdate();
        }
    }

    private static void move(Connection con, long at, Bucket b, long amount, long account, boolean debit)
            throws SQLException, StoreException {
        if (b.kind() == Kind.WALLET && b.asset().item()) {
            return; // the live character's inventory is written whole by the batch (checked in apply)
        }
        if (b.kind() == Kind.WALLET) {
            String sql = b.asset() == Asset.NX ? (debit ? DEBIT_NX : CREDIT_NX) : (debit ? DEBIT_MESO : CREDIT_MESO);
            if (b.asset() == Asset.NX && b.id() != account) {
                throw new StoreException(Failure.NOT_OWNER, b + " is not account " + account);
            }
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setLong(1, amount);
                ps.setLong(2, b.id());
                if (b.asset() == Asset.NX) {
                    ps.setLong(3, amount);
                } else {
                    ps.setLong(3, account);
                    ps.setLong(4, amount);
                }
                if (ps.executeUpdate() != 1) {
                    if (b.asset() == Asset.MESO && !Long.valueOf(account).equals(characterAccount(con, b.id()))) {
                        throw new StoreException(Failure.NOT_OWNER, b + " is not on account " + account);
                    }
                    throw new StoreException(debit ? Failure.INSUFFICIENT : Failure.ERROR,
                            b + (debit ? " cannot pay " : " cannot take ") + amount);
                }
            }
            return;
        }
        if (debit) {
            try (PreparedStatement ps = con.prepareStatement(DEBIT_BUCKET)) {
                ps.setLong(1, amount);
                ps.setLong(2, at);
                ps.setString(3, b.kind().name());
                ps.setLong(4, b.id());
                ps.setString(5, b.asset().name());
                ps.setLong(6, amount);
                if (ps.executeUpdate() != 1) {
                    throw new StoreException(Failure.INSUFFICIENT, b + " cannot pay " + amount);
                }
            }
            return;
        }
        long owner = 0;
        if (b.kind() == Kind.PAYOUT) {
            Long acc = b.asset().perCharacter() ? characterAccount(con, b.id()) : Long.valueOf(b.id());
            owner = acc == null ? 0 : acc;
        }
        try (PreparedStatement ps = con.prepareStatement(CREDIT_BUCKET)) {
            ps.setString(1, b.kind().name());
            ps.setLong(2, b.id());
            ps.setString(3, b.asset().name());
            ps.setLong(4, owner);
            ps.setLong(5, amount);
            ps.setLong(6, at);
            ps.executeUpdate();
        }
    }

    private static Long characterAccount(Connection con, long character) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(CHAR_ACCOUNT)) {
            ps.setLong(1, character);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    @Override
    public List<Posting> collect(long batchId, long at, long account) throws StoreException {
        return tx(con -> {
            lockOffline(con, List.of(account));
            List<Posting> out = new ArrayList<>();
            long nxNow = 0;
            try (PreparedStatement ps = con.prepareStatement("SELECT COALESCE(nxCredit, 0) FROM accounts WHERE id = ?")) {
                ps.setLong(1, account);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        nxNow = rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT amount FROM exchange_escrow WHERE kind = 'PAYOUT' AND asset = 'NX' AND owner_id = ? AND amount > 0 FOR UPDATE")) {
                ps.setLong(1, account);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long move = Math.min(rs.getLong(1), Integer.MAX_VALUE - nxNow);
                        if (move > 0) {
                            out.add(new Posting(Ledger.Why.COLLECT, Bucket.payout(Asset.NX, account),
                                    Bucket.wallet(Asset.NX, account), move, account, 0, 0));
                        }
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT b.owner_id, b.amount, c.meso FROM exchange_escrow b JOIN characters c ON c.id = b.owner_id "
                            + "WHERE b.kind = 'PAYOUT' AND b.asset = 'MESO' AND c.accountid = ? AND b.amount > 0 "
                            + "ORDER BY b.owner_id FOR UPDATE")) {
                ps.setLong(1, account);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long move = Math.min(rs.getLong(2), Integer.MAX_VALUE - rs.getLong(3));
                        if (move > 0) {
                            out.add(new Posting(Ledger.Why.COLLECT, Bucket.payout(Asset.MESO, rs.getLong(1)),
                                    Bucket.wallet(Asset.MESO, rs.getLong(1)), move, account, 0, 0));
                        }
                    }
                }
            }
            for (Posting p : out) {
                post(con, batchId, at, p);
            }
            return out;
        });
    }

    @Override
    public List<Long> accountsWithPayout() throws StoreException {
        return tx(con -> {
            List<Long> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT DISTINCT account_id FROM exchange_escrow WHERE kind = 'PAYOUT' AND amount > 0 AND account_id > 0 ORDER BY account_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
            return out;
        });
    }

    @Override
    public OrderRow byRequest(String requestId) throws StoreException {
        return tx(con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT " + ORDER_COLUMNS + " FROM exchange_orders WHERE request_id = ?")) {
                ps.setString(1, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? order(rs) : null;
                }
            }
        });
    }

    @Override
    public List<CharRef> charactersOf(long account, int world) throws StoreException {
        return tx(con -> {
            List<CharRef> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, name, world FROM characters WHERE accountid = ? AND world = ?")) {
                ps.setLong(1, account);
                ps.setInt(2, world);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new CharRef(rs.getLong(1), rs.getString(2), rs.getInt(3)));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public List<OrderRow> ordersOf(long account, int limit) throws StoreException {
        return tx(con -> {
            List<OrderRow> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT " + ORDER_COLUMNS
                    + " FROM exchange_orders WHERE account_id = ? AND regular_id = 0 ORDER BY id DESC LIMIT ?")) {
                ps.setLong(1, account);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(order(rs));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public long balance(Bucket b) throws StoreException {
        return tx(con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT amount FROM exchange_escrow WHERE kind = ? AND owner_id = ? AND asset = ?")) {
                ps.setString(1, b.kind().name());
                ps.setLong(2, b.id());
                ps.setString(3, b.asset().name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    @Override
    public OrderRow order(long id) throws StoreException {
        return tx(con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT " + ORDER_COLUMNS + " FROM exchange_orders WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? order(rs) : null;
                }
            }
        });
    }

    @Override
    public FillRow fill(long id) throws StoreException {
        return tx(con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT " + FILL_COLUMNS + " FROM exchange_fills WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? fillRow(rs) : null;
                }
            }
        });
    }

    @Override
    public List<FillRow> fillsSince(long since) throws StoreException {
        return tx(con -> {
            List<FillRow> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT " + FILL_COLUMNS + " FROM exchange_fills WHERE at >= ? ORDER BY at, id")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(fillRow(rs));
                    }
                }
            }
            return out;
        });
    }

    private static FillRow fillRow(ResultSet rs) throws SQLException {
        return new FillRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                rs.getLong(7), rs.getBoolean(8), rs.getBoolean(9), rs.getString(10));
    }

    @Override
    public List<Posting> postingsOfFill(long fillId) throws StoreException {
        FillRow f = fill(fillId);
        if (f == null) {
            return List.of();
        }
        return tx(con -> {
            List<Posting> out = new ArrayList<>();
            // through the order_id index: every posting of a fill carries one of its two orders
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT why, from_kind, from_id, to_kind, to_id, asset, amount, account_id, order_id, fill_id "
                            + "FROM exchange_ledger WHERE order_id IN (?, ?) AND fill_id = ? ORDER BY id")) {
                ps.setLong(1, f.buyOrder());
                ps.setLong(2, f.sellOrder());
                ps.setLong(3, fillId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Asset a = Asset.valueOf(rs.getString(6));
                        out.add(new Posting(Ledger.Why.valueOf(rs.getString(1)),
                                new Bucket(Kind.valueOf(rs.getString(2)), rs.getLong(3), a),
                                new Bucket(Kind.valueOf(rs.getString(4)), rs.getLong(5), a), rs.getLong(7),
                                rs.getLong(8), rs.getLong(9), rs.getLong(10)));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public Loaded load(int recentFills) throws StoreException {
        return tx(con -> {
            List<OrderRow> open = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT " + ORDER_COLUMNS
                    + " FROM exchange_orders WHERE status = 'OPEN' ORDER BY seq");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    open.add(order(rs));
                }
            }
            Map<Bucket, Long> balances = new HashMap<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT kind, owner_id, asset, amount FROM exchange_escrow WHERE amount <> 0");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    balances.put(new Bucket(Kind.valueOf(rs.getString(1)), rs.getLong(2), Asset.valueOf(rs.getString(3))),
                            rs.getLong(4));
                }
            }
            Map<Bucket, Long> sums = new HashMap<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT to_kind, to_id, asset, SUM(amount) FROM exchange_ledger WHERE to_kind <> 'WALLET' GROUP BY to_kind, to_id, asset");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sums.merge(new Bucket(Kind.valueOf(rs.getString(1)), rs.getLong(2), Asset.valueOf(rs.getString(3))),
                            rs.getLong(4), Long::sum);
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT from_kind, from_id, asset, SUM(amount) FROM exchange_ledger WHERE from_kind <> 'WALLET' GROUP BY from_kind, from_id, asset");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sums.merge(new Bucket(Kind.valueOf(rs.getString(1)), rs.getLong(2), Asset.valueOf(rs.getString(3))),
                            -rs.getLong(4), Long::sum);
                }
            }
            sums.values().removeIf(v -> v == 0);
            List<FillRow> fills = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT " + FILL_COLUMNS + " FROM exchange_fills ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, recentFills);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fills.add(fillRow(rs));
                    }
                }
            }
            java.util.Collections.reverse(fills);
            return new Loaded(open, balances, sums, fills, max(con, "SELECT COALESCE(MAX(id), 0) FROM exchange_orders"),
                    max(con, "SELECT COALESCE(MAX(id), 0) FROM exchange_fills"),
                    max(con, "SELECT COALESCE(MAX(seq), 0) FROM exchange_orders"),
                    max(con, "SELECT COALESCE(MAX(batch_id), 0) FROM exchange_ledger"));
        });
    }

    private static long max(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static OrderRow order(ResultSet rs) throws SQLException {
        return new OrderRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                OrderBook.Side.valueOf(rs.getString(6)), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
                rs.getLong(11), rs.getLong(12), rs.getLong(13), rs.getString(14), rs.getLong(15), rs.getString(16));
    }
}
