-- SlumsMS 1.6.0 (owner decision 2026-10-25, ticket 102): Nimble (NPC 9977779, the forger and ore refiner) stood only
-- in the Base of Roots hub (993910000), while the five retired refiners (Mr. Thunder in Perion, Vogen, Pi, Arwen,
-- Muhamad) tell players to "see Nimble in the Free Market". This adds him to the Free Market entrance (910000000), the
-- way 026 placed Dalair. His Base of Roots row stays: he now stands in both places.
--
-- Spot: the entrance street (y 34, foothold 86 runs flat from x -360 to -270) at x -300, 238 px from the exit portal,
-- 144 px from the spawn point and 229 px from the nearest NPC (NimbleFreeMarketMigrationTest checks it against the WZ).
-- Rows for world 0 and world 1 (Bera): a world that does not run never loads its rows.
--
-- No existing row changes. Stop the game and take a backup first, as for every migration here. Applying it twice
-- changes nothing (ledger id '027-nimble-free-market', and each row is inserted only when missing).
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '027-nimble-free-market');

-- The game must be stopped: snapshot existing ids so only this insert is owned.
CREATE TEMPORARY TABLE migration_plife_before (id bigint PRIMARY KEY);
START TRANSACTION;
INSERT INTO migration_plife_before SELECT id FROM plife;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, 'n', m.y, 0, m.fh, m.x + 50, m.x - 50, m.x, m.y, 0, -1, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- map, npc, x, ground y, foothold
  SELECT 910000000 AS map, 9977779 AS life, -300 AS x, 34 AS y, 86 AS fh   -- Free Market entrance street
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '027-nimble-free-market', 'plife', p.id FROM plife p
WHERE @apply AND NOT EXISTS (SELECT 1 FROM migration_plife_before b WHERE b.id = p.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('027-nimble-free-market');
COMMIT;
DROP TEMPORARY TABLE migration_plife_before;
