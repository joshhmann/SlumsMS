# 181: Repair client map dependencies and prove safe area entry

**State:** data sweep complete; v232 repair candidate prepared and verified offline.
Installation, native client proof, remaining missing assets and Rose Garden runtime repair are pending. This is a repository ticket,
not a newly assigned release-book bug ID. Link the Rose Garden work to existing **b251** and missing-art
work to **b246 / client-163-mapload** rather than opening duplicate fixes.
**Requested by:** owner, client map/crash audit followed by “look into those; write a ticket”.
**Recorded:** 2026-11-04 06:22 UTC (2026-11-03 10:22 PM PST).
**Target:** 1.6.4 review; Client lane leads, Server Dev reviews routing. Proposed severity: S2 for the
recorded client failure; broken-link reachability and individual missing-art runtime impact need triage.
**CLOSER:** Client Development SlumsMS, with Server Dev for routing changes.
**PROOF:** fail-before regression tests plus targeted real-client entry, dwell, exit and re-entry evidence;
source inventory alone cannot close the ticket.

## Current findings and handoff — 2026-11-04

- **Crack in Time272000000:** traced missing-object failure; the private v232 candidate resolves
  all27 missing pillar/crack placements. Requires native rendering and entry/exit proof before release.
- **Rose Garden211080100 (b251):** art resolves; separate runtime crash remains open.
- **Rose Garden211080700:**12 Obj/mario.img placements remain unresolved; v232 lacks that image.
- **Broader data sweep:** candidate removes2151 fatal-class references (920 object placements and
  1231 missing whole BGM-image references).247 map IDs still have fatal-class findings;14 newly
  exposed missing-track notes also need attention. Static counts are not gameplay guarantees.
- **Transfer hypothesis:** all49750 original repack files checked are present in QA. Missing nodes
  also occur in the original pack; this comparison does not support failed transfer as their cause.
- **Validation:**24 relevant Python tests pass plus the full11352-map offline candidate sweep.
  Candidate is local only; nothing installed, published, pushed or merged. No game was launched.

Implementation and pinned source manifest: client branch `client-164-mapart-fixes`, commit `c97a953`.
[Detailed v232 results and remaining-map list](/root/work/client/src-164-mapart-fixes/docs/map-data-sweep-164/v232-results.md).

Client lane: review/stage the compatible candidate and prove native behavior; source missing mario,
starPlanet, PL_RetroWorld and remaining audio dependencies. Handle b251 independently. Server Dev:
review the Temple menu/return routing findings below. Retain restrictions until each map has proof.

## Problem and investigation

Some areas can close the client on entry or during field updates. A fresh offline scan parsed **19,059**
retained client map images with **0 parse errors**. All **11,352** retained server maps have a corresponding
client file, but this hides broken dependencies inside otherwise present files.

These are three separate causes to investigate and prove independently:

| Finding | Affected maps | Evidence and impact |
|---|---|---|
| Missing object-set image `Map/Obj/mario.img` | `211080700` (12 references), `402000324` (3), `863010240` (2) | Referenced image absent from retained client data; all three maps exist in retained server data. Prior stock investigation identifies an uncaught resource error path. No fresh in-game reproduction in this audit. |
| Missing object-set image `Map/Obj/starPlanet.img` | `814040000` (28 references), `814041900` (2) | Same class; both maps exist in retained server data. |
| Missing linked map `100020500` | Every ID from `100020549` through `100020599`, inclusive (51 maps) | `info/link` chains end at an absent map. All 51 are absent from the retained server map inventory, so normal player reachability is **not established**. Do not describe this as 51 reproduced crashes. |
| Rose Garden runtime error, **b251** | Reproduced previously on `211080100` | Saved v2 trace records `E_POINTER` (`80004003`) with application frame `00529EB6`. Client lane identifies a per-frame view-layer access when the layer was not created. This is separate from missing art and requires a local DLL fix review. |

The scan checked **9,889 map links**, found **0 link cycles**, and found exactly the five map/image pairs
above missing an Obj/Back/Tile image. It checks whole-image existence, not every internal canvas/node.

The two feature lists are coverage candidates only: **7** maps with `qrLimit=31036`
(`211080100` through `211080700`, steps of 100), and **21** maps with `fieldType=9`.
`211080100` does **not** have `fieldType=9`. Neither feature alone proves a crash.

## Existing mitigation and work to reuse

### Owner-reported route: Temple of Time → Leafre of the Past

