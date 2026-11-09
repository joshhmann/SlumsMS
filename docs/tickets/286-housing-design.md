# 286 - Housing v0 design: Kuro + MushMS blend, with partner co-habitation and guild halls

Recorded 2026-11-09. Design only; nothing implemented. Companion tickets: 180 is the owner's "what"
(the personal vertical slice), 284 records the Kuro client RE rig and 180 carries the Kuro/MushMS reference
notes. This ticket is the "how", extended with the owner's additions (2026-11-09): partners can live there
too, and guilds can have a hall.

## Principles

1. **MushMS model for the room**: an instanced interior built from assets we already ship, live collaborative
   editing, no per-house WZ. Our WZ dump has no 87xxxxxxx maps, so v0 uses an existing small town interior as
   the template.
2. **Kuro tricks for the plumbing**: client-side object layers created from a packet, a platform-physics hook
   for placed pieces, foothold-based depth ordering, overlay-WZ art delivery only later and with soft-fail
   validation. Behaviour references only - no bytes, art or code copied.
3. **Server authoritative always**: ownership, ACL, asset eligibility, bounds, limits, revisions; the client
   renders what it is told.
4. **SlumsMS style**: rooms, not mansions; clutter from the world's own assets; bots are the neighbours and the
   customers; the home is a social space and a small shop, not a furniture editor.

## Ownership and access model (extends ticket 180)

House types: `PERSONAL` (account-owned), `SHARED` (personal with co-owners), `GUILD` (guild-owned).

Tables (migration proposed, conventions to be checked against the repo):

- `houses(id, owner_type, owner_id, template_map_id, revision, upkeep_paid_until, flags)`
- `house_acl(house_id, subject_type(account|guild_rank|character), subject_id, role(owner|editor|visitor|staff))`
- `house_pieces(house_id, uid, asset_kind, asset_id, layer, x, y, flip, z, placed_by, piece_rev, locked_by)`
- `house_visits(house_id, visitor_cid, at)` for the visitor book and neighbour score

One server-side `HouseAcl.can(subject, house, action)` for `enter|edit|lock|manage|invite`; every entry and
edit goes through it. Unauthorized entry/edit is rejected server-side (ticket 180 acceptance).

## Layout contract (define before any code, ticket 180's rule)

Packets: `HOUSE_SNAPSHOT` (full layout + revision), `HOUSE_EDIT` (add/move/delete/attach piece, with expected
revision), `HOUSE_REJECT` (reason + current revision, client resyncs), `HOUSE_ACL` (role changes),
`HOUSE_VISITOR_JOIN/LEAVE`.

Layout document: `{ houseId, templateMapId, revision, bgm, bg, pieces[], footholds[], ropes[], seats[] }`.

Concurrency: per-piece `piece_rev` + `locked_by`; whole-house edit lock only for bulk moves; stale edits are
rejected, never merged blindly. Two editors cannot move the same piece.

## The client risk (Phase 0 spike, fail-before)

v83 renders only what the map's WZ declares, so `slums.dll` must create client-side object layers at runtime
from `(assetId, x, y, layer)` - the same shape Kuro's `CreateLayer failed for piece uid=... furniture=...`
messages show - plus a platform-physics hook so a placed chair can be stood on, and foothold-based depth
ordering so overlapping pieces draw correctly. Fail-before: with the unpatched DLL the object never appears;
with the patch it appears for a second client and is walkable. Crash on an invalid asset must fail the edit,
not the client.

## Partner co-habitation (owner request 2026-11-09)

- Marriage link grants the partner `owner` role on the other's house via an explicit "move in" action; both
  accounts can enter and edit; one shared home (if both have rooms, one becomes the couple's home, the other
  stays personal).
- Divorce / move-out: the house stays with whoever owns it; the leaving partner's `placed_by` pieces become
  reclaimable as furniture items during a 30-day grace, then revert to the owner. Itemisation is recommended
  because the project's no-loss rule says players must not lose what they own - but it is an economy decision
  (see questions).
- Optional and cheap: an account's owned alt/companion can be set as "live-in" and idles in the house when
  not in use - cosmetic, ties to the companion systems.

## Guild halls (owner request 2026-11-09)

- One hall per guild, bought by the guild leader from guild funds and kept with a periodic upkeep (amounts are
  owner decisions). Empty/frozen hall if upkeep lapses; pieces persist.
- Roles by guild rank: master/officers `editor`, members `visitor`, allies never. Same `HouseAcl` path.
- Facilities: meeting hall, guild storage chest, boss-planning board, a guild house maid NPC
  (potion/teleport/storage), banners/motifs; capacity limit.
