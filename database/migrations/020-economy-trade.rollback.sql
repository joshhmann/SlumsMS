-- Stop the game first and take a backup. Removes what 020 added: the price board's samples, the daily economy
-- meters and the gift timers. The server keeps working with all three in memory only. Applying it twice changes
-- nothing.
DROP TABLE IF EXISTS pricesamples;
DROP TABLE IF EXISTS economyday;
DROP TABLE IF EXISTS botgifts;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='020-economy-trade';
