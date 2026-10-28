# 131: @bug with automatic context (and client logs later)

**Area:** server / support tooling  **Status:** server side built (branch `161-bugcmd`, off release-1.6.1). Unit tests
green; auto-QA `t-br-1` written, not yet run on TEST; nothing seen on a real client. The client log upload (part 4) is
server-ready behind stamp caps bit 1; no client sets that bit yet.

Owner 2026-10-28: "an in-game `@bug <text>` that auto-attaches version + as much useful data/logs as possible."

**Ships on its own.** It is an isolated bug-reporting tool (one command, a read-only capture, a file writer, a GM
command and an opcode no current client receives), so under the owner's rule it can go out as its own hotfix as well as
with 1.6.1 or later.

## 1. Player side

- `@bug <text>` (also `@reportbug`), GM level 0. 5 to **500** characters. `@bug` alone shows the usage.
- Limit: **3 reports in any 10 minutes per account** (and 20 a day per account; 60 an hour / 300 a day for everyone).
  Shared with `@idea` (whose text limit stays 2000).
- Reply: `Thanks! Bug #B-1234 sent.` (`@idea`: `Thanks! Idea #I-1234 sent.`). GMs online still get the yellow tip and
  popup, now with the ref.

## 2. What is captured (the context)

Captured on the command's thread only after the report passed the length and rate checks, each section on its own (one
that throws becomes `{"error": "<Exception>"}`), then capped at **64 KB** (`BugContext.cap`: halves the server log
first, then events, players/NPCs/mobs nearby, quests, NPC talks, maps, buffs, party; it lists what it cut under
`truncated`). Schema 1:

```json
{
  "ref": "B-12", "id": 12, "schema": 1, "capturedAt": "2026-10-28T20:41:07Z",
  "versions": {
    "server": "1.6.1", "commit": "5e21fd275", "dirty": false, "jarSha256": "<hex or null while hashing>",
    "client": {"build": 10680, "release": "1.6.1", "stampFormat": 2, "caps": 1, "capsNames": ["customNpcData"]},
    "world": 1, "channel": 3
  },
  "character": {
    "name": "Tester", "id": 321, "job": {"id": 110, "name": "FIGHTER"}, "level": 35, "gmLevel": 0,
    "map": {"id": 100000000, "name": "Henesys", "street": "Victoria Road"},
    "x": 120, "y": -45, "foothold": 17, "hp": 800, "maxHp": 1200, "mp": 90, "maxMp": 150,
    "meso": 1234567, "alive": true
  },
  "party": {"id": 9, "members": [{"name": "Tester", "level": 35, "job": 110, "mapId": 100000000,
                                  "online": true, "bot": false, "leader": true}]},
  "buffs": [{"item": 2022003, "name": "..."}, {"skill": 1001003, "name": "Iron Body"}],
  "quests": {"count": 60, "started": [{"id": 1000, "name": "..."}]},
  "trail": {
    "maps":   [{"t": "...Z", "id": 104000000, "name": "Lith Harbor"}],
    "npcs":   [{"t": "...Z", "id": 1012000, "name": "Regular Cab"}],
    "events": [{"t": "...Z", "kind": "attack", "id": 1001004, "name": "Power Strike", "note": "close", "count": 12},
               {"t": "...Z", "kind": "pickup", "id": 500, "note": "meso"}]
  },
  "surroundings": {
    "mobs": [{"id": 100100, "name": "Snail", "count": 2}],
    "npcs": [{"id": 1012000, "name": "Regular Cab"}],
    "playerCount": 2, "players": [{"name": "Friend", "bot": false}, {"name": "Bot1", "bot": true}]
  },
  "serverLog": ["2026-10-28T20:39:58.120Z WARN NPCTalkHandler - NPC Regular Cab (1012000) is not coded"],
  "clientLogs": {"receivedAt": "...", "format": 1, "totalLength": 41230, "truncated": true, "bytes": 32768,
                 "sections": {"build": "...", "crash": "...", "integration": "...", "display": "..."}},
  "truncated": ["serverLog"]
}
```

`clientLogs` only appears after a supporting client answered (part 4); `truncated` only when something was cut.

Where each part comes from:

- **versions**: `ServerConstants.SLUMSMS_VERSION`; `constants.net.BuildInfo` (the build's commit from
  `slums-git.properties`, written by the git-commit-id plugin at build time, absent without git; the running jar's
  sha256, hashed once in the background from boot); the session's build stamp (CP 0x3738).
