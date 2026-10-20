-- SlumsMS ticket 43: GM safety. Adds two tables; no existing row or value is changed. Safe to apply with the game
-- stopped (take a fresh backup first, as for every migration here). Applying it twice changes nothing. Without it the
-- server still runs: GM commands then only reach the server log, and the writer warns that the table is missing.
--
-- gm_command_audit: one row per GM command (the server writes it off the game thread, client.command.GmAudit).
--   outcome is ok, denied (the character's GM level is below the command's rank) or error (the command threw).
--   Tunables are JVM properties, not config.yaml keys: -Dslumsms.gmAudit=false, -Dslumsms.gmAudit.queue=N.
-- gm_level_changes: one row per GM level change made with the slumsms-gm script (who ran it, from where, old and
--   new level per account). The script also creates this table if it is missing, with the same definition.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS gm_command_audit
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    at             DATETIME(3)     NOT NULL COMMENT 'UTC',
    account_id     INT             NOT NULL,
    account_name   VARCHAR(64)     NOT NULL DEFAULT '',
    character_id   INT             NOT NULL,
    character_name VARCHAR(64)     NOT NULL DEFAULT '',
    gm_level       TINYINT         NOT NULL,
    command        VARCHAR(64)     NOT NULL,
    args           VARCHAR(512)    NOT NULL DEFAULT '',
    command_rank   TINYINT         NOT NULL DEFAULT 0,
    map_id         INT             NOT NULL DEFAULT 0,
    channel        TINYINT         NOT NULL DEFAULT 0,
    ip             VARCHAR(64)     NOT NULL DEFAULT '',
    outcome        VARCHAR(16)     NOT NULL DEFAULT 'ok',
    KEY gm_command_audit_at (at),
    KEY gm_command_audit_character (character_id, at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS gm_level_changes
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    at           DATETIME(3)     NOT NULL COMMENT 'UTC',
    account_id   INT             NOT NULL,
    account_name VARCHAR(64)     NOT NULL,
    old_levels   VARCHAR(255)    NOT NULL DEFAULT '',
    new_level    TINYINT         NOT NULL,
    characters   INT             NOT NULL DEFAULT 0,
    changed_by   VARCHAR(128)    NOT NULL DEFAULT '',
    note         VARCHAR(255)    NOT NULL DEFAULT '',
    KEY gm_level_changes_at (at)
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('009-gm-audit');
