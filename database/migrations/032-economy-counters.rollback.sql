-- Stop the game first and take a backup. Removes what 032 added: the stored daily economy counters. The server keeps
-- working with the counters in memory only (an older jar never reads them); the nightly snapshot files are not
-- touched. Applying it twice changes nothing.
DROP TABLE IF EXISTS economycounters;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='032-economy-counters';
