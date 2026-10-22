-- SlumsMS 1.5.0 (bots lane): bot guilds. About ten guilds run by roster bots are real stock `guilds` rows (players see
-- the name over a bot's head, the guild list and the ranking NPC); bots join player guilds too. Adds two tables;
-- nothing else changes, so an older jar runs fine on a migrated database (it simply never loads the bot members).
-- Stop the game and take a backup first, as for every migration here. Applying it twice changes nothing (ledger id
-- '014-bot-guilds'). Without it the server still runs: no bot guild is founded, and a bot that joins a player's guild
-- stays in it only until a restart.
--
-- The server founds the bot guilds itself at the first start with this table empty (it writes the `guilds` rows, the
-- names reserved like any guild's), so nothing is seeded here.
--
-- botguild: one row per bot-run guild. GuildId = guilds.guildid. Vibe is casual, hardcore, merchant, social, newbie
-- or pvm; RivalId is the guild it has a light rivalry with (0 = none); TargetSize the member count it keeps to.
-- botguildmember: one row per bot in any guild (a bot is in at most one). BotId is a botstore.BotId (character id =
-- 1000000000 + BotId). GuildRank 1-5 as characters.guildrank. JoinedAt / AttentionAt are epoch ms: AttentionAt is
-- the last time a player of that guild did something with the bot (a bot in a player guild quits after weeks without).
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS botguild
(
    GuildId    INT UNSIGNED      NOT NULL,
    Vibe       VARCHAR(12)       NOT NULL DEFAULT 'casual',
    RivalId    INT UNSIGNED      NOT NULL DEFAULT 0,
    TargetSize SMALLINT UNSIGNED NOT NULL DEFAULT 20,
    CreatedAt  BIGINT            NOT NULL DEFAULT 0,
    PRIMARY KEY (GuildId)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS botguildmember
(
    BotId       INT UNSIGNED     NOT NULL,
    GuildId     INT UNSIGNED     NOT NULL,
    GuildRank   TINYINT UNSIGNED NOT NULL DEFAULT 5,
    JoinedAt    BIGINT           NOT NULL DEFAULT 0,
    AttentionAt BIGINT           NOT NULL DEFAULT 0,
    PRIMARY KEY (BotId),
    KEY guild (GuildId)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('014-bot-guilds');
