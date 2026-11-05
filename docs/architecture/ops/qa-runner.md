# QA runner tools (operator side)

## Purpose

The scripts an operator runs from 187 to start, collect and prove QA work on the TEST box, plus the read-only audit
tools that need no server at all. This is the operator's half of QA: it queues runs, pulls evidence back and judges one
run folder. The in-server half (what a check stages, how `!qa` gates, what `PASS`/`FAIL`/`SKIP` mean) is
[testing.md](../testing.md) and [qa-suite.md](../../ops/qa-suite.md:876); the server-side runner internals are
[server/qa-and-autoqa.md](../server/qa-and-autoqa.md). Every tool here talks to TEST only — nothing in this folder can
reach live, and the guard that enforces it is `tools/ops/client-mcp-win/src/testhost.py:23-27`.

## Entry points

| Command | Arguments | Reaches |
|---|---|---|
| `tools/ops/autoqa-run` | `[--feature] [all\|smoke\|<id\|family\|id,id>] [--fast] [--failed] [--release]` | TEST `data/autoqa/run-queue.txt` over ssh (`tools/ops/autoqa-run:4,62`) |
| `tools/ops/test-status` | `[--feature] [--since "1 hour ago"] [--checks]` | TEST read-only: unit state, `latest.json`, journal (`tools/ops/test-status:5,25`) |
| `tools/ops/autoqa-proof` | `[<base>..<head>]` \| `--files PATH...` \| bare (worktree) `[--map] [--root] [--json] [--quiet]` | nothing — local git + files (`tools/ops/autoqa-proof:246-279`) |
| `tools/ops/feature-health` | `[--file live.json] [--host mapletest] [--only a,b] [--json] [--live-readonly] [--rules TSV]` | `ssh mapletest cat …/live.json` (`tools/ops/feature-health:134-137`) |
| `tools/ops/qa-run` | `<script.json>`, env `CLIENT_MCP_TARGET=vm\|silvia` | Windows QA box over ssh/scp (`tools/ops/qa-run:17-26`) |
| `tools/ops/client-mcp` | `<tool> [JSON args]`, env `CLIENT_MCP_TARGET`, `CLIENT_MCP_WAIT`, `CLIENT_MCP_QUEUE_WAIT` | the worker's file queue on the same box (`tools/ops/client-mcp:32-44`) |
| `tools/ops/qa-run-collect` | `--run <folder> --from <UTC> --to <UTC> [--chars ids] [--checks t-xx-n] [--pad 60] [--dry-run]` | TEST read-only (`tools/ops/qa-run-collect:2-24`) |
| `tools/ops/map-integrity` | `[--wz DIR] [--json] [--map ID,ID] [--limit 25] [--quiet]` | local extracted WZ XML (`tools/ops/map-integrity:215-221`) |
| `tools/ops/pq-audit` | `[--cosmic DIR] [--wz DIR] [--json] [--diff] [names…]` | local scripts + WZ + stock Cosmic (`tools/ops/pq-audit:165-171`) |
| `tools/ops/decide-probe` | `[MAIN_URL] [TONE_URL] [MODEL] [TONE_MODEL]` | the model host, 8 HTTP calls (`tools/ops/decide-probe:1-9`) |

