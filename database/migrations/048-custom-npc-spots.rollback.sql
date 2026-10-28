-- Stop the game first and take a backup. Puts the moved custom NPCs back on their 042/046 spots.
START TRANSACTION;
UPDATE plife p
JOIN (
  SELECT 100000000 AS map, 9990000 AS life, 1040 AS ox, 334 AS oy, 91 AS ofh, 1215 AS nx, 334 AS ny, 102 AS nfh   -- Slums-chan, Henesys
  UNION ALL SELECT 209000000, 2985, 1305, 154, 80, 1306, -94, 79   -- Christmas, Happyville
  UNION ALL SELECT 100000000, 2986, 102, 34, 38, 573, 11, 60   -- Cherry, Henesys
  UNION ALL SELECT 101000000, 2987, 285, -693, 638, -65, -305, 408   -- Lemon, Ellinia
  UNION ALL SELECT 102000000, 2988, 1540, 589, 186, 1526, 589, 186   -- Sunny, Perion
  UNION ALL SELECT 103000000, 2989, 470, -54, 257, 496, -135, 275   -- Cocoa, Kerning City
  UNION ALL SELECT 220000000, 2994, -353, -678, 62, -132, -705, 251   -- Teal, Ludibrium
  UNION ALL SELECT 230000000, 2995, 1325, 460, 26, 1757, 469, 72   -- Clown, Aquarium
  UNION ALL SELECT 250000000, 2997, -1645, 51, 307, -1492, -488, 315   -- Berry, Mu Lung
  UNION ALL SELECT 600000000, 2999, 2001, 392, 441, 2655, 364, 465   -- Classic, New Leaf City
) m ON p.map = m.map AND p.life = m.life AND p.x = m.ox AND p.y = m.oy AND p.fh = m.ofh
SET p.x = m.nx, p.y = m.ny, p.cy = m.ny, p.fh = m.nfh, p.rx0 = m.nx + 50, p.rx1 = m.nx - 50
WHERE p.world IN (0, 1) AND p.type = 'n';
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '048-custom-npc-spots';
COMMIT;
