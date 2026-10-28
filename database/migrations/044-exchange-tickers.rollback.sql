-- Stop the game first and take a backup. Removes what 044 added: the ticker columns and their keys.
-- DANGER once an item ticker has traded: item orders and fills would read as NX after this, and rows in exchange_escrow
-- with an item asset (LEAF, CHAOS, WS, ELIXIR) are players' items held by the exchange. Cancel every item order and let
-- every item payout reach its character (no exchange_escrow row with an item asset above zero) before rolling back.
-- The asset columns stay VARCHAR(8): narrowing them back is not needed by any jar and could fail on item rows.
-- Applying it twice changes nothing.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_orders' AND INDEX_NAME = 'ticker') > 0,
              'ALTER TABLE exchange_orders DROP KEY ticker', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_orders' AND COLUMN_NAME = 'ticker') > 0,
              'ALTER TABLE exchange_orders DROP COLUMN ticker', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_fills' AND INDEX_NAME = 'ticker_at') > 0,
              'ALTER TABLE exchange_fills DROP KEY ticker_at', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'exchange_fills' AND COLUMN_NAME = 'ticker') > 0,
              'ALTER TABLE exchange_fills DROP COLUMN ticker', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='044-exchange-tickers';
