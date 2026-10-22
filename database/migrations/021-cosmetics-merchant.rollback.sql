-- Stop the game first and take a backup. Removes the cosmetics merchant 021 placed (only that row: same world, map
-- and NPC id). Applying it twice changes nothing.
START TRANSACTION;
DELETE FROM plife WHERE type = 'n' AND map = 910000000 AND life = 9209006 AND world IN (0, 1);
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '021-cosmetics-merchant';
COMMIT;
