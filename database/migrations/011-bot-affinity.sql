-- SlumsMS 1.5.0 (ticket 58): how each bot feels about each real player, and the players who opted out of bot drama.
-- Adds two tables; no existing table, column or row changes, so an older jar runs fine on a migrated database (it
-- just ignores them). Stop the game and take a backup first, as for every migration here. Applying it twice changes
-- nothing (ledger id '011-bot-affinity'). Without it the server still runs: affinity then lives in memory only and
-- is forgotten at a restart, and @botdrama off lasts until the restart.
--
-- botaffinity: one row per bot and real player that have history. BotId is botstore.BotId (character id =
-- 1000000000 + BotId), CharacterId the player's characters.id. Score is -100..100 at UpdatedAt (epoch ms); the server
-- fades it toward 0 from there (grudges over days, friendships over weeks), so a row is only rewritten when
-- something new happens. Kind / Hostile count the events behind the score (a nemesis needs a history, not one bad
-- moment). The server keeps at most a few dozen rows per player and drops the weakest; rows that faded to nothing
-- are deleted when the player next logs in.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS botaffinity
(
    BotId       INT UNSIGNED      NOT NULL,
    CharacterId INT UNSIGNED      NOT NULL,
    Score       SMALLINT          NOT NULL DEFAULT 0,
    Kind        SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    Hostile     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    UpdatedAt   BIGINT            NOT NULL DEFAULT 0,
    PRIMARY KEY (CharacterId, BotId)
) ENGINE = InnoDB;

-- botdrama: characters that said @botdrama off (bots stay neutral or friendly to them). No row = drama on.
CREATE TABLE IF NOT EXISTS botdrama
(
    CharacterId INT UNSIGNED NOT NULL PRIMARY KEY,
    OptedOutAt  BIGINT       NOT NULL DEFAULT 0
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('011-bot-affinity');
