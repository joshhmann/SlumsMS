-- SlumsMS 1.5.2 (owner decision, quest sweep #6): Dalair (NPC 9000066, "I serve the God of Honor"), the NPC who
-- hands out the medal (title) quests, stands on no map in the server data, so every medal quest that is turned in
-- at him (about 60 in Quest.wz) can never be completed. Stock Cosmic has the same gap. This places him in Henesys
-- (100000000), the way 013 and 021 placed their NPCs. He has no NPC script (as in Cosmic): the client offers his
-- quests from its own Quest.wz.
--
-- Spot: the lower main street (y 454, foothold 214 runs flat under x 4550) between Bruce (x 4139) and Roca (x 4951),
-- 586 px from the nearest portal and 269 px from the nearest spawn point (DalairMigrationTest checks it against the
-- WZ). Rows for world 0 and world 1 (Bera, after 008): a world that does not run never loads its rows.
--
-- No existing row changes. Stop the game and take a backup first, as for every migration here. Applying it twice
-- changes nothing (ledger id '026-dalair-henesys', and each row is inserted only when missing).
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '026-dalair-henesys');

-- The game must be stopped: snapshot existing ids so only this insert is owned.
CREATE TEMPORARY TABLE migration_plife_before (id bigint PRIMARY KEY);
START TRANSACTION;
INSERT INTO migration_plife_before SELECT id FROM plife;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, 'n', m.y, 0, m.fh, m.x + 50, m.x - 50, m.x, m.y, 0, -1, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- map, npc, x, ground y, foothold
  SELECT 100000000 AS map, 9000066 AS life, 4550 AS x, 454 AS y, 214 AS fh   -- Henesys, lower street, Bruce..Roca
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '026-dalair-henesys', 'plife', p.id FROM plife p
WHERE @apply AND NOT EXISTS (SELECT 1 FROM migration_plife_before b WHERE b.id = p.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('026-dalair-henesys');
COMMIT;
DROP TEMPORARY TABLE migration_plife_before;
