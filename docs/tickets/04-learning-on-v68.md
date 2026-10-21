# 04: Carry nav learning onto the new edge types

**Area:** Nav v2  
**Depends on:** 01  
**Status:** done 2026-10-19 on branch `nav-learning` (includes `nav-ops`); merged into release 1.3.0 (live). Follow-up 2026-10-21 on branch
`nav-player-learning` (from `test-rc4`): why live learned nothing from players, fixes, diagnostics, kept data, replay
tool (see the last section)

## What

NavEdgeLearning keys and NavObservedEdges overlays must cover the new edge types (teleport and flash-jump windows, rope Y windows).

## Done when

Failure penalties and player-learned jumps work on v68 graphs; tests.

## Result

### Failure learning (`NavEdgeLearning`)

- **Keys:** one key format for every v68 edge type: type, regions, X and Y launch windows, launch step, start and
  landing.
  - Left and up teleports out of one window get separate keys.
  - So do a running and a standing jump, a flash jump and a plain jump, and rope exits that differ only in their
    climb-height window.
  - Files carry `"format": 2`. A format-1 file (old v68 keys) is still read on its own graph.
- **Merged walk edges:** a plan that starts with zero-length walks is committed as one merged edge
  (`collapseLeadingWalkEdges`). Its outcomes now count against the graph's own edge, the one the planner asks about.
  Before this they went to a key nobody read.
- **New outcomes:**
  - **Teleports** count when they land. They used to clear the plan and count nothing.
  - **Launched moves** (jump, flash jump, drop, rope jump) that come down in a third region count as a miss.
  - A mob hit during the flight voids that verdict (the i-frame countdown going up marks the hit).
  - `NavEdgeLearning.observe` now owns the success/miss check that `resolveTarget` used to do inline.
- **Only genuine failures count** (`Cause`):
  - A skill edge the bot's job (or the map's MOVEMENTSKILLS limit) does not allow is `SKILL_UNAVAILABLE` and is
    ignored.
  - A deliberate flaw is `DELIBERATE_FLAW` and is ignored. Both are counted in the GM read-out.
- **Graph version bumps no longer wipe learning:**
  - A file from an older version is migrated: each learned move goes to the new graph's edge with the same type and
    direction, overlapping launch window (within 12 px; for rope moves, the same rope and overlapping climb heights)
    and a landing within 40 x 16 px.
  - Region ids may be renumbered.
  - Moves with no match are dropped. Two moves that land on one new edge are added up.
- **Scale fix:** each graph's book is now cached on the graph (`BotNavigationGraph.learningBook`, transient). Before
  this every planner edge relaxation went through one global `synchronizedMap`. Behaviour is the same.
  - Measured with 32 threads calling `factor()` on a 1,000-edge graph: 15 M lookups/s before, 720 M–1.5 G after.

### Ticket 33 hook

- `NavEdgeLearning.tagDeliberateFlaw(entry, edge)`: call this before executing a fumble. The edge's next failure is
  ignored, whether it is a give-up, an unstuck hop or a landing in the wrong place. The tag is spent by that edge's
  next outcome.
- `NavEdgeLearning.deliberateFailure(graph, edge)`: reports a flaw after the fact.
- Test: `NavEdgeLearningTest.aDeliberateFlawIsNeverLearnedAndOnlyGenuineFailuresRaiseTheCost`.

### Learning from players (`NavObservedEdges`)

- **Capture:**
  - Absolute moves now pass their stance, so a rope or ladder stance counts as climbing.
  - Teleport fragments (move types 3 and 4) and flash-jump fragments (type 6) are reported through
    `onPlayerSkillMove`. They are counted only from a player whose job has that skill: teleport for F/P, I/L and
    Cleric lines and Blaze Wizard; flash jump for Hermit and Night Walker.
- **What is learned:**
  - Plain jumps, as before.
  - Flash jumps and teleports between platforms.
  - Rope moves: jump-grabs, jump-offs and rope-to-rope transfers.
- **Proof before adoption:** each move is re-simulated with the bots' own physics and skills before adoption:
  - `simulateFlashJumpLanding` for flash jumps;
  - `teleportLanding` with the graph's range and snap for teleports;
  - the rope grab and rope jump sims for rope moves.
  - Every move gets the v67 phantom-landing guard.
- **Edges:** adopted edges have the graph's own shapes (a rope launch is a one-height Y window).
  - A flash jump that a plain jump also makes is adopted as a plain jump, so every bot can use it.
  - Skill edges are planned only for jobs that have the skill, through the same `isEdgeUsable` gate as the graph's
    own.
- **Version bumps:** the overlay file now stores the type and which ends are ropes. On a newer graph, each learned
  move whose ends still lie on a platform or rope is kept, re-keyed to the new region ids; one the new graph already
  makes is dropped.
- **Cache directory:** both learned directories now honour `bot.nav.cacheDir`.

### Tests

