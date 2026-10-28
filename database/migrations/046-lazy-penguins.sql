-- SlumsMS 1.6.1 (owner 2026-10-28): 15 decorative Lazy Penguins, NPCs 2985-2999, one per town (flavour; click for a lazy line,
-- scripts/npc/29xx.js). Placement table and NPC data: tools/data-align/lazy-penguins/penguins.tsv; the host WZ entries
-- (Npc.wz + String.wz name "Lazy Penguin") are a release-night step, tools/data-align/add_lazy_penguins.py, see
-- docs/release/1.6.1-deploy-steps.md. 2985 (Christmas) stands in Happyville, the rest are spread over 14 towns.
-- Each spot is a point on a real foothold of the map (y is the foothold's height at x, +-1), clear of every portal
-- and map NPC; LazyPenguinsTest checks all 15 against the WZ.
-- Rows for world 0 and world 1 (Bera, after 008): a world that does not run never loads its rows.
--
-- No existing row changes. Stop the game and take a backup first, as for every migration here. Applying it twice
-- changes nothing (ledger id '046-lazy-penguins', and each row is inserted only when missing).
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '046-lazy-penguins');

-- The game must be stopped: snapshot existing ids so only this insert is owned.
CREATE TEMPORARY TABLE migration_plife_before (id bigint PRIMARY KEY);
START TRANSACTION;
INSERT INTO migration_plife_before SELECT id FROM plife;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, 'n', m.y, m.f, m.fh, m.x + 50, m.x - 50, m.x, m.y, 0, -1, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- map, npc, x, y, foothold, facing
  SELECT 209000000 AS map, 2985 AS life, 1306 AS x, -94 AS y, 79 AS fh, 0 AS f   -- Happyville, Christmas
  UNION ALL
  SELECT 100000000, 2986, 573, 11, 60, 1   -- Henesys, Cherry
  UNION ALL
  SELECT 101000000, 2987, -65, -305, 408, 0   -- Ellinia, Lemon
  UNION ALL
  SELECT 102000000, 2988, 1526, 589, 186, 1   -- Perion, Sunny
  UNION ALL
  SELECT 103000000, 2989, 496, -135, 275, 0   -- Kerning City, Cocoa
  UNION ALL
  SELECT 104000000, 2990, 3298, 424, 325, 1   -- Lith Harbor, Popcorn
  UNION ALL
  SELECT 105040300, 2991, 793, -720, 85, 0   -- Sleepywood, Blueberry
  UNION ALL
  SELECT 120000000, 2992, 2689, -333, 334, 1   -- Nautilus, Tangerine
  UNION ALL
  SELECT 200000000, 2993, -252, 88, 398, 0   -- Orbis, Sage
  UNION ALL
  SELECT 220000000, 2994, -132, -705, 251, 1   -- Ludibrium, Teal
  UNION ALL
  SELECT 230000000, 2995, 1757, 469, 72, 0   -- Aquarium, Clown
  UNION ALL
  SELECT 240000000, 2996, 1519, -270, 266, 1   -- Leafre, Pebble
  UNION ALL
  SELECT 250000000, 2997, -1492, -488, 315, 0   -- Mu Lung, Berry
  UNION ALL
  SELECT 800000000, 2998, 2965, -105, 107, 1   -- Showa, Mint
  UNION ALL
  SELECT 600000000, 2999, 2655, 364, 465, 0   -- New Leaf City, Classic
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '046-lazy-penguins', 'plife', p.id FROM plife p
WHERE @apply AND NOT EXISTS (SELECT 1 FROM migration_plife_before b WHERE b.id = p.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('046-lazy-penguins');
COMMIT;
DROP TEMPORARY TABLE migration_plife_before;
