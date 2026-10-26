-- SlumsMS 1.6.0 (slice S5, the social gate): who started it, and each player's bot-noise choices kept across restarts.
-- Adds two tables; no existing table, column or row changes, so an older jar runs fine on a migrated database (it just
-- ignores them). Stop the game and take a backup first, as for every migration here. Applying it twice changes nothing
-- (ledger id '031-social-gate'). Without it the server still runs: both then live in memory only and are forgotten at
-- a restart (as before 1.6.0).
--
-- botsocialopt: one row per character that chose something: @quiet megaphones (QuietMegaphones), @quiet bots
-- (QuietBots: also no unsolicited bot whispers, party/guild invites or buddy requests) and an explicit @romance off
-- (RomanceOff: no bot flirts even after the player flirted first). No row = the defaults (all 0).
--
-- botstartedit: the "started it" ledger. One row per bot and real player where the player started something with
-- that bot: Spice is the weight of their provocations (a KS, a stolen drop, a scam, a roast, a defame; 1 each, at
-- most 4) at SpiceAt, Flirt the weight of their flirts at FlirtAt (epoch ms). The server halves both every two weeks
-- from those times; a bot may spice back while Spice is at least 0.5. LastKind is the latest provocation (ks, steal,
-- scam, roast, defame, staged). BotId is botstore.BotId (character id = 1000000000 + BotId). At most a few dozen rows
-- per player; rows that faded to nothing are deleted at boot.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS botsocialopt
(
    CharacterId     INT UNSIGNED NOT NULL PRIMARY KEY,
    QuietMegaphones TINYINT(1)   NOT NULL DEFAULT 0,
    QuietBots       TINYINT(1)   NOT NULL DEFAULT 0,
    RomanceOff      TINYINT(1)   NOT NULL DEFAULT 0,
    UpdatedAt       BIGINT       NOT NULL DEFAULT 0
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS botstartedit
(
    CharacterId INT UNSIGNED NOT NULL,
    BotId       INT UNSIGNED NOT NULL,
    Spice       FLOAT        NOT NULL DEFAULT 0,
    SpiceAt     BIGINT       NOT NULL DEFAULT 0,
    Flirt       FLOAT        NOT NULL DEFAULT 0,
    FlirtAt     BIGINT       NOT NULL DEFAULT 0,
    LastKind    VARCHAR(8)   NOT NULL DEFAULT '',
    PRIMARY KEY (CharacterId, BotId)
) ENGINE = InnoDB;

-- A bot that already holds a grudge from before 1.6.0 had it started by the player (only a player's KS, stolen drop,
-- defame or rude line raises the Hostile count), so it keeps its right to spice back: one row per such tie, weight
-- = the hostile events (at most 4), dated when the tie last changed. The defame-claim row (BotId 4000000000) is not
-- a tie and is skipped.
-- Only on the first apply (a second apply finds the ledger row and copies nothing), and only when botaffinity exists.
SET @seed = NOT EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id = '031-social-gate')
    AND EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botaffinity');
SET @dml = IF(@seed, 'INSERT IGNORE INTO botstartedit (CharacterId, BotId, Spice, SpiceAt, Flirt, FlirtAt, LastKind)
 SELECT CharacterId, BotId, LEAST(Hostile, 4), UpdatedAt, 0, 0, ''grudge'' FROM botaffinity
 WHERE Hostile > 0 AND BotId < 4000000000', 'DO 0');
PREPARE seed_started_it FROM @dml;
EXECUTE seed_started_it;
DEALLOCATE PREPARE seed_started_it;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('031-social-gate');
