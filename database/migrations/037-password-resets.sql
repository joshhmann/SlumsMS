-- SlumsMS 1.6.0, ticket 107 (owner 2026-10-26): forgotten passwords, reset after a GM approves the request (no email,
-- no codes). Adds one table; no existing table, column or row changes, so an older jar runs fine on a migrated database
-- (it just ignores it). Stop the game and take a backup first, as for every migration here. Applying it twice changes
-- nothing (ledger id '037-password-resets'). Without it the server still runs: !resetpw says requests aren't available
-- and /reset/complete answers UNAVAILABLE.
--
-- password_resets: one row per request the site sent for an existing, resettable account (requests for unknown, banned
-- or bot accounts store nothing). request_id: 32 hex characters. secret_hash: SHA-256 (hex) of the browser's random
-- 32-byte secret; the secret itself is never stored. contact: what the player typed (a Discord name or such), at most
-- 64 characters, PG-13. ip_hash / used_ip_hash: the login service's salted IP hash, never a raw IP. state: pending,
-- approved, denied, used (the password was set) or replaced (a newer request for the account came in). All times are
-- epoch ms; 0 means not set. decided_by: the GM who approved or denied it. No password is ever stored here.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS password_resets
(
    request_id   CHAR(32)     NOT NULL,
    account_id   INT          NOT NULL,
    account_name VARCHAR(64)  NOT NULL DEFAULT '',
    secret_hash  CHAR(64)     NOT NULL,
    contact      VARCHAR(64)  NOT NULL DEFAULT '',
    ip_hash      VARCHAR(128) NOT NULL DEFAULT '',
    created_at   BIGINT       NOT NULL,
    state        VARCHAR(12)  NOT NULL DEFAULT 'pending',
    decided_at   BIGINT       NOT NULL DEFAULT 0,
    decided_by   VARCHAR(64)  NOT NULL DEFAULT '',
    used_at      BIGINT       NOT NULL DEFAULT 0,
    used_ip_hash VARCHAR(128) NOT NULL DEFAULT '',
    PRIMARY KEY (request_id),
    KEY password_resets_account (account_id, state),
    KEY password_resets_created (created_at)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('037-password-resets');
