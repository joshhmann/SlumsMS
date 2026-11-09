-- Stop the game first and take a backup. Removes only the plife rows 070 recorded (Plunk in 211070100 and 271040100).
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS mapleroot_integration_migration_rows (
  migration_id varchar(100) NOT NULL,
  table_name varchar(64) NOT NULL,
  row_id bigint NOT NULL,
  PRIMARY KEY (migration_id, table_name, row_id)
) ENGINE=InnoDB;

START TRANSACTION;
DELETE FROM plife WHERE id IN (
  SELECT row_id FROM mapleroot_integration_migration_rows
  WHERE migration_id = '070-plunk-boss-reward-rooms' AND table_name = 'plife'
);
DELETE FROM mapleroot_integration_migration_rows WHERE migration_id = '070-plunk-boss-reward-rooms';
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '070-plunk-boss-reward-rooms';
COMMIT;
