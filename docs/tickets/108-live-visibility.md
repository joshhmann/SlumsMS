# 108: Live visibility: the staff dashboard on live (1.6.0) and a 5-minute live feed for the coordinator (1.6.1)

**Area:** Server / Ops
**Kind:** FEATURE, 1.6.0 (dashboard) and 1.6.1 (feed) (owner 2026-10-26)
**Status:** 1.6.0 part built (branch `160-livedash`); 1.6.1 part designed, not built
**Related:** 35 (admin dashboard), 42 (slumsms-monitor), 43 (GM audit), 105 (invite sign-up), 107 (password resets),
`docs/release/1.6.0.conf`, `docs/release/1.6.0-deploy-steps.md` item 2

Source: the owner, 2026-10-26, on how much of live the owner and the coordinator (Claude) can see.

## Why

Live is a black box between releases: the owner sees it in game, the coordinator only through what the owner pastes.
The staff dashboard (ticket 35) has run on TEST since 1.3.0 but was never switched on for live. Bugs that only happen
at live's mix of real players and 3,000 bots (a dupe, an error that starts after a day, one player repeating something
hundreds of times) are found late or not at all.

## Owner decisions (2026-10-26)

1. **1.6.0:** turn on the already-built staff dashboard exporter on live (`soloMapling.dashboard.DashboardExporter`,
   `-Dsolomapling.dashboard=true`, `-Dsolomapling.dashboard.dir=...`, `periodSeconds`) and serve the dashboard
   privately: LAN only, like TEST's `:8091`, with the same auth TEST uses.
2. **1.6.1:** a live data feed every 5 minutes with 90 days of history (7 days at 5-minute resolution, then daily
   rollups). Sections: population, health, failures, player actions, economy, bots, oddities (below).
3. **Privacy of what the coordinator sees:** character names OK; per-player activity OK only when an oddity flags
   that player; chat as statistics only (counts and categories, never a real player's chat text); account names OK;
   never IPs, emails, passwords or hashes, PICs, birthdays.
4. **Access:** one locked-down SSH key on 164 that can ONLY print the latest feed bundle (authorized_keys forced
   command with `restrict`, `no-pty`, `no-port-forwarding`, `no-agent-forwarding`, `no-X11-forwarding`), plus one
   Claude Code permission rule allowing that single command. The owner installs the key; deleting it revokes access.

## Part 1: 1.6.0, the staff dashboard on live (built)

### The exporter is safe for live (reviewed 2026-10-26)

- **Off the game thread.** One `dashboard-export` daemon thread at `MIN_PRIORITY`, `scheduleWithFixedDelay` (a run
  never overlaps the next), every failure caught and logged at most once per 15 minutes. Started from `Server.java`
  only when `-Dsolomapling.dashboard=true`.
- **Cost at 3,000 bots.** Everything is O(online bots) once per run: one pass over `CharacterStorage.getAllBots()`
  (plain field reads), one copy of each world's player list (`PlayerStorage.getAllCharacters`, a read lock held for a
  list copy), per-map and per-level tallies in hash maps, a sort of at most a few hundred map rows, and map names
  from a cache (at most 20,000). Nothing is O(players x maps). Measured on TEST: **8 ms a run at 2,254 bots** (every
  30 s there; live runs every 60 s).
- **Database.** Only the Accounts panel (ticket 105/107: totals, invite counts, the 20 newest sign-ups, reset
  requests) reads the DB: 5 small queries through the pool on the exporter thread. **Changed in 1.6.0:** it now reads
  at most every 5 minutes and the last reading rides along in between (`ACCOUNTS_EVERY_MS`, test
  `theAccountsPanelReadsTheDatabaseAtMostEveryFiveMinutes`), so at live's pool load the exporter asks for a connection
  12 times an hour instead of 60.
- **Disk.** `live.json` (about 9 KB) every run; the day's series (about 0.5 MB at 60 s) rewritten every 5 runs; one
  daily rollup per day; 90 days kept. All writes are atomic (temp file + rename; nginx denies dot files).
- **Chat.** `live.json`'s `chat` block is `ChatStats`: counts and latency per reply tier, counts per flag kind (an
  enum) and per action (enum names). No line anyone said is stored or exported (test
  `chatIsCountsOnlyNeverWhatWasSaid`). The chat-quality judge (`autoqa/ChatQuality`, transcripts) is auto-QA, writes
  to the auto-QA folder, and is not part of the dashboard.