Owner clarification, recorded 2026-11-04 06:36 UTC (2026-11-03 10:36 PM PST): the affected path is
the Temple of Time portal's **“Leafre of the Past (Crack in Time)”** choice. This identifies a
normal player route outside the five missing-image maps above. It does not establish that the
51 broken-link maps are reachable. Subsequent owner report confirms the symptom is a client crash;
the exact transition, error code and diagnostic timing still need to be recorded.

**New incident, recorded 2026-11-04 07:12 UTC (2026-11-03 11:12 PM PST):** owner reports,
“friend just went in and it crashed”, in direct reference to the Future Leafre / Crack in Time route.
Treat this as a real-user crash report and prioritize targeted reproduction. The friend’s precise
map ID, installed client build, selected portal and error/trace were not supplied. Do not mark every
Leafre map as reproduced, attribute the failure to the missing object nodes without a trace, or
claim the adjoining maps are safe because static checks passed. No fix is verified.

Source trace:

1. Temple map `270000000`, `portal1` (index 10), is intended to use
   `scripts/portal/270000000.10.js`; the follow-up below identifies a wiring discrepancy.
2. That script opens keeper NPC `2140000` with `scripts/npc/gateOfTime.js`.
3. The third menu choice (selection 2) checks `AreaGates.denyReason` and calls `cm.warp(272000000, 0)`.
4. Retained client map `272000000` has `in00`, type **10**, targeting `272000100/out00`.
   Map `272000100` then has `in00`, type **7**, targeting `272000200/out00`.

Additional offline findings for this route:

- Existing `check_map_refs.py` scanned `272000000`, `272000100`, `272000200`, `272000300`,
  `272000400`, `272000500`, `272000600`: **27 note findings, 0 fatal/fixed listed maps**
  (23 layer-3 object findings, 4 layer-4). Notes include missing internal nodes such as
  `Obj/acc10.img/timeTemple/pillar_old/7` and `timeTemple/crack/3` in `272000000`.
  An existing image with absent internal nodes is a different class from missing `mario.img`;
  the checker does not establish these notes as the cause of a process exit.
- `272000000/west00` targets `270000000/east00`; retained client `270000000` has no `east00`.
  This is a return-destination mismatch to check against current server data and fallback handling,
  not a demonstrated explanation for a failure on initial entry.
- Retained `272000000` declares `onUserEnter=q31165e`; no matching script was found under this
  checkout's `scripts/`. Check actual runtime script handling and logs before attributing symptoms.
- Check native-client handling of portal types 10 and 7 at the two onward transitions; neither
  is declared unsupported by this investigation. Capture outgoing portal packets to distinguish
  a client portal that never activates from a server warp or map-load failure.
- `GateOfTimeScriptTest` checks the chosen destination and level gate; `t-ld-1` checks script
  text and access rules. Those checks cannot establish successful client rendering or traversal.

**Priority reproduction:** use an eligible character (level 120, no rebirths) and the actual Temple
menu path, not only `!warp`. Capture menu selection, requested destination, server transition,
client map-ready/error/cleanup and exact timestamp. Test first arrival at `272000000`, onward
travel to `272000100` and `272000200`, and the return to `270000000` separately. Compare direct
warp only as a diagnostic control. Archive client integration/trace/error logs and matching server
logs per leg. Do not reuse a Rose Garden fix without evidence that the same instruction fails.

Evidence outputs: `/root/work/agent-runs/client/map-audit-20261103/temple-route-check.out`,
`temple-route-check.err`, `temple-route-notes.txt`; copied checker tools in `temple-route-tools/`.
The Sound root is absent from this retained Data subset, so the checker skipped BGM validation.
No fresh client run, runtime script proof or diagnosis of this route's reported error is claimed.

### Follow-up: all seven route maps checked, plus read-only TEST files

Recorded 2026-11-04 06:39 UTC (2026-11-03 10:39 PM PST). Owner asked to check these maps even
without knowing which transition failed. No service, QA worker or game was started or changed.

**Stronger findings:**

- All **27** internal-object misses occur in **`272000000` itself**: `acc10/timeTemple/pillar_old`
  and `acc10/timeTemple/crack`. The six Leafre fields `272000100..272000600` produced **zero
  checker findings**. This is the checked subset, not proof that those fields render or play correctly.
- Parsed every portal in the Temple hub and the seven destination maps. Their portal data agrees
  between the retained client inventory, retained server XML and **current TEST XML read on .111**.
  All checked destination maps exist. The only absent target portal name is the previously noted
  `272000000/west00 -> 270000000/east00`.
