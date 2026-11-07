# bot-offmap-frozen (1): a map stops showing characters that are no longer on it

Written before the code (1.6.6, card bot-offmap-frozen, part 1).

## What was seen (B-24, TEST c614ea6f7, 2026-11-07)

The owner entered Henesys Hunting Ground I (104040000) and was sent SPAWN_PLAYER for two regulars, pwnkiller (595)
and CloudPunk (314), at (5034, -116). That is a Henesys town spot, outside 104040000. pwnkiller was also sent on the
Free Market Entrance (910000000) at the same spot, but not on Henesys itself. Neither sent a single movement packet
all day, and their last saves (12:18 and 14:32 UTC) look like logouts. So both were stale entries in other maps'
`mapobjects`: still in a map's object list after leaving its `characters`.

`MapleMap.sendObjectPlacement` sends every Character in the `mapobjects` snapshot. It never checks that the character
is still in `characters`, so one stale entry is shown, frozen, to every player who enters that map until reboot.
The race that leaves the entry behind is part 2 of the card. It predates 1.6.5; b350 (c054ff8a5) shortened that
window.

## Expected outcome

1. When a player enters a map, any Character in its `mapobjects` that is not in its `characters` is not sent, and is
   removed from `mapobjects` (only if that key still holds the same object).
2. Each one dropped is logged once at INFO:
   `ghost map object dropped: map=<id> char=<id> kind=<bot|player>`. On live 1.6.6 this gives the rate.
3. A Character still in the map's lists whose own map is another one (what `BotGeneration.placeBotOnMap` leaves
   when it warps a bot elsewhere: it only removes the bot from the map it lands on) is not sent either. It stays in
   the lists, because removing it would run leave-map logic against its real map. Logged once per map and
   character: `ghost map object skipped: map=<id> char=<id> kind=<bot|player> realMap=<id>`.
4. This holds for a ghost regular and for a ghost real player who logged out. It holds with the entry trickle on
   and off.
5. Nothing else changes. Characters really on the map are sent exactly as before, once per entry (the b350 tests
   still pass), and the entrant itself is still skipped.

## Tests (fail before, pass after)

`server.maps.GhostMapObjectTest`, using `MapEntryHarness`:
- a regular that left `characters` but stays in `mapobjects` is not sent to a player entering, with the trickle
  off and on, and it is gone from `mapobjects` afterwards;
- the same for a real player who logged out;
- a regular still on the map is still sent exactly once;
- a regular warped to another map but still listed here is not sent, and is logged once over two entries.

Mutation checks: with the not-in-characters skip removed, the 6 ghost tests fail; with the other-map skip removed,
the 2 warped-elsewhere tests fail.
