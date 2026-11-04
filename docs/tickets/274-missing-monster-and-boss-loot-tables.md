# 182: Triage missing monster loot tables without duplicating the gear progression work

**State:** read-only audit complete; candidates need source/reward triage and targeted kill proof.
Repository ticket only; no release-book ID or severity assignment fabricated.
**Requested by:** owner, including bosses and comparing the existing gear progression branch.
**Recorded:** 2026-11-04 07:08 UTC (2026-11-03 11:08 PM PST).
**Target / closer:** Server Dev, 1.6.4 review; Client lane for real-client drop/pickup proof.
**PROOF:** per-group source/reward decision, fail-before regression for any repair, installed DB/cache
verification and targeted kills. Do not close this on row counts alone.

## Scope and evidence

SELECT-only exports from Live `drop_data` and TEST `drop_data` each contain **30,731 rows** and are
byte-identical in the selected six columns. SHA256:
`e43e2d20666df12d2a24430baa78841889564b7e6111b0790a7c4b5d4cf14497`.
No player/account tables were queried. No database, config, service or drop cache was changed.

The audit crossed these rows with **current TEST WZ**: **11,356 map images**, **6,789 monster templates**,
and **1,980 unique mob IDs in map life sections**. Of those placed IDs, **270 lack a matching template**
in the inspected TEST Mob.wz; classify those as a separate asset/coverage issue, not as loot-only defects.
Map presence does not establish portal reachability, enabled events or successful spawning.

Among the **1,710 placed IDs with resolvable templates**:

| Database-table classification | Mob IDs | Meaning |
|---|---:|---|
| No `drop_data` rows | **739** | Includes **125 boss-flagged IDs**; raw candidate count, not 739 confirmed bugs |
| Positive rows, all with `questid > 0` | **35** | Quest-only by database gate; conditional loot, not an absent table |
| Ungated rows, but no non-card item rows | **37** | Mesos/cards only by database metadata; may be intentional |
| At least one ungated, non-card item row | **899** | Table coverage present; item validity, item-level quest flags and practical rates still need separate checks |

All 811 placed IDs in the first three groups are listed in
[placed-gaps.csv](../economy/loot-audit-1.6.4/placed-gaps.csv).
[boss-review.csv](../economy/loot-audit-1.6.4/boss-review.csv) also includes boss templates found through
revive links or source references; those extra references are **not proof of active encounters**.

## Concrete review candidates outside the gear branch

These seven ordinary Edelstein mobs have **zero rows in both exported databases**, appear in TEST
map life data, award positive template EXP and are not targeted by gear migrations 059/060/061:

| Mob ID | Name | Example TEST map |
|---|---|---:|
| 1150000 | Patrol Robot | 310030000 |
| 1150001 | Strange Sign | 310030100 |
| 1150002 | Serpent | 310030110 |
| 2150000 | Water Thief Monster | 310030200 |
| 2150001 | Dust Box | 310030300 |
| 2150002 | Streetlight | 310030310 |
| 2150003 | Patrol Robot S | 310040000 |

Check actual maps against the CSV/WZ before staging. Proposed first triage group: verify these regular
hunting mobs should have ordinary loot/mesos, then choose compatible source tables with owner-visible rates.
Do not blindly copy tables by appearance or `info/link`: the drop provider queries the exact monster ID.

Boss examples with zero rows include **Chao 5250004** (placed in `300010420`), **Ephenia 5250007**
(includes `300030310`) and **Gatekeeper Nex 7120100** (includes `240070010`). These remain review
candidates: establish whether the actual fight uses this ID, what phase dies with drops enabled,
and whether a quest, NPC, event-clear reward or different form provides the intended payout.

The requested Leafre of the Past region is **not an empty-table case**: Corrupted Wyvern/Cornian/
Skelegon/Kentaurus **8220016–8220019 each have 22 rows**. The six placed Rose Garden variants
**8211000, 8211001, 8211002, 8211005, 8211006, 8211007 each have 20 rows**.
This does not validate the client maps or guarantee every row can drop for every character.

## Why global drops do not automatically cover this

`MonsterInformationProvider.retrieveDrop` queries `drop_data` by **exact `dropperid`**. The alternate
`LootManager.retrieveRelevantDrops` path filters that same table; it does not synthesize a fallback.
`MapleMap.dropFromMonster` obtains applicable globals, but returns when `lootEntry.isEmpty()` **before**
calling `registerMobItemDrops`. An absent per-mob table therefore bypasses global drops on this path.
Drops-disabled maps/mobs, quest eligibility and direct event/NPC rewards remain additional controls.
This is source evidence, not a fresh runtime/cache proof or a request to change global-drop policy.

