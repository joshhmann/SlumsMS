-- SlumsMS 1.6.0, ticket 105 (owner 2026-10-26): invite codes for site/launcher sign-up. Adds one table; no existing
-- table, column or row changes, so an older jar runs fine on a migrated database (it just ignores it). Stop the game
-- and take a backup first, as for every migration here. Applying it twice changes nothing (ledger id
-- '036-invite-codes'). Without it the server still runs: !invite says codes aren't available and sign-up answers
-- UNAVAILABLE.
--
-- invite_codes: one row per code a GM made with !invite. code: 10 characters, stored without the dash. All times are
-- epoch ms; 0 means not set (expires_at 0: never expires). A code creates one account: used_at, used_by_account,
-- used_by_name and used_ip_hash (the login service's salted IP hash, never a raw IP) are set in the same transaction
-- that creates the account. revoked_at / revoked_by: !invite revoke on an unused code.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS invite_codes
(
    code               CHAR(10)     NOT NULL,
    created_at         BIGINT       NOT NULL,
    created_by_account INT          NOT NULL DEFAULT 0,
    created_by_name    VARCHAR(64)  NOT NULL DEFAULT '',
    note               VARCHAR(255) NOT NULL DEFAULT '',
    expires_at         BIGINT       NOT NULL DEFAULT 0,
    used_by_account    INT          NOT NULL DEFAULT 0,
    used_by_name       VARCHAR(64)  NOT NULL DEFAULT '',
    used_at            BIGINT       NOT NULL DEFAULT 0,
    used_ip_hash       VARCHAR(128) NOT NULL DEFAULT '',
    revoked_at         BIGINT       NOT NULL DEFAULT 0,
    revoked_by         VARCHAR(64)  NOT NULL DEFAULT '',
    PRIMARY KEY (code),
    KEY invite_codes_created (created_at)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('036-invite-codes');
