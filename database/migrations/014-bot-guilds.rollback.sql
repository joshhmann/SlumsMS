-- Stop the game first and take a backup. Removes what 014 added: every bot guild (its `guilds` row; players who
-- joined one are guildless again, as after a disband) and every bot's guild membership (player guilds lose their bot
-- members). Player-run guilds stay. Applying it twice changes nothing.
CREATE TABLE IF NOT EXISTS botguild
(
    GuildId INT UNSIGNED NOT NULL,
    PRIMARY KEY (GuildId)
) ENGINE = InnoDB;

UPDATE characters SET guildid = 0, guildrank = 5, allianceRank = 5 WHERE guildid IN (SELECT GuildId FROM botguild);
DELETE FROM bbs_replies WHERE threadid IN (SELECT threadid FROM bbs_threads WHERE guildid IN (SELECT GuildId FROM botguild));
DELETE FROM bbs_threads WHERE guildid IN (SELECT GuildId FROM botguild);
DELETE FROM guilds WHERE guildid IN (SELECT GuildId FROM botguild);
DROP TABLE IF EXISTS botguildmember;
DROP TABLE IF EXISTS botguild;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='014-bot-guilds';
