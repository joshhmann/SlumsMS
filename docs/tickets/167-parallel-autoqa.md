# 167: Run auto-QA checks in parallel

Status: in progress for 1.6.2 (supersedes the 1.6.0 sketch in `84-parallel-auto-qa.md`).

## Why

A full auto-QA run on TEST is one lane: check 1, wait, check 2, wait. The 2026-10-30 run (`d4f103b0e`) took
9578 s of check time for 181 checks (2.66 h wall clock). Most of that time is real waiting, not CPU: bot replies
(up to ~55 s), guild/party accept windows, coupon and cooldown expiries, PQ stage timers. Two checks alone —
`t-mx-2` 1514 s and `t-mx-1` 914 s — are a quarter of the run. Waiting cannot be made faster without lying about the
timings (`--fast` already scales the staged waits for smoke runs only), so the only honest lever is to overlap waits
that cannot see each other.

## What "cannot see each other" means

Every check is a `Check` from `AutoQaCatalog` plus the `!qa` scenario it runs. A check touches four kinds of state:

1. its own synthetic players (already private: `CheckRun` keeps its own actors, `QaRun` is keyed by GM id);
2. the map its players stand on — bots it stages, map-wide chat/megaphone lines, "nothing else in the room" counts;
3. a shared instance — the KPQ party quest, the OPQ lobby, the dojo hall, a wedding chapel, a shop/FM room;
4. world state — server rates, the event clock, the bot roster/director, export/news files, the cash-shop catalog,
   the drama switch, the NX exchange, classifiers.

Only (1) is free today. (2), (3) and (4) are what the scheduler has to keep apart. So each check now declares what it
holds, and the scheduler only starts a check when nothing running holds any of the same keys.

## Decisions

- **Declaration on the check, not in a second table.** `Check` gains `Set<String> holds`. `AutoQaCatalog` declares it
  per check with `Check.Builder.laneOnly()` (holds nothing beyond its own players) or `.holds("map:100000000")`,
  `.holds("pq:kpq")`, `.holds("ch:1")`, `.holds("world")`.
- **Unclear means `world`.** No declaration = `{"world"}` = runs alone, exactly like today. This keeps the safe
  default even for a check someone adds later and forgets to annotate.
- **Keys are strings, not an enum**, so a new kind of resource (a new PQ, a new file) needs no scheduler change.
  Vocabulary: `world`, `map:<mapId>`, `pq:<name>`, `ch:<n>`. No `lane:<n>` keys: a lane is occupied by the check
  running on it, and a lane's synthetic players are its own, so nothing else can be mistaken for it.
- **Each lane gets its own two synthetic players, and its own GM.** Lane 1 keeps slots 1 (`AutoQaOne`, GM) and 2
  (`AutoQaTwo`) so a one-lane run is today's run. Lane *k* (2..4) uses slots `2k+2+logical` — 7/8, 9/10, 11/12 — and
  each lane's first slot is a GM (level 3) so that check's player 1 can run `!qa`. The four social-trace observers
  (slots 3..6) keep their meaning and are never handed to a lane.
- **Order is preserved.** The scheduler walks the catalogue order and starts the first check whose keys are free, so
  a one-lane run emits results in exactly today's order, and a multi-lane run keeps catalogue order for the report.
- **Cap:** `-Dslums.autoqa.parallel=<n>` (default 1, clamped to 1..4). 1 = today's serial loop, byte-identical
  ordering and no extra logins. The cap is also written into `latest.json` (`runs[].parallel`), so a report says how
  it was produced.
- **A check's own map is not declared by hand when the check never warps**: two checks that each stay on the map
  their GM logged into are already separated by their lane players and by `QaLog` keying runs on the GM id. Checks
  that assert exclusivity of a map (nothing else in the room, a specific boss, a specific instance) do declare it.
- **`QaSuite` already runs one scenario per GM** (`active` is keyed by GM id), so lanes need no change there; only
  the log file name has to stay unique when the same scenario id starts twice in the same second.
- **Waiters see all running checks.** `AutoQaRunner`'s single `current`/`stepNow` become per-check fields, and
  `!autoqa status` lists every running check with its lane.

## Work

1. `Check`: `holds`, `Builder.laneOnly()`, `Builder.holds(String...)`, default `{"world"}`; `hostsADisjoint`-style
   helper `conflicts(Set<String>)`.
