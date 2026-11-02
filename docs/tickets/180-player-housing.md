# 180: Player housing — architecture and first playable vertical slice

**Status:** idea, later (not 1.6.3; owner liked it 2026-11-02 ~02:40 UTC). Inspiration: MushMS's public housing post on
r/mapleservers (a purchasable town house, a private instanced interior built from existing map assets, live
collaborative editing). The ticket text below is the owner-provided draft, kept as given; scope it before any work.
SlumsMS-specific angle to add when scoping: bots visiting, decorating or running small shops from homes.

## Objective

Build the foundation for account-owned player housing in our server and modified client. The long-term goal is a
private interior players can build using existing map assets: objects, tiles, backgrounds, custom footholds, ladders,
ropes, seats, and music, with live collaborative editing. This ticket delivers the first working vertical slice. Do not
attempt the entire housing feature set at once.

## Inspect before implementing

Inspect the actual server, client modifications, map-loading pipeline, movement validation, persistence, and custom
packet conventions. Identify reusable functionality and relevant files. Treat the suggested architecture below as a
starting point, not a claim about how our code currently works. Choose the implementation that fits the repository.

## Required behavior

1. A designated town doorway or NPC lets an account claim a test property and enter it.
2. Ownership belongs to the account, so its other characters can use the same house.
3. Each property has a stable house ID independent of its interior map template.
4. Different houses using the same template remain isolated.
5. A saved house definition supports: room bounds and a safe entrance/exit; existing WZ objects and tiles with
   position, layer, and flip; custom foothold segments; at least one functional ladder or rope; a background and BGM
   selection.
6. The owner can place, move, and delete an object through a minimal usable in-game editor.
7. Provide a developer-only way to configure geometry for this milestone; a polished foothold-drawing UI is deferred.
8. A second player can visit through an explicit invitation or allowlist and see the same layout.
9. Accepted object edits appear live for visitors.
10. The house survives instance unloading and a server restart.

## Architecture requirements

- Reuse installed WZ assets through validated asset references where practical. Avoid generating and distributing a
  separate WZ map per house unless repository constraints make that necessary.
- Keep persistent house data separate from the live map instance.
- Make the server authoritative for ownership, entry, editing, asset eligibility, placement bounds, and resource limits.
- Give placed pieces stable IDs. Use revisions or an equivalent mechanism to reject stale edits and recover client state.
- Send a complete snapshot on entry and incremental accepted changes afterward.
- Ensure client geometry and server movement validation agree. Rendering a platform without functional collision does
  not satisfy this ticket.
- Define safe behavior for geometry reloads or edits affecting players. For this milestone, geometry edits may require
  an empty room or controlled reload.
- Set configurable limits on dimensions, piece count, and costly assets. Do not claim these guarantee lag-free
  operation without measurements.
- Follow existing packet, database migration, logging, and permission conventions.

## Out of scope

Property resale, regional EXP/drop bonuses, premium currency, furniture packs, purchase ledgers, co-builder editing,
guild permissions, door codes, advanced background controls, and polished editor UI. Keep reasonable extension points
for these features without implementing speculative frameworks.

## Acceptance criteria

- Two accounts enter separate houses using the same template without sharing state.
- Another character on the owning account can access its house.
- An invited visitor sees the saved layout and live object changes.
- Unauthorized entry and editing are rejected server-side.
- Walking, jumping, and climbing work on the custom geometry for both owner and visitor.
- Placing, moving, and deleting an object persist correctly after leaving, instance unloading, and server restart.
- Invalid asset references, out-of-bounds placements, stale edits, and configured limit violations fail cleanly.
- Normal town maps and existing map transitions still work.

Use focused automated checks for persistence, isolation, authorization, and edit validation, plus an in-client smoke
test for rendering and movement.

## Execution and completion

Start with a concise implementation plan grounded in the inspected code, then proceed with implementation. If work
crosses client and server sessions, define the shared data and packet contract first and coordinate both sides. Bugs
preventing these acceptance criteria are part of this ticket; optional enhancements go in follow-up tickets. Deliver the
changed files, migrations/configuration, exact verification steps, and evidence of the playable flow. Clearly
distinguish implemented, tested, and blocked behavior. If client testing is unavailable, report that limitation and
leave the affected acceptance criteria open.

## Ideas from the inspiring post, for later tickets (not this slice)

Town facades per region; a house list NPC with prices; door codes; account-owned house; region EXP/drop bonus;
asset viewer with tiles, footholds, ropes/ladders, seats, objects, nudge/duplicate/flip/layers; layered backgrounds with
parallax; room size up to the largest map with a weight limit; themed asset packs bought with mesos; BGM picker; a house
maid NPC (town teleport, potion shop, storage); public/guild/locked access, kick/ban, co-builders; selling houses with
mesos only. Any paid or premium element needs the owner's economy decision first.