## Existing boss exclusions and rewards must be preserved

- `tools/economy/gen_bb_drops.py` already documents deliberate phase/form handling for migration 055:
  one clear-level table, selected fighting forms, and explicit `GAP_STUBS` including Lucid 8880150,
  Will 8880302, Verus Hilla 8880415 and Darknell 8645009. Do not bulk-fill these just because rows are absent.
- `scripts/npc/3004006.js` already grants clear rewards, including Von Leon's Seal and hard-boss tokens;
  event scripts can grant additional rewards directly. A missing floor-drop table does not mean no payout.
- PQ/dojo copies, dummies, friendly objectives, invincible/timed objects, quest-only mobs and intermediate
  revive forms require an explicit classification. The CSV flags are hints, not approved exclusions.

## Comparison with gear progression (owner clarification)

Pinned branch **`164-gear-progression-codex` at `0444737f23b1367b6c6f945a47c715da3ed7f100`**.
Read its report, bridge report, migration inputs and forge source contract. That branch is a local
candidate; its report says TEST activation and client/combat proof remain pending.

| Candidate migration | Target pairs | Absent from both DB exports | Existing pairs |
|---|---:|---:|---:|
| 059 normal gear through 200 | 136 | 134 | 2 |
| 060 Royal crafting materials | 5 | 1 | 4 |
| 061 normal 120–149 bridge | 226 | 225 | 1 |
| Total | **367** | **360** | **7** |

These targets cover **38 distinct mobs**, **all of which already have database tables**. These migrations
add missing item pairs or adjust a permitted ordinary rate; they do **not** supply tables for the 739
placed zero-row IDs. Keep the 360 pending pairs owned by that branch rather than proposing duplicate inserts.
Exact pair comparison: [gear-pair-overlap.csv](../economy/loot-audit-1.6.4/gear-pair-overlap.csv).

Two existing migration-059 scroll pairs, `(8610006,2043001)` and `(8610009,2043301)`, have base chance
**300 per million**, below that candidate's **2000**. Migration 059 preserves existing pairs, so admitting
it alone does not raise these two rows. Review intended grandfathering/source-rate acceptance on the gear
branch; this audit neither changes those rates nor creates a competing migration. The bridge's existing
`(2400004,1072960)` row at **800** versus candidate **2400** is already covered by migration 061's rate floor.

## Work and acceptance

1. Classify each proposed repair as ordinary hunting loot, boss final-form loot, quest-only, fixed clear reward,
   event copy/phase, unused/inaccessible content, or source-data failure. Name evidence and the actual killer path.
2. Start with the seven Edelstein mobs and selected Chao/Ephenia encounters; independently establish player access
   and expected reward behavior. Compare intended drops with valid installed items and current economy rules.
3. For any repair, specify every added/changed item, quantity, quest gate and base chance. Reuse existing
   deterministic generators and idempotent migrations with rollback and fail-before tests. Avoid duplicate gear work.
4. After authorized TEST activation/cache reload, inspect the effective table and run targeted eligible kills.
   Prove floor drops and pickup or the intended clear reward. Test excluded forms do not duplicate payouts.
5. Record proof per mob/group and remaining intentional exclusions. Do not auto-close all 739 candidates,
   unblock maps or publish changes from this audit. Live writes still require a separate owner go.

**Audit limitations:** XML parsing completed without exceptions, but 66 templates have nonnumeric placeholder
HP strings, recorded in the raw summary; no stats were repaired or guessed. Live WZ, jar/cache contents,
all variable-computed scripted spawns, item existence/quest metadata, active events and real kills were not
verified. Source-reference/revive scans are discovery aids and can include unused templates.
No runtime tests were run; documentation/data assertions checked counts, pair extraction and export parity.
**Economy changes: none.** CSV candidate chances describe the existing gear branch, not new approvals.

Artifacts and read-only reproduction: `/root/work/agent-runs/server/164-loot-audit/` contains
`audit_loot.py`, `test-wz-mobs.json`, the two six-column drop exports, copied candidate SQL for comparison only,
`test-ledger-global.tsv`, `summary.json`, `all-mobs.csv`, `loot-gaps.csv`, and `priority-review.csv`.
Run `python3 /root/work/agent-runs/server/164-loot-audit/audit_loot.py` to regenerate from saved inputs.
