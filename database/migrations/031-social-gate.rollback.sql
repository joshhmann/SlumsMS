-- Stop the game first and take a backup. Removes what 031 added: the "started it" ledger and the stored @quiet /
-- @romance off choices. The server keeps working with both in memory only (an older jar never reads them). Applying it
-- twice changes nothing.
DROP TABLE IF EXISTS botstartedit;
DROP TABLE IF EXISTS botsocialopt;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='031-social-gate';
