# slumsms-monitor (tickets 42 and 46)

Health checks every minute with Discord alerts, a Discord status message, release news and public player
announcements, for one or more game servers on 192.168.0.164. Python 3 standard library plus PyYAML; read-only towards the server.

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
  local log only. `slumsms-monitor maintenance <target> start` before a planned stop (generated deploy scripts do
  this), `... end` after; the recovery then is quiet too. Both also post to the public channel (below).
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

## Public announcements (`announce`, `maintenance`, `announce-feed`)

The **public** player channel only gets player-facing posts: maintenance ("going down" / "back up") and world
events. Alerts and the status message never go there. Every post is in the voice of **Cody** (NPC 9200000, the v83
"Wizet Wizard": flustered, swears he really is from Wizet, ends lines with ~) and is sent with the webhook username
`ANNOUNCE_USERNAME` (`Cody`) and, if set, `ANNOUNCE_AVATAR_URL`. The lines are the `CODY` table in
`slumsms-monitor`, several variants per message, never the same variant twice in a row. A fan-server NPC voice only:
no Nexon/Wizet logos or links.

**Webhook:** `ANNOUNCE_WEBHOOK_URL`, else `NEWS_WEBHOOK_URL`. It **never** falls back to `WEBHOOK_URL`, which is meant
for the private ops channel (alerts/status/news fall back to it as before).

**Rate cap (shared by all three commands, per target):** at most `ANNOUNCE_MAX_PER_HOUR` (6) posts an hour and
`ANNOUNCE_MIN_GAP_SEC` (120) seconds apart. Maintenance posts skip the gap (a 30 s deploy still gets its "back up")
but count toward and obey the hourly cap. A post over the cap goes to the local log only (`[announce, rate
limited: ...]`) and is not retried. State: `/var/lib/slumsms-monitor/<target>/announce.json`.

**Scrubbing:** on top of the usual webhook/password scrub, public posts lose IPv4 addresses (with ports), bearer
tokens, `api_key=`-style pairs, PEM blocks and any 32+ character token, and `@everyone`/`@here` are defused (posts
are sent with `allowed_mentions: none` anyway). Names from the server are one line, markdown-escaped.

- `slumsms-monitor announce <target> "text" [--cody] [--dry-run]`: posts the text as written; `--cody` wraps it in
  one of Cody's lines.
- `slumsms-monitor maintenance <target> start [--minutes N] [--reason TEXT] [--no-announce] [--dry-run]`: touches
  `MAINTENANCE_FILE` (alerts go quiet, as before) and posts e.g. "I'm telling you, I really AM from WIZET! ...and
  Wizet says Bera needs a quick update. Back in about 5 min~ -Cody". `--minutes` defaults to `MAINTENANCE_MINUTES`
  (5). A second `start` while still in maintenance does not post again.
- `slumsms-monitor maintenance <target> end [--no-announce] [--dry-run]`: removes the file and posts "Bera's back!
  Boy, that was fun. See you around~ -Cody" (only if maintenance was on).
