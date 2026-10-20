-- Stop the game first and take a backup. Undoes 008: every row keyed by world goes from 1 (Bera) back to 0 (Scania),
-- including characters created in Bera after the deploy, for the 1.3.0 jar and its config (world 0 only).
-- Only runs while the ledger holds 008; applying it twice changes nothing.
SET @applied = EXISTS (SELECT 1 FROM mapleroot_integration_migrations WHERE migration_id='008-world-bera');

START TRANSACTION;
UPDATE characters SET world = 0 WHERE @applied AND world = 1;
UPDATE storages s LEFT JOIN storages t ON t.accountid = s.accountid AND t.world = 0
  SET s.world = 0 WHERE @applied AND s.world = 1 AND t.storageid IS NULL;
UPDATE playernpcs SET world = 0 WHERE @applied AND world = 1;
UPDATE playernpcs_field SET world = 0 WHERE @applied AND world = 1;
UPDATE plife SET world = 0 WHERE @applied AND world = 1;
UPDATE worldtransfers SET `from` = 0 WHERE @applied AND `from` = 1;
UPDATE worldtransfers SET `to` = 0 WHERE @applied AND `to` = 1;
DELETE FROM mapleroot_integration_migrations WHERE migration_id='008-world-bera';
COMMIT;
