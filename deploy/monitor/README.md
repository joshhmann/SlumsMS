# slumsms-monitor (tickets 42 and 46)

Health checks every minute with Discord alerts, a Discord status message and release news, for one or more game
servers on 192.168.0.164. Python 3 standard library plus PyYAML; read-only towards the server.

## What it checks (`slumsms-monitor check <target>`, every minute)

| Check | Fails when | Alerts after |
|---|---|---|
| server process | the unit is not `active` | 1 run |
| login port | no v83 hello packet from the login port (skipped while the unit is down) | 2 runs |
| error rate | more than `ERROR_MAX` (60) `ERROR`/`Exception` journal lines in `ERROR_WINDOW_MIN` (5) minutes | 1 run |
| memory | old gen above `HEAP_WARN_PCT` (92%) of `-Xmx` (jstat), or RSS above `RSS_WARN_PCT` (175%) of `-Xmx` | 5 runs |
| database | the DB container is not running/healthy, or `SELECT 1` fails | 2 runs |
| disk space | under `DISK_MIN_FREE_GB` (15) or `DISK_MIN_FREE_PCT` (5%) free on `/`, the server folder and `DISK_PATHS` | 1 run |
| bot population | online bots (botroster: `LastLoginAt > LastLogoutAt`) under `BOTS_MIN_RATIO` (60%) of the director target, after `BOTS_GRACE_MIN` (20) minutes of uptime; or `[Director] tick failed` lines | 10 runs |
| bot chat LLM / Decide | `LLM_URL` (`192.168.0.17:8095/health`) or `DECIDE_URL` (`:8900/health`) not 2xx within 5 s, twice 3 s apart | 1 run |
| scheduled jobs | a timer in `TIMERS` is not active, its last run failed, or it has not run for `TIMER_MAX_AGE_H` (26) hours | 1 run |

The director target is computed like `BotPopulationCurve` does: peak and curve from the boot line
`Population director started: peak N, curve C, tick Ts` (cached per server start), weekend lift and timezone from
`config.yaml` (defaults from `ServerConfig`). Bots are only checked when the process runs with
`-Dsolomapling.enabled=true -Dsolomapling.autopopulate=true`. The bot count is the roster's own login/logout stamps,
so after a crash it can overcount until bots log in again; the server-process alert covers the crash itself.

The DB user, password and schema are read from the server's `config.yaml` at run time and passed to
`docker exec ... mysql` through the environment (never on the command line). Only `SELECT`s are run.

Every login probe adds one `DEBUG ... Client connected to login server from 127.0.0.1` journal line a minute.

## Alerts

- One message per run at most, listing each check that just crossed its `FAIL_AFTER_<check>` count:
  `[live] **ALERT** login port: port 8485 connection failed (Connection refused) (since 14:02 UTC)`.
- `**RECOVERED** ...` once the check passes again (with how long it was down), `**STILL FAILING** ...` every
  `REMIND_MIN` (120) minutes while it stays down, and no more than `MAX_MESSAGES_PER_HOUR` (12) messages an hour
  (the rest go to the local log).
- No secrets: messages carry only check names, ports, counts and host:port of the LLM endpoints. Webhook URLs and
  `password=`/`token=` patterns are scrubbed from anything sent or logged. Journal lines are counted, never quoted.
- **Maintenance:** while `MAINTENANCE_FILE` exists (live: `/etc/slumsms-monitor/live.maintenance`) alerts go to the
  local log only. `touch` it before a planned stop (deploy script), `rm` it after; the recovery then is quiet too.
- **No webhook configured:** everything goes to `/var/log/slumsms-monitor/<target>.log`.

State: `/var/lib/slumsms-monitor/<target>/` (`checks.json` alert state, `status.json` the last results, for the
ticket 35 dashboard strip). See the current results any time with `slumsms-monitor check <target> --dry-run`.

## Status message (ticket 46, `slumsms-monitor status <target>`, every 5 minutes)

Posts one message to `STATUS_WEBHOOK_URL` and edits that same message from then on (a fresh one if it was deleted):

```
**SlumsMS (Bera)**
Status: **Online**
Players online: 3
Version: 1.3.1
Uptime: 2d 4h
Updated <t:...:R>
```

Online means the unit is active and the login port answers. Players are real players only: accounts in game
(`loggedin = 2`) that own no character with id >= 1000000000 (bots). `VERSION` comes from the target config (bump it
with each deploy) or `VERSION_FILE`. `STATUS_SHOW_BOTS=1` adds a bots-online line. Nothing is read back from Discord.

## News (ticket 46, `slumsms-monitor news <target>`)

Reads the launcher news (`NEWS_FILE`: the published `manifest.json` with its `news` array, or a bare array like
`/srv/slums-staging/news-1.3.0.json`) and posts every entry not posted before, oldest first, as
`**title** (date)` + body. `install.sh --news` seeds the current entries as already posted and adds a
`slumsms-news-<target>.path` unit, so publishing a new manifest (the publisher writes it last) posts the new entry.
By hand: `slumsms-monitor news live --file /srv/slums-staging/news-1.4.0.json --dry-run`.

## Configuration

`/etc/slumsms-monitor/<target>.conf` (from `targets/<target>.conf`; keys and defaults at the top of
`slumsms-monitor`) and `/etc/slumsms-monitor/<target>.env` (root, 0600, not in git):

```
ALERT_WEBHOOK_URL=https://discord.com/api/webhooks/...
STATUS_WEBHOOK_URL=https://discord.com/api/webhooks/...
NEWS_WEBHOOK_URL=https://discord.com/api/webhooks/...
# WEBHOOK_URL= covers any of the three left out
```

Test a webhook: `slumsms-monitor test-webhook live alerts` (or `status`, `news`).
Without Discord: `mock-webhook.py --port 8599 --out messages.jsonl` takes the same POST/PATCH calls, then
`WEBHOOK_URL=http://127.0.0.1:8599/api/webhooks/1/test`.

## Install

The folder is on 164 at `/opt/slumsms-monitor` (copy of this one). As root:

```
/opt/slumsms-monitor/install.sh <target> [--status] [--news] [--conf FILE] [--replace-conf]
/opt/slumsms-monitor/install.sh <target> --uninstall
```

It installs `/usr/local/bin/slumsms-monitor`, the template units `slumsms-monitor@`, `slumsms-status@`,
`slumsms-news@` and `/etc/slumsms-monitor/<target>.{conf,env}`, prints a dry-run check, and enables
`slumsms-monitor@<target>.timer` (plus the status timer / news path unit when asked). It never touches the game
server's unit or files.

### Live (the owner runs this)

```
sudo /opt/slumsms-monitor/install.sh live --status --news
sudo nano /etc/slumsms-monitor/live.env          # paste the webhook URLs
sudo slumsms-monitor test-webhook live alerts
sudo slumsms-monitor status live                  # posts the status message now
```

Until the webhook URLs are in, alerts and status changes go to `/var/log/slumsms-monitor/live.log`. Before a deploy:
`touch /etc/slumsms-monitor/live.maintenance`; after: `rm` it and set `VERSION=` in `/etc/slumsms-monitor/live.conf`.
