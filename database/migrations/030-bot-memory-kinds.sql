-- SlumsMS 1.6.0 (slice S3, memory kinds): what a bot remembers about a player gets a kind, a weight and a follow-up
-- tag, so bots write their own memories without the LLM ("did KPQ together", "traded me a claw", "scammed me on a
-- claw trade"), keep a last-topic and an inside-joke slot per pair, and forget old notes nobody uses. Adds four columns
-- to botmemory, all with defaults, so every existing row stays valid and an older jar runs fine on a migrated
-- database (it never names them). Stop the game and take a backup first, as for every migration here. Applying it
-- twice changes nothing (ledger id '030-bot-memory-kinds'). Without it the server still runs: new notes are stored
-- as plain notes and the two slots live in memory only.
--
-- Kind:         NOTE (a chat note, the default for every older row), FEELING (the note a bot writes when its feelings
--               change), an episode (PQ, BOSS, PARTY, HELP, FAME, TRADE, GIFT, FAVOR, SCAM), GOAL (a player's goal or
--               wanted item), or a slot (TOPIC: what the pair last talked about; JOKE: an inside joke; one each).
-- Weight:       how much the note matters, 1..10 (a scam 9, a PQ 6, a buff 2); older rows get 1.
-- Tag:          a follow-up tag on goal and item notes ("item:zhelm", "goal:4th job"); '' when none.
-- FollowedUpAt: when a bot followed the tag up (epoch ms); 0 = not yet.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- MySQL has no ADD COLUMN IF NOT EXISTS, so each column is added only when it is missing.
SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Kind');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN Kind VARCHAR(8) NOT NULL DEFAULT ''NOTE''', 'DO 0');
PREPARE add_kind FROM @ddl;
EXECUTE add_kind;
DEALLOCATE PREPARE add_kind;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Weight');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN Weight TINYINT UNSIGNED NOT NULL DEFAULT 1', 'DO 0');
PREPARE add_weight FROM @ddl;
EXECUTE add_weight;
DEALLOCATE PREPARE add_weight;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'Tag');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN Tag VARCHAR(48) NOT NULL DEFAULT ''''', 'DO 0');
PREPARE add_tag FROM @ddl;
EXECUTE add_tag;
DEALLOCATE PREPARE add_tag;

SET @missing = NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'botmemory' AND COLUMN_NAME = 'FollowedUpAt');
SET @ddl = IF(@missing, 'ALTER TABLE botmemory ADD COLUMN FollowedUpAt BIGINT NOT NULL DEFAULT 0', 'DO 0');
PREPARE add_followed FROM @ddl;
EXECUTE add_followed;
DEALLOCATE PREPARE add_followed;

-- Backfill: the notes bots wrote when their feelings changed (BotAffinity.NOTE_*) become FEELING notes. Only rows still
-- at the default kind are touched, so a second apply finds nothing to change.
UPDATE botmemory SET Kind = 'FEELING', Weight = 5
WHERE Kind = 'NOTE' AND Source = 'DIRECT' AND Note IN ('is a good friend, always nice to me', 'keeps ksing my mobs',
    'took my drops', 'defamed me for no reason', 'was rude to me', 'we have beef', 'my nemesis, cant stand them');

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('030-bot-memory-kinds');
