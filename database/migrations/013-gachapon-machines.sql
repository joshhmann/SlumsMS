-- SlumsMS 1.5.0 gachapon: machines in the six towns whose v83 maps have none on this WZ (Ellinia, Perion, Kerning
-- City, Sleepywood, Mushroom Shrine, New Leaf City), as permanent NPC rows (plife, the table !pnpc writes and
-- MapFactory.loadLifeFromDb reads on map load). NPC ids are the machine ids the scripts and gachapon-pools.yaml use
-- (NPCTalkHandler sends 9100100-9100117 to scripts/npc/gachapon.js). The client needs no Map.wz change: it draws a
-- server-spawned NPC from Npc.wz, as for !pnpc (docs/release/1.5.0-gachapon-machines-client.md).
--
-- Spots: on the ground floor near the shops, at least 160 px from a usable portal and 90 px from other NPCs
-- (GachaponMachinesMigrationTest checks every row against the WZ footholds and portals). cy/y are the ground at x,
-- fh the foothold under it; rx0/rx1 as !pnpc writes them. Rows for world 0 and world 1 (Bera, after 008): a world that
-- does not run never loads its rows.
--
-- No existing row changes. Stop the game and take a backup first, as for every migration here. Applying it twice
-- changes nothing (ledger id '013-gachapon-machines', and each row is inserted only when missing).
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

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '013-gachapon-machines');

-- The game must be stopped: snapshot existing ids so only this insert is owned.
CREATE TEMPORARY TABLE migration_plife_before (id bigint PRIMARY KEY);
START TRANSACTION;
INSERT INTO migration_plife_before SELECT id FROM plife;
INSERT INTO plife (world, map, life, type, cy, f, fh, rx0, rx1, x, y, hide, mobtime, team)
SELECT w.world, m.map, m.life, 'n', m.y, 0, m.fh, m.x + 50, m.x - 50, m.x, m.y, 0, -1, 0
FROM (SELECT 0 AS world UNION ALL SELECT 1) w
CROSS JOIN (
  -- machine: map, npc, x, ground y, foothold
  SELECT 101000000 AS map, 9100101 AS life, 1120 AS x, 288 AS y, 1107 AS fh   -- Ellinia, by the lower shop door
  UNION ALL SELECT 102000000, 9100102, 1500, 589,  186                        -- Perion, past Mr. Thunder
  UNION ALL SELECT 103000000, 9100103,  900, 156,  313                        -- Kerning City, street by the shops
  UNION ALL SELECT 105040300, 9100104,  545, 255,   72                        -- Sleepywood, by Mr. Oh
  UNION ALL SELECT 800000000, 9100105, 3320,  95,  120                        -- Mushroom Shrine, food street
  UNION ALL SELECT 600000000, 9100109, 3800, 501,  561                        -- New Leaf City, by Kyle and Miki
) m
WHERE @apply
  AND NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life);
INSERT INTO mapleroot_integration_migration_rows (migration_id, table_name, row_id)
SELECT '013-gachapon-machines', 'plife', p.id FROM plife p
WHERE @apply AND NOT EXISTS (SELECT 1 FROM migration_plife_before b WHERE b.id = p.id);
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('013-gachapon-machines');
COMMIT;
DROP TEMPORARY TABLE migration_plife_before;
