-- Stop the game first and take a backup. Removes only the drops and plife rows 065 recorded; reload drops afterward.
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
CREATE TABLE IF NOT EXISTS lkc_quest_drops_065_rows (
  dropperid int NOT NULL,
  itemid    int NOT NULL,
  PRIMARY KEY (dropperid, itemid)
) ENGINE=InnoDB;

START TRANSACTION;
DELETE d FROM drop_data d JOIN lkc_quest_drops_065_rows r ON r.dropperid = d.dropperid AND r.itemid = d.itemid;
DELETE FROM lkc_quest_drops_065_rows;
DELETE FROM plife WHERE id IN (
  SELECT row_id FROM mapleroot_integration_migration_rows
  WHERE migration_id = '065-lkc-quest-drops' AND table_name = 'plife'
);
DELETE FROM mapleroot_integration_migration_rows WHERE migration_id = '065-lkc-quest-drops';
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '065-lkc-quest-drops';
COMMIT;
