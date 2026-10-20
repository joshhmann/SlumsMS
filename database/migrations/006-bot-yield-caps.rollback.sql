-- Stop the game first and take a backup. Removes what 006 added: the bots' stored daily meso counts. The caps keep
-- working with the counts in memory, starting from zero at each boot. Applying it twice changes nothing.
DROP TABLE IF EXISTS botyield;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='006-bot-yield-caps';
