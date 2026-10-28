package soloMapling.Economy.exchange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 113: the MySQL store against a real, throwaway MySQL 8 database. Skipped unless
 * {@code -Dslums.exchange.it.url=jdbc:mysql://127.0.0.1:PORT/DB?user=..&password=..} points at an empty scratch schema
 * (never a game database: it creates its own minimal accounts/characters tables). Applies migration 038 twice, runs
 * place/fill/cancel/collect, a simulated crash at every statement, the login race, recovery and a tampered balance,
 * then rolls 038 back twice.
 */
class JdbcExchangeStoreIT {

    static final String URL = System.getProperty("slums.exchange.it.url");
    static final long P = 1_000_000;

    Connection admin;
    AtomicLong now = new AtomicLong(1_800_000_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        admin = DriverManager.getConnection(URL);
        exec("DROP TABLE IF EXISTS exchange_ledger, exchange_escrow, exchange_fills, exchange_orders, accounts, characters, mapleroot_integration_migrations");
        exec("CREATE TABLE accounts (id INT PRIMARY KEY, loggedin TINYINT NOT NULL DEFAULT 0, nxCredit INT DEFAULT NULL) ENGINE=InnoDB");
        exec("CREATE TABLE characters (id INT PRIMARY KEY, accountid INT NOT NULL, meso INT NOT NULL DEFAULT 0) ENGINE=InnoDB");
        exec("INSERT INTO accounts VALUES (1, 0, 100000), (2, 0, NULL), (3, 0, 5000)");
        exec("INSERT INTO characters VALUES (11, 1, 50000000), (12, 2, 50000000), (13, 3, 1000)");
        runFile("038-nx-exchange.sql");
        runFile("038-nx-exchange.sql"); // twice: no change
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null) {
            runFile("038-nx-exchange.rollback.sql");
            runFile("038-nx-exchange.rollback.sql");
            try (ResultSet rs = admin.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'exchange_%'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "rollback removed every exchange table");
            }
            admin.close();
        }
    }

    void exec(String sql) throws SQLException {
        try (Statement s = admin.createStatement()) {
            s.execute(sql);
        }
    }

    void runFile(String name) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(Path.of("database/migrations", name))) {
            if (!line.strip().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        for (String stmt : sb.toString().split(";")) {
            if (!stmt.isBlank()) {
                exec(stmt);
            }
        }
    }

    long one(String sql) throws SQLException {
        try (ResultSet rs = admin.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    long totalNx() throws SQLException {
        return one("SELECT COALESCE(SUM(nxCredit),0) FROM accounts")
                + one("SELECT COALESCE(SUM(amount),0) FROM exchange_escrow WHERE asset='NX'");
    }

    long totalMeso() throws SQLException {
        return one("SELECT SUM(meso) FROM characters") + one("SELECT COALESCE(SUM(amount),0) FROM exchange_escrow WHERE asset='MESO'");
    }

    Exchange exchange(JdbcExchangeStore.Connections c) throws Exception {
        Exchange ex = new Exchange(new JdbcExchangeStore(c), ExchangeTest.open(), a -> false, now::get);
        assertTrue(ex.recover().isEmpty());
        return ex;
    }

    @Test
    void placeFillCancelCollectConserveAndRecover() throws Exception {
        long nx0 = totalNx(), meso0 = totalMeso();
        Exchange ex = exchange(() -> DriverManager.getConnection(URL));
        Response sell = ex.place(new Place("s", 1, 11, Side.SELL, P, 2000));
        assertTrue(sell.ok(), sell.toString());
        assertEquals(98_000, one("SELECT nxCredit FROM accounts WHERE id=1"));
        Response buy = ex.place(new Place("b", 2, 12, Side.BUY, 1_200_000, 1000));
        assertTrue(buy.ok(), buy.toString());
        assertEquals(1, ((Number) buy.data().get("fills")).intValue());
        assertEquals("NOT_OWNER", ex.place(new Place("n", 3, 12, Side.BUY, P, 100)).code());
        assertEquals("INSUFFICIENT_FUNDS", ex.place(new Place("i", 3, 13, Side.BUY, P, 100)).code());
        assertEquals(nx0, totalNx());
        assertEquals(meso0, totalMeso());
        assertTrue(ex.cancel("c", 1, ((Number) sell.data().get("orderId")).longValue()).ok());
        assertEquals(2, ex.collectAll());
        assertEquals(1000, one("SELECT nxCredit FROM accounts WHERE id=2"), "NULL nxCredit counts as 0");
        assertEquals(99_000, one("SELECT nxCredit FROM accounts WHERE id=1"));
        assertEquals(51_000_000, one("SELECT meso FROM characters WHERE id=11"));
        assertEquals(49_000_000, one("SELECT meso FROM characters WHERE id=12"));
        assertEquals(nx0, totalNx());
        assertEquals(meso0, totalMeso());
        Exchange again = exchange(() -> DriverManager.getConnection(URL));
        assertEquals(0, again.openOrders());
        assertEquals(1, again.lastTrades(5).size());
        // a hand edit of a balance without a ledger row halts the next start
        exec("UPDATE exchange_escrow SET amount = amount + 5 WHERE kind='PAYOUT' LIMIT 1");
        exec("INSERT INTO exchange_escrow (kind, owner_id, asset, account_id, amount) VALUES ('PAYOUT', 1, 'NX', 1, 7) ON DUPLICATE KEY UPDATE amount = amount + 7");
        Exchange tampered = new Exchange(new JdbcExchangeStore(() -> DriverManager.getConnection(URL)),
                ExchangeTest.open(), a -> false, now::get);
        assertFalse(tampered.recover().isEmpty());
        assertTrue(tampered.halted());
    }

    @Test
    void aLoggedInAccountIsRefusedAndALoginInFlightIsWaitedFor() throws Exception {
        Exchange ex = exchange(() -> DriverManager.getConnection(URL));
        exec("UPDATE accounts SET loggedin = 2 WHERE id = 1");
        assertEquals("LOG_OUT_FIRST", ex.place(new Place("o", 1, 11, Side.SELL, P, 1000)).code());
        exec("UPDATE accounts SET loggedin = 0 WHERE id = 1");
        // a login that has written loggedin but not committed: the exchange waits for it, then sees it
        CountDownLatch locked = new CountDownLatch(1);
        Thread login = new Thread(() -> {
            try (Connection c = DriverManager.getConnection(URL)) {
                c.setAutoCommit(false);
                c.createStatement().executeUpdate("UPDATE accounts SET loggedin = 2 WHERE id = 1");
                locked.countDown();
                Thread.sleep(700);
                c.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        login.start();
        locked.await();
        assertEquals("LOG_OUT_FIRST", ex.place(new Place("o2", 1, 11, Side.SELL, P, 1000)).code());
        login.join();
        assertEquals(100_000, one("SELECT nxCredit FROM accounts WHERE id=1"));
        assertEquals(0, one("SELECT COUNT(*) FROM exchange_ledger"));
    }

    @Test
    void aCrashAtAnyStatementRollsBackEverything() throws Exception {
        Exchange setup = exchange(() -> DriverManager.getConnection(URL));
        assertTrue(setup.place(new Place("s1", 1, 11, Side.SELL, 900_000, 300)).ok());
        assertTrue(setup.place(new Place("s2", 1, 11, Side.SELL, 950_000, 500)).ok());
        long nx0 = totalNx(), meso0 = totalMeso(), ledger0 = one("SELECT COUNT(*) FROM exchange_ledger");
        AtomicInteger failAt = new AtomicInteger();
        AtomicInteger count = new AtomicInteger();
        JdbcExchangeStore.Connections flaky = () -> {
            Connection real = DriverManager.getConnection(URL);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, m, args) -> {
                        // count the writes (the lookups before a place are reads and their failures are not crashes)
                        if (m.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && (sql.startsWith("UPDATE") || sql.startsWith("INSERT"))
                                && count.incrementAndGet() == failAt.get()) {
                            throw new SQLException("simulated crash");
                        }
                        try {
                            return m.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        };
        Exchange ex = exchange(flaky);
        int crashes = 0;
        for (int n = 1; ; n++) {
            count.set(0);
            failAt.set(n);
            Response r = ex.place(new Place("b" + n, 2, 12, Side.BUY, P, 1000));
            if (r.ok()) {
                break;
            }
            crashes++;
            assertEquals(nx0, totalNx(), "at statement " + n);
            assertEquals(meso0, totalMeso());
            assertEquals(50_000_000, one("SELECT meso FROM characters WHERE id=12"));
            assertEquals(ledger0, one("SELECT COUNT(*) FROM exchange_ledger"));
            assertTrue(n < 200);
        }
        // escrow in (debit + credit + ledger), 2 fills x 2 postings x 3, 3 order rows (update, then insert for the new
        // one), 2 fill rows
        assertTrue(crashes >= 20, "crashed at " + crashes + " statements");
        failAt.set(-1);
        assertEquals(nx0, totalNx());
        assertEquals(meso0, totalMeso());
        Exchange again = exchange(() -> DriverManager.getConnection(URL));
        assertEquals(ex.depth(Side.BUY, 5), again.depth(Side.BUY, 5));
    }

    /** A logged-in player's wallet as the game holds it: memory values, saved whole to the database by saveNow. */
    final class DbWallet implements LiveWallet {
        final long account, character;
        long nx, meso;
        int saves;

        DbWallet(long account, long character) throws SQLException {
            this.account = account;
            this.character = character;
            nx = one("SELECT COALESCE(nxCredit,0) FROM accounts WHERE id=" + account);
            meso = one("SELECT meso FROM characters WHERE id=" + character);
        }

        public long account() {
            return account;
        }

        public long character() {
            return character;
        }

        public <T> T locked(java.util.function.Supplier<T> body) {
            synchronized (this) {
                return body.get();
            }
        }

        public boolean usable() {
            return true;
        }

        public boolean saveNow() {
            try {
                exec("UPDATE accounts SET nxCredit = " + nx + " WHERE id = " + account);
                exec("UPDATE characters SET meso = " + meso + " WHERE id = " + character);
                saves++;
                return true;
            } catch (SQLException e) {
                return false;
            }
        }

        public long have(Ledger.Asset a) {
            return a == Ledger.Asset.NX ? nx : meso;
        }

        public boolean take(Ledger.Asset a, long amount) {
            if (have(a) < amount) {
                return false;
            }
            if (a == Ledger.Asset.NX) {
                nx -= amount;
            } else {
                meso -= amount;
            }
            return true;
        }

        public boolean give(Ledger.Asset a, long amount) {
            if (a == Ledger.Asset.NX) {
                nx += amount;
            } else {
                meso += amount;
            }
            return true;
        }
    }

    /**
     * Round 2: placing while logged in (loggedin = 2) writes the escrow and the wallet's delta in one transaction; a
     * channel change in flight (loggedin = 1) refuses and memory gets it back; a crash at any write statement of a
     * live place leaves the database as it was and memory whole; payouts land in memory and the database together;
     * a staff reversal reads the fill's postings and puts everything back.
     */
    @Test
    void placingWhileLoggedInAndPayingOutOnline() throws Exception {
        exec("UPDATE accounts SET loggedin = 2 WHERE id = 2");
        DbWallet w = new DbWallet(2, 12);
        w.meso += 4_000; // looted since the last save
        long nx0 = totalNx(), meso0 = totalMeso() + 4_000;
        ExchangeConfig cfg = ExchangeLiveTest.cfg(100, 0);
        Exchange ex = new Exchange(new JdbcExchangeStore(() -> DriverManager.getConnection(URL)), cfg,
                a -> a == 2, a -> a == 2 ? w : null, now::get);
        assertTrue(ex.recover().isEmpty());
        Response buy = ex.place(new Place("live-b", 2, 12, Side.BUY, P, 2000));
        assertTrue(buy.ok(), buy.toString());
        assertEquals(50_004_000 - 2_020_000, w.meso);
        assertEquals(w.meso, one("SELECT meso FROM characters WHERE id=12"), "database = memory");
        assertEquals(1, w.saves);
        assertEquals(meso0, totalMeso());
        // a channel change in flight: refused, memory whole
        exec("UPDATE accounts SET loggedin = 1 WHERE id = 2");
        long before = w.meso;
        assertEquals("TRY_AGAIN", ex.place(new Place("live-moved", 2, 12, Side.BUY, P, 100)).code());
        assertEquals(before, w.meso);
        exec("UPDATE accounts SET loggedin = 2 WHERE id = 2");
        // an offline seller fills it; the payout lands in memory and the database at once
        assertTrue(ex.place(new Place("off-s", 1, 11, Side.SELL, P, 2000)).ok());
        ex.collectAll();
        assertEquals(2000, w.nx);
        assertEquals(2000, one("SELECT nxCredit FROM accounts WHERE id=2"));
        assertEquals(nx0, totalNx());
        assertEquals(meso0, totalMeso());
        assertEquals(40_000, one("SELECT amount FROM exchange_escrow WHERE kind='FEES'"), "1% from each side");
        long fill = ex.lastTrades(1).get(0).id();
        // a staff reversal while halted: the payouts were collected, so it draws from the wallets (offline only)
        ex.halt("test");
        assertEquals("PLAYER_ONLINE", ex.reverse(fill, "it").code());
        exec("UPDATE accounts SET loggedin = 0 WHERE id = 2");
        Exchange offline = new Exchange(new JdbcExchangeStore(() -> DriverManager.getConnection(URL)), cfg,
                a -> false, now::get);
        assertTrue(offline.recover().isEmpty());
        offline.halt("test");
        Response rev = offline.reverse(fill, "it");
        assertTrue(rev.ok(), rev.toString());
        assertEquals("ALREADY_REVERSED", offline.reverse(fill, "it").code());
        offline.resume();
        offline.collectAll();
        assertEquals(0, one("SELECT nxCredit FROM accounts WHERE id=2"));
        assertEquals(100_000, one("SELECT nxCredit FROM accounts WHERE id=1"));
        assertEquals(50_004_000, one("SELECT meso FROM characters WHERE id=12"));
        assertEquals(50_000_000, one("SELECT meso FROM characters WHERE id=11"));
        assertEquals(nx0, totalNx());
        assertEquals(meso0, totalMeso());
        assertTrue(new Exchange(new JdbcExchangeStore(() -> DriverManager.getConnection(URL)), cfg, a -> false,
                now::get).recover().isEmpty(), "reconciles");
    }

    @Test
    void aCrashAtAnyStatementOfALivePlaceLeavesDatabaseAndMemoryWhole() throws Exception {
        Exchange setup = exchange(() -> DriverManager.getConnection(URL));
        assertTrue(setup.place(new Place("s1", 1, 11, Side.SELL, 900_000, 300)).ok());
        exec("UPDATE accounts SET loggedin = 2 WHERE id = 2");
        DbWallet w = new DbWallet(2, 12);
        long nx0 = totalNx(), meso0 = totalMeso(), ledger0 = one("SELECT COUNT(*) FROM exchange_ledger");
        AtomicInteger failAt = new AtomicInteger();
        AtomicInteger count = new AtomicInteger();
        JdbcExchangeStore.Connections flaky = () -> {
            Connection real = DriverManager.getConnection(URL);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, m, args) -> {
                        if (m.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && (sql.startsWith("UPDATE") || sql.startsWith("INSERT"))
                                && count.incrementAndGet() == failAt.get()) {
                            throw new SQLException("simulated crash");
                        }
                        try {
                            return m.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        };
        Exchange ex = new Exchange(new JdbcExchangeStore(flaky), ExchangeLiveTest.cfg(100, 0), a -> a == 2,
                a -> a == 2 ? w : null, now::get);
        failAt.set(-1);
        assertTrue(ex.recover().isEmpty());
        int crashes = 0;
        for (int n = 1; ; n++) {
            count.set(0);
            failAt.set(n);
            Response r = ex.place(new Place("lb" + n, 2, 12, Side.BUY, P, 1000));
            if (r.ok()) {
                break;
            }
            crashes++;
            assertEquals(50_000_000, w.meso, "memory given back at statement " + n);
            assertEquals(50_000_000, one("SELECT meso FROM characters WHERE id=12"));
            assertEquals(nx0, totalNx());
            assertEquals(meso0, totalMeso());
            assertEquals(ledger0, one("SELECT COUNT(*) FROM exchange_ledger"));
            assertTrue(n < 200);
        }
        assertTrue(crashes >= 10, "crashed at " + crashes + " statements");
        assertEquals(w.meso, one("SELECT meso FROM characters WHERE id=12"));
        assertEquals(meso0, totalMeso());
    }
}
