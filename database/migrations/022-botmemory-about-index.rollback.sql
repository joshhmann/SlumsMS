-- Stop the game first and take a backup. Removes what 022 added: the botmemory index on AboutCharacterId. No data is
-- touched. Applying it twice changes nothing.
SET @present = EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND INDEX_NAME = 'about');
SET @ddl = IF(@present, 'ALTER TABLE botmemory DROP INDEX about', 'DO 0');
PREPARE drop_about FROM @ddl;
EXECUTE drop_about;
DEALLOCATE PREPARE drop_about;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='022-botmemory-about-index';