- **What else it exports** (staff-only, so allowed on the private page, but NOT for the 1.6.1 feed as-is): bot names,
  account names of the 20 newest sign-ups, invite code prefixes, notes and makers, and the password reset `contact`
  field (free text a player typed: may be an email or Discord handle).
- **Map view.** The Map tab reads `nav/*.json` from `DashboardMapExport`, a separate hourly JVM (1 GB heap, 1 CPU at
  idle priority, about 200 MB of output on TEST). Not needed for 1.6.0: without it the Map tab is empty and every
  other tab works. If wanted later: `deploy/dashboard/slumsms-dashboard-maps.{service,timer}` (reads live's WZ and nav
  cache read-only, writes only `/srv/slums-dashboard/data/nav`), owner's go.
- **Ops strip and GM page** (`slums-dashboard-extras@live`) are not installed either: the GM page reads the live DB
  through `docker exec`, which needs the owner's go. The page shows those tabs as empty.

### What ships

- `docs/release/1.6.0.conf`: a second `UNIT_EDITS` entry adds `-Dsolomapling.dashboard=true
  -Dsolomapling.dashboard.dir=/srv/slums-dashboard/data -Dsolomapling.dashboard.periodSeconds=60` before `-jar`
  (the market/site/auth edit is unchanged and stays first; `test_release_160_turns_on_the_staff_dashboard`).
- `deploy/dashboard/slums-dashboard-live.nginx.conf`: `listen 192.168.0.164:8096` (the LAN address, not 0.0.0.0),
  `allow 192.168.0.0/24; deny all;`, and basic auth with TEST's file `/etc/nginx/slums-dashboard-test.htpasswd`
  (referenced by path; never read or copied). Both must pass.
  **Port 8096, not 8090:** the shared nginx already listens on `0.0.0.0:8090` for another site; an
  `192.168.0.164:8090` server would have taken that site's LAN requests.
- `tools/ops/install-live-dashboard` (owner-run from 187; `--check` is the default and changes nothing, `--go`
  installs, `--remove` takes the site away). Records the shared nginx's pid and config hash before and after
  (`/var/log/slums-dashboard-install.log` on 164), runs `nginx -t` before the reload and puts the old site file back
  if it fails, and checks the page answers 401 without the password.
- Release-night steps: `docs/release/1.6.0-deploy-steps.md` item 2 (LAUNCH.md lives on the site branch
  `site-160-theme`, so the step is in the release notes instead).

Known risk: 164's LAN address comes from DHCP. If it ever changed, nginx could not bind `192.168.0.164:8096` and would
refuse to start at all (taking the patch feed and site with it) until the site is removed. Keep the DHCP reservation,
or run `tools/ops/install-live-dashboard --remove` before readdressing the host.

## Part 2: 1.6.1, the live feed (design)

### Writer

`soloMapling.telemetry.LiveFeed`, off unless `-Dslums.telemetry=true`, on its own daemon thread at `MIN_PRIORITY`
every 300 s (`-Dslums.telemetry.periodSeconds`, at least 60), same safety shape as `DashboardExporter` (fixed delay,
catch everything, warn once per 15 min). It reuses the dashboard's `Sources` for population and health and adds
counters fed from the game:

- **Counters** are `LongAdder`/`AtomicLong` increments at the event (sign-in, PQ clear, trade done, purchase...): no
  allocation, no lock, no DB, no I/O on the game thread. Keyed maps are bounded (enum keys, or at most 500 string
  keys with an "other" bucket).
- **Errors** come from a Logback appender on WARN/ERROR that normalises the message (digits to `N`, quoted strings
  to `"…"`, first app stack frame) into a signature, `sha1` first 12 hex characters, kept in a map of at most 500 with
  count, first seen and last seen. The appender only increments; the feed thread reads.
- **Sessions:** login time per character in a map; the length is added to a histogram at logout.

### Where it writes (live, 164)

```
/var/lib/slums-telemetry/            root:slums-telemetry 2750
  latest.json                        the bundle (atomic rename), 0640; what the SSH key prints
  5min/YYYY-MM-DD.jsonl              one compact line per run (288 a day), kept 7 days
  daily/YYYY-MM-DD.json              the day's rollup, kept 90 days
  state/errors.json                  signature table (first seen survives a restart)
```

