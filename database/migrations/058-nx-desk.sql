-- SlumsMS 1.6.3 (ticket 162, the NX house desk): the desk keeps its own state so R survives a restart. One row
-- (id 1) of JSON: R, the UTC day's first R (the day cap's anchor), the last hourly step, the last 168 hours of quotes
-- and volume, and the desk's last 200 fills. The money itself is not here: it is two ordinary exchange_escrow buckets
-- (kind HOUSE, owner -100, NX and MESO) and their exchange_ledger rows, so Exchange.reconcile checks it as it does
-- every other balance.
--
-- Adds one table; no existing table, column or row changes, so an older jar runs fine on a migrated database (it just
-- ignores it). Applying it twice changes nothing (ledger id '058-nx-desk'). Without it the desk does not start (the
-- exchange halts at boot rather than trade with an unknown rate); with the desk switched off nothing reads it.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS exchange_desk
(
    id         TINYINT    NOT NULL,
    state_json MEDIUMTEXT NOT NULL,
    updated_at BIGINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('058-nx-desk');
