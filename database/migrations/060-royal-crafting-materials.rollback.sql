-- Roll back only pairs introduced by060; preserve older drops. Reload drops afterward.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS royal_materials_060_rows (
  dropperid int NOT NULL,
  itemid    int NOT NULL,
  PRIMARY KEY (dropperid, itemid)
) ENGINE=InnoDB;

DELETE d FROM drop_data d JOIN royal_materials_060_rows r ON r.dropperid = d.dropperid AND r.itemid = d.itemid;
DELETE FROM royal_materials_060_rows;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '060-royal-crafting-materials';