- `GenericPortal.attemptTeleportForUnscriptedPortal` explicitly falls back to destination portal 0
  when the named portal does not exist. Both maps have portal 0. Therefore the return mismatch is
  confirmed bad destination metadata, but this source path should fall back rather than dereference
  that missing portal. Verify the actual landing location; do not attribute initial-entry closure to it.

**New menu-wiring discrepancy to reproduce before changing code:**

- Current TEST `270000000` portal 10 is named `portal1`, has no explicit `script` node and directly
  targets `271000000/out00` (the Future). The corresponding retained client/server data agrees.
- The source `PortalFactory.loadPortal` looks for an explicit script or the fallback filename
  **`270000000.portal1.js`**, constructed from map ID plus portal **name**, not index.
- Current TEST has `scripts/portal/270000000.10.js` (SHA256
  `2aa3db631837c76cfbae41a28e79448da234c3d3f6959968e9cc809d860b59e6`, matching this branch)
  but no `270000000.portal1.js`. The inspected source therefore does not wire the intended menu
  to this XML portal via the fallback. Existing script tests load `.10.js` directly and miss this gap.
- This is source plus current TEST file evidence, not proof of the running jar's portal behavior or
  Live's data. The owner's reported selection may come from different runtime data or code. Capture
  which actual portal/menu runs and compare deployed resolver behavior before selecting a fix.
- Add an integration regression that constructs the portal from actual map data through
  `PortalFactory` and verifies the menu script binding; a direct script invocation is insufficient.
  A repair can use the established portal-name fallback or a deliberate WZ script binding after review.

**Missing entry scripts are not established crash causes:**

- Current TEST also lacks both `map/onUserEnter/q31165e.js` (`272000000`) and
  `map/onUserEnter/undomorphblackdraco.js` (`272000600`). The map XML requests them.
- Inspected `AbstractScriptManager.getInvocableScriptEngine` returns null for an absent file;
  `MapScriptManager.runMapScript` returns false in that case. `MapleMap` ignores that return.
  This source path skips the missing entry behavior; it does not throw because the script is absent.
  Establish the intended quest/morph behavior before adding or deleting scripts.
- Portal types 10 and 7 are accepted as `GenericPortal` by the server factory. Native-client
  activation remains unverified; packet capture is still needed if an onward portal does nothing.

New artifacts beside the original audit: `inspect_temple_route.py`, `temple-route-structure.json`
(map hashes, info, portals and destination-name checks), and `temple-test-portals.json`
(read-only TEST XML extraction). TEST reads were limited to these map files and named scripts;
no player or database data was accessed. No native client test or definitive crash diagnosis yet.

- The inspected release-1.6.4 `src/main/resources/soloMapling/map-routing.tsv` already lists all five
  missing-art maps and all seven Rose Garden maps as `client-crash`, with fallback destinations.
  In particular, `211080000 east00 -> 211080100` routes **back to `211080000`**; it is not an
  unrestricted entrance in this table. Gollux entrance routes also redirect away from `863010240`.
- `server.maps.MapRouting` defaults to enabled through `slums.maps.routing` and supports a table override.
  That establishes source intent, not proof that current TEST or Live uses this table or setting.
- Candidate `client-163-mapload`, commit **`b19c17b`**, contains exceptions at the object/background/tile
  builders. Its existing report records **39/39 native CTest passes**, but no real-client proof.
  Review and reuse it before writing a competing guard. Skipping an object avoids one exception;
  it does not restore art, prove an area playable, or repair a missing whole-map link.
- Existing b251 investigation/trace branches are `client-b251-codex` (`6595765`) and
  `client-b251-wztrace` (`6f52dc9`). These provide diagnostics; do not label them a shipped crash fix.

## Implementation work

1. Record the current QA DLL/build, Custom.wz and Data identity and compare with this retained inventory.
   Confirm which guard, if any, is installed. Preserve a trace per run instead of overwriting it.
2. For b251, inspect stock instructions and bytes around `00529EB6` and the layer creation/lifecycle.
   Review a narrowly scoped null guard with regression coverage for absent and valid layers.
   Do not suppress errors at the shared `_com_error` raiser or skip the whole field initializer.
3. Review the existing missing-art guard and prove it on the five exact maps. Decide per map whether
   compatible original art must be restored or a reviewed omission is acceptable. Preserve geometry,
   collision, portals, life, reactors and unrelated assets. Data changes use `tools/wz` scripts with
   semantic checks; runtime survival alone does not justify removing a routing restriction.