- **trail**: `server.reports.PlayerTrail`, three rings per real player (none for population bots): last 10 maps, last
  10 NPC talks, last 50 events. Events: `skill` (cast), `attack` (with the route close/ranged/magic/touch), `use`,
  `pickup` (item id, or the amount with note `meso`), `trade` (partner's character name, items, mesos received), `shop`
  (bought item, NPC shop id), `death`, `warp`. Repeats of the same event fold into one entry with a `count`, so an
  attack spam does not push out what came before. Hooks: `Character.changeMapInternal` / `playerDead` / `pickupItem`,
  `AttackCapture.attack` / `cast` (every attack and special-move packet passes there), `NPCTalkHandler`,
  `UseItemHandler`, `Shop.buy`, `Trade.completeTrade`. Cost: a field read and a small synchronized add.
- **serverLog**: `server.reports.WarnLogRing`, an appender added to the root logger at boot keeping the last 500
  WARN/ERROR lines (400 characters each, the exception's class and message, no stack). At report time it keeps lines
  from the last 10 minutes that name the reporter (whole word) or their id (`chr 123`, `id=123`, ...), at most 60.
  Loggers with their own files and no additivity (chat, trades, BotLog) never reach it.

### Privacy

- The reporter's own character in detail (exact mesos: their own). Other characters only by character name (party
  members also level/job/map), each with a bot flag. No account name, account data, IP, e-mail, chat or whispers.
- Server log lines are dropped whole (`BugPrivacy.serverLineAllowed`) when they mention passwords, PIN/PIC, tokens,
  salts, hashes, e-mail, whispers, IPs (v4/v6), remote addresses, sessions, cookies, API keys, login/account names
  (`account Bob`, the GM audit line), or carry a long hex string.
- Uploaded client text is scrubbed again on the server (`BugPrivacy.scrubClientText`): lines about passwords / PIN /
  PIC / secrets / keys / serials are replaced by `[line removed]`; `C:\Users\<name>` becomes `<user>`; `COMPUTERNAME=`,
  `USERNAME=`, `hostname:` and the like become `<redacted>`; IPs, MAC addresses and e-mails are replaced; the
  account's login name becomes `<account>`. File hashes (the slums.dll sha256) stay.

## 3. Storage

- The reports file is the one the website's report form writes: `-Dsolomapling.reportsFile=<path>`; live
  `/var/lib/slums-feedback/reports.jsonl`, TEST `/var/lib/slums-report-test/reports.jsonl`. Without the property the
  server keeps its old default `data/reports/reports.jsonl` (so nothing changes until the switch is set).
- **One submission, one report, one place** (owner 2026-10-28). An `@bug` is ONE line in the site's report store
  (the same reports.jsonl the site form writes; the website's report viewer and triage read it), in the report
  service's own format (site-161-framework `tools/site/report_service.py`, 6f63483f4): its fields in its order (`id`,
  `time`, `type`, `title`, `details`, `character`, `where`, `source`, `versions`), then the in-game extras. There is no
  second copy: no in-game table, no separate log (the server log line says only "Bug B-12 filed by X"). The big
  context is an attachment of that same report, `context/<ref>.json`, named by its ref and pointed at from the line's
  `context.file`; it is never a report of its own. `!bugs` / `!bug <ref>` read that same store.

```json
{"id":12,"time":"2026-10-28T20:41:07+00:00","type":"bug","title":"<first 80 characters>","details":"<text>",
 "character":"Tester","where":"Henesys (100000000) ch3","source":"ingame",
 "versions":{"server":"1.6.1","server_commit":"5e21fd275","client":"1.6.1","clientBuild":10680},
 "ref":"B-12","accountId":42,"world":1,"channel":3,"mapId":100000000,"mapName":"Henesys","x":120,"y":-45,
 "clientBuild":"10680 (1.6.1)",
 "context":{"server":"1.6.1","commit":"5e21fd275","clientBuild":10680,"clientRelease":"1.6.1","clientCaps":1,
            "world":1,"channel":3,"characterId":321,"level":35,"job":"FIGHTER","mapId":100000000,"x":120,"y":-45,
            "events":17,"logLines":2,"file":"context/B-12.json"}}
```

  `versions` uses the site's shape: `server` and `server_commit` as the service stamps them from world.json, `client`
  as a short version string (the slums.dll release), plus `clientBuild` (the build number). Values that are not
  short version strings by the service's own pattern are left out. `time` is the service's format (`+00:00`, whole
  seconds). Site lines have `source:"site"` and an `ip_hash`; in-game lines have `source:"ingame"`, a `ref` and never
  an `ip_hash` (the first in-game lines from 1.6.1 before this ticket say `"game"`; the GM tools read both).
- Permissions as the report service: a file the server creates is 0640 and a folder 0750, owner and group copied from
  the folder it is in (so a root-run server leaves `slums-report:slums-report` files the service can append to).
- **Why the file and not the service's HTTP endpoint.** The service listens on 127.0.0.1:8093, `POST /api/report`, no
  secret, but as it stands it cannot take an in-game report: it overwrites `source` with "site" and `versions` with its
  own, drops `ref`/`context`, and rate-limits by the caller's IP hash (5 an hour from 127.0.0.1 would cap every
  player together). So the server writes the file itself, the way the service does (`O_APPEND`-style append, 0640).
