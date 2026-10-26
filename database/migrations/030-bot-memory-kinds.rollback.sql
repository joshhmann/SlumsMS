-- Stop the game first and take a backup. Removes what 030 added: botmemory's Kind, Weight, Tag and FollowedUpAt
-- columns. The notes themselves stay (as plain notes, as before 1.6.0); the last-topic and inside-joke slots become
-- plain notes too. Applying it twice changes nothing.
SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'FollowedUpAt');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN FollowedUpAt', 'DO 0');
PREPARE drop_followed FROM @ddl;
EXECUTE drop_followed;
DEALLOCATE PREPARE drop_followed;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Tag');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN Tag', 'DO 0');
PREPARE drop_tag FROM @ddl;
EXECUTE drop_tag;
DEALLOCATE PREPARE drop_tag;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Weight');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN Weight', 'DO 0');
PREPARE drop_weight FROM @ddl;
EXECUTE drop_weight;
DEALLOCATE PREPARE drop_weight;

SET @present = EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Kind');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP COLUMN Kind', 'DO 0');
PREPARE drop_kind FROM @ddl;
EXECUTE drop_kind;
DEALLOCATE PREPARE drop_kind;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='030-bot-memory-kinds';
