-- Removes the stored mob-kill counts (only the site's "most hunted" board uses them; it restarts from zero).
-- Applying it twice changes nothing.
DROP TABLE IF EXISTS site_mob_kills;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '047-mob-kills';
