-- Stop the game first and take a backup. Undoes 039: the removed Billion Coins go back where they were
-- (stamps_039_coins, same row ids), vote points come back (stamps_039_accounts), and accounts.stamps is dropped.
-- Stamps earned or spent since are lost, and what was bought with them stays. A coin whose old slot has since been
-- filled lands in the same slot number (the client shows one of the two until the player moves it). Applying it twice
-- changes nothing.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS stamps_039_coins LIKE inventoryitems;
CREATE TABLE IF NOT EXISTS stamps_039_accounts (
  account_id INT NOT NULL PRIMARY KEY,
  votepoints INT NOT NULL DEFAULT 0,
  coins      INT NOT NULL DEFAULT 0,
  stamps     INT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

START TRANSACTION;
INSERT IGNORE INTO inventoryitems SELECT * FROM stamps_039_coins;
UPDATE accounts a JOIN stamps_039_accounts s ON s.account_id = a.id SET a.votepoints = a.votepoints + s.votepoints;
DELETE FROM stamps_039_accounts;
DELETE FROM stamps_039_coins;
COMMIT;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'stamps');
SET @ddl = IF(@present, 'ALTER TABLE accounts DROP COLUMN stamps', 'DO 0');
PREPARE drop_stamps FROM @ddl;
EXECUTE drop_stamps;
DEALLOCATE PREPARE drop_stamps;

DROP TABLE IF EXISTS stamps_039_coins;
DROP TABLE IF EXISTS stamps_039_accounts;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='039-slums-stamps';
