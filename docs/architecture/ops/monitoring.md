# Monitoring and the staff dashboard

## Purpose

Two read-only surfaces tell an operator whether the live server is healthy without logging into the game: `slumsms-monitor`, a
per-target Python script driven by systemd timers that probes the unit, the login port, the game log, the journal, memory, the DB
container, disk, the bot population, the LLM endpoints, the backup/nav timers and the cloud-LLM spend, and pushes alerts to a
webhook; and the staff dashboard, a static page that reads JSON snapshots the server's `DashboardExporter` and the map export
write to disk. Neither system can change game state — the monitor only runs `SELECT`s, `systemctl show`, `jstat` and `docker
inspect`, and the dashboard only serves files. The monitor is also the thing that would report its own absence, and cannot.

## Entry points

| Command | Unit / caller | What it does |
|---|---|---|
| `slumsms-monitor check <target> [--dry-run] [--verbose]` | `deploy/monitor/slumsms-monitor@.service:7` (timer `@.timer:5-7`, every minute) | probes + alerts; writes `status.json` (`deploy/monitor/slumsms-monitor:1188`) |
| `slumsms-monitor status <target>` | `slumsms-status@.service:7` (timer 5 min, `:5-7`) | posts/edits one Discord status message (`:1202`) |
| `slumsms-monitor news <target> [--seed] [--file F]` | `slumsms-news@.service:9`, fired by `slumsms-news-<target>.path` on `PathChanged=<NEWS_FILE>` (`deploy/monitor/install.sh:84-96`) | posts new launcher news entries (`:1318`) |
| `slumsms-monitor announce-feed <target> [--seed]` | `slumsms-announce@.service:7` + `<target>.path` (`deploy/monitor/install.sh:108-120`) | posts new world-event lines as Cody (`:1622`) |
| `slumsms-monitor announce <target> <text…> [--cody]` / `maintenance <target> start\|end [--minutes N] [--reason R] [--no-announce] [--client V]` / `client-ready <target> <ver>` / `test-webhook <target> <kind>` | operator, and generated deploy scripts (`deploy/v1.4.1/deploy.sh:75`, `tools/release/release:210`, `tools/release/publish-client:154-155`) | public posts; maintenance quiet-mode file |
| `sudo install.sh <target> [--status] [--news] [--announce] [--conf F] [--replace-conf]` / `--uninstall` | owner, on 164 from `/opt/slumsms-monitor` | installs binary + six units + conf/env (`deploy/monitor/install.sh:53-126`) |
| `slums-dashboard-extras <instance> [--dry-run] [--conf F]` | `deploy/dashboard/slums-dashboard-extras@.service:7` + timer 1 min | copies `status.json` into `data/ops/status.json`, writes `gm.json`, nav `world.json` (`tools/dashboard/extras/slums-dashboard-extras:284-320`) |
| `java … soloMapling.dashboard.DashboardMapExport --out=… --nav-reports=… --budget-minutes=40` | `deploy/dashboard/slumsms-dashboard-maps.service:13`, timer hourly at `*:40` (`…maps.timer:7`) | map/graph/learning JSON |
| `tools/ops/install-live-dashboard [--check\|--go\|--remove]` | owner | writes the :8096 nginx site, `nginx -t` then reload, expects 401 (`tools/ops/install-live-dashboard:120-132`) |

## How it works

1. **One target = one config pair.** `Target.__init__` (`deploy/monitor/slumsms-monitor:153-167`) rejects any name not matching
   `[A-Za-z0-9_.-]+` (`:155-156`), requires `/etc/slumsms-monitor/<name>.conf` (`:158-160`), then layers
   `<name>.env` over it (`:163`). State is `/var/lib/slumsms-monitor/<name>/`, log `…/log/<name>.log` (`:164-167`).