- `NavEdgeLearningTest`: 10 tests.
  - Every v68 type keyed apart.
  - A merged walk edge counts against the real move.
  - Job-gated failures ignored.
  - A miss in a third region counts, unless a hit caused it.
  - Migration across a renumbering version bump, and dropped when the landing moved.
  - A format-1 file is still read.
  - Deliberate flaws.
- `NavObservedEdgesTest`: 7 tests. The physics tests need `wz/` (`-Dwz-path=`).
  - Capture tells apart flash jumps, teleports, jump-grabs, jump-offs and transfers, and applies job gating.
  - On real town maps, a teleport, a flash jump, a rope jump-off and a jump-grab each:
    - are removed from the graph and re-learned from two sightings with their own type;
    - are planned only for the right jobs;
    - carry over a version bump;
    - are dropped when the new graph has the move itself.
- Full suite: 2,572 tests, 0 failures. With `wz/`, all 73 GCMoveSystem tests pass.

### Not done

- No headless soak on 164. It is covered by the physics tests above.
- Swim edges (ticket 34) will plug into the same `Cause` / `observe` path when they exist.

## Follow-up 2026-10-21: why live learned nothing from players (branch `nav-player-learning`)

Live had two real players (Henesys hunting ground 104040000, Kerning, Perion, FM) and wrote player heat for 53 maps,
but `cache/bot-nav/observed/` did not exist and the journal never said "Bots learned a ... from players". Nothing
counted why, and sightings were lost at every restart.

### What the data says

The repo ships real recorded movement packets (the GM recorder, `BotMovementSystem/movementDataPackets/map<id>/*.csv`:
1,768 moves on 23 maps). `NavPlayerReplay --csv` runs them through the live capture path against today's graphs:

- **The fh ids are the same id space** as the server's footholds (`regionIdByFootholdId`), and the takeoff is
  exact. A grounded absolute element carries the WZ foothold id. Each element's position is where the state changed,
  and its duration is the time since the previous one. So the last grounded element before the air is the takeoff
  point: back-solving the first airborne element with v83 jump physics (555 px/s, 2000 px/s²) lands on it to the
  pixel. Climbing elements carry the rope as a **negative** fh (for example -5 with a rope stance), not 0.
- **On a baked hunting ground every move is one the graph already has.** On 104040000, 101 of 106 moves are known
  and 5 are hops on one platform. Nothing is new. On all 23 maps: 1,651 known, 31 hops, 0 new moves seen twice that
  a bot's own tier graph lacks. Zero adoptions is mostly legitimate: low-level maps are well baked.

### Real bugs found (each would have blocked a genuine new move)

1. **No graph, no count.** `consider()` used only an in-memory base graph (`peekGraph(map, base)`). No bot has a
   100/100 profile (the lowest level tier is 115 speed), so the base graph is in memory only where something asked
   for it (spot finding, ledges). Every move on any other map was dropped silently. Now the base graph is loaded
   from the disk cache, never built (`loadCachedGraphAsync`). The move waits (up to 64 per map) and is looked at
   once the graph is there. A map with no cached graph counts as `NO_GRAPH`.
2. **Proof at the base profile only.** Players are faster than 100/100: recorded walking is 138-145 px/s. A jump
   needing more speed or jump failed every time, even though every bot has more. Now each move is re-simulated at
   the base profile and at each bot tier (115/100, 125/105, 130/110, 135/115, 145/120, Haste 155/123). The edge is
   stored with a tier mask and planned only on graphs of a tier that proved it. Faster is not automatically safe:
   graph edge counts are not monotonic in speed, because faster bots overshoot. A tier whose own graph already makes
   the move is dropped from the mask. A move every tier's graph makes counts as known.
3. **Walk-offs were proven as jumps.** The client reports walking off a ledge exactly like a jump: ground, then air
   (fh 0), then other ground. The proof simulated an upward jump from the ledge end, which never lands where the
   fall did. On 104040000, 9 of 85 recorded ground moves failed this way. A takeoff within 10 px of the region's
   end, landing lower, is now also tried as the graph's own directional walk-off (`DROP` with a step, same runway
   and caps as `addDirectionalDropEdge`). All 85 now re-prove.
4. **Down-jumps were not captured.** Move type 15 (fall-down) is now reported (`onPlayerDownJump`). It is proven
   with `simulateDownJumpLanding` and becomes a straight `DROP` edge with the graph's down-jump cap and cost penalty.
5. **Rope ends.** Climbing off a rope's top or bottom reports a point a few pixels past the rope. Before, these were
   unknown regions: 11 of 106 moves on 104040000. A rope end now counts within 12 px.
6. **Foothold ids were trusted blindly.** Each end is now checked: the reported foothold's region must be under the
   reported position (6 px). If it is not, the end is found by position and counted as `fhMismatch`. If the
   MapleRoot client's data numbers a map's footholds differently from the server's wz, this shows it. The repo's
   own Henesys recordings come from an older Henesys and show 203 such moves, all resolved.
7. **Sighting key too narrow.** "Seen twice from the same 20 px spot" almost never happened twice. Now a move counts
   per map, type, regions and direction; the proof re-simulates the latest takeoff. Sightings are saved (see below).
