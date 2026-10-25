-- Stop the game first and take a backup. Removes what 023 added: the secondary indexes it named (an index that was
-- already there under another name, and so made 023 skip that table, is left alone). No data is touched. Applying it
-- twice changes nothing.

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'queststatus' AND INDEX_NAME = 'idx_characterid');
SET @ddl = IF(@present, 'ALTER TABLE queststatus DROP INDEX idx_characterid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'questprogress' AND INDEX_NAME = 'idx_characterid');
SET @ddl = IF(@present, 'ALTER TABLE questprogress DROP INDEX idx_characterid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'keymap' AND INDEX_NAME = 'idx_characterid');
SET @ddl = IF(@present, 'ALTER TABLE keymap DROP INDEX idx_characterid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'savedlocations' AND INDEX_NAME = 'idx_characterid');
SET @ddl = IF(@present, 'ALTER TABLE savedlocations DROP INDEX idx_characterid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trocklocations' AND INDEX_NAME = 'idx_characterid');
SET @ddl = IF(@present, 'ALTER TABLE trocklocations DROP INDEX idx_characterid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'buddies' AND INDEX_NAME = 'idx_characterid');
SET @ddl = IF(@present, 'ALTER TABLE buddies DROP INDEX idx_characterid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skillmacros' AND INDEX_NAME = 'idx_characterid');
SET @ddl = IF(@present, 'ALTER TABLE skillmacros DROP INDEX idx_characterid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'area_info' AND INDEX_NAME = 'idx_charid');
SET @ddl = IF(@present, 'ALTER TABLE area_info DROP INDEX idx_charid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventoryitems' AND INDEX_NAME = 'idx_accountid');
SET @ddl = IF(@present, 'ALTER TABLE inventoryitems DROP INDEX idx_accountid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'drop_data' AND INDEX_NAME = 'idx_itemid_chance_dropperid');
SET @ddl = IF(@present, 'ALTER TABLE drop_data DROP INDEX idx_itemid_chance_dropperid', 'DO 0');
PREPARE drop_index FROM @ddl;
EXECUTE drop_index;
DEALLOCATE PREPARE drop_index;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='023-character-lookup-indexes';