2. **Defaults + conf + env.** `DEFAULTS` (`:50-112`) holds every threshold as a string; the target conf overrides; env holds only
   webhook URLs. `Target.webhook(kind)` (`:180-185`) maps `ALERT|STATUS|NEWS` to `<KIND>_WEBHOOK_URL` else `WEBHOOK_URL`, and
   `ANNOUNCE` to `ANNOUNCE_WEBHOOK_URL` else `NEWS_WEBHOOK_URL` — never to the private ops channel.
3. **Eleven checks per run** (`run_checks`, `:770-1019`), in order: unit (`:781-787`, `systemctl show ActiveState` via `:270-286`);
   login port (`:789-797`, TCP connect + the base client's 4-byte hello, `:289-308`, skipped while the unit is down `:793`); game-log silence
   (`:799-853`); journal error rate (`:856-866`, `re.search(r"\bERROR\b|Exception")`); memory (`:868-888`, `jstat -gc` old gen and
   RSS ÷ `-Xmx`); DB container + `SELECT 1` (`:890-906`, `docker exec` env-passed credentials, `container_health:401`); disk
   (`:908-926`, deduped by `st_fsid` `:914-917`); bot population (`:928-965`, roster SQL `:485`, director peak from the journal
   `:452`); LLM/Decide health (`:967-977`, one retry after `sleep(3)`); scheduled jobs (`:979-1005`); cloud spend/balance
   (`:1007-1019`, managed results `:1016`).
4. **The log-silence rule** (card b268, `:799-853`): the game log is `<SERVER_DIR>/BotLog.txt` unless `GAME_LOG_FILE` is set
   (`game_log_path:562-570` — deliberately not the rolled `logs/cosmic-log.log`). A run is *fresh* when the file is new, shrank, or
   changed inode (`:828-831`); growth ≥ `LOG_MIN_BYTES` per elapsed minute counts as alive (`:832`). Silent **and** the login port
   answers ⇒ failure (`:846-849`); silent with the port down is left to the login check (`:851-853`); within `LOG_GRACE_MIN` of boot
   nothing is judged (`:813-816`).
5. **Alert bookkeeping** (`cmd_check`, `:1095-1189`): per-key `{fails, alerted, failing_since}` (`:1112`); a check alerts when
   `fails >= FAIL_AFTER_<key>` (`:1139-1144`), `**RECOVERED**` once it passes (`:1132`), `**STILL FAILING**` every `REMIND_MIN`
   (`:1147-1150`). One message per run (`:1159`), suppressed into the local log in maintenance mode (`:1163-1171`) or above
   `MAX_MESSAGES_PER_HOUR` (`:1172-1173`). The status file is written last, always, and `cmd_check` returns 0 whatever happens
   (`:1185-1189`).
6. **Delivery** (`webhook_send:1027-1058`, `deliver:1060-1082`): `POST …?wait=true`, `PATCH /messages/<id>` for edits, 429 retry
   capped at 10 s; content `scrub()`ed, and for `ANNOUNCE` `scrub_public()`ed (IPs, bearer tokens, 32-char tokens, `@everyone`
   defanged, `:239-243`), truncated to 1900 chars (`:1062`). No webhook ⇒ local log only (`:1067-1069`). A failed send re-raises and
   un-alerts the keys so the next run retries (`:1080`, `:1176-1181`).
7. **Dashboard export.** `DashboardExporter.startIfEnabled()` is called once from `src/main/java/net/server/Server.java:1038`; the
   switch is `-Dsolomapling.dashboard` (`DashboardExporter.java:36`), dir `-Dsolomapling.dashboard.dir` default `reports/dashboard`
   (`:64`), period `Math.max(15, …periodSeconds)` default 60 (`:65`). A daemon thread named `dashboard-export` at minimum priority
   runs `runSafely` first at 10 s (`:72-77`); `runSafely` swallows every `Throwable` and warns at most every 15 min (`:147-162`),
   so a broken snapshot never stops the schedule or the game. `live.json` is written through `AtomicFiles.writeJson`
   (`:199`, `AtomicFiles.java:21-49`: temp file, 0644, `ATOMIC_MOVE`), plus `calibration.json` (`:200-203`) and a series point.
8. **Dashboard serving.** nginx serves the static app from `/srv/slums-dashboard/web` and the snapshots by alias
   `location /data/ → /srv/slums-dashboard/data/` with `Cache-Control: no-cache` (`deploy/dashboard/slumsms-dashboard.nginx.conf:23,41-50`);
   dotfiles are denied (`:37-39`). Two sites: TEST `listen 8091` + basic auth (`:17,20-21`); live `listen 8096`, `allow
   192.168.0.0/24; deny all;`, basic auth, same root/alias (`deploy/dashboard/slums-dashboard-live.nginx.conf:11,15-21,39-40`).
   The app polls `data/live.json` and `data/ops/status.json` every 15 s (`tools/dashboard/web/app.js:822-826,845`).
9. **The ops strip.** `slums-dashboard-extras` copies each target's `status.json` into `data/ops/status.json` (`…extras:284`) —
   only `name/ok/skipped/detail[:200]`, no secrets (`monitor_status:77-98`, comment `:89`) — so nginx never reads `/var/lib`. The
   strip is green/red from those checks and turns **amber when the snapshot is older than 300 s** with the text `monitor silent
   <age>` (`app.js:104-108`); the live-snapshot dot uses 180 s/900 s bands (`app.js:85-88`).

Every signal, its source, the default threshold and what actually happens when it trips (one minute per run, `@.timer:6`):

| Signal | Source | Threshold / default | Action | Cite |
|---|---|---|---|---|
| server process | `systemctl show ActiveState` | not `active`, 1 run | alert | `slumsms-monitor:781-787`, `FAIL_AFTER_unit:109` |
| login port | TCP + hello to `LOGIN_PORT` | no answer, 2 runs | alert | `:789-797` |
| game log silence | `BotLog.txt` size vs elapsed | <1024 B/min for 5 min while the port answers, 2 runs | alert | `:799-853` |
| journal errors | `journalctl -u` last 5 min | >60 `ERROR`/`Exception` lines, 1 run | alert | `:856-866` |
| memory | `jstat -gc` + `/proc` RSS | old gen >92 % or RSS >175 % of `-Xmx`, 5 runs | alert | `:868-888` |
| database | `docker inspect` + `SELECT 1` | container not running/healthy or query fails, 2 runs | alert | `:890-906` |
| disk | `statvfs` per `DISK_PATHS` + `SERVER_DIR` | <15 GB or <5 % free, 1 run | alert | `:908-926` |
| bot population | roster SQL + director peak | <60 % of target after 20 min uptime, 10 runs | alert | `:928-965` |
| LLM / Decide | `GET …/health` | non-2xx twice 3 s apart, 1 run | alert (+ "bots fall back") | `:967-977` |
| scheduled jobs | `systemctl show <t>.timer/.service` | inactive, failed, or >26 h since last run, 1 run | alert | `:979-1005` |
| cloud spend | `<server>/data/convo/cloud-budget.json` | >80 % of the daily cap, once a day | alert / once-only recover | `:641-689` |
| cloud balance | `GET <base>/user/balance` | <$5, or 3 failed calls in a row | alert | `:693-755` |
| **server health sweep** | `<server>/data/autoqa/health-latest.json` | — | **P2-8: opt-in `auto-QA health` check when the target sets `HEALTH_FILE`** (persistence-test only); also read by `tools/ops/test-status:40` | `HealthSweeper.java:118` |
| **monitor's own liveness** | `status.json` age | 300 s | **no action** — amber strip only, no message | `app.js:105-108` |
| **unit failed / timer masked** | systemd | any | **no action** — no `OnFailure=`, exit 0 always | `slumsms-monitor:1189` |

## Data

| Path | Writer | When |
|---|---|---|
| `/etc/slumsms-monitor/<target>.conf`, `<target>.env` (0600 root, secret) | `deploy/monitor/install.sh:60-70` | install |
| `/var/lib/slumsms-monitor/<target>/{checks,cloud,status,status-message,news,announce,director}.json` | monitor (`save_json:216-220`) | each run / each post |
| `/var/log/slumsms-monitor/<target>.log` | `Target.log:201-205` | every alert, suppressed alert, status change |
| `/var/lib/slumsms-announce/<target>.jsonl` | the game server (`-Dslums.announceFile`), read by `announce-feed` | world events |
| `/srv/slums-dashboard/data/live.json`, `calibration.json`, `series/`, `days.json` | `DashboardExporter` | every 60 s / hourly roll |
| `/srv/slums-dashboard/data/nav/*`, `world.json` | `DashboardMapExport` / extras | hourly at `:40` |
| `/srv/slums-dashboard[-test]/data/ops/{status,gm}.json` | `slums-dashboard-extras` | 1 min / 5 min |
| `<server>/data/autoqa/health-latest.json` + `health/<day>.jsonl` | `HealthSweeper.java:118`, every `AUTOQA_SWEEP_MINUTES` (5, `src/main/java/config/ServerConfig.java:207`) | read by the monitor's opt-in `HEALTH_FILE` check (P2-8) and `tools/ops/test-status:40` |

## Switches

- `SLUMSMS_MONITOR_CONF_DIR|STATE_DIR|LOG_DIR` override the three roots (`slumsms-monitor:44-46`); `SLUMSMS_MONITOR_STATE_DIR`
  is honoured by the extras script too (`tools/dashboard/extras/slums-dashboard-extras:40`).
- Per-target conf keys (defaults at `slumsms-monitor:50-112`): `LABEL UNIT LOGIN_HOST LOGIN_PORT SERVER_DIR GAME_LOG_FILE
  DB_CONTAINER DB_SCHEMA DISK_PATHS TIMERS VERSION VERSION_FILE STATUS_TITLE STATUS_SHOW_BOTS NEWS_FILE MAINTENANCE_FILE
  ANNOUNCE_FILE ANNOUNCE_NAME CLOUD_ENV_FILE CLOUD_BUDGET_FILE CLOUD_WARN_PCT BALANCE_MIN_USD LLM_URL DECIDE_URL` and the
  thresholds in the table below. `SLUMS_TEST_HOST` is unrelated (the deploy tools only).
- Server side: `-Dsolomapling.dashboard[.dir|.periodSeconds]` (off by default, `DashboardExporter.java:36-38,64-65`);
  `AUTOQA_SWEEP_MINUTES` (5). Nothing else in these two subsystems reads an environment variable.

## Invariants

- The monitor never writes inside `SERVER_DIR`; it reads `config.yaml`, `BotLog.txt`, `data/convo/cloud-budget.json` and runs only
  `SELECT`s (`deploy/monitor/README.md:59-60`, `db.query("SELECT 1")` at `:900`).
- No secret reaches a message or the log: `scrub()` on every check detail (`add()` at `:779`) and every payload (`:1062`), and
  `scrub_public()` additionally for the public channel (`:239-243`).
- A failed delivery is not spent: the alert un-sets `alerted` and rolls back once-only warnings (`:1176-1181`).
- Maintenance suppresses, never records: alerts during maintenance are not counted as alerted (`:1163-1171`).
- The dashboard reads only files the exporter wrote atomically; a half-written JSON is impossible by rename (`AtomicFiles.java:38-44`).
- `slumsms-monitor@.service` is `Type=oneshot` with `TimeoutStartSec=50` (`:6,10`) — a run longer than 50 s is killed by systemd.
- Neither `deploy/monitor/` nor `deploy/dashboard/` has `OnFailure=` or `Restart=` anywhere; the only `Restart=` in `deploy/` is
  `deploy/slumsms.service.example:12` (grep over `deploy/`, 2026-11-05).

## Origin

`eb9e092b1` — `slumsms-monitor`, `install.sh`, `README.md`, `mock-webhook.py`, the monitor/status/news units, `targets/*`
(9 commits, newest `30dfd5834`); `31f68cb2c` `test_log.py`; `30dfd5834` `test_bots.py`, `test_news.py`; `a70958b60` `test_cloud.py`;
`cfb19c8a3` `slumsms-announce@.service`, `test_announce.py`. Dashboard: `a3d8a6ebf` nginx confs, `*maps*` units,
`DashboardExporter`, `AtomicFiles`, `app.js`; `196a52e20` extras units/conf/script; `7813bcee9` `*maps-test.service`;
`b746d8899` `install-live-dashboard`; `e1f005b5e` `HealthSweeper`. Baseline `03d13cb21` contains neither directory.

## Tests and QA

| File | Tests | Covers |
|---|---|---|
| `deploy/monitor/test_log.py` (189 lines) | 6 | the log-silence rule: silent log + answering port alerts after two samples (`:108`), growing log with a stale mtime (`:125`), rotation (`:135`), port down (`:146`), switched off (`:159`), restart grace (`:165`) |
| `deploy/monitor/test_announce.py` (541) | 43 | rate cap, gap, dedupe, fallback rules, maintenance, Cody's lines, the deploy hook — against `mock-webhook.py:51-60` |
| `deploy/monitor/test_cloud.py` (439) | 30 | `budget_tick` (`:143`), budget file shapes (`:215`), `balance_tick` (`:296`) with a local `http.server` |
| `deploy/monitor/test_bots.py` (236) / `test_news.py` (205) | 13 / 20 | roster/settled counting; news seeding and dedupe |
| `tools/dashboard/extras/test_world.py` (141) | 15 | nav world-graph dead-end derivation |

`mock-webhook.py` (92 lines) is a Discord-shaped recorder (`POST`, `PATCH /messages/<id>`, regex path guard `:52`) so no test
touches a real channel. **Not covered:** unit/login/memory/db/disk/errors/timers checks, `cmd_status`, `monitor_status` in the
extras, `install.sh`, the strip's amber/"monitor silent" branch, and any nginx behaviour beyond `nginx -t` at install time. No CI
runs any `deploy/monitor/test_*.py`: `.github/workflows/build.yml` is the only workflow and does no Python.

## Known issues

| Id | One line |
|---|---|
| b268 (S2, shipped-waiting) | Log-silence check exists and is tested, but the proof it fires on live has not landed (`/root/work/STATUS-1.6.4.md:61`); merged as `2da9f8f55` into release-1.6.3. |
| b299 (S2) | Live disk was 95 % full; mitigated Nov 3 (530 G, 123 G free). The disk check runs at `:908-926` and would have fired at <15 GB / <5 %. |
| NEW-FINDING | **The monitor cannot report its own silence.** No heartbeat unit, no `OnFailure=` on any monitor timer, and `cmd_check` always exits 0 (`:1189`), so a masked/failing timer or a crashed run produces nothing but an amber strip nobody is watching. |
| NEW-FINDING | `run_checks` runs unguarded inside `cmd_check`: an unexpected exception (e.g. `int(t.num("LOGIN_PORT"))` on a bad conf, `:790`) skips the `status.json` write at `:1188`, so both the strip and the alert path go quiet without a log line. |
| NEW-FINDING | `/var/log/slumsms-monitor/*.log` has no rotation: nothing in `deploy/` mentions logrotate, and logged-only alerts grow the file indefinitely (b299 shows disk pressure is real). |
| NEW-FINDING | `save_json` (`:216-220`) renames without `fsync`, so a host crash can lose the last alert state — the next run re-alerts, which is benign, but `cloud.json`'s once-a-day flags can repeat. |
| NEW-FINDING | The `**ALERT**` text is capped at 1900 chars (`:1062`) with no per-check truncation; a journal-burst detail plus ten checks can silently drop the tail of a message. |
| ticket 42 | closed 2026-10-25; the dashboard strip was left unbuilt at close and exists now only via the extras script (`docs/tickets/42-live-monitoring-alerts.md:6`). |
| ticket 35 | live dashboard install still open; the "amber when the monitor goes quiet" requirement (`docs/tickets/35-admin-dashboard.md:57`) is met only visually (`app.js:105-108`). |
| NEW-FINDING | `HealthSweeper` writes `health-latest.json` every 5 min with heap, pool, gate and phase data, and **no monitor check reads it** (only `tools/ops/test-status:40`). It is the one server-side health signal that could survive a hung world loop, and it is unused. |

## State

**Monitor: working, with a blind spot on itself.** Evidence: eleven checks with thresholds, defaulted `FAIL_AFTER_*` counts and
per-target confs (`slumsms-monitor:770-1019`, `targets/live.conf:2-19`); 112 tests across five `test_*.py` covering the newest and
riskiest rules; systemd timers and a documented install path (`deploy/monitor/install.sh:53-126`, `deploy/monitor/README.md:206-239`). The blind spot is the
silence class above: nothing in the repo can turn "monitor stopped running" into a notification, and the only in-repo reader
that notices is the dashboard strip after 300 s.

**Dashboard: working on TEST, partial on live.** Evidence: the exporter, atomic writer, series store, map export, extras job and
two nginx sites are all present and cited above; TEST is installed per `docs/tickets/35-admin-dashboard.md:66-73`. Live is
undetermined: `docs/release/1.6.4.conf` carries no `-Dsolomapling.dashboard` (grep, 0 hits) while `deploy/dashboard/extras-live.conf:1`
still says "not installed yet" and `slums-dashboard-live.nginx.conf:2` assumes `/srv/slums-dashboard/data` exists.

**Staleness handling: working, pull-only.** `app.js:85-88` (live: live/delayed/stale at 180 s/900 s) and `:104-108`
(ops: amber `monitor silent` past 300 s) are the whole mechanism; the extras script passes a missing snapshot through as an
error string (`…extras:93-96`), which renders as a grey pill rather than an alert.

## Open questions

- Is live monitoring installed today? `docs/ops/lxc125-architecture.md:73-86` says "installed for the test server only; live
  waits on the owner", while `docs/tickets/42-live-monitoring-alerts.md:5` says "done in v1.4.0, timers on live" and
  `/root/work/STATUS-1.6.4.md:61` treats live log-silence as awaiting proof. The repo records both states; only the host can say.
- Does live's `slumsms.service` still carry `-Dsolomapling.dashboard=true` and `.dir`? `docs/release/1.6.0.conf:78` adds them,
  `1.6.1`–`1.6.4` do not mention the flag at all, and `tools/ops/install-live-dashboard:20-21` says the page stays empty without
  it. `docs/architecture/server/site-and-dashboard.md:209` and `docs/architecture/ops/hosts.md:198-201` already ask this; it
  stays open because the repo cannot see the unit file.
- Why does `targets/live.conf:10` still say `VERSION=1.4.0` while 1.6.4 is being released? The status message would advertise
  the wrong version until an operator edits the conf (`deploy/monitor/README.md:228-229`).
- `docs/release/1.6.4.conf:271` sets 69 flags; none of them is a monitoring flag, so a new check that depends on a server
  property (e.g. `-Dslums.announceFile`, which `deploy/monitor/install.sh:121-122` prints but cannot add) needs an owner edit the release
  tooling does not perform — should `release` verify it, the way it already calls `maintenance` (`tools/release/release:210`)?
- Should the monitor alert on its own `status.json` age (or on a heartbeat the extras script could read), closing b268's
  sibling failure? Nothing in the repo does today; the design is a one-line check against a file the extras job already reads.
