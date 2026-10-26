-- SlumsMS 1.6.0 social life, slice S1: a durable home for each bot's daily-life profile (its Bartle blend, the seven
-- bias channels seeded from it, the bounded drift learned on top, goals and life events). Until now this rode inside
-- botroster.ActivityState under "life", which only a training session wrote: a bot whose next session was SOCIAL or
-- TOWN had it wiped (docs/design/social-life-audit.md, finding 1).
--
-- botlifeprofile: one row per roster slot. BotId is a botstore.BotId (character id = 1000000000 + BotId). Version is
-- the LifeMemory JSON version (2). Profile is that JSON: {version, savedAt, seed{bartle, blend}, delta{bartle, blend},
-- goals, lastJob, lastSocial, driftDay, changesToday, events}. The seed never changes; delta values stay within
-- +-0.15 per channel. UpdatedAt is epoch ms of the last write.
--
-- Adds one table; nothing else changes. The 1.6.0 jar reads a v1 "life" copy from ActivityState once and writes it
-- here; without this migration the jar keeps the profile in ActivityState for every bot type instead. An older jar
-- ignores the table. Stop the game and take a backup first, as for every migration here. Applying it twice changes
-- nothing (ledger id '028-bot-life-profile').
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS botlifeprofile
(
    BotId     INT UNSIGNED      NOT NULL,
    Version   SMALLINT UNSIGNED NOT NULL DEFAULT 2,
    Profile   TEXT              NOT NULL,
    UpdatedAt BIGINT            NOT NULL DEFAULT 0,
    PRIMARY KEY (BotId)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('028-bot-life-profile');
