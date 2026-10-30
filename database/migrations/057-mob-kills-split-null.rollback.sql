-- Undoes 057: unknown (NULL) estimated parts become 0 again and the column is NOT NULL DEFAULT 0 (as 056 made it).
-- Applying it twice changes nothing.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'site_mob_kills' AND COLUMN_NAME = 'estimated' AND IS_NULLABLE = 'YES') = 1,
              'UPDATE site_mob_kills SET estimated = 0 WHERE estimated IS NULL', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'site_mob_kills' AND COLUMN_NAME = 'estimated' AND IS_NULLABLE = 'YES') = 1,
              'ALTER TABLE site_mob_kills MODIFY COLUMN estimated INT NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

DELETE FROM mapleroot_integration_migrations WHERE migration_id = '057-mob-kills-split-null';