- The release pipeline's generated `deploy.sh` / `rollback.sh` (`tools/release/templates/`) call `maintenance live
  start` just before the stop and `end` once the server is online, if `/usr/local/bin/slumsms-monitor` exists; a
  missing or failing monitor never stops a deploy. `MAINT_MINUTES` / `MAINT_REASON` in the release conf set the text.
  An aborted deploy leaves maintenance on (rollback.sh ends it). The rehearsal sets `MONITOR=""`, so a copy of live
  never posts.
- `slumsms-monitor announce-feed <target> [--file F] [--seed] [--dry-run]`: posts new lines of `ANNOUNCE_FILE`, the
  world event feed the game server writes when it runs with `-Dslumsms.announceFile=<same path>` (ticket 21,
  `WorldEventFeed`; off when unset). The server writes one JSON line when a **boss sighting, treasure hunt or hot
  spot** starts, and when a real player wins a boss sighting or treasure hunt (never invasions, the wandering NPC,
  bot chatter or megaphones; a treasure hunt's start names the region, never the map). The monitor keeps the byte
  offset and the last 500 line ids (`announce-feed.json`), so nothing posts twice, also after the file is replaced.
  Lines older than `ANNOUNCE_MAX_AGE_MIN` (30) are logged, not posted. `ANNOUNCE_EVENT_WINNERS=0` drops the winner
  posts, `ANNOUNCE_GM_EVENTS=0` drops events a GM started with `!event start`. Run by `slumsms-announce-<target>.path`
  (`install.sh --announce`), which seeds what is already in the file.

Test without Discord: `python3 test_announce.py` (runs `mock-webhook.py`; rate cap, fallback rules, dedupe,
maintenance, Cody's lines, the deploy hook).

## Configuration

`/etc/slumsms-monitor/<target>.conf` (from `targets/<target>.conf`; keys and defaults at the top of
`slumsms-monitor`) and `/etc/slumsms-monitor/<target>.env` (root, 0600, not in git):

```
ALERT_WEBHOOK_URL=https://discord.com/api/webhooks/...
STATUS_WEBHOOK_URL=https://discord.com/api/webhooks/...
NEWS_WEBHOOK_URL=https://discord.com/api/webhooks/...
ANNOUNCE_WEBHOOK_URL=https://discord.com/api/webhooks/...   # public; empty = NEWS_WEBHOOK_URL
# WEBHOOK_URL= covers alerts, status and news left out (never announcements)
```

Test a webhook: `slumsms-monitor test-webhook live alerts` (or `status`, `news`, `announce`; `announce` posts to the
public channel).
Without Discord: `mock-webhook.py --port 8599 --out messages.jsonl` takes the same POST/PATCH calls, then
`WEBHOOK_URL=http://127.0.0.1:8599/api/webhooks/1/test`.

## Install

The folder is on 164 at `/opt/slumsms-monitor` (copy of this one). As root:

```
/opt/slumsms-monitor/install.sh <target> [--status] [--news] [--announce] [--conf FILE] [--replace-conf]
/opt/slumsms-monitor/install.sh <target> --uninstall
```

It installs `/usr/local/bin/slumsms-monitor`, the template units `slumsms-monitor@`, `slumsms-status@`,
`slumsms-news@`, `slumsms-announce@` and `/etc/slumsms-monitor/<target>.{conf,env}`, prints a dry-run check, and enables
`slumsms-monitor@<target>.timer` (plus the status timer / news and announce path units when asked). It never touches the game
server's unit or files.

### Live (the owner runs this)

```
sudo /opt/slumsms-monitor/install.sh live --status --news
sudo nano /etc/slumsms-monitor/live.env          # paste the webhook URLs
sudo slumsms-monitor test-webhook live alerts
sudo slumsms-monitor status live                  # posts the status message now
```

Until the webhook URLs are in, alerts and status changes go to `/var/log/slumsms-monitor/live.log`. Before a hand
deploy: `slumsms-monitor maintenance live start --minutes 5`; after: `slumsms-monitor maintenance live end` and set
`VERSION=` in `/etc/slumsms-monitor/live.conf` (generated release deploys do the maintenance part themselves).

World events to the public channel (once the scheduler or GM events are wanted there):

```
# the installed live.conf is kept on re-install, so add the two new keys to it (see targets/live.conf):
echo -e 'ANNOUNCE_NAME=Bera\nANNOUNCE_FILE=/var/lib/slumsms-announce/live.jsonl' | sudo tee -a /etc/slumsms-monitor/live.conf
sudo /opt/slumsms-monitor/install.sh live --announce
# then in /etc/systemd/system/slumsms.service ExecStart, before -jar:  -Dslumsms.announceFile=/var/lib/slumsms-announce/live.jsonl
sudo systemctl daemon-reload    # the server reads the property at its next restart (a maintenance window)
```
