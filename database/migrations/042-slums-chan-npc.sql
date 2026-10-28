-- SlumsMS 1.6.1 (owner 2026-10-28): Slums-chan, NPC 9990000, the friendly guide who replaces Cody at the first login.
-- She stands in Base of Roots (993910000, where Beginner/Noblesse/Legend creators start) and in Henesys (100000000).
-- Script scripts/npc/9990000.js; the NPC's own data (Npc.wz entry, name in String.wz) is a host step, see
-- tools/data-align/add_slums_chan.py and docs/release/1.6.1-deploy-steps.md.
--
-- Base of Roots: the new-character spawn point sp is (185, -38); the ground under it is foothold 66, y 117, from
-- x 252 to 402. She stands at x 340 on it: 155 px from the spawn, 75 px clear of the classicFM portal (265), and clear
-- of every NPC. Henesys: the lower street, foothold 102 (x 1170 to 1260, y 334), x 1215, in the middle of town, the
-- nearest NPC 196 px away. SlumsChanNpcMigrationTest checks both against the WZ.
-- Rows for world 0 and world 1 (Bera, after 008): a world that does not run never loads its rows.
--
-- No existing row changes. Stop the game and take a backup first, as for every migration here. Applying it twice
-- changes nothing (ledger id '042-slums-chan-npc', and each row is inserted only when missing).
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '042-slums-chan-npc');

-- The game must be stopped: snapshot existing ids so only this insert is owned.
CREATE TEMPORARY TABLE migration_plife_before (id bigint PRIMARY KEY);
START TRANSACTION;
INSERT INTO migration_plife_before SELECT id FROM plife;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, 'n', m.y, 0, m.fh, m.x + 50, m.x - 50, m.x, m.y, 0, -1, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- map, npc, x, ground y, foothold
  SELECT 993910000 AS map, 9990000 AS life, 340 AS x, 117 AS y, 66 AS fh   -- Base of Roots, by the new-character spawn
  UNION ALL
  SELECT 100000000, 9990000, 1215, 334, 102                                -- Henesys, lower street
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '042-slums-chan-npc', 'plife', p.id FROM plife p
WHERE @apply AND NOT EXISTS (SELECT 1 FROM migration_plife_before b WHERE b.id = p.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('042-slums-chan-npc');
COMMIT;
DROP TEMPORARY TABLE migration_plife_before;
