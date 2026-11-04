-- SlumsMS 1.6.4 (owner Round 7): make the Lion King's Castle questline completable.
-- 1. Quest-only drops (drop_data.questid = the quest, so a mob drops it only while that quest is started):
--      4032831 (q3164 Jenn's Brother)        Crocky the Gatekeeper 8210000, Reindeer 8210001      40%
--      4032835 (q3170 Knight's Magic Scroll)  Castle Golem 8210005                                 40%
--      4032836 (q3174 Key to the Rose Garden) Grey Vulture 8210004                                 40%
--      4032838 x10 (q3177 A Frozen Heart)     Reindeer 8210001, Blood Reindeer 8210002, Bearwolf 8210003   20%
--    chance is out of 1,000,000 (400000 = 40%); the server applies its drop rate on top.
-- 2. NPC placement (plife, worlds 0 and 1): Von Leon recruiter 2161000 in the Audience Room Corridor 211070000
--    (1500, -139, fh 3) and the wake NPC 2161008 in the Audience Room 211070100 (1500, -181, fh 8).
-- 3. King Castle Golem 8211004 in the Rose Garden 211080700 (1500, -200, fh 19), field-boss respawn 3600 s.
-- Murt 2160000 is already in Map 211060400's own life (100, 165); his Npc.wz entry is a host step
-- (tools/data-align/add_murt.py), so nothing is inserted for him here.
-- Existing rows are never changed; applying twice adds nothing. Rollback removes only the rows recorded here.
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '065-lkc-quest-drops');

CREATE TEMPORARY TABLE lkc_quest_drops_065_new (
  dropperid int NOT NULL, itemid int NOT NULL, minimum_quantity int NOT NULL, maximum_quantity int NOT NULL,
  questid int NOT NULL, chance int NOT NULL, PRIMARY KEY (dropperid, itemid));
INSERT INTO lkc_quest_drops_065_new (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance) VALUES
(8210000, 4032831, 1, 1, 3164, 400000),
(8210001, 4032831, 1, 1, 3164, 400000),
(8210005, 4032835, 1, 1, 3170, 400000),
(8210004, 4032836, 1, 1, 3174, 400000),
(8210001, 4032838, 1, 1, 3177, 200000),
(8210002, 4032838, 1, 1, 3177, 200000),
(8210003, 4032838, 1, 1, 3177, 200000);

INSERT IGNORE INTO lkc_quest_drops_065_rows (dropperid, itemid)
SELECT n.dropperid, n.itemid FROM lkc_quest_drops_065_new n
 WHERE @apply AND NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = n.dropperid AND d.itemid = n.itemid);
INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
SELECT n.dropperid, n.itemid, n.minimum_quantity, n.maximum_quantity, n.questid, n.chance
  FROM lkc_quest_drops_065_new n JOIN lkc_quest_drops_065_rows r ON r.dropperid = n.dropperid AND r.itemid = n.itemid
 WHERE @apply AND NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = n.dropperid AND d.itemid = n.itemid);
DROP TEMPORARY TABLE lkc_quest_drops_065_new;

-- The game must be stopped: snapshot existing ids so only this insert is owned.
CREATE TEMPORARY TABLE migration_plife_before (id bigint PRIMARY KEY);
START TRANSACTION;
INSERT INTO migration_plife_before SELECT id FROM plife;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, m.type, m.y, 0, m.fh, m.x - 50, m.x + 50, m.x, m.y, 0, m.mobtime, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- map, life, type, x, ground y, foothold, mobtime
  SELECT 211070000 AS map, 2161000 AS life, 'n' AS type, 1500 AS x, -139 AS y, 3 AS fh, -1 AS mobtime   -- recruiter, Corridor
  UNION ALL
  SELECT 211070100, 2161008, 'n', 1500, -181, 8, -1                                                     -- wake NPC, Audience Room
  UNION ALL
  SELECT 211080700, 8211004, 'm', 1500, -200, 19, 3600                                                  -- King Castle Golem, Rose Garden
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '065-lkc-quest-drops', 'plife', p.id FROM plife p
WHERE @apply AND NOT EXISTS (SELECT 1 FROM migration_plife_before b WHERE b.id = p.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('065-lkc-quest-drops');
COMMIT;
DROP TEMPORARY TABLE migration_plife_before;
