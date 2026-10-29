-- Stop the game first and take a backup. Undoes 052: the wedding-gift coins, Adventurer's Bag rows and MTS listings (and their carts) go back
-- as they were, and the stamps credited for them (stamps_052_accounts) are taken off the accounts, never below zero.
-- Stamps already spent stay spent. Run this BEFORE the 039 rollback (that one drops accounts.stamps). Applying it
-- twice changes nothing.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS stamps_052_coins LIKE inventoryitems;
CREATE TABLE IF NOT EXISTS stamps_052_mts LIKE mts_items;
CREATE TABLE IF NOT EXISTS stamps_052_bag LIKE infinitystorage;
CREATE TABLE IF NOT EXISTS stamps_052_carts LIKE mts_cart;
CREATE TABLE IF NOT EXISTS stamps_052_accounts (
  account_id INT NOT NULL PRIMARY KEY,
  coins      INT NOT NULL DEFAULT 0,
  stamps     INT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

START TRANSACTION;
INSERT IGNORE INTO inventoryitems SELECT * FROM stamps_052_coins;
INSERT IGNORE INTO infinitystorage SELECT * FROM stamps_052_bag;
INSERT IGNORE INTO mts_items SELECT * FROM stamps_052_mts;
INSERT IGNORE INTO mts_cart SELECT * FROM stamps_052_carts;
UPDATE accounts a JOIN stamps_052_accounts s ON s.account_id = a.id SET a.stamps = GREATEST(a.stamps - s.stamps, 0);
DELETE FROM stamps_052_accounts;
DELETE FROM stamps_052_coins;
DELETE FROM stamps_052_mts;
DELETE FROM stamps_052_bag;
DELETE FROM stamps_052_carts;
COMMIT;

DROP TABLE IF EXISTS stamps_052_coins;
DROP TABLE IF EXISTS stamps_052_mts;
DROP TABLE IF EXISTS stamps_052_bag;
DROP TABLE IF EXISTS stamps_052_carts;
DROP TABLE IF EXISTS stamps_052_accounts;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='052-stamps-gifts-mts';
