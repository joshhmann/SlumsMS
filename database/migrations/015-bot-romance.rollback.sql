-- Stop the game first and take a backup. Removes what 015 added: every player-bot romance and every @romance opt-in.
-- A player engaged or married to a bot is set free the stock way, so an older jar (which cannot name a bot spouse) still
-- logs them in: their marriages row and both rings rows go, their ring items become plain rings, their partner fields
-- reset. Engagement tokens stay in their inventory as plain items. Applying it twice changes nothing.
UPDATE inventoryequipment SET ringid = -1 WHERE ringid IN (SELECT id FROM (SELECT id FROM rings WHERE partnerChrId >= 1000000000) x);
DELETE FROM rings WHERE partnerRingId IN (SELECT id FROM (SELECT id FROM rings WHERE partnerChrId >= 1000000000) x);
DELETE FROM rings WHERE partnerChrId >= 1000000000;
DELETE FROM marriages WHERE husbandid >= 1000000000 OR wifeid >= 1000000000;
UPDATE characters SET partnerId = -1, marriageItemId = -1 WHERE partnerId >= 1000000000;
DROP TABLE IF EXISTS botromance;
DROP TABLE IF EXISTS botromanceopt;

DELETE FROM mapleroot_integration_migrations WHERE migration_id='015-bot-romance';
