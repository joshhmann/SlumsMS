# id-oid-overlap: map object ids start above every character id (1.6.6, S)

Written before the code. Coordinator's go 2026-11-07 22:3x UTC.

## The overlap

A map's `mapobjects` is keyed by object id. A character's object id is its character id
(`Character.getObjectId() == getId()`). Every other map object (mob, drop, reactor, NPC) gets the next id from the
map's own counter, `runningOid`. That counter started at 1,000,000,001 and wrapped back there at 2,147,000,000.
The early roster regulars have character ids 1,000,000,100 to 1,000,610,534 (BotStore's on-demand and plan slots).
So on any map that has spawned a few hundred objects, a mob or drop can carry the same number as a regular.

`IdOidOverlapTest` (characterisation, c42a85edf) showed what that does. No order leaves a ghost, so this is not the
B-24 path. But it does break other things:
- object first, then the regular enters: the regular's entry overwrites the live object, giving an **invisible mob**
  (players entering never get it);
- object first, the regular enters, then the object dies: the death removes the regular's entry, giving a **lost
  character** (missing for anyone entering until it re-enters).

TEST, 2026-11-07: about 24% of the regulars active in the last day have ids in that range.

## Expected outcome

1. `MapleMap.FIRST_OID = 1_100_000_001`. `runningOid` starts just below it, and the wrap at 2,147,000,000 goes back to
   it, so no map object id is ever below the floor.
2. Every character id is below the floor. Players are under 1,000,000,000. Bots are `BOT_ID_BASE + slot`, and the
   highest slot BotStore can hand out today is the roster block from `FIRST_ROSTER_SLOT` (4,268,132) up. A stated
   cap, `BotStore.ROSTER_SLOT_CAP` = 10,000,000 roster regulars (TEST has about 9,100), puts the highest possible bot
   id at 1,014,268,131, under the floor. A test fails if BotStore's layout plus that cap reaches the floor. The cap is
   a stated bound, not enforced at enroll (TEST is at about 0.1% of it).
3. The two broken orders now work: a map that has spawned 600 objects before regular 1,000,000,595 enters keeps
   every object and the regular under their own keys, and the objects dying leaves the regular in place.
4. Nothing else assumes 1,000,000,001. Checked 2026-11-07 on c42a85edf:
   - the only literal is MapleMap's own counter;
   - player NPCs (≥ 2,147,000,000) and developer NPCs (≥ 2,147,483,000) are above the floor and the wrap;
   - every `BOT_BASE_ID` range check (`BotHelpers.isArtificial`, GuideMenu, StarterChairs, GameFishing,
     PlayerWealthIndex) takes a character id, never an object id, and object ids stay above 1e9 either way.
   Seen while checking, not changed: party ids also start at 1,000,000,001 (`World.runningPartyId`, "must not clash
   with charid"), and they now overlap the low bot ids. That is a separate question for the Coordinator.

## Party ids (checked 2026-11-08, read-only; Coordinator's question closed)

Party ids are only ever compared with party ids (MapleMap's `mapParty`, `getAnyCharacterFromParty`,
`MapItem.canBePickedBy`), except in one place: the drop packet's single owner field (PacketCreator.java:1824,
`MapItem.getClientsideOwnerId`), which carries a char id or a party id, and which the client compares with both
its own. A regular's own drop can therefore look like a partied player's party drop when that party's id equals the
regular's char id; the server still refuses the loot, so nothing is lost. Card b368 (1.6.7, S4): start party ids
above the highest possible bot id.