Client-MCP worker start (owner, on the Windows box): `Start-QA.cmd` — self-elevates, refuses a second worker, archives
`logs\worker-inbox.jsonl`/`worker-outbox.jsonl` to `logs\old\`, asks "Skip the idle wait …? [Y/N, N in 20 s]"
(`tools/ops/client-mcp-win/Start-QA.cmd:1-40`).

## How it works

1. **Start a smoke / all / id run.** `autoqa-run` parses one selector (`:30-37`), refuses a second positional and an
   unknown `-*` flag, applies the release guard (`:38-42`), defaults to `smoke` (`:43`) and re-validates against
   `^[A-Za-z0-9,_-]+$` (`:44`). `enqueue()` takes `flock` on `.run-queue.lock` (`:50-51`), bumps `run-queue.seq`
   (`:52-53`), appends `"<id>\t<UTC stamp>\t<request>"` (`:54-55`) and prints `queued (#n, position k)` where `k` counts
   `run-queue.txt` + `run-queue.work` (`:56-57`). The append runs on TEST via `test_ssh "$(declare -f enqueue); enqueue …"`
   (`:62`) unless `AUTOQA_LOCAL_DIR` is set, which keeps it local for tests (`:59-61`).
2. **Pickup.** `AutoQaService.watchRunRequests()` polls every 30 s (`src/main/java/soloMapling/autoqa/AutoQaService.java:408`),
   returns early while a run is active (`:411`), then `AutoQaRunQueue.takeNext` renames the operator file away atomically
   and appends it to the server-only `run-queue.work` (`AutoQaRunQueue.java:41-53`); the log line is
   `Auto-QA run request '{}': {}` (`AutoQaService.java:413-415`). Selectors resolve in `select()`: `all`, `smoke` =
   `bg-1,vg-1,ch-1` (`:47-52,72`), otherwise a comma list with id/prefix match (`:53-70`).
3. **`--failed` and `--release`.** `--failed` calls `AutoQaRerun.plan(...)`, which refuses to carry anything when the
   mode changed (`AutoQaRerun.java:45-48`), the deployed commit cannot be read (`:51-52`) or it differs (`:54-56`) — in
   each case it says "rerun all". `--release` needs `all` and refuses `--fast`/`--failed` (`autoqa-run:38-42`); the run
   line then carries `"release": {verdict, fails, unexpectedSkips, …}` and FAILs on any SKIP not in
   `src/main/resources/soloMapling/autoqa-release-skips.tsv` (11 MANUAL ids, docs/ops/qa-suite.md:1097-1105).
4. **Collect results.** `test-status` is the read surface: one `test_ssh` heredoc reads `systemctl is-active`,
   `ActiveEnterTimestamp`, `DEPLOYED_COMMIT`, journal ERROR counts and then parses `health-latest.json`, `latest.json`
   and `bot-life.json` (`tools/ops/test-status:25-46`). Each FAIL prints `commit <9>, <when>, log <file|none (probe only)>`
   (`:62-72`), and rows carried by `--failed` show as `OLD` (`:73-81`).
5. **Pull a run's evidence.** `qa-run-collect` validates the window (≤ 6 h, `:62`), refuses live paths and the live unit
   (`:56-57`), and requires the run folder to sit under `$RUNS_ROOT/<date>/` by realpath (`:66-69`). It then reads
   `journalctl -u slums-persistence-test --utc` (`:86-88`), greps `--chars` lines out (`:91-93`), copies `logs/qa/*.log`
   files whose name stamp falls inside the padded window (`:98-108`), filters `latest.json` (`:116-127`), reads
   `DEPLOYED_COMMIT` (`:130-134`) and writes `collect.json` (`:137-145`); any missing piece exits 1 with the gaps listed
   (`:146-149`). It is read-only by construction: every remote command is `journalctl`, `find`, `cat` or `python3`
   reading a file (`:22-24`).
6. **Prove a check ran.** `autoqa-proof` maps changed files to check ids from `autoqa-coverage.tsv` (TAB
   `check_id/key/note`, `tools/ops/autoqa-proof:89-112`), cross-checked against `AutoQaCatalog.java`
   (`catalogue_ids`, `:152-170`) so an undeclared id or a repeated id is a hard error (`:169,183`). A key of `-` means
   manual-only, `-Dprop=value` means a switch the check needs (`:104-110`). Verdicts: docs-only and tests-only exit 0
   with prose (`:238-243,344`), a covered change prints a pasteable `tools/ops/autoqa-run <ids>` line (`:321,379`),
   nothing covered exits 3 (`:371,385`). It never queues anything.
7. **Feature-health sweep.** `feature-health` fetches or loads `live.json` (`:134-137,145`), reads every row of
   `tools/ops/feature-health.tsv` (`:58-65`), evaluates the rule (`:67-113`) with helpers `fail_rate`/`per_hour`/`ratio`,
   groups by feature and sorts worst-first (`:119-132`), then prints `feature-health: N OK, N SILENT, N FAILING, N SLOW
   (uptime Xh)` (`:155`) and exits 1 only when something is FAILING (`:166`).
8. **Map / PQ audit.** `map-integrity` indexes `Map.wz/Map/**` plus `Mob/Npc/Reactor/String.wz` and classifies ten issue
   kinds (`tools/ops/map-integrity:139-190,234-245`), ignoring `portalInertTarget` in the exit code (`:266`).
   `pq-audit` finds every `scripts/event/*.js` with `isPq = true` (`tools/ops/pq-audit:175-180`), parses its
   eligibility and maps (`:44-54`), then diffs the maps against stock Cosmic (`:116-130`) and labels the delta
   deliberate (level cap 255, scroll rewards, quantity/order only) or "review" (`:136-163`).
9. **Drive the client-MCP QA worker.** `qa-run` copies the script into `<QA_ROOT>/qa-scripts/<id>.json` (`:24-25`), calls
   `client-mcp run_script '{"name":id}'` with `CLIENT_MCP_WAIT=900` (`:26`), reads the `run` path out of the reply
   (`:28-33`) and `scp -r`s it to `/root/work/qa-runs/<UTC date>/<id>-<UTC>/` (`:34-37`), then prints the manifest
   summary (`:38-46`). `client-mcp` itself appends one JSON line to `logs/worker-inbox.jsonl`, remembers the outbox
   offset, and polls that file in binary mode because "a text-mode offset drifts on CRLF lines" (`client-mcp:102-114`);
   it handles `queued`/`started`/`exited`/`error`/result (`:118-135`) and gives up with exit 4 after cancelling
   (`:137-143`). The worker side records, it does not judge: the manifest `result` is `RECORDED` or `ERROR`
   (`tools/ops/client-mcp-win/src/driver.py:965-972`), and `tools/qa-runner/verdict` turns a folder into PASS/FAIL/SKIP
   per covered t-id (`tools/qa-runner/qaverdict.py:2-20`).

## Data

| Path | Written by | When |
|---|---|---|
| `Server/data/autoqa/run-queue.txt`, `run-queue.seq`, `.run-queue.lock` | `autoqa-run:52-55` | on each queued request |
| `Server/data/autoqa/run-queue.work`, `run-request.txt` | server only (`AutoQaRunQueue.java:14-20,51`) | at pickup |
| `Server/data/autoqa/report-<day>.json`, `latest.json` | `AutoQaReport.write` (`AutoQaReport.java:368-372`), one per run | after every run |
| `Server/data/autoqa/health-latest.json`, `health/<day>.jsonl` | health sweeps (`AutoQa.java:48-60`) | every `AUTOQA_SWEEP_MINUTES` |
| `Server/data/autoqa/client-verdicts/<t-id>.json` | `tools/qa-runner/verdict --out` (`ClientScriptVerdicts.java:30-31`) | on review |
| `Server/logs/qa/<id>-<UTC>.log` | `!qa` scenario suite (docs/ops/qa-suite.md:503-512) | per scenario |
| `Server/DEPLOYED_COMMIT` | `tools/ops/deploy-test:115` | every deploy |
| `/root/work/qa-runs/<date>/<id>-<ts>/` | `qa-run:34-37`, `qa-run-collect:80-145` | per scripted run |

Retention: auto-QA day files older than a month are gzipped, never deleted unless
`-Dslums.history.autoqaKeepDays=N` (`AutoQa.java:44-60`, "history is kept, never deleted by default"). `qa-runs/` has
no pruning anywhere in the repo (grep over `tools/`, `deploy/`: no owner) — it grows until someone removes folders.

**Attribution.** A result records `build`, `mode` and `commit` per check (`AutoQaReport.java:140-142`) and per run
(`:275-303`). `build` is `ServerConstants.SLUMSMS_VERSION` — the string `1.6.4` for every build of this release
(`src/main/java/constants/net/ServerConstants.java:10`), which is exactly why `deployCommit()` exists: "the build banner
cannot be used for this — every build of a release reports the same version string" (`AutoQa.java:72-88`). `commit` is
`Server/DEPLOYED_COMMIT`, so a hand-started `start-test.sh` without a deploy still reports the previous commit. **No
branch is recorded anywhere** in the report or the journal; a run is attributed to (version, commit), never to a branch.

## Switches

| Name | Default | Where |
|---|---|---|
| `SLUMS_TEST_HOST` | `192.168.0.111` | read only by `testhost.py:48-58`; `lib.sh:12-15` aborts before any ssh when refused |
| `AUTOQA_LOCAL_DIR` | unset | makes `autoqa-run` queue locally (`autoqa-run:59-61`) |
| `QA_RUNS_ROOT` | `/root/work/qa-runs` | `qa-run-collect:30` |
| `CLIENT_MCP_TARGET` | `vm` | `qa-run:17`, `client-mcp:36`; `silvia` is the owner's PC |
| `CLIENT_MCP_WAIT` / `CLIENT_MCP_QUEUE_WAIT` | `120` / `3600` s (`900` in `qa-run`) | `client-mcp:35`, `qa-run:26` |
| `worker-config.json` | lifetime 12 h, idle 10 min, queue 120 min, recover ≤ 3/h, heartbeat 15 s, health 20 s | `tools/ops/client-mcp-win/worker-config.json`; unknown or out-of-range keys refuse to start (`src/worker.py:41-62`) |
| `-Dslums.autoqa.dir` | `data/autoqa` | `AutoQa.java:66-68` |
| `-Dslums.history.autoqaKeepDays` | unset (keep forever) | `AutoQa.java:48-60` |
| `-Dslums.autoqa.parallel` / `.longestFirst` | `1` / `false` | `AutoQaScheduler.java:34-42` |
| `WZ_PATH` | `<repo>/wz` (pq-audit) / `wz` (map-integrity) | `pq-audit:168`, `map-integrity:217` |

TEST's `start-test.sh` lives on the host and was not read; which of `-Dslums.qa`, `-Dslums.attackcapture`,
`-Dslums.autoqa` and the lane count it passes today is **unknown** for this review.

## Invariants

| # | Rule | Guard |
|---|---|---|
| 1 | Only TEST's two boxes, only port 8489; nothing else may read `SLUMS_TEST_HOST` | `tools/ops/client-mcp-win/src/testhost.py:23-27,61-67`; `lib.sh:12-15` |
| 2 | The operator file is append-only; the server renames it away and writes its own | `AutoQaRunQueue.java:14-20,51` |
| 3 | One auto-QA run at a time; a queued request waits for the run in progress | `AutoQaService.java:138-140,411` |
| 4 | A pickup happens within 30 s of the previous run ending | `AutoQaService.java:408`; `autoqa-run:64` |
| 5 | `--failed` never carries across a redeploy or a mode change | `AutoQaRerun.java:45-56` |
| 6 | A release run is whole-catalogue, fresh, normal clock | `autoqa-run:38-42`; docs/ops/qa-suite.md:1097-1105 |
| 7 | One `!qa` scenario per GM, and no two runs hold the same claim (a map) | `src/main/java/soloMapling/qa/QaSuite.java:26,144-157` |
| 8 | `qa-run-collect` refuses live paths, the live unit, and windows over 6 h | `qa-run-collect:56-57,62` |
| 9 | The coverage map may not name an undeclared check, and the catalogue may not declare one twice | `autoqa-proof:169,183` |
| 10 | Feature-health touches nothing: "queues nothing, deploys nothing, restarts nothing"; live refused without `--live-readonly` | `feature-health:10,146-147` |

The claim rule is the server's, not the tooling's: `QaSuite.run` refuses `"You are already running …"` (`QaSuite.java:144-147`)
and `"… is running … on <map>; wait for it"` (`:150-157`); two lanes starting the same scenario in the same second get
their own log file via a `"-" + gm.getId()` suffix (`:158-163`).

## Origin

`custom` (no upstream equivalent in the Cosmic tree). Oldest commits, `git log --follow --format=%h | tail -1`, against
baseline `03d13cb21`:

| Path | Oldest commit | Date | Commits |
|---|---|---|---|
| `tools/ops/autoqa-run` | `f0c621c6f` | 2026-10-23 | 8 |
| `tools/ops/test-status` | `383b0df55` | 2026-10-23 | 7 |
| `tools/ops/client-mcp` | `2ebf31c5a` | 2026-10-24 | 5 |
| `tools/ops/pq-audit`, `map-integrity` | `468d15de7` | 2026-10-25 | 3 / 2 |
| `tools/ops/client-mcp-win/worker-config.json` | `490b005c2` | 2026-10-27 | 1 |
| `tools/ops/decide-probe` | `9a881e6db` | 2026-10-28 | 1 |
| `tools/ops/autoqa-proof`, `qa-run`, `qa-run-collect` | `af7a68986`, `0e0119945`, `a30cee279` | 2026-10-30 | 2 / 2 / 4 |
| `tools/ops/lib.sh`, `client-mcp-win/src/testhost.py` | `243fd5591`, `ad8a48f58` | 2026-11-01 | 4 / 2 |
| `tools/ops/feature-health`, `feature-health.tsv` | `00a2131bb` | 2026-11-04 | 1 / 1 |

`tools/qa-runner/` (the scripted-client orchestrator) is documented with the client QA model, not here; this page covers
the operator's wrappers.

## Tests and QA

| Test | Covers | Not covered |
|---|---|---|
| `tools/ops/test_autoqa_run.sh:1-16` | local-mode queue: two requests print `queued (#1, position 1)` / `(#2, position 2)`, the TSV has both lines in order, the stamp is ISO-Z, and the legacy `run-request.txt` is not written | anything that reaches TEST: no ssh path is exercised, so pickup, `--failed` and `--release` are untested |
| `tools/ops/test_autoqa_proof.py:1-211` (16 tests) | glob semantics (`*`, `**`, `?`), the KPQ→`t-mx-1..t-mx-6` mapping, exit 3 for an uncovered file, docs-only/tests-only verdicts, `all` for an engine file, the count derived from the catalogue (source must not contain `186`), trigger-vs-coverage `-Dslums.mechanics=true`, range mode on a throw-away repo, and that the printed line queues the same ids under `AUTOQA_LOCAL_DIR` | the real repo's `autoqa-coverage.tsv` rows (the test uses temp maps for the failure cases); no test touches a server |
| `tools/ops/test_feature_health.py:1-72` (8 tests) | `fail_rate`/`ratio`/`per_hour`, missing path is SILENT unless `optional`, a broken rule is FAILING, keys with spaces and colons, worst-first ordering, exit 0/1/2, live refused (`:65-70`) | reads a fixture, never a real `live.json`; no timing or uptime edge beyond the fixed 7200 s |
| `tools/ops/test_testhost.sh:1-76` | the shared guard: default argv `root@192.168.0.111` + `id_slums_lxc` + BatchMode, `SLUMS_TEST_HOST` override, refusals | no remote call, by design |
| `tools/ops/client-mcp-win/tests/test_worker.py` (19) | idle wait, queue give-up, cancel, lifetime, recovery (3 strikes, cap, owner wait), heartbeat, exit refusal, config validation | no Windows service install, no real client start |
| `tools/ops/client-mcp-win/tests/test_qatools.py` (22) | vault login, anchors, world/channel/character clicks, `qa_events` truncation, and the host guard (`:249-295`) | no live client |

No script under `tools/`, `deploy/` or CI runs these: `test_autoqa_run.sh`, `test_autoqa_proof.py` and
`test_feature_health.py` are only named in docs/ops/qa-suite.md:1336-1342 and
docs/architecture/ops/hosts.md:171-176. Running them is a manual step (`python3 tools/ops/test_autoqa_proof.py`,
`python3 -m unittest tools/ops/test_feature_health.py`).

## Known issues

| Card | One line | What an operator should do today |
|---|---|---|
| b284 (`/root/work/STATUS-1.6.4.md:80`) | Multi-lane auto-QA gives false failures: 8 of the 22:00 run's FAILs passed when rerun one at a time; triage blames the scheduler's key declarations — `keys(check)` is literally `check.holds()` (`AutoQaScheduler.java:53-55`) | Re-run the FAILs serially before believing them: `tools/ops/autoqa-run <ids> --failed` (or the plain ids) on the same commit, then `tools/ops/test-status --checks`. docs/ops/qa-suite.md:1011 already asks for a serial-vs-parallel diff per setting |
| b286 (`/root/work/STATUS-1.6.4.md:82`, ticket 179) | The QA worker loses batched ready events, so consecutive waits time out though the signal was recorded — `SilviaWorker.wait_signal` searches only the newest batch while `seen` keeps the rest (`tools/qa-runner/qarunner.py:476-497`) | Treat a timeout on the second of two consecutive waits as suspect, and rerun the script serially; the ticket's rule is "a FAIL that is also b286, rerun before acting" |
| b287 (`/root/work/STATUS-1.6.4.md:83`) | t-xc-5 fails serially too — a real product lead, not a harness artefact | Do not wave it off as b284 |

`NEW-FINDING: tools/ops/client-mcp-win/src/testhost.py:4` — the docstring still says "DEFAULT_HOST is 164 today and
flips to 192.168.0.111 then", while `:24` has already flipped. Harmless (the constant is what runs), but it is the file
every QA tool's safety rests on.

`NEW-FINDING: tools/ops/qa-run-collect:15-20 vs docs/qa/client-scripts/run-folder.md:13-20` — the run-folder doc lists
`server/feed.jsonl` as collector output, but `qa-run-collect` never writes it; `feed.jsonl` comes from the orchestrator
(`tools/qa-runner/`). A folder collected without the orchestrator will lack the file the manifest's `evidence` points at.

`NEW-FINDING: tools/ops/qa-run:9` — the tool's own header says it "only RECORDS (result RECORDED or ERROR)" and that "an
agent reviews the run folder and marks PASS/FAIL", but nothing enforces that an agent ran: a run folder with
`result: RECORDED` can be left unreviewed indefinitely, and only `tools/qa-runner/verdict` (a separate command) turns it
into a verdict.

`NEW-FINDING: tools/ops/autoqa-proof:238-243` — `is_docs` treats any `.md` anywhere as docs, so a change to a
`src/main/resources/**/*.md` file exits 0 with "docs only, no proof run needed" even though it ships in the jar.

## State

`working`, with two open accuracy defects and one review gap. Evidence: the queue path is complete and tested offline
(`test_autoqa_run.sh`, `test_autoqa_proof.py:169-187`), the guard is tested at shell level (`test_testhost.sh`) and in
`test_qatools.py:249-295`, the report carries per-check evidence (build/mode/commit/logs) so a FAIL can be cited
(`AutoQaReport.java:125-148`), and docs/ops/qa-suite.md records full runs on TEST (`d4f103b0e`, 2026-10-30, 181 checks,
9,578 s; 187 checks at 4 lanes the next day). Partial: parallel accuracy (b284) and the worker's batched-event handling
(b286) are open, so a FAIL is a hypothesis until it reproduces serially. Nothing in this document was verified by
running a tool: no run was queued, no ssh was made, no test was executed for this review.

## Open questions

* Does `autoqa-run --feature` (`:29`) put the queue in the isolated bot-brain copy
  (`/opt/mapleroot-dev/bot-life-160/Server/data/autoqa`) and does a server started there watch it? `test-status --feature`
  reads that unit (`test-status:26-27`), but no repo file starts the unit, so whether the watcher runs is unverified.
* `--release` FAILs on any SKIP not on the 11-id allow-list. `qa-and-autoqa.md` notes that an infrastructure SKIP is
  indistinguishable from a feature SKIP (`AutoQaRunner.java:355-400`), so a server that is not fully up can fail a
  release run. Is the operator expected to read the 11 reasons each time, or should the allow-list grow? Not decided in
  any doc read for this page.
* Where does `/root/work/qa-runs/` get pruned? No script in the repo removes it, the run-folder doc asks for folders
  under ~50 MB (docs/qa/client-scripts/run-folder.md:28), and nothing enforces that either.
* `docs/ops/qa-suite.md:950` documents a lane slot table for up to 10 lanes; b284 was measured at 10. Which lane count
  the release run is meant to use is not written down — `AutoQaScheduler.parallel()` defaults to 1
  (`AutoQaScheduler.java:40-42`) and no release conf sets the property (docs/release/1.6.4.conf:201 SERVER_SET).
