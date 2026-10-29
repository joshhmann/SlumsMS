-- Family orphan check and repair (1.6.1, b079). Run with the game server STOPPED (it rewrites family rows on save).
-- 1.6.1's boot guard (Family.repairMissingLeader, 3c09a2606) already copes with orphans in memory; this fixes the data.
-- Cause on TEST 2026-10-29: auto-QA t-fy-4 dissolved a family while a bot's join was still being written (fixed in
-- 161-family5: joins and dissolves share one lock, dissolves delete juniors first).

-- CHECK 1 (read-only): juniors whose senior row is missing from their own family. Expect 0 rows.
SELECT j.familyid, j.cid, j.seniorid
FROM family_character j
LEFT JOIN family_character s ON s.cid = j.seniorid AND s.familyid = j.familyid
WHERE j.seniorid > 0 AND s.cid IS NULL;

-- CHECK 2 (read-only): families with no leader row (no member with seniorid <= 0). Expect 0 rows.
SELECT f.familyid
FROM (SELECT DISTINCT familyid FROM family_character) f
WHERE NOT EXISTS (SELECT 1 FROM family_character l WHERE l.familyid = f.familyid AND l.seniorid <= 0);

-- REPAIR (only if CHECK 1 returned rows; run in a transaction, compare with the CHECK output, then COMMIT):
-- each orphan becomes the root (leader) of what is left of its family, which is what the boot guard does in memory.
-- START TRANSACTION;
-- UPDATE family_character j
--   LEFT JOIN family_character s ON s.cid = j.seniorid AND s.familyid = j.familyid
--   SET j.seniorid = 0
--   WHERE j.seniorid > 0 AND s.cid IS NULL;
-- (re-run CHECK 1 and CHECK 2: both 0 rows; a family can now have more than one root only if it had several orphans:
--  staff decide, the boot guard picks the lowest id)
-- COMMIT;