- Public-open flag for recruitment and a guild-house leaderboard entry (the site already has boards).
- Bot guilds: later phase - bot guild members using halls for meetups would tie into the bot social systems.

## Phases

| Phase | Content | Size | Gate |
|---|---|---|---|
| 0 | Spike: packet + object-layer + physics + depth; one object, two clients, walkable | S-M | crash-safe on bad asset |
| 1 | Personal slice (ticket 180): claim/enter/exit, command editor, snapshot+increments, isolation, visitor allowlist, persistence, server validation, geometry movement | L | ticket 180 acceptance |
| 1.5 | Partner co-habitation: move-in/out, shared edits, reclaim-on-leave, ACL tests | S | 1 green |
| 2 | Guild halls: purchase/upkeep, roles, storage/board/maid, capacity, leaderboard | M-L | 1.5 green, economy decisions |
| 3 | Optional Kuro-tier: `SlumsHousing.wz` overlay for art beyond stock, namespace validation + soft-fail, layered depth | M-L | only if art is needed |

## SlumsMS flavour (what makes it ours, not a generic editor)

- A rented room in town: one doorway NPC, monthly meso rent (decision), eviction when unpaid.
- Furniture from the game's own clutter: chairs, barrels, plants, lamps, signs.
- Bots are the neighbours: they wander in when the door is open, sit, emote and *talk about the decor* using
  the existing persona/chat systems; visitor book and neighbour score.
- The home is a stall: a house can host a small bot-facing shop, tying housing into the bot economy.

## What we take and do not take

- Take: MushMS's instanced-from-existing-assets model and live editing; Kuro's object layers, physics hook,
  depth ordering, overlay-art validation/soft-fail.
- Do not take: Kuro's 68 MB WZ, their map 871100001 (not in our data), their protocol layout, or any bytes.


## MushMS client inspection (2026-11-09)

Checked the installed client at Silvia `Z:\MSv83\Client_Sources\Mush_Beta_V0.1` read-only. Findings:

- **`MushMS.exe` is byte-identical to `Kuro.exe`** - sha256 `1198fa57ca5a7c489bae43ec13c69681d9cabe0f96762f3dc0357facf2e7d4df`,
  8,503,296 B, PE timestamp 2010-02-17. Both are the stock v83 exe plus the same four-byte patch at
  VA ~0x796F57. That patch is shared client infrastructure for these two servers, not a housing or job feature.
  Our own stock exe stays unpatched; `slums.dll` does not need it.
- The client mod is `mushcore.dll` (4,988,944 B, built 2026-06-13) and it is **Themida-protected**: sections
  `.themida` and a ~4.76 MB `.boot` payload, an unreadable export table, effectively no readable feature strings,
  and a bcrypt import that belongs to the protector. **Hard stop: do not attempt to read or defeat it** - their
  layer is protected IP and our clean-room rule already says behaviour references only.
- Their data is a **split-WZ v83 tree**: `Data/{Character,Map,...}`, 7,291 files under `Data/Map`, with Map8
  holding 461 maps from `800000000` to `889100100`. There is **no `871100001`** (Kuro's house map) and no other
  `87xxxxxxx` map. So MushMS housing is server/instance driven on existing assets - matching their public
  description and ticket 180's model - not a Kuro-style WZ overlay.

Consequence for this design: Kuro remains the only *readable* example of the client-side dynamic-object and
physics mechanism, so the Phase 0 spike stays the gate. MushMS confirms the asset-reuse, no-per-house-WZ model;
its own client trick, if any, is hidden behind Themida and is out of bounds.

## Questions for the owner (decisions)

1. Rent/upkeep for personal rooms - yes/no, and rough amounts?
2. Guild hall price and upkeep source (guild funds?), plus one-hall-per-guild and capacity.
3. Itemise furniture on move-out/divorce (recommended) or let it stay?
4. Co-owner limit (one partner? two?) and whether both keep a personal room.
5. Bot "live-in" companions: in scope for 1.5 or later?
6. Do we ever want the Kuro-tier custom-art overlay, or is stock-asset clutter enough?

## Acceptance additions to ticket 180

- Partner sees live edits and can edit; a non-partner cannot.
- Guild officer edits; ordinary member cannot; member and ally entry rules enforced.
- Divorce/move-out reclaim works; no piece is lost.
- Stale edit rejected with a resync; two editors cannot move one piece.
- Upkeep lapse freezes the hall without deleting pieces.