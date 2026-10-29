-- Removes the saved Family buffs: perks running now keep running until the next restart, then end (as in 1.6.0).
-- Applying it twice changes nothing.
DROP TABLE IF EXISTS family_buffs;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '052-family-buffs';
