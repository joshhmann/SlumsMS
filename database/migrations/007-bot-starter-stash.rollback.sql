-- Stop the game first and take a backup. Removes what 007 added: the record of which bots got their starter supplies.
-- The supplies themselves stay in the bots' inventories (they are ordinary item rows). With 007 applied again later,
-- every bot would be given a second stash. Applying it twice changes nothing.
SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botstore' AND COLUMN_NAME = 'StockedAt');
SET @ddl = IF(@present, 'ALTER TABLE botstore DROP COLUMN StockedAt', 'DO 0');
PREPARE drop_stocked FROM @ddl;
EXECUTE drop_stocked;
DEALLOCATE PREPARE drop_stocked;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='007-bot-starter-stash';
