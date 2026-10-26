-- SlumsMS 1.6.0 (owner decision 2026-10-26, site write features): the Buy/Sell board NPC at the Free Market
-- Entrance (910000000). It shows the open posts from the website's board (read-only; script scripts/npc/1092017.js,
-- logic soloMapling.Economy.board.SiteBoard). NPC 1092017 "Anonymous Merchant" is a merchant's stall with no name
-- tag that stock v83 data has but places on no map, and it had no script: nothing else uses it.
--
-- Spot: the ground floor (y 34, foothold 86 runs flat from x -360 to -270), 144 px right of the spawn point players
-- arrive at (-444) and 238 px from the exit portal (-538); clear of every NPC (SiteBoardNpcMigrationTest checks it
-- against the WZ). Rows for world 0 and world 1 (Bera, after 008): a world that does not run never loads its rows.
--
-- No existing row changes. Stop the game and take a backup first, as for every migration here. Applying it twice
-- changes nothing (ledger id '034-site-board-npc', and each row is inserted only when missing).
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '034-site-board-npc');

-- The game must be stopped: snapshot existing ids so only this insert is owned.
CREATE TEMPORARY TABLE migration_plife_before (id bigint PRIMARY KEY);
START TRANSACTION;
INSERT INTO migration_plife_before SELECT id FROM plife;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, 'n', m.y, 0, m.fh, m.x + 50, m.x - 50, m.x, m.y, 0, -1, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- map, npc, x, ground y, foothold
  SELECT 910000000 AS map, 1092017 AS life, -300 AS x, 34 AS y, 86 AS fh   -- FM Entrance, ground floor, by the way in
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '034-site-board-npc', 'plife', p.id FROM plife p
WHERE @apply AND NOT EXISTS (SELECT 1 FROM migration_plife_before b WHERE b.id = p.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('034-site-board-npc');
COMMIT;
DROP TEMPORARY TABLE migration_plife_before;