- **Ids, and the one site change this needs.** The server takes an exclusive POSIX lock (`fcntl`) on reports.jsonl for
  every write, re-reads the highest `"id"` from the file's last 256 KB and appends id+1 through that one descriptor
  (GM reads run on the same writer thread, because a POSIX lock drops when any descriptor of the file closes). The
  service does **not** lock and keeps `last_id` in memory from its start, so after an in-game report it would hand
  out the same id again. **Site session, before the switch goes on:** make `Store.add` lock and re-read, e.g.

```python
import fcntl
def _max_id(fd):                      # highest "id" in the file's last 256 KB
    size = os.fstat(fd).st_size
    os.lseek(fd, max(0, size - 262144), os.SEEK_SET)
    best = 0
    for line in os.read(fd, 262144).decode("utf-8", "replace").splitlines():
        m = re.match(r'^\{\s*"id"\s*:\s*(\d+)', line)
        if m:
            best = max(best, int(m.group(1)))
    return best
# in Store.add, replacing the open/write/close:
fd = os.open(self.path, os.O_RDWR | os.O_APPEND | os.O_CREAT, 0o640)
try:
    fcntl.lockf(fd, fcntl.LOCK_EX)               # fcntl locks: the same kind Java's FileChannel.lock takes
    rid = max(self.last_id, _max_id(fd)) + 1     # then build rec/line with this rid
    os.write(fd, line.encode("utf-8")); os.fsync(fd)
finally:
    os.close(fd)                                  # closing releases the lock
```

  (`report_export.py` and the viewer read whole lines and need no change.) Until that lands, keep
  `-Dsolomapling.reportsFile` unset on live, or accept that a site report right after an in-game one can share its id
  (the in-game one still has its unique `ref`).
- **No duplicates on retry.** There is no retry loop: one `@bug` is one write. If the line is written but the sync
  fails, the player is still told it was sent (a "failed" reply would make them file it twice). A player is told
  "could not be saved" only when nothing was written. If a later version goes through the service's endpoint, it
  should send the ref as an idempotency key and the service should answer an already-stored ref with its existing id.
- Without `-Dsolomapling.reportsFile` the store is `data/reports/reports.jsonl` (the 1.6.1 behaviour) and the server
  logs a WARN at the first report: still one place, just not the site's.
- Triage: `tools/site/report_export.py --since N` already prints in-game lines too (it drops only `ip_hash`).

## 4. Client logs (server side built; client side is the client session's)

Agreed with the client session 2026-10-28. Rides with the next client after 1.6.1. Server side is in this branch and
harmless while no client sets the bit.

- **Discovery:** build stamp format 2 (CP 0x3738), **caps bit 1** (`0x2`) = "can upload logs". The server only asks
  sessions that set it; old clients never see the opcode. (Bit 0 stays "has the custom NPC data".)
- **LP 0x373A `BUG_LOG_REQUEST`** (server -> client), sent right after a filed `@bug`:
  `short format (1); int nonce; mapleString ref ("B-12"); int maxBytes (32768)`.
- **CP 0x373B `BUG_LOG_UPLOAD`** (client -> server), at most one per request:
  `short format (1); int nonce; int totalLength (bytes before the client cut); byte truncated (0/1); int length;
  byte[length]` UTF-8 text, `length <= 32768`.
