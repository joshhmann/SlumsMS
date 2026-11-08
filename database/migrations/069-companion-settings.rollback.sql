-- Undoes 069-companion-settings: drops the settings table and its ledger row. Stop the game first and take a backup.
-- Players' own choices are lost by this rollback (they are only in this table); an older jar does not read them.
-- Running it twice changes nothing.
DROP TABLE IF EXISTS companionsettings;
DELETE FROM mapleroot_integration_migrations WHERE migration_id = '069-companion-settings';
