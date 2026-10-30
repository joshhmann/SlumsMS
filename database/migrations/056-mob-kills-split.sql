-- SlumsMS 1.6.2 (b015, the site's "Most hunted" mixed real and estimated kills): site_mob_kills (047) gains
-- `estimated`, the part of each (hour, mob) count that was bots' modelled offscreen kills (OffscreenCombat); kills
-- stays the total, so counted = kills - estimated. world.json's top_mobs rows add "counted" and "estimated".
-- Additive only: an older jar never writes the column (its default 0 reads as "all counted") and ignores it. Without
-- this migration the new jar still keeps the totals; only the split is not saved across a restart.
-- Runs after 047 (without site_mob_kills it changes nothing). Applying it twice changes nothing (ledger id
-- '056-mob-kills-split').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'site_mob_kills') = 1
              AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'site_mob_kills' AND COLUMN_NAME = 'estimated') = 0,
              'ALTER TABLE site_mob_kills ADD COLUMN estimated INT NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('056-mob-kills-split');
