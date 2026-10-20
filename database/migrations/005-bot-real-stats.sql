-- SoloMapling real bots (real-bots tickets 05/06): a bot's base stats, AP/SP and skills are stored with it.
-- Adds nullable columns to botstore and one new table; no existing row or value is changed. Safe to apply with
-- the game stopped (take a fresh backup first, as for every migration here). Applying it twice changes nothing.
-- Number 005 because 004 belongs to the separate daily check-in series.
--
-- The new botstore columns stay NULL for a bot whose stats have never been written: that is how the server
-- knows to back-fill the bot's build from its level and job once, on its next load. Bots whose job is not a
-- stock job (MapleRoot custom classes) are never built, so their columns stay NULL.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='005-bot-real-stats');

-- MySQL has no ADD COLUMN IF NOT EXISTS, so the column set is added in one ALTER only when it is missing.
SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botstore' AND COLUMN_NAME = 'BuildLevel');
SET @ddl = IF(@apply AND @missing,
  'ALTER TABLE botstore
     ADD COLUMN StatStr     SMALLINT UNSIGNED NULL,
     ADD COLUMN StatDex     SMALLINT UNSIGNED NULL,
     ADD COLUMN StatInt     SMALLINT UNSIGNED NULL,
     ADD COLUMN StatLuk     SMALLINT UNSIGNED NULL,
     ADD COLUMN MaxHp       INT UNSIGNED NULL,
     ADD COLUMN MaxMp       INT UNSIGNED NULL,
     ADD COLUMN Hp          INT UNSIGNED NULL,
     ADD COLUMN Mp          INT UNSIGNED NULL,
     ADD COLUMN RemainingAp SMALLINT UNSIGNED NULL,
     ADD COLUMN Sp          VARCHAR(128) NULL,
     ADD COLUMN BuildLevel  SMALLINT UNSIGNED NULL',
  'DO 0');
PREPARE add_real_stats FROM @ddl;
EXECUTE add_real_stats;
DEALLOCATE PREPARE add_real_stats;

-- StatStr..StatLuk: base stats (no gear). MaxHp/MaxMp: base pools; Hp/Mp: current, as last saved.
-- RemainingAp: unspent AP. Sp: unspent SP per skill book, comma separated like characters.sp ("3,0,0,0,0,0,0,0,0,0").
-- BuildLevel: the level the build was last settled at; a stored Level above it is caught up on the next load.

-- A bot's skills, one row per skill. BotId is botstore.BotId (character id = 1000000000 + BotId). MasterLevel is 0
-- for skills that have none (everything below 4th job). Rewritten as a whole with the bot's row, in the same
-- transaction.
CREATE TABLE IF NOT EXISTS botskills
(
    BotId       INT UNSIGNED      NOT NULL,
    SkillId     INT UNSIGNED      NOT NULL,
    Level       SMALLINT UNSIGNED NOT NULL,
    MasterLevel SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (BotId, SkillId)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('005-bot-real-stats');
