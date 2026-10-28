-- SlumsMS 1.6.1 (ticket 111, owner 2026-10-27): Slums Stamps, an account balance earned in the Daily Check-In, replace
-- the Billion Coin (4001253) and vote points. Adds accounts.stamps (an older jar ignores the column) and converts
-- what players hold: each vote point and each Billion Coin becomes 6 stamps (what one bought before: one Donor NX
-- cosmetic). Coins are counted and removed in characters' inventories (type 1), hired merchants (6), storage (2,
-- the account's) and Duey packages (9, the receiver's account). Coins with no player account (bots) are removed
-- with no stamps. Vote points are set to 0 (the column stays). Other item stores (MTS, wedding gifts) are untouched.
--
-- What was converted is kept for the rollback and for audit: stamps_039_coins (the removed inventoryitems rows,
-- as they were) and stamps_039_accounts (per account: vote points, coins, stamps credited).
-- The check-in itself needs nothing new: its streak is daily_checkin (migration 004).
--
-- Stop the game and take a backup first, as for every migration here. Applying it twice changes nothing (ledger id
-- '039-slums-stamps').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='039-slums-stamps');

-- MySQL has no ADD COLUMN IF NOT EXISTS: add it only when missing.
SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'stamps');
SET @ddl = IF(@apply AND @missing, 'ALTER TABLE accounts ADD COLUMN stamps INT NOT NULL DEFAULT 0', 'DO 0');
PREPARE add_stamps FROM @ddl;
EXECUTE add_stamps;
DEALLOCATE PREPARE add_stamps;

CREATE TABLE IF NOT EXISTS stamps_039_coins LIKE inventoryitems;
CREATE TABLE IF NOT EXISTS stamps_039_accounts (
  account_id INT NOT NULL PRIMARY KEY,
  votepoints INT NOT NULL DEFAULT 0,
  coins      INT NOT NULL DEFAULT 0,
  stamps     INT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

START TRANSACTION;
INSERT INTO stamps_039_coins
  SELECT * FROM inventoryitems WHERE @apply AND itemid = 4001253 AND type IN (1, 2, 6, 9);

INSERT INTO stamps_039_accounts (account_id, votepoints)
  SELECT id, votepoints FROM accounts WHERE @apply AND votepoints > 0;

INSERT INTO stamps_039_accounts (account_id, coins)
  SELECT acct, SUM(quantity) FROM (
    SELECT CASE
             WHEN i.type = 2 THEN i.accountid
             WHEN i.type = 9 THEN (SELECT c.accountid FROM dueypackages d JOIN characters c ON c.id = d.ReceiverId
                                   WHERE d.PackageId = i.characterid)
             ELSE (SELECT c.accountid FROM characters c WHERE c.id = i.characterid)
           END AS acct,
           i.quantity
    FROM stamps_039_coins i) owned
  WHERE @apply AND acct IS NOT NULL AND quantity > 0
  GROUP BY acct
  ON DUPLICATE KEY UPDATE coins = VALUES(coins);

UPDATE accounts a JOIN stamps_039_accounts s ON s.account_id = a.id
  SET s.stamps = (s.votepoints + s.coins) * 6,
      a.stamps = a.stamps + (s.votepoints + s.coins) * 6,
      a.votepoints = 0
  WHERE @apply;

DELETE FROM inventoryitems WHERE @apply AND itemid = 4001253 AND type IN (1, 2, 6, 9);

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) SELECT '039-slums-stamps' FROM DUAL WHERE @apply;
COMMIT;
