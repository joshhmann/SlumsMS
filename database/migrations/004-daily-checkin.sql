-- Daily Check-In: one 28-day login-streak per account. A new table only; no existing table or row changes.
-- Stop the game and take a backup first, as for every migration here.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS daily_checkin
(
    account_id   INT              NOT NULL,
    day          TINYINT UNSIGNED NOT NULL DEFAULT 0,  -- last day claimed this cycle (0..28)
    claimed_mask INT UNSIGNED     NOT NULL DEFAULT 0,  -- bit d-1 set = day d claimed this cycle
    last_claim   BIGINT           NOT NULL DEFAULT 0,  -- epoch millis of the last claim (0 = never)
    PRIMARY KEY (account_id)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('004-daily-checkin');
