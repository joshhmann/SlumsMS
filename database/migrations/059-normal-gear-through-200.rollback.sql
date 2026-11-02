-- Roll back only pairs introduced by 059; older pairs are preserved. Reload drops after rollback.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS normal_gear_059_rows (
  dropperid int NOT NULL,
  itemid    int NOT NULL,
  PRIMARY KEY (dropperid, itemid)
) ENGINE=InnoDB;

DELETE d FROM drop_data d JOIN normal_gear_059_rows r ON r.dropperid = d.dropperid AND r.itemid = d.itemid;
DELETE FROM normal_gear_059_rows;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '059-normal-gear-through-200';