Rotation is the writer's job (no logrotate): at the day turn it rolls up yesterday's `5min` file into `daily/`,
catches up days the server was down for (as `SeriesStore` does), and deletes `5min` files older than 7 days and
`daily` files older than 90. Disk: about 4 KB a line, 1.2 MB a day, under 10 MB for the 5-minute week and about 2 MB
for 90 daily files.

### The bundle (`latest.json`, at most 256 KB; oversize drops the oldest trend points first)

```json
{
  "schema": 1, "at": "2026-11-02T03:05:00Z", "version": "1.6.1", "uptimeS": 86400,
  "population": {...}, "health": {...}, "failures": {...}, "actions": {...},
  "economy": {...}, "bots": {...}, "oddities": [...],
  "trend24h": {"t": [...], "players": [...], "bots": [...], "tickMs": [...], "heapMb": [...], "errors": [...]},
  "days": [{"day": "2026-11-01", "playersPeak": 9, "botsAvg": 2840, "errors": 31, "signups": 1, ...}]
}
```

`trend24h` is the last 288 five-minute points of a few series; `days` the last 90 daily rollups (summary fields only).

| Section | Contents |
|---|---|
| `population` | real players and bots online per channel; busiest 15 maps (map id, name, players, bots); session length histogram (under 10 min, 10-30, 30-60, 1-2 h, 2-4 h, over 4 h) and median for the window; distinct characters seen today |
| `health` | tick ms per second (window avg and max), heap used/max, GC count and total/max pause in the window, CPU, threads; LLM queue depth, calls, failures, timeouts and avg latency per tier (local, Decide, cloud); DB save count, avg and max ms, pool active/waiting; `BotDbGate` overruns |
| `failures` | per error signature: `sig`, logger, level, normalised message (at most 160 characters, with names and numbers stripped), count in the window, total, first seen, last seen, `rising` (window count at least 3x the 24 h average and at least 10), `new` (first seen in the last 24 h) |
| `actions` | counts in the window and today: sign-ups, sign-ins, failed sign-ins, PQ starts/clears per PQ, boss runs/clears per boss, world events started/finished, trades (player-player, player-bot), board posts, price alerts sent, cash shop purchases and NX spent, Leaf shop purchases |
| `economy` | the existing stats: `DashboardExporter` economy block (mesos in today, wealth percentiles), Track D market totals, NX rate, wealth index |
| `bots` | passive bot-watch totals (`BotWatch`), social-trace totals (conversations per stage and topic, decisions, acts), chat statistics (`ChatStats` counts per tier, flag kind and action) |
| `oddities` | flagged rows, each `{kind, who, what, value, baseline, at}` (rules below); the only place a single player's activity appears |

**Oddity rules** (thresholds in one place, tuned on TEST first):
- `meso_spike`: a character's mesos rise by more than max(50m, 20x their 7-day median daily gain) in a window.
- `possible_dupe`: the same item serial (cash or equip unique id) seen on two characters, or an item count for one
  item id across all characters rising with no matching drop/shop/trade source.
