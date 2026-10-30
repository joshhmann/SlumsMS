-- Removes what 056 added: site_mob_kills.estimated (the site's counted/estimated split; the totals stay).
-- Applying it twice changes nothing.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'site_mob_kills' AND COLUMN_NAME = 'estimated') > 0,
              'ALTER TABLE site_mob_kills DROP COLUMN estimated', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

DELETE FROM mapleroot_integration_migrations WHERE migration_id = '056-mob-kills-split';
