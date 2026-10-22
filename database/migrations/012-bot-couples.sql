-- SlumsMS 1.5.0 (ticket 58): bot couples and bot best friends. A few pairs of bots per world date (any genders), now
-- and then one marries in Amoria (a staged ceremony; the stock marriage tables are not used and no characters row
-- changes), and a few others are best friends who hang out and wear friendship rings. Adds one table; nothing
-- else changes, so an older jar runs fine on a migrated database. Stop the game and take a backup first, as for every
-- migration here. Applying it twice changes nothing (ledger id '012-bot-couples'). Without it the server still runs:
-- couples then live in memory only and are forgotten at a restart.
--
-- BotA / BotB are botstore.BotId values (character id = 1000000000 + BotId); a bot is in at most one couple (the
-- server checks both columns). Stage is DATING, MARRIED or FRIENDS. Times are epoch ms: FormedAt when they got together,
-- MarriedAt the wedding (0 = not yet), LastNoteAt the last love-note megaphone.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS botcouple
(
    CoupleId   INT UNSIGNED NOT NULL AUTO_INCREMENT,
    BotA       INT UNSIGNED NOT NULL,
    BotB       INT UNSIGNED NOT NULL,
    Stage      VARCHAR(8)   NOT NULL DEFAULT 'DATING',
    FormedAt   BIGINT       NOT NULL DEFAULT 0,
    MarriedAt  BIGINT       NOT NULL DEFAULT 0,
    LastNoteAt BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (CoupleId),
    UNIQUE KEY bot_a (BotA),
    UNIQUE KEY bot_b (BotB)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('012-bot-couples');
