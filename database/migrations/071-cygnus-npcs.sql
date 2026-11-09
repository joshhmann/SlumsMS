-- 1.6.8: card cygnus-fight-unreachable.
-- The Cygnus expedition could not be started or advanced: its recruiter 3003970 (scripts/npc/3003970.js, creates the
-- CYGNUS expedition) and its summoner 3003903 (scripts/npc/3003903.js, spawns boss 8850011 and then removes itself)
-- stood on no map, so the garden had no recruiter and the chamber only held NPC 2143004.
--   3003970 -> Cygnus Garden 271040000 at x -1000, y 108 (fh 1 is flat from x -1324 to 502). The gate drops players
--              on portal out00 (x -1110), so the recruiter is 110 px from the arrival point, 274 px from the town
--              portal pt0 (x -726) and far from the chamber door in00 (x 177).
--   3003903 -> Cygnus's Chamber 271040100 at x -104, y 115, the spot the climb uses (RebirthBossRuns). plife rows
--              load into every MapleMap of that id, including the base copy; 3003903.js now refuses to act without an
--              event instance, so a player in the base chamber cannot summon her.
-- Runs for world 0 and 1, idempotent, and records its rows so the rollback removes exactly what it added.

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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '071-cygnus-npcs');

CREATE TEMPORARY TABLE migration_plife_before (id bigint PRIMARY KEY);

START TRANSACTION;
INSERT INTO migration_plife_before SELECT id FROM plife;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, m.type, m.y, 0, m.fh, m.x - 50, m.x + 50, m.x, m.y, 0, m.mobtime, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- map, life, type, x, ground y, foothold, mobtime
  SELECT 271040000 AS map, 3003970 AS life, 'n' AS type, -1000 AS x, 108 AS y, 1 AS fh, -1 AS mobtime   -- recruiter, Cygnus Garden, on the arrival ledge
  UNION ALL
  SELECT 271040100, 3003903, 'n', -104, 115, 1, -1                                                       -- summoner, Cygnus's Chamber
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '071-cygnus-npcs', 'plife', p.id FROM plife p
WHERE @apply AND NOT EXISTS (SELECT 1 FROM migration_plife_before b WHERE b.id = p.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('071-cygnus-npcs');
COMMIT;
DROP TEMPORARY TABLE migration_plife_before;
