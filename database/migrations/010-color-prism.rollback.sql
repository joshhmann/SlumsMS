-- Undoes 010: drops the Coloring Prism tint tables, and with them every dyed colour (dump them first if they should
-- be kept). Set server.COLOR_PRISM_ENABLED: false (or remove it) before starting the game again. No other table is
-- touched; applying it twice changes nothing.
DROP TABLE IF EXISTS equip_tints, character_look_tints;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='010-color-prism';
