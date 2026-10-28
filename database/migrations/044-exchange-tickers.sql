-- SlumsMS 1.6.1 (ticket 113, owner 2026-10-28): exchange tickers. Besides NX, the exchange runs a player order book for
-- four items, priced in mesos: LEAF (Maple Leaf 4001126), CHAOS (Chaos Scroll 60% 2049100), WS (White Scroll 2340000)
-- and ELIXIR (Power Elixir 2000005). Runs after 038-nx-exchange (without the exchange tables it changes nothing). Additive only:
--   exchange_orders.ticker / exchange_fills.ticker  VARCHAR(8) NOT NULL DEFAULT 'NX': every existing row is NX, as it was.
--     For an item ticker nx_total/nx_left/nx count items and price is mesos per item.
--   exchange_orders KEY ticker (ticker, status, seq), exchange_fills KEY ticker_at (ticker, at): the per-ticker book
--     and the hourly price history.
--   exchange_escrow.asset / exchange_ledger.asset widen VARCHAR(4) -> VARCHAR(8) (asset names CHAOS and ELIXIR).
-- An older jar runs fine on a migrated database (it never writes the new column, whose default is NX, and never sees
-- an item asset unless item orders were placed). Stop the game and take a backup first, as for every migration here.
-- Applying it twice changes nothing (ledger id '044-exchange-tickers').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_orders') = 1
              AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_orders' AND COLUMN_NAME = 'ticker') = 0,
              'ALTER TABLE exchange_orders ADD COLUMN ticker VARCHAR(8) NOT NULL DEFAULT ''NX''', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_orders' AND COLUMN_NAME = 'ticker') = 1
              AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_orders' AND INDEX_NAME = 'ticker') = 0,
              'ALTER TABLE exchange_orders ADD KEY ticker (ticker, status, seq)', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_fills') = 1
              AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_fills' AND COLUMN_NAME = 'ticker') = 0,
              'ALTER TABLE exchange_fills ADD COLUMN ticker VARCHAR(8) NOT NULL DEFAULT ''NX''', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_fills' AND COLUMN_NAME = 'ticker') = 1
              AND (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_fills' AND INDEX_NAME = 'ticker_at') = 0,
              'ALTER TABLE exchange_fills ADD KEY ticker_at (ticker, at)', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @ddl = IF((SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_escrow' AND COLUMN_NAME = 'asset') < 8,
              'ALTER TABLE exchange_escrow MODIFY COLUMN asset VARCHAR(8) NOT NULL', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @ddl = IF((SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_ledger' AND COLUMN_NAME = 'asset') < 8,
              'ALTER TABLE exchange_ledger MODIFY COLUMN asset VARCHAR(8) NOT NULL', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('044-exchange-tickers');