4. Investigate the intended base map for the 51 broken links, using matching source/version evidence.
   Check current server maps, portals, scripts and instance generation before assigning player impact.
   Repair the genuine target when available; otherwise document intentional exclusion or apply a
   reviewed server restriction only if reachable. Do not invent geometry or redirect to an arbitrary map.
5. Keep an inventory regression check that detects absent whole images and linked-map targets, reports
   parse failures, and produces a reproducible affected-map list. Extend existing checker tooling where
   possible. Scope any routing change through the generator `tools/data-align/map_routing.py` and
   `tools/data-align/client-crash-maps.txt`; do not hand-edit generated table rows.

## Acceptance and targeted proof

### Fresh data sweep and archive-source follow-up (2026-11-04 08:12 UTC / 12:12 AM PST)

Owner directed a data-only sweep using Silvia's QA Data and current TEST Map.wz, then asked to check
the Community and MapleRoot archives for missing-transfer versus missing-source evidence. Work is on
client branch `client-164-mapart-fixes`, commit `58799bb` (with the existing checker cherry-pick `490ca55`).

- Fresh sweep: 11,352 unique TEST map IDs, 13,935 findings; 102 maps with missing object references
  (97 missing internal nodes, five absent whole images), plus1,461 maps with absent BGM images under
  the existing checker policy. The raw1,643-row list includes106 already-guarded canvas-shape maps;
  it is not a count of reproduced crashes and must not be bulk-imported into routing.
- The corrected checker now lists `272000000` with its17 pillar_old and10 crack object placements.
  The new saved trace in `agent-runs/client/evidence/trace-272000000/run2-wztrace.log` records
  the missing pillar_old lookup immediately before E_POINTER in the object-build path. Earlier
  internal-node notes did not justify calling this safe; that checker classification is repaired.
- All49,750 original MapleRoot Game Files images in the copied categories are present in fresh QA.
  ZIP member sizes/CRC32 differ only for23 Free Market maps and login.img; no original files are
  missing. Source acc10 and sampled missing-image evidence agrees with the retained full .164 reference.
  The main missing nodes are inherited from source packs, not shown lost during QA transfer.
- Neither archive contains pillar_old/crack, Obj/mario.img or Obj/starPlanet.img. Exact source
  revisions remain unidentified. Content-era leads (not exact image revisions) are Kinesis v169,
  Heroes of Maple v174–175 and MONAD/Abrup v197, with primary-source links in the report.
- Community Repack contains genuine BM1/boss/spine/0 and Lacheln/Boss/obj/9,/10 nodes. A pinned
  additive Custom.wz candidate restores exactly7 references across maps450009400/450009450/
  450009500/450003840/450004150, preserving unrelated leaves/images in offline checks. It contains
  Spine metadata and has not been installed or verified rendering in a client. Five-map data proof
  is not full map-playability proof and does not close this ticket.
- Proof:13 checker tests and4 restoration tests pass, with fail-before receipts; full sweep exits0.
  No game, QA worker, native build, restart, deployment or drop change was performed.

Review [the sweep/source report](/root/work/client/src-164-mapart-fixes/docs/map-data-sweep-164/README.md)
and [source checklist](/root/work/client/src-164-mapart-fixes/docs/map-data-sweep-164/source-shopping-list.csv).
Full inputs, all-class findings, archive comparison, candidate hashes and before/after receipts:
`/root/work/agent-runs/client/164-map-data-sweep/`. Keep source-version hunting and targeted runtime proof open.

- [ ] Reproduce the owner's Temple menu → `272000000` path, record the exact symptom, and prove
      arrival, onward transitions and the return portal separately using the route plan above.
- [ ] Re-run inventory against the exact QA/release Data set and record hashes, counts, errors and findings.
- [ ] Fail-before tests cover the chosen b251 fix, valid-layer behavior and missing-art containment.
      Run native CTest; separately record any prior test results used only as historical evidence.
- [ ] On a lane-controlled QA build, enter `211080100`, remain through field updates, move around,
      exit and re-enter. Record map-ready, final outcome, build and preserved diagnostic logs.
- [ ] Test the remaining Rose Garden maps individually, including `211080700` which also has missing art.
      Track the 21 `fieldType=9` maps separately; flagging them is not grounds to block them all.
- [ ] Visit each of the five missing-art maps on controlled TEST. Verify survival and the intended rendering,
      movement, collision, portals and exit. If art is skipped, identify the exact missing objects and log
      entries, and obtain lane review of whether the result is playable.
- [ ] For the 51 linked maps, provide evidence of repair or intentional exclusion, and a reachability
      conclusion. If reachable, verify entry and recovery after the selected fix; never bypass restrictions
      on Live to reproduce a crash.
