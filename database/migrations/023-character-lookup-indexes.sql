-- SlumsMS 1.5.2 (ticket 80, after nutnnut/Cosmic migrations 028, 029 and 032): secondary indexes for the per-character
-- and per-account lookups that loading, saving and deleting a character run. The MapleRoot schema indexes only the
-- primary key of these tables, so each `WHERE characterid = ?` (and the account-scoped cash-item DELETE on
-- inventoryitems) scanned the whole table and, inside a save transaction, lock-tested every row in it. nutnnut measured
-- InnoDB deadlock storms between concurrent saves from the missing inventoryitems.accountid index (60 before, 0 after).
-- drop_data gets a covering (itemid, chance, dropperid) index for the "who drops item X" lookups.
--
-- Checked on the TEST database (mapleroot_persist) on 2026-10-25 with SHOW INDEX: none of these exist there.
-- Adds secondary indexes only; no row or column changes, so an older jar runs fine on a migrated database. Stop the
-- game and take a backup first, as for every migration here. Each index is added only when its table has no index
-- that already leads with the same column, so applying it twice changes nothing (ledger id
-- '023-character-lookup-indexes').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- MySQL has no ADD INDEX IF NOT EXISTS: each block adds its index only when no index on the table starts with the column.

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'queststatus' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'characterid');
SET @ddl = IF(@missing, 'ALTER TABLE queststatus ADD INDEX idx_characterid (characterid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'questprogress' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'characterid');
SET @ddl = IF(@missing, 'ALTER TABLE questprogress ADD INDEX idx_characterid (characterid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'keymap' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'characterid');
SET @ddl = IF(@missing, 'ALTER TABLE keymap ADD INDEX idx_characterid (characterid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'savedlocations' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'characterid');
SET @ddl = IF(@missing, 'ALTER TABLE savedlocations ADD INDEX idx_characterid (characterid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trocklocations' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'characterid');
SET @ddl = IF(@missing, 'ALTER TABLE trocklocations ADD INDEX idx_characterid (characterid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'buddies' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'characterid');
SET @ddl = IF(@missing, 'ALTER TABLE buddies ADD INDEX idx_characterid (characterid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skillmacros' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'characterid');
SET @ddl = IF(@missing, 'ALTER TABLE skillmacros ADD INDEX idx_characterid (characterid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'area_info' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'charid');
SET @ddl = IF(@missing, 'ALTER TABLE area_info ADD INDEX idx_charid (charid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventoryitems' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'accountid');
SET @ddl = IF(@missing, 'ALTER TABLE inventoryitems ADD INDEX idx_accountid (accountid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'drop_data' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'itemid');
SET @ddl = IF(@missing, 'ALTER TABLE drop_data ADD INDEX idx_itemid_chance_dropperid (itemid, chance, dropperid)', 'DO 0');
PREPARE add_index FROM @ddl;
EXECUTE add_index;
DEALLOCATE PREPARE add_index;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('023-character-lookup-indexes');
