-- SlumsMS 1.6.2 (b015 follow-up): site_mob_kills.estimated (056) was NOT NULL DEFAULT 0, so a row written before the
-- split existed (0) looked the same as an hour with no offscreen kills (0), and the site could not tell where the
-- split starts. It becomes nullable: NULL = unknown (no split data), 0 or more = a split-aware build wrote it.
-- Existing 0 rows are set to NULL (they cannot be told apart; at worst the site's "split since" starts a little later).
-- Applying it twice changes nothing (ledger id '057-mob-kills-split-null').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'site_mob_kills' AND COLUMN_NAME = 'estimated' AND IS_NULLABLE = 'NO') = 1,
              'ALTER TABLE site_mob_kills MODIFY COLUMN estimated INT NULL DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

-- only on the first run (ledger row absent): later runs must not erase real zeros
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'site_mob_kills' AND COLUMN_NAME = 'estimated') = 1
              AND (SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id = '057-mob-kills-split-null') = 0,
              'UPDATE site_mob_kills SET estimated = NULL WHERE estimated = 0', 'DO 0');
PREPARE s FROM @ddl;
EXECUTE s;
DEALLOCATE PREPARE s;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('057-mob-kills-split-null');
