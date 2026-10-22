-- SlumsMS 1.5.0 bot drama: romance between real players and bots (opt-in with @romance on). Adds two tables; nothing
-- else changes, so an older jar runs fine on a migrated database. Stop the game and take a backup first, as for every
-- migration here. Applying it twice changes nothing (ledger id '015-bot-romance'). Without it the server still runs:
-- romances then live in memory only and are forgotten at a restart.
--
-- botromance: one row per player and bot they dated. BotId is a botstore.BotId (character id = 1000000000 + BotId).
-- Stage is DATING, ENGAGED, MARRIED or EX. Times are epoch ms: StartedAt the latest step (together, engaged, married),
-- TogetherAt the last time both stood on one map (neglect), EndedAt the breakup (EX only). RingItemId is the wedding
-- ring kind, BotRingId the bot's half of the stock `rings` pair once married. An engagement or marriage also uses the
-- stock rows (marriages, rings, characters.partnerId / marriageItemId) exactly as for two players.
-- botromanceopt: every character with @romance on (absent = off, the default).
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS botromance
(
    CharacterId INT          NOT NULL,
    BotId       INT UNSIGNED NOT NULL,
    Stage       VARCHAR(8)   NOT NULL DEFAULT 'DATING',
    StartedAt   BIGINT       NOT NULL DEFAULT 0,
    TogetherAt  BIGINT       NOT NULL DEFAULT 0,
    EndedAt     BIGINT       NOT NULL DEFAULT 0,
    RingItemId  INT          NOT NULL DEFAULT 0,
    BotRingId   INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (CharacterId, BotId),
    KEY bot (BotId)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS botromanceopt
(
    CharacterId INT    NOT NULL,
    OptedInAt   BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (CharacterId)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('015-bot-romance');
