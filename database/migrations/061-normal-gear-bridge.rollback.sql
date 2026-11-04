-- Guard by original row ID, pair, quantity, ordinary status and exact written chance.
-- Later operator edits are preserved. Apply/rollback only with game/drop writers stopped.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY, applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS normal_bridge_061_rows (
  dropperid int NOT NULL, itemid int NOT NULL, drop_id bigint NULL,
  operation char(1) NOT NULL, old_chance int NULL, target_chance int NOT NULL,
  minimum_quantity int NOT NULL, maximum_quantity int NOT NULL,
  PRIMARY KEY (dropperid, itemid)
) ENGINE=InnoDB;

DELETE d FROM drop_data d JOIN normal_bridge_061_rows r ON d.id=r.drop_id
WHERE r.operation='I' AND d.dropperid=r.dropperid AND d.itemid=r.itemid AND d.questid=0
  AND d.chance=r.target_chance AND d.minimum_quantity=r.minimum_quantity AND d.maximum_quantity=r.maximum_quantity;
UPDATE drop_data d JOIN normal_bridge_061_rows r ON d.id=r.drop_id
SET d.chance=r.old_chance
WHERE r.operation='U' AND d.dropperid=r.dropperid AND d.itemid=r.itemid AND d.questid=0
  AND d.chance=r.target_chance AND d.minimum_quantity=r.minimum_quantity AND d.maximum_quantity=r.maximum_quantity;
DELETE FROM normal_bridge_061_rows;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='061-normal-gear-bridge';
