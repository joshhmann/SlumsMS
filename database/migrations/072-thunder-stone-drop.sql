SET @thunder_stone_chance = 15000;   -- 1.5%: chance is per 1,000,000 (15000 / 1000000), the one number to change

-- 1.6.8: card cygnus-fight-unreachable.
-- The Dream Key 4032923 that opens the Cygnus Garden gate is made by the Thunder Stone 2430200
-- (scripts/item/dreamkey.js) from 1 each of Dawn 4000660, Blaze 4000661, Wind 4000662 and Night 4000663, and nothing
-- dropped the Thunder Stone, so no player could make a key and the garden could not be entered.
-- Reference: swordie-plus-v271 sql/init/03_drops.sql lists Thunder 8610004 -> 2430200 at 100 per million; the owner
-- wants 1.5% here, so the one row below carries @thunder_stone_chance. The stored chance is the base rate; the
-- server's world drop rate (and coupons) multiply on top of it, as for every other drop row.
-- 8610004 is the Thunder that stands in Knight District 3 (271030205), in the same Knight Stronghold block as the
-- gate (Hall of Honor 271030600).
-- One row only. Applying it twice adds nothing (ledger id '072-thunder-stone-drop'); a row for the pair that is
-- already there is left exactly as it is and is not recorded, so the rollback never removes it. The inserted row id
-- is recorded in mapleroot_integration_migration_rows so the rollback deletes that one row and nothing else.
-- drop_data is MyISAM, so its insert is not transactional; the ledger insert is what makes a second run a no-op.
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '072-thunder-stone-drop');

CREATE TEMPORARY TABLE migration_drop_data_before (id bigint PRIMARY KEY);

START TRANSACTION;
INSERT INTO migration_drop_data_before SELECT id FROM drop_data WHERE dropperid = 8610004 AND itemid = 2430200;
INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
SELECT 8610004, 2430200, 1, 1, 0, @thunder_stone_chance FROM DUAL
 WHERE @apply AND NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = 8610004 AND d.itemid = 2430200);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '072-thunder-stone-drop', 'drop_data', d.id FROM drop_data d
 WHERE @apply AND d.dropperid = 8610004 AND d.itemid = 2430200
   AND NOT EXISTS (SELECT 1 FROM migration_drop_data_before b WHERE b.id = d.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('072-thunder-stone-drop');
COMMIT;
DROP TEMPORARY TABLE migration_drop_data_before;
