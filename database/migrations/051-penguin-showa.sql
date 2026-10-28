-- SlumsMS 1.6.1 (promise audit 2026-10-28): the "Showa" Lazy Penguin (2998, Mint) stood on map 800000000, which is
-- Mushroom Shrine (Zipangu), not Showa Town (801000000). Moves her to Showa Town's main street, on the ground (y 155,
-- foothold 52), clear of portals, NPCs and front map objects (LazyPenguinsTest checks the spot against the WZ).
-- Updates the row 049 left (worlds 0 and 1); a row that is not at that spot is left alone. Stop the game and take a
-- backup first. Applying it twice changes nothing (ledger id '051-penguin-showa'). A plife change needs a restart.
-- (050 is reserved for 161-bbdrops.)
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '051-penguin-showa');

START TRANSACTION;
UPDATE plife p
JOIN (
  SELECT 800000000 AS omap, 2998 AS life, 2905 AS ox, 95 AS oy, 106 AS ofh, 801000000 AS nmap, 1560 AS nx, 155 AS ny, 52 AS nfh   -- Mint, Mushroom Shrine -> Showa Town
) m ON p.map = m.omap AND p.life = m.life AND p.x = m.ox AND p.y = m.oy AND p.fh = m.ofh
SET p.map = m.nmap, p.x = m.nx, p.y = m.ny, p.cy = m.ny, p.fh = m.nfh, p.rx0 = m.nx + 50, p.rx1 = m.nx - 50
WHERE @apply AND p.world IN (0, 1) AND p.type = 'n';
INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('051-penguin-showa');
COMMIT;
