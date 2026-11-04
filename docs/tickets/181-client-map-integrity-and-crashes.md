# 181: Repair client map dependencies and prove safe area entry

**State:** investigation recorded; implementation and real-client proof pending. This is a repository ticket,
not a newly assigned release-book bug ID. Link the Rose Garden work to existing **b251** and missing-art
work to **b246 / client-163-mapload** rather than opening duplicate fixes.
**Requested by:** owner, client map/crash audit followed by “look into those; write a ticket”.
**Recorded:** 2026-11-04 06:22 UTC (2026-11-03 10:22 PM PST).
**Target:** 1.6.4 review; Client lane leads, Server Dev reviews routing. Proposed severity: S2 for the
recorded client failure; broken-link reachability and individual missing-art runtime impact need triage.
**CLOSER:** Client Development SlumsMS, with Server Dev for routing changes.
**PROOF:** fail-before regression tests plus targeted real-client entry, dwell, exit and re-entry evidence;
source inventory alone cannot close the ticket.

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
51 broken-link maps are reachable. The exact visible failure (error dialog, closure, disconnect,
wrong destination or inability to proceed), error code and timing still need to be recorded.

Source trace:

1. Temple map `270000000`, `portal1` (index 10), opens `scripts/portal/270000000.10.js`.
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