- `nx_rate_jump`: the market NX rate moves more than 25% between two windows.
- `restart_loop`: more than 2 server starts in an hour (read from the feed's own state file at start-up).
- `repeat_action`: one character doing the same counted action (trade, shop sale, NPC talk, command) more than 300
  times in a window.
- `error_storm`: one error signature over 1,000 in a window.

### Privacy: an enforced allowlist

- `FeedSchema` holds the full set of allowed key paths (`population.channels[].players`, `oddities[].who`, ...). The
  writer builds the bundle, then `FeedSchema.filter()` drops any key not on the list and logs it once; a test fails if
  the writer produces a key that is not on the list, so a new field is a deliberate schema change.
- Banned key names anywhere (test asserts none can be added to the list): `ip`, `ipHash`, `email`, `mail`,
  `password`, `pass`, `hash`, `salt`, `pic`, `pin`, `birthday`, `dob`, `contact`, `secret`, `token`, `session`,
  `text`, `message`, `line`, `chat` (except the `bots.chat` counts block, whose leaves must all be numbers).
- Value guard on every string leaf: an IPv4/IPv6 address, an email address, or a run of 32+ hex characters is replaced
  by `[redacted]` and counted in `bundle.redactions`. A test feeds such values through every string field.
- Character names appear only in `oddities[].who` and in busiest-map/economy rows that already show bot names; account
  names only in `oddities[].who` and the sign-ups count rows. Invite notes, invite makers and reset `contact` are never
  in the feed.
- Chat: counts and categories only. The feed has no field that can hold a sentence.
- Tests: `FeedSchemaTest` (allowlist complete, banned names, value guard) and `LiveFeedTest` (a full bundle from fake
  sources passes the schema unchanged; an oddity for a player carries only `who`, numbers and enum kinds).

### Budget at 3,000 bots

- Game threads: only counter increments (tens of ns each); the error appender is one hash and one map update per
  WARN/ERROR line.
- Feed thread: one pass over online characters and maps (the same work as the dashboard, about 10 ms), the oddity
  rules over in-memory per-character counters (O(online characters)), JSON of at most 256 KB. Target under 50 ms CPU
  per 5-minute run, measured into `health.feedMs`.
- DB: at most 3 aggregate queries per run (sign-ups and failed sign-ins already counted in memory; the dupe check
  reads item serial collisions with one indexed query every 30 minutes, not every run).
- I/O: one 256 KB `latest.json` write and one 4 KB append per 5 minutes; one rollup per day.

## Access for the coordinator (1.6.1)

The owner does all of this on the night 1.6.1 ships. Claude never sees the private key's contents: ssh reads it.

1. On 187, make the key (owner):
   ```
   ssh-keygen -t ed25519 -N '' -C claude-feed@187 -f /root/.ssh/slums_feed_ed25519
   ```
2. On 164, a user that can do nothing else (owner):
   ```
   groupadd --system slums-telemetry            # the live unit writes the feed as this group
   useradd --system --create-home --shell /bin/sh --groups slums-telemetry slumsfeed
   install -m 0755 /dev/stdin /usr/local/bin/slums-feed-latest <<'EOF'
   #!/bin/sh
   # Forced command for the coordinator's feed key: print the latest bundle, nothing else. Ignores any arguments.
   exec cat /var/lib/slums-telemetry/latest.json
   EOF
   install -d -m 0700 -o slumsfeed -g slumsfeed /home/slumsfeed/.ssh
   ```
   The shell must be a real one (sshd runs the forced command through it); the account has no password. If sshd
   refuses a locked account (only with `UsePAM no`), `usermod -p '*' slumsfeed`.
3. Put exactly one line in `/home/slumsfeed/.ssh/authorized_keys` (owner; mode 0600, owner slumsfeed). Template, with
   `<PUBLIC KEY>` being the single line of `/root/.ssh/slums_feed_ed25519.pub` from 187 (`ssh-ed25519 AAAA... claude-feed@187`):
   ```
   restrict,command="/usr/local/bin/slums-feed-latest",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding,from="192.168.0.187" <PUBLIC KEY>
   ```
   (`restrict` already turns all forwarding and the pty off; the explicit options are kept so the line reads as the
   owner asked. `from=` limits it to 187 and can be dropped.)
4. The Claude Code permission rule the owner adds to `/root/SoloMapling/.claude/settings.json` (or
   `~/.claude/settings.json`) under `permissions.allow`, an exact command with no wildcard:
   ```
   "Bash(ssh -i /root/.ssh/slums_feed_ed25519 -o IdentitiesOnly=yes slumsfeed@192.168.0.164)"
   ```
   Claude runs exactly that command; the output is the bundle.
5. Check (owner): the command above prints JSON; `ssh -i /root/.ssh/slums_feed_ed25519 -o IdentitiesOnly=yes
   slumsfeed@192.168.0.164 cat /etc/passwd` still prints the bundle (the forced command ignores it); `ssh -N -L
   9000:127.0.0.1:3309 ...` with the key is refused.

**Revoke** (any one of these cuts access; all three to clean up):
1. On 164: delete the line from `/home/slumsfeed/.ssh/authorized_keys` (or `userdel -r slumsfeed`).
2. On 187: remove the permission rule from the settings file.
3. On 187: `rm /root/.ssh/slums_feed_ed25519 /root/.ssh/slums_feed_ed25519.pub`.
To stop the feed itself, drop `-Dslums.telemetry=true` from the live unit at the next restart.

## Done when

- 1.6.0: live writes `/srv/slums-dashboard/data/live.json` every minute; `http://192.168.0.164:8096/` asks for the
  TEST dashboard password from the LAN and is refused from anywhere else; the game's tick time does not move.
- 1.6.1: the coordinator's one command prints a bundle under 256 KB with every section filled; `FeedSchemaTest`
  passes; a planted IP, email and chat line on TEST never reach the bundle; revoking the key line stops access.
