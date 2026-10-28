-- Stop the game first and take a backup. Removes what 038 added: the exchange's orders, fills, balances and ledger.
-- DANGER once the exchange has run: rows in exchange_escrow are players' NX and mesos held by the exchange (escrow
-- and payouts). Cancel every open order and let every payout collect (exchange_escrow all zero) before rolling back,
-- or that money is lost. An older jar never reads these tables. Applying it twice changes nothing.
DROP TABLE IF EXISTS exchange_ledger;
DROP TABLE IF EXISTS exchange_escrow;
DROP TABLE IF EXISTS exchange_fills;
DROP TABLE IF EXISTS exchange_orders;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='038-nx-exchange';
