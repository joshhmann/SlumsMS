-- SlumsMS 1.6.6.1 (hotfix, owner 8 Nov 2026, card rebirth2-seal-blocked): put Plunk in the two boss rooms his
-- reward script already serves, so a cleared Von Leon run hands out Von Leon's Seal again.
--
-- Rebirth 2 needs the Red Essence Stone 4033442, made at Wyatt the Reborn (1300009) from a Rock of Time 4021010 and a
-- Von Leon's Seal 4001693. The Seal's only source is Plunk (NPC 3004006) after a clear: scripts/npc/3004006.js picks
-- the reward by the map he stands in (case 211070100: Von Leon's Seal; case 271040100: the Cygnus reward). Map.wz
-- places Plunk in 240060200, 280030000, 401060100, 450010100 and 450012210 but in neither of these rooms, and no plife
-- row put him there, so on live (read 8 Nov) a Von Leon clear could never be rewarded and nobody holds a Seal.
--
-- NPC placement (plife, worlds 0 and 1), on the room's floor by the entry and away from the boss:
--   Von Leon's Audience Room 211070100: x -1800, ground y -181, fh 1 (spawn points -2053/-1904; the summoner 2161008
--     stands at 1500 and the statue 2161005 at 1745, the fight's end of the room).
--   Cygnus's room 271040100: x -800, ground y 115, fh 1 (exit portal out00 at -909).
-- Nothing a player holds changes. Existing rows are never changed; applying twice adds nothing. Rollback removes only
-- the rows recorded here.
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '070-plunk-boss-reward-rooms');

CREATE TEMPORARY TABLE migration_plife_before (id bigint PRIMARY KEY);

START TRANSACTION;
INSERT INTO migration_plife_before SELECT id FROM plife;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, m.type, m.y, 0, m.fh, m.x - 50, m.x + 50, m.x, m.y, 0, m.mobtime, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- map, life, type, x, ground y, foothold, mobtime
  SELECT 211070100 AS map, 3004006 AS life, 'n' AS type, -1800 AS x, -181 AS y, 1 AS fh, -1 AS mobtime   -- Plunk, Von Leon's Audience Room
  UNION ALL
  SELECT 271040100, 3004006, 'n', -800, 115, 1, -1                                                         -- Plunk, Cygnus's room
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '070-plunk-boss-reward-rooms', 'plife', p.id FROM plife p
WHERE @apply AND NOT EXISTS (SELECT 1 FROM migration_plife_before b WHERE b.id = p.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('070-plunk-boss-reward-rooms');
COMMIT;
DROP TEMPORARY TABLE migration_plife_before;
