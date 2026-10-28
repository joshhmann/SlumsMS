package soloMapling.Economy.exchange;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 113: migration 038 matches the store's SQL, has a rollback, and the ledger is append-only in code. */
class ExchangeMigrationTest {

    static final Path DIR = Path.of("database/migrations");

    @Test
    void migrationHasItsLedgerIdAndRollback() throws Exception {
        String sql = Files.readString(DIR.resolve("038-nx-exchange.sql"));
        String rollback = Files.readString(DIR.resolve("038-nx-exchange.rollback.sql"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('038-nx-exchange')"));
        assertTrue(rollback.contains("migration_id='038-nx-exchange'"));
        for (String t : List.of("exchange_orders", "exchange_fills", "exchange_escrow", "exchange_ledger")) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + t), t);
            assertTrue(rollback.contains("DROP TABLE IF EXISTS " + t), t);
        }
    }

    @Test
    void everyColumnTheStoreWritesExists() throws Exception {
        String sql = Files.readString(DIR.resolve("038-nx-exchange.sql"));
        String tickers = Files.readString(DIR.resolve("044-exchange-tickers.sql"));
        for (String insert : List.of(JdbcExchangeStore.ORDER_INSERT, JdbcExchangeStore.FILL_INSERT,
                JdbcExchangeStore.LEDGER, JdbcExchangeStore.CREDIT_BUCKET)) {
            Matcher m = Pattern.compile("INSERT INTO (\\w+) \\(([^)]*)\\)").matcher(insert);
            assertTrue(m.find(), insert);
            String table = sql.substring(sql.indexOf("CREATE TABLE IF NOT EXISTS " + m.group(1)));
            table = table.substring(0, table.indexOf(") ENGINE"));
            for (String col : m.group(2).split(",\\s*")) {
                boolean in038 = Pattern.compile("\\n\\s+" + col.trim() + "\\s").matcher(table).find();
                boolean in044 = tickers.contains("ALTER TABLE " + m.group(1) + " ADD COLUMN " + col.trim() + " ");
                assertTrue(in038 || in044, col + " is a column of " + m.group(1));
            }
        }
    }

    @Test
    void tickerMigrationIsAdditiveWithItsLedgerIdAndRollback() throws Exception {
        String sql = Files.readString(DIR.resolve("044-exchange-tickers.sql"));
        String rollback = Files.readString(DIR.resolve("044-exchange-tickers.rollback.sql"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('044-exchange-tickers')"));
        assertTrue(rollback.contains("migration_id='044-exchange-tickers'"));
        for (String t : List.of("exchange_orders", "exchange_fills")) {
            assertTrue(sql.contains("ALTER TABLE " + t + " ADD COLUMN ticker VARCHAR(8) NOT NULL DEFAULT ''NX''"), t);
            assertTrue(rollback.contains("ALTER TABLE " + t + " DROP COLUMN ticker"), t);
        }
        // the longest asset name must fit the widened column
        for (Ledger.Asset a : Ledger.Asset.values()) {
            assertTrue(a.name().length() <= 8, a.name());
        }
        assertFalse(Pattern.compile("(?i)DROP\\s+TABLE|DELETE\\s+FROM\\s+exchange_").matcher(sql).find());
        assertFalse(Pattern.compile("(?i)DROP\\s+TABLE").matcher(rollback).find());
    }

    @Test
    void theLedgerIsOnlyEverInserted() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/Economy/exchange/JdbcExchangeStore.java"));
        assertFalse(Pattern.compile("(?i)(UPDATE|DELETE FROM|REPLACE INTO|TRUNCATE)\\s+(TABLE\\s+)?exchange_ledger").matcher(src).find());
        assertFalse(Pattern.compile("(?i)(UPDATE|DELETE FROM)\\s+exchange_fills").matcher(src).find());
    }
}