- [ ] If routing changes, test affected portal/script/GM moves and loading a saved character in a blocked map.
      Retain safe fallbacks until each affected map has its own runtime proof.
- [ ] Record current TEST proof, remaining restrictions and release notes before proposing publication.
      Live changes require the owner's separate go. Close b251 only on its own proof.

## Reproduction artifacts and limits

Audit script/results: `/root/work/agent-runs/client/map-audit-20261103/`:
`audit.py`, `inventory.json`, `parity.json`, `REPORT.md`. Reproduce the offline scan with:

```sh
python3 /root/work/agent-runs/client/map-audit-20261103/audit.py
```

Inputs: `/root/work/rampart-scratch/cd/Map` and `/root/work/rampart-scratch/servermaps`.
These are retained snapshots, **not fresh installed/live parity**. The folder's date is the task label;
the investigation record above uses the actual UTC/Pacific timestamp.

Saved b251 evidence: `/root/work/agent-runs/client/evidence/b251-trace-v2-20261102/run2-wztrace.log`,
lines 4892–4896. Prior investigations: `/root/work/agent-runs/client/rosegarden/REPORT.md`,
`/root/work/agent-runs/client/b251-codex/REPORT.md`, and
`/root/work/agent-runs/client/163-mapload/REPORT.md`.

This follow-up compared affected IDs against retained server filenames and inspected routing source/table
and the candidate guard. No fresh client launch, native build/test, current deployment verification,
internal canvas/BGM/mob/NPC/reactor validation or fix was performed. No economy changes.

## v232 donor follow-up — 2026-11-04

The owner's Steam app216150/depot216151 manifest4440651722208802215 was inspected read-only.
Ten copied source files match Silvia by SHA256. A private, uninstalled candidate restores137
exact object nodes across13 Obj images and adds13 legacy-key Sound images, preserving existing
QA Custom.wz leaves and unrelated images. Linked pixels are materialized from genuine donor
canvases; no portal, geometry, mob, loot or economy changes were made.

The full11352-map offline candidate sweep removes920 missing object placements and1231 missing
whole BGM-image references: fatal-class findings2468→317 across1539→247 map IDs. Fourteen newly
exposed missing-track notes remain. These are static data results, not proven playable maps.
All27 object failures in Crack in Time272000000 resolve, including pillar_old/7.

**Rose Garden remains open:**211080100's separate b251 runtime failure is outside this asset
repair;211080700 still has12 references to absent Obj/mario.img, which the v232 donor lacks.
Do not close either case or remove routing restrictions based on this package.

Proof:13 checker tests (including actual QA Rose-family input),4 prior restoration tests and7
converter tests pass; full offline candidate sweep exits0. No native build, game launch, rendering,
audio playback, deployment or publication was performed. Detailed source pins, reproduction and
remaining-map list: [v232 results](/root/work/client/src-164-mapart-fixes/docs/map-data-sweep-164/v232-results.md).
Private package: `/root/work/agent-runs/client/164-map-data-sweep/v232-repair/`.
Native compatibility and targeted map-entry proof remain required before installation/publication.

## Swordie v271 source review

Read-only review of the owner's `Z:\swordie 271` archives found useful Rose Garden behavior, Temple
portal and missing-loot references, but no full client art pack or included client hook source.
All ten inspected low-level Edelstein gap mobs have candidate base drop rows; no rates were imported.
[Source hashes, exact paths, compatibility limits and loot leads](281-swordie271-source-review.md).
Rose Garden crash/missing mario art remain unresolved; the downloaded Steam assets require a separate sweep.

### Server-side fix for b300 (branch 164-crackintime)

- Correction of the "menu-wiring discrepancy" above: `PortalFactory.loadPortal` builds the fallback script name from
  `portal.getName()`, which is the portal's INDEX node name (10), not its `pn`. So `270000000.10.js` IS the script the
  loader finds for portal1; there is no wiring bug. `CrackInTimePortalsTest` loads the real 270000000 map data through
  `PortalFactory` and pins the binding.
- Real defect: `272000000` west00 -> `270000000/east00` (absent). `GenericPortal` falls back to portal 0, so the return
  landed on `sp` at (0,0), not at the Gate. `tools/data-align/portal-fixes.tsv` gains a row (apply with
  `wz_set_values.py` to the host `wz/`, then restart): west00 now arrives at `270000000/portal1` (the Gate of Time).
  Same kind of row for `272000310` west00 -> `272000300/sp` (272000300 has no east00).
