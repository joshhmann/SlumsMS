-- SoloMapling real bots (ticket 15): each bot's daily meso count survives a restart, so a restart cannot reset the
-- daily meso cap. Adds one new table; no existing row or value is changed. Safe to apply with the game stopped (take a
-- fresh backup first, as for every migration here). Applying it twice changes nothing. Without it the server still
-- runs and keeps the counts in memory only.
--
-- One row per bot: BotId is botstore.BotId (character id = 1000000000 + BotId), Day the server-local day the bot last
-- took mesos from the world, Meso how many it took that day. A row whose Day is not today counts as zero.
-- The caps themselves are tuned with JVM system properties (-Dsolomapling.botYieldCaps, botMapYieldShare,
-- botYieldWindowMs, botDailyMesoPerLevel, botDailyMesoFloor), not config.yaml keys.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS botyield
(
    BotId INT UNSIGNED    NOT NULL PRIMARY KEY,
    Day   DATE            NOT NULL,
    Meso  BIGINT UNSIGNED NOT NULL DEFAULT 0
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('006-bot-yield-caps');
