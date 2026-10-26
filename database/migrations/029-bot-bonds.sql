-- SlumsMS 1.6.0 (slice S2, bot-to-bot bonds): how each bot feels about a few other bots, kept across restarts.
-- Adds one table; no existing table, column or row changes, so an older jar runs fine on a migrated database (it just
-- ignores it). Stop the game and take a backup first, as for every migration here. Applying it twice changes nothing
-- (ledger id '029-bot-bonds'). Without it the server still runs: bonds then live in memory only and are forgotten at a
-- restart.
--
-- botbond: one row per bot and another bot it has feelings about (directional: a KS sours the victim, not the thief).
-- BotId and OtherId are botstore.BotId (character id = 1000000000 + BotId). Score is -100..100 as of UpdatedAt (epoch
-- ms); the server halves it every six weeks (friendship) or two weeks (grudge) from there. Kind and Hostile count the
-- kind and hostile shared events (party, thread, guild, PQ, buff, gift / KS, spat) that built it. At most 14 rows per
-- bot (about 42,000 at 3,000 bots); the server drops the weakest past that and rows that faded to nothing.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS botbond
(
    BotId     INT UNSIGNED      NOT NULL,
    OtherId   INT UNSIGNED      NOT NULL,
    Score     FLOAT             NOT NULL DEFAULT 0,
    Kind      SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    Hostile   SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    UpdatedAt BIGINT            NOT NULL DEFAULT 0,
    PRIMARY KEY (BotId, OtherId)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('029-bot-bonds');
