-- SoloMapling playerbots: durable bot store, roster and chat memory (bot-persistence series).
-- Adds three new tables only; no existing table or row is changed. Safe to apply with the game stopped
-- (take a fresh backup first, as for every migration here). Bots stay off until solomapling.enabled is set.
-- Bot character ids are BOT_ID_BASE (1000000000) + BotId; their items live in the shared item tables under
-- those ids, which no real character uses.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
SET @apply = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='003-solomapling-bots');

-- Durable store for the simulated bot population.
--
-- A bot is not a player: it has no login session, so it never passes the `loggedIn` gate that guards
-- character saves, and its identity is not something a player can recreate. It therefore gets its own
-- table instead of a row in `characters`, which also keeps bot ids out of the `characters`
-- AUTO_INCREMENT range - an inserted bot row would push the next real character past the bot id
-- threshold and get it classified as a bot everywhere.
--
-- BotId is the bot's creation slot, so a bot's character id stays BOT_ID_BASE + BotId (1000000100, ...)
-- for its whole life and a slot is re-adopted by the bot that takes that slot in the next run's spawn
-- order. The bot's type is deliberately not stored: the spawn wave that adopts a slot still owns which
-- type the bot is, so a bot re-homed by a config change does not come back claiming a role this run did
-- not give it.
--
-- Names are deliberately NOT unique: bot names come from a fixed pool that reshuffles when it runs out
-- (FMShopDescGen.getRandomIGN), the pool is smaller than one environment load, and duplicate names are
-- harmless because BotId is the identity. A unique key here would reject every bot past the first pool
-- wrap instead.
CREATE TABLE IF NOT EXISTS botstore
(
    BotId      INT UNSIGNED NOT NULL AUTO_INCREMENT,
    Name       VARCHAR(13)  NOT NULL,
    Tier       VARCHAR(8)   NOT NULL,
    Level      INT UNSIGNED NOT NULL,
    Exp        BIGINT UNSIGNED NOT NULL,   -- MapleRoot keeps 64-bit EXP
    Meso       INT UNSIGNED NOT NULL,
    Job        INT UNSIGNED NOT NULL,
    Skin       INT UNSIGNED NOT NULL,
    Gender     TINYINT UNSIGNED NOT NULL,
    Hair       INT UNSIGNED NOT NULL,
    Face       INT UNSIGNED NOT NULL,
    MapId      INT UNSIGNED NOT NULL,
    PosX       INT          NOT NULL,
    PosY       INT          NOT NULL,
    EquipSlots TINYINT UNSIGNED NOT NULL,
    UseSlots   TINYINT UNSIGNED NOT NULL,
    SetupSlots TINYINT UNSIGNED NOT NULL,
    EtcSlots   TINYINT UNSIGNED NOT NULL,
    CreatedAt  BIGINT       NOT NULL DEFAULT '0',
    SavedAt    BIGINT       NOT NULL DEFAULT '0',
    PRIMARY KEY (BotId)
) ENGINE = InnoDB AUTO_INCREMENT = 100;

-- The roster: population bots that are players with a life of their own, not positions in the boot plan
-- (bot-persistence ticket 04). A roster bot keeps its botstore row - identity, progression, holdings - and
-- this table adds what makes it a player: whether it still plays, its personality and persona, when it
-- usually plays, where home is, and what it was doing when it logged out.
--
-- Kept apart from botstore on purpose: botstore's write path is the ledger's durability boundary, and none of
-- these columns hold value.
--
-- BotId is the same slot as botstore.BotId (character id = BOT_ID_BASE + BotId). Bots enrolled by this
-- migration keep their boot-plan slot, because their items are stored under that character id; new roster
-- bots take slots above the whole boot plan (BotStore.FIRST_ROSTER_SLOT).
CREATE TABLE IF NOT EXISTS botroster
(
    BotId           INT UNSIGNED      NOT NULL,
    Status          VARCHAR(8)        NOT NULL DEFAULT 'ACTIVE',
    Personality     TEXT              NULL,
    Persona         TEXT              NULL,
    PlayWindowStart SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    PlayWindowLen   SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    SessionMinutes  SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    HomeMapId       INT UNSIGNED      NOT NULL DEFAULT 0,
    Activity        VARCHAR(16)       NOT NULL DEFAULT '',
    ActivityState   TEXT              NULL,
    LastLoginAt     BIGINT            NOT NULL DEFAULT 0,
    LastLogoutAt    BIGINT            NOT NULL DEFAULT 0,
    EnrolledAt      BIGINT            NOT NULL DEFAULT 0,
    PRIMARY KEY (BotId)
) ENGINE = InnoDB;

-- Enrol the population the boot plan already has: waves 8 (training cohorts) and 9 (town presence).
-- 8292 is BotStore.FIRST_PLANNED_SLOT and 65536 is BotStore.SLOTS_PER_WAVE; BotRosterTest pins both.
INSERT IGNORE INTO botroster (BotId, EnrolledAt, LastLogoutAt)
SELECT BotId, CreatedAt, SavedAt
FROM botstore
WHERE @apply AND BotId >= 8292
  AND FLOOR((BotId - 8292) / 65536) IN (8, 9);

-- What bots remember about the players they talk to (bot-persistence ticket 14): short notes written by the bot's
-- brain when a conversation ends. BotId is the bot's slot (character id = BOT_ID_BASE + BotId); AboutCharacterId is
-- the real player's character id. Source DIRECT = the bot was there; HEARSAY = another bot told it (ticket 15),
-- with FromBotId and the number of hops it travelled.
CREATE TABLE IF NOT EXISTS botmemory
(
    Id               INT UNSIGNED     NOT NULL AUTO_INCREMENT,
    BotId            INT UNSIGNED     NOT NULL,
    AboutCharacterId INT UNSIGNED     NOT NULL,
    Note             VARCHAR(160)     NOT NULL,
    Source           VARCHAR(8)       NOT NULL DEFAULT 'DIRECT',
    FromBotId        INT UNSIGNED     NOT NULL DEFAULT 0,
    Hops             TINYINT UNSIGNED NOT NULL DEFAULT 0,
    CreatedAt        BIGINT           NOT NULL DEFAULT 0,
    LastUsedAt       BIGINT           NOT NULL DEFAULT 0,
    PRIMARY KEY (Id),
    KEY bot_about (BotId, AboutCharacterId)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('003-solomapling-bots');