8. **The dashboard never showed player-learned edges.** `NavDashboardData.observedEdges` read only the pre-ticket-04
   8-field format. It now reads every format through `NavObservedEdges.storedEdges`, gated by tier.

Overlay files gain a 12th field (the tier mask). 11-field and 8-field files still load, applying to every tier as
before.

### Diagnostics (`NavPlayerLearning`)

- **Outcomes.** Every captured move gets one of `NO_MAP`, `NO_GRAPH`, `ROPE_SKILL`, `UNKNOWN_REGION`,
  `SAME_REGION`, `ALREADY_JOINED`, `SIGHTED`, `PROVE_FAILED` or `ADOPTED`. Counts are kept per map and per UTC day,
  plus `fhMismatch` and lines dropped by the caps.
- **Cost.** The packet thread only updates the per-player capture state, as before. Everything else runs on the
  async executor or the scheduler.
- **Hourly INFO line**, only when something changed, for example: `Players' moves in the last hour: 42 (30 known,
  8 same platform, 4 new, counting). Since 2026-10-21: ... busiest maps: 104040000 30 (28 known), ...`.
- **`!gcmove learned`** adds one line with the totals and the number of portal routes seen.
- **Dashboard.** The map export puts `players` (totals, last 14 days, top maps, top portals) into `learning.json`.
  The Learning tab gains three cards: outcomes, busiest maps, portals taken.

### Data kept (`cache/bot-nav/players/`, honours `bot.nav.cacheDir`)

| File | What | Caps |
|---|---|---|
| `moves-<day>.jsonl` | One line per move: time, map, kind, both ends `[fh,x,y]`, outcome, graph version, regions. Real players only (bots are filtered at the hook). Nothing that names the player: no character id, no name. | 2,000 lines per map per day; 4 MB per day file; 30 days; 64 MB for the folder (oldest days deleted, never the newest) |
| `sightings.json` | New moves being counted towards adoption: key -> [count, last seen] | 20,000 keys; 90 days unseen expires |
| `stats.json` | The counters (since the first start, 30 days, top 50 maps) | small |
| `portals.json` | Map pair -> portal name -> uses, real players only (`GenericPortal.enterPortal`, scripted portals included when the map changed) | 5,000 routes |

All files except the day files are written atomically. The flush runs every 5 minutes and at shutdown (`BotShutdown`).
The overlay files in `observed/` are now written atomically too.

**Backups (ticket 45):** the v2 kit backed up only `learned stuck heat`. `NAV_DIRS` now defaults to
`learned stuck heat observed players`; the restore drill copies and checks them too. Live still runs the v1 nightly
job (only the DB dump; `/usr/local/bin/slumsms-nightly-backup` has no `NAV_DIRS`), so no bot learning data is backed
up on live until the v2 kit is installed.

### Offline replay (`NavPlayerReplay`)

```
java -Dwz-path=wz -Dbot.nav.cacheDir=cache/bot-nav -cp target/Cosmic.jar \
    soloMapling.ArtificialPlayer.GCMoveSystem.NavPlayerReplay [--days=7 | --all] [--map=ID] [--dir=...] [--csv=DIR] [--build]
```

The tool reads files only, adopts nothing and writes nothing. It replays the stored moves, or recorded packet CSVs
with `--csv`, against the cached base graph and the other tiers' graphs. It prints:

- outcomes per map;
- the moves that would be learned, with their edge type, takeoff and tiers;
- examples of every other outcome, with their regions and any fh mismatch.

`--build` builds missing graphs in memory and never saves them.

### Cross-map routes (proposal, not wired in)

Nothing used players' portal choices before: `GCWorldGraph` routes on portal geometry and travel time, and
`PlayerHeat` is per-map cells. `portals.json` now counts them, and `NavPlayerLearning.portalChoices(from, to)` reads
them. Proposal: when two portals lead from one map to the same next map, `GCWorldGraph.route` could take the one
players use most, as a tie-break inside a small cost margin. That changes bot routing, so it waits until there is
data and an owner decision.

### Tests

- `NavObservedEdgesTest`: 14 tests. The new ones need `-Dwz-path`:
  - recorded packets give the exact takeoff and landing;
  - on 104040000 every recorded move is known or a hop, none is unknown, and 85 of 85 recorded jumps re-prove
    (76 of 85 before);
  - a recorded jump between two platforms, once removed from the graph, is learned from two sightings;
  - a walk-off is learned as a directional `DROP`;
  - a wrong foothold id is resolved by position;
  - rope-end slack;
  - tier gating.
- `NavPlayerLearningTest`: 9 tests, no WZ needed:
  - log lines without player identity;
  - counts and sightings carried across a restart, with expiry;
  - per-map-day cap and pruning;
  - the summary only on change;
  - portal counts kept and shown on the dashboard;
  - down-jump capture;
  - `NO_GRAPH` counted, not dropped;
  - every overlay format read with tier gating.
