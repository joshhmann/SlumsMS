-- SlumsMS 1.6.7 (alt-qol-batch group B, the settings card): each owner's switches and potion lines for one companion,
-- kept across a relog and a restart. Adds one table; no existing table, column or row changes, so an older jar runs
-- fine on a migrated database (it just ignores it). Stop the game and take a backup first, as for every migration here.
-- Applying it twice changes nothing (ledger id '069-companion-settings'). Without it the server still runs: the
-- settings then live in memory only and are forgotten at a restart.
--
-- companionsettings: one row per companion the owner changed something for. SettingsJson is the whole choice as JSON
--   (heals, support, buffPots, hpPercent, mpPercent, lootMode), written by soloMapling.companions.CompanionSettings.
--   hpPercent / mpPercent are 0 for "the companion's own line" (today's habit line, or the boss-run line) and 10-90
--   when the owner chose one; lootMode is stored for the loot-card work and is not read by 1.6.7's settings gate.
--   No row = every default (heals on, support on, buff pots on, both lines as they are today), so an untouched
--   companion writes nothing. CompanionIdx exists because the bot's own tick looks its settings up by companion.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS companionsettings
(
    OwnerId     INT UNSIGNED  NOT NULL,
    CompanionId INT UNSIGNED  NOT NULL,
    SettingsJson VARCHAR(1000) NOT NULL,
    UpdatedAt   BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (OwnerId, CompanionId),
    KEY CompanionIdx (CompanionId)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('069-companion-settings');