- **Nonce rules (server enforces):** one pending nonce per character (a new `@bug` replaces it), valid 5 minutes, used
  once (an oversized answer also uses it up); at most one accepted upload a minute per character; a wrong character,
  a stale or reused nonce, a missing caps bit, a bad format, a length over 32 KB or over what the packet holds are all
  refused (logged at INFO, no reply). The client should answer only for an unused nonce and at most once a minute.
- **Content (client):** the text is sections, each headed by `[name]` on its own line:
  `[build]` build, release, slums.dll sha256; `[crash]` the tail of `LATEST_CRASH.txt` (exception code, module and
  offset, call stack); `[integration]` the last N lines of `integration.log`; `[display]` D3D / dgVoodoo mode. No
  screenshots, no dumps. Newest content wins when cutting to 32 KB (cut from the front, set `truncated`).
- **Scrub (client first):** an allow-list per line type, then redact `C:\Users\<name>` paths, the account name, the PC
  name and IPs. The server scrubs again (section 2, Privacy) and caps at 32 KB again.
- **Merge:** the scrubbed text is split into its sections and written into `context/<ref>.json` as `clientLogs`
  (section 2). The jsonl line is not changed.
- Code: `server.reports.BugClientLogs`, `net.server.channel.handlers.BugLogUploadHandler`; opcodes in
  `RecvOpcode.BUG_LOG_UPLOAD` / `SendOpcode.BUG_LOG_REQUEST`. `/root/work/addon-opcodes.md` should list the pair
  (done 2026-10-28; 0x373A/0x373B were the last free pair in 0x3736..0x373F).

## 5. GM tools

- `!bugs [n]` (GM 2): the newest n in-game reports (default 10, at most 30), newest first: `ref time character @ map:
  title`.
- `!bug <ref>` (any GM; `@bug B-12`, `!bug 12`): the report, server/commit and client build/caps, level/job/HP/MP and
  position, last maps and NPCs, the last events, how many log lines, whether client logs arrived, what was cut, and
  the context file's path. A player typing `@bug 12` just gets "too short".

## 6. Tests and checks

- Unit: `ReportStoreTest` (ids across restarts and site lines, 3/10 min and 20/day per account, global cap, lengths
  500/2000, site time format, 80-character title, context file + summary, 0640/0750, read back, QA removal),
  `ReportCommandTest` (reply with ref, context file for bugs only, usage, the log request only to caps-bit-1 clients),
  `BugContextTest` (shape, a failing section, 64 KB cap, summary), `PlayerTrailTest` (bounded rings, folding, no
  trail for bots), `WarnLogRingTest` (bounded, name/id/time filter, privacy, attach), `BugPrivacyTest`,
  `BugClientLogsTest` (caps gating, packet layout, nonce once/expiry/rate, size, scrub and sections),
  `BugLogUploadHandlerTest` (end to end merge, replay refused), `BugsCommandTest`, `BuildInfoTest`, `SiteWorldTest`
  (world.json `version`).
- Auto-QA **`t-br-1`** (probe `bugReport`, nightly): logs two WARN lines about the QA character (one mentions a
  password), the pretend player types `@bug autoqa t-br-1 <marker> ...`; wants the `Thanks! Bug #B-.. sent.` reply,
  the jsonl line (source ingame, type bug, text, character, `context.file`), the context file under 64 KB with
  versions, character, trail, surroundings, quests and buffs, the first WARN line in `serverLog` and the password line
  not. Cleanup removes exactly that line (under the file lock) and its context file, and resets the QA account's rate
  limit.

## 7. Switches and rollout

- TEST: add `-Dsolomapling.reportsFile=/var/lib/slums-report-test/reports.jsonl` to `start-test.sh` (the folder must
  exist and be writable by the TEST server; `context/` is created on the first report). Then `tools/ops/autoqa-run
  t-br-1`.
- Live (owner's go): `-Dsolomapling.reportsFile=/var/lib/slums-feedback/reports.jsonl` in the unit's `-D` flags (a
  release conf `UNIT_EDITS` entry, or a hotfix conf of its own). Check first that the live server's user can write
  `/var/lib/slums-feedback` (owned by `slums-report`; a root-run server can). Not checked from this session (reading
  164 was refused).
- No migration, no config.yaml key. The build now runs the git-commit-id plugin (cached in `~/.m2`, works offline,
  uses the `git` command so worktrees work); a build without git simply has no commit.
- world.json (site export) now carries `"version": {"server": "1.6.1", "commit": "5e21fd275"}` for the site's report
  service to stamp into site reports (commit left out when unknown); the shape check requires `version.server`.
