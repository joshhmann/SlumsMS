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
