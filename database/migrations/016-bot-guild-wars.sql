-- SlumsMS 1.5.0 bot drama: guild wars and alliances. Adds one table; nothing else changes, so an older jar runs fine on
-- a migrated database. Stop the game and take a backup first, as for every migration here. Applying it twice changes
-- nothing (ledger id '016-bot-guild-wars'). Without it the server still runs: wars then live in memory only and are
-- forgotten at a restart.
--
-- One row per war or alliance. Kind is WAR or ALLIANCE. GuildA is the bot guild that declared (or proposed); GuildB a
-- bot guild, or a player guild when BPlayer = 1 (a bot guild's war on a player guild). State is ACTIVE, ENDED, PEACE or
-- BETRAYED. Times are epoch ms (EndsAt: a war's end, a week after it started). ScoreA / ScoreB: turf points (one per
-- grind map held per minute).
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS botguildwar
(
    WarId     INT UNSIGNED NOT NULL AUTO_INCREMENT,
    Kind      VARCHAR(8)   NOT NULL DEFAULT 'WAR',
    GuildA    INT          NOT NULL,
    GuildB    INT          NOT NULL,
    BPlayer   TINYINT(1)   NOT NULL DEFAULT 0,
    State     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    StartedAt BIGINT       NOT NULL DEFAULT 0,
    EndsAt    BIGINT       NOT NULL DEFAULT 0,
    ScoreA    INT          NOT NULL DEFAULT 0,
    ScoreB    INT          NOT NULL DEFAULT 0,
    EndedAt   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (WarId),
    KEY state (State, EndedAt)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('016-bot-guild-wars');
