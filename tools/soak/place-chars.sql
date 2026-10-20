-- Ticket 48: put the load generator's characters (accounts soak_NN, characters soakNN) in the busy towns, on the
-- spawn point LoadGen picks there ("LoadGen homes"). Only soak characters are touched; run while they are logged out.
UPDATE characters c JOIN accounts a ON a.id = c.accountid
SET c.map = ELT(MOD(CAST(SUBSTRING(c.name, 5) AS UNSIGNED) - 1, 10) + 1,
                100000000, 910000000, 101000000, 102000000, 103000000, 104000000, 200000000, 220000000, 105040300, 100000100),
    c.spawnpoint = ELT(MOD(CAST(SUBSTRING(c.name, 5) AS UNSIGNED) - 1, 10) + 1, 8, 1, 0, 4, 0, 0, 4, 4, 5, 10)
WHERE a.name LIKE 'soak\_%' AND c.name LIKE 'soak%' AND a.loggedin = 0;
SELECT c.name, c.map, c.spawnpoint FROM characters c JOIN accounts a ON a.id = c.accountid WHERE a.name LIKE 'soak\_%' ORDER BY c.name;
