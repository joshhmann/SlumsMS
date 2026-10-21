-- Coloring Prism (slums.dll MAPLEROOT_COLOR_PRISM, item 5782000): equip, hair and face tints. Adds two tables; no
-- existing table, column or row changes, so older jars keep working on a migrated database (they just ignore the
-- tints, and the foreign keys below drop a tint when an older jar rewrites the item rows). Stop the game and take a
-- backup first, as for every migration here. Applying it twice changes nothing (ledger id '010-color-prism').
-- Then set server.COLOR_PRISM_ENABLED: true in config.yaml; with the flag on but this migration missing the server
-- logs an error and keeps the feature off.
--
-- Tint encoding (server.colorprism.Tint): hue is SIGNED, -360..-1 for absolute hue 0..359 (stored as -(h+1));
-- chroma and bright are -100..100; 0/0/0 is untinted. The server writes rows only for tinted values and reads every
-- row through setters that reject anything else.
CREATE TABLE IF NOT EXISTS mapleroot_integration_migrations (
  migration_id varchar(100) PRIMARY KEY,
  applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- One row per tinted equip item row. ItemFactory deletes and re-inserts a character's item rows on every save and
-- writes these right after, in the same transaction; the cascade removes them with their item row.
CREATE TABLE IF NOT EXISTS equip_tints
(
    inventoryitemid INT UNSIGNED NOT NULL,
    tint_hue        SMALLINT     NOT NULL DEFAULT 0,  -- body layer
    tint_chroma     TINYINT      NOT NULL DEFAULT 0,
    tint_bright     TINYINT      NOT NULL DEFAULT 0,
    fx_hue          SMALLINT     NOT NULL DEFAULT 0,  -- effect layer (glows, auras)
    fx_chroma       TINYINT      NOT NULL DEFAULT 0,
    fx_bright       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (inventoryitemid),
    CONSTRAINT equip_tints_item FOREIGN KEY (inventoryitemid) REFERENCES inventoryitems (inventoryitemid) ON DELETE CASCADE
) ENGINE = InnoDB;

-- One row per character whose hair or eyes are tinted.
CREATE TABLE IF NOT EXISTS character_look_tints
(
    characterid INT      NOT NULL,
    hair_hue    SMALLINT NOT NULL DEFAULT 0,
    hair_chroma TINYINT  NOT NULL DEFAULT 0,
    hair_bright TINYINT  NOT NULL DEFAULT 0,
    face_hue    SMALLINT NOT NULL DEFAULT 0,
    face_chroma TINYINT  NOT NULL DEFAULT 0,
    face_bright TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (characterid),
    CONSTRAINT character_look_tints_character FOREIGN KEY (characterid) REFERENCES characters (id) ON DELETE CASCADE
) ENGINE = InnoDB;

INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('010-color-prism');