2. `AutoQaCatalog`: declare for every check. World-locked today: `t-ev-*`, `t-tw-1`, `t-bg-1`, `t-ch-1`, `t-bt-*`,
   `t-ss-1`, `t-hp-1`, `t-nv-2`, `t-ld-1`, `t-mk-1`, `t-sf-1`, `t-ds-1`, `t-au-1`, `t-iv-2`, `t-xc-*`, `t-oa-1`,
   `t-st-1`, `t-bw-1`, `t-sg-*`, `t-af-3`, `t-gw-*`, `t-rg-*`, `t-fy-*`, `t-ti-1`, `t-cq-1`, `t-gs-1` and the
   MANUAL ones. Instances: `t-mx-1..6` (`pq:kpq`), `t-pq-*`/`t-ac-2` (`pq:opq`), `t-dj-*` (`pq:dojo`), `t-we-1`
   (`map:680000110`), `t-jq-1` (`map:101000100`), `t-gd-*` (`map:guild-hq`), `t-fm-*`, `t-tr-1`, `t-ps-2`, `t-bk-1`,
   `t-at-1`, `t-bk-3` (`map:` their town), `t-nv-1`/`t-id-1` (`map:104040000`), `t-fh-2` (`map:120010000`),
   `t-af-2`/`t-rv-4` (`map:104040000`, `map:104040001`).
3. `AutoQaScheduler` (new): pure packing logic — takes the check list, the parallel cap and a per-check "may start
   now?" callback, returns the next check or null; unit-testable without a server.
4. `AutoQaRunner`: run lanes; per-check `current`/`stepNow`; results in catalogue order; abort still stops everything.
5. `SyntheticPlayers`: lane slots 7..12, names, GM level for each lane's first slot, `laneOfSlot`,
   `laneSlot(lane, logical)`, `isQaSlot`, `isQaId(int)`.
6. `CheckRun`: a `lane`; `actor(logical)` maps to the lane's physical slot.
7. `GameAutoQaWorld`: forward `QaLog` lines for every QA slot (not just 1 and 2); scope `remembered` (meso, NX,
   codes) by lane; map logical slots in `probe(...)` to the lane's physical slots.
8. `AutoQaReport.Run`: `parallel` and `sumSeconds` (the sum of check seconds, next to the wall clock we already have).
9. `QaRun`: unique log file name when the same scenario starts twice in the same second.
10. KPQ stall guard (separate commit): `t-mx-1`/`t-mx-2` stage waits currently burn their whole budget when a stage
    never clears. A stalled stage must fail the check in `N` minutes with "no stage cleared in N min"
    (`-Dslums.autoqa.kpqStallMin`, default 8) instead of waiting for the budget. On for QA only, and the checks
    still pass when the stages do clear.
11. Tests: scheduler never starts two checks sharing a key; a `world` check runs alone; `parallel=1` keeps catalogue
    order; two scenario runs at once keep their own state (slot mapping, log files, remembered wallets); declaration
    coverage (every AUTO/HALF check has an explicit declaration, none silently world-locked); report fields.
12. Review follow-up (external agent, 2026-10-31):
    - the lane callbacks add to a `Collections.synchronizedList`, and the run's results are rebuilt in catalogue order
      (`AutoQaService.orderedByCatalogue`) before the release verdict, the report and the run line — finishing first
      no longer reorders a report, and no plain `ArrayList` is written from several lane threads;
    - `engaged` / `notEngaged` and the family perks probe act on the caller's own pair (`SyntheticPlayers.lanePair`,
      `partnerSlot`), not on slots 1 and 2, so lane 2 no longer reads lane 1's player;
    - eight checks that read map-wide lines or set a global property were redeclared: `t-sp-1`, `t-cb-4`, `t-id-1` hold
      `map:104040000` + `map:104040001`, `t-bs-1` holds `map:910000001`, `t-wc-1` holds `map:100000100`, `t-vb-7`,
      `t-jb-1` and `t-fy-2` hold the world (the last two set a system property for the whole JVM);
    - `AutoQaSchedulerTest.checksThatReadMapWideLinesOrSetAGlobalPropertyDeclareIt` keeps that from regressing, and the
      free-text catalogue commands were checked for literal `{` (none: only placeholders and regex quantifiers).
13. Docs: `docs/ops/qa-suite.md` section "Parallel runs" (switch, cap, what a declaration means, how to read a
    parallel report, how to re-run a single check).

## Proof the lane session has to run

On one commit, same release set, no `--fast`:

```
!autoqa run all            # -Dslums.autoqa.parallel=1 (default) -> baseline
!autoqa run all            # -Dslums.autoqa.parallel=4          -> compare
```

Accept when the `PASS`/`FAIL`/`SKIP` per check id agree, except checks that fail for a different listed reason
(write the diff down in the report). Restart the test server between the two runs so lane players log in fresh, and
turn the cap on with `-Dslums.autoqa.parallel=4` in `start-test.sh` (or `AUTOQA_PARALLEL` in the release conf
`UNIT_EDITS` for live, off by default).

## Risks

- Shared-map cross-talk: a check's own log can receive map-wide lines from another lane's staged bots. Checks that
  count "the map" must declare their map; the ones that do not are the ones whose patterns name their own bot.
- More synthetic players mean more real characters in world 1 (`AutoQaSeven..Twelve` are created on first use, level
  70 warriors in Henesys, hidden from players by the synthetic client). They are logged out when the run ends.
- A leak shows up as a check that passes alone and fails in parallel, so the report keeps the lane and the timing per
  check and `--failed` re-runs them one lane at a time.
