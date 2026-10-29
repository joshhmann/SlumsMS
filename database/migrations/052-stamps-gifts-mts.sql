-- SlumsMS 1.6.1 (ticket 111 follow-up): 039 turned Billion Coins (4001253) into Slums Stamps but only looked in
-- characters' inventories, hired merchants, storage and Duey. Three places were missed: wedding gifts (inventoryitems
-- type 8, MARRIAGE_GIFTS, kept per character), the Adventurer's Bag (@bp, infinitystorage: charid, itemid, amount) and
-- Maple Trading System listings (mts_items, seller = the character).
-- Same rule as 039: every coin becomes 6 stamps for the owner's account and the coin is removed. A coin owned by a
-- character with no characters row (a bot) is removed with no stamps. A removed MTS listing also leaves players'
-- mts_cart entries, which are dropped with it.
--
-- Kept for the rollback and for audit: stamps_052_coins (the removed wedding-gift rows), stamps_052_mts (the removed
-- listings, whole rows), stamps_052_bag (the removed Adventurer's Bag rows), stamps_052_carts, stamps_052_accounts (per account: coins, stamps credited).
-- Needs 039 first (accounts.stamps): if that column is missing the credit step fails and nothing is changed.
-- Stop the game and take a backup first. Applying it twice changes nothing (ledger id '052-stamps-gifts-mts').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='052-stamps-gifts-mts');

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
INSERT INTO stamps_052_coins
  SELECT * FROM inventoryitems WHERE @apply AND itemid = 4001253 AND type = 8;
INSERT INTO stamps_052_mts
  SELECT * FROM mts_items WHERE @apply AND itemid = 4001253;
INSERT INTO stamps_052_bag
  SELECT * FROM infinitystorage WHERE @apply AND itemid = 4001253;
INSERT INTO stamps_052_carts
  SELECT * FROM mts_cart WHERE @apply AND itemid IN (SELECT id FROM stamps_052_mts);

INSERT INTO stamps_052_accounts (account_id, coins)
  SELECT acct, SUM(quantity) FROM (
    SELECT (SELECT c.accountid FROM characters c WHERE c.id = i.characterid) AS acct, i.quantity
    FROM stamps_052_coins i
    UNION ALL
    SELECT (SELECT c.accountid FROM characters c WHERE c.id = m.seller) AS acct, m.quantity
    FROM stamps_052_mts m
    UNION ALL
    SELECT (SELECT c.accountid FROM characters c WHERE c.id = b.charid) AS acct, b.amount
    FROM stamps_052_bag b) owned
  WHERE @apply AND acct IS NOT NULL AND quantity > 0
  GROUP BY acct
  ON DUPLICATE KEY UPDATE coins = VALUES(coins);

UPDATE accounts a JOIN stamps_052_accounts s ON s.account_id = a.id
  SET s.stamps = s.coins * 6,
      a.stamps = a.stamps + s.coins * 6
  WHERE @apply;

DELETE FROM mts_cart WHERE @apply AND itemid IN (SELECT id FROM stamps_052_mts);
DELETE FROM mts_items WHERE @apply AND itemid = 4001253;
DELETE FROM infinitystorage WHERE @apply AND itemid = 4001253;
DELETE FROM inventoryitems WHERE @apply AND itemid = 4001253 AND type = 8;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) SELECT '052-stamps-gifts-mts' FROM DUAL WHERE @apply;
COMMIT;
