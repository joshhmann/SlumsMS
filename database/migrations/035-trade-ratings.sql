-- SlumsMS 1.6.0 (Track D, owner 2026-10-26): trade ratings on the website's leaderboards. Adds one table; no existing
-- table, column or row changes, so an older jar runs fine on a migrated database (it just ignores it). Stop the game
-- and take a backup first, as for every migration here. Applying it twice changes nothing (ledger id
-- '035-trade-ratings'). Without it the server still runs: the counts stay in memory (bounded) and are retried.
--
-- traderatings: one row per character (players and bots; CharId is the character id, bots' ids are above
-- 1,000,000,000). Completed: trades completed. FellThrough: trade windows that closed without completing after either
-- side had put in items or mesos (an empty window counts for nobody). UpdatedAt: the last write (epoch ms). The server
-- adds its pending counts in one batch every minute and at shutdown.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS traderatings
(
    CharId      INT    NOT NULL,
    Completed   INT    NOT NULL DEFAULT 0,
    FellThrough INT    NOT NULL DEFAULT 0,
    UpdatedAt   BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (CharId),
    KEY completed (Completed)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('035-trade-ratings');
