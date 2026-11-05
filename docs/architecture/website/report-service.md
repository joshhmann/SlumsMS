# Report service

## Purpose

Player feedback arrives two ways and lands in one file: the public form on the site, served by a small stdlib HTTP
service (`tools/site/report_service.py`), and the in-game `@bug` / `@idea` commands (`src/main/java/server/reports/ReportStore.java`).
Both append one JSON line per report to the same `.jsonl`, number ids out of that file under a POSIX lock, and let staff
read the result through a second loopback-only reader. The rest of the package — trail, context capture, log ring,
privacy rules — exists to make an in-game report useful without putting a person's data in the file.

## Entry points

| Entry | What starts it | Where |
|---|---|---|
| `slums-report.service` → `main()` | boot; `python3 /opt/slums-report/report_service.py` as user `slums-report`; arguments, then environment, then defaults | `tools/site/slums-report.service:7-17`, `tools/site/report_service.py:514-536` |
| nginx `location = /api/report`, `/api/report/health` | 6 r/m per visitor, `client_max_body_size 8k`, proxy to 127.0.0.1:8093; health is loopback only | `tools/site/slums-report.nginx.conf:11-27` |
| nginx `location /api/report/staff/` | GM 3+ guard, GET only, proxy to 127.0.0.1:8097 | `tools/site/slums-login.nginx.conf:118-130` |
| `POST /api/report` (JSON or form) | the form page and its script | `docs/site/report.html:6`, `docs/site/assets/report.js:27-35` |
| `GET /api/report/health` | deploy checks | `tools/site/report_service.py:409-410` |
| staff Reports tab (`admin/reports`, `#r<id>`) | site build; reads `api/report/staff/` | `tools/site/sections/admin.py:70`, `:239`, `docs/site/assets/reports.js:1-7` |
| `@bug` / `@idea` | chat command, GM 0 | `src/main/java/client/command/commands/gm0/ReportBugCommand.java:54-71` |
| `!bugs` / `!bug <ref>` | GM 2 list and describe | `src/main/java/client/command/commands/gm2/BugsCommand.java:41-102` |
| `CP 0x373B` bug-log upload | client packet handled at boot | `src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:24` |
| `WarnLogRing.install()` | server boot; without it reports carry no log lines | `src/main/java/net/server/Server.java:926` |
| `report_export.py --since N` | operator, by hand over ssh | `tools/site/report_export.py:30-41` |
| `-Dsolomapling.reportsFile=<path>` | JVM flag in the unit's `-D` set | `src/main/java/server/reports/ReportStore.java:115-120` |

## How it works

Site form, one submission:

1. `do_POST` requires `Content-Length` (411) and refuses bodies over `MAX_BODY` = 8 KB (413)
   (`tools/site/report_service.py:417-429`).
2. `parse_fields` accepts `application/json` or `application/x-www-form-urlencoded`, anything else 415 (`:233-249`).
3. `validate` drops the honeypot field `website` (answers success, stores nothing), checks `type` against
   `{"bug","feature"}`, and enforces `LIMITS` — title 140 / details 2000 / character 13 / where 200 (`:252-270`, `:51-54`).
4. `client_ip` takes the right-most `X-Forwarded-For` entry, because the front proxy appends and everything left of it
   is client-supplied (`:354-362`); the address is hashed, never written (`:301-302`).
5. `Store.add` checks the global day limit (200), then the per-hash hour (5) and day (20), takes
   `fcntl.lockf(fd, LOCK_EX)`, numbers the report `max(last_id, max_id(fd)) + 1`, writes one line, `fsync`s and closes
   the descriptor — closing is what releases the lock (`:309-347`).
6. The line is stamped `source:"site"` plus a `versions` object read from `world/online.json` and `assets/build.json`
   at that moment; browser-supplied versions are kept only if they match `VERSION` (`:438`, `:82-110`).

In-game report, one submission:

1. `ReportBugCommand.report` measures the text in code points against 500 (bug) or 2000 (idea), builds a `Context`
   (character, account id, world, channel, map, x/y, client build, versions), and passes a context supplier only for
   bugs (`ReportBugCommand.java:90-107`).
2. `ReportStore.submit` rejects short/long text, then rate-limits under `lock` — 3 per 10 minutes and 20 a day per
   account, 60 an hour and 300 a day overall (`ReportStore.java:205-225`).
3. The supplier runs on the caller's thread *after* the rate check, so a spam attempt captures nothing (`:226`).
4. The write is queued on the single daemon thread `report-writer` (`:128-133`, `:228`): it creates the file 0640 and
   the folder 0750 with the parent's owner and group (`:299-311`, `:331-354`), takes `ch.lock()`, numbers the line the
   same way the site does (`:234-236`), writes `context/<ref>.json` by temp file plus atomic move (`:242-246`,
   `:356-364`), then appends the line and calls `ch.force(true)` (`:247-259`).
5. On success the player sees `Thanks! Bug #B-12 sent.`, GMs get a yellow tip and a notice, and a client that sets caps
   bit 1 is asked for its log tail (`ReportBugCommand.java:119-127`).

Reading it back:

1. `Reader.rows` walks the newest `READ_MAX` = 16 MB, drops the first line when the file was cut, skips unparsable
   lines, keeps only `STAFF_FIELDS`, de-duplicates by id keeping the last copy (a QA rewrite of one line), and sorts
   newest first (`report_service.py:138-162`).
2. `Reader.list` truncates `details` to 200 characters for the list only; `Reader.one` returns the whole row, so the
   detail view shows the full text (`:185-186`, `:210-214`).
3. `Reader.context` opens only a path matching `^context/[A-Z]-[0-9]{1,9}\.json$` whose real path is still inside
   `<folder>/context/` (`:190-208`).
4. `report_export.export` prints the same records as JSON lines with `ip_hash` removed (`report_export.py:16-27`).

Failure modes:

| Situation | Site | Server |
|---|---|---|
| file absent | `rows()` returns `[]` (`:144-145`); `add()` creates it 0640 (`:329`) | `openLocked` creates it and tolerates a race with the site (`:299-311`) |
| partial last line | skipped by `json.loads` + `continue` (`:149-154`); when the window cut the head, `lines[1:]` (`:146-147`) | `tail()` drops the first fragment when `from > 0` (`:414-417`) |
| one huge line | no per-line cap, so a line near 16 MB pushes the rest out of the window and parses to nothing (`:138-147`) | context capped at 64 KB (`BugContext.java:41`), log lines at 400 chars (`WarnLogRing.java:28`), client log at 32 KB (`BugClientLogs.java:42`) |
| write while read | the reader takes no lock, so a read during `removeForQa` can see a half file (`:138`) | every read goes through `onWriter` because closing any descriptor drops the process's lock (`:424-437`) |
| write fails | 503 with a "try again" page (`:340-341`) | `WRITE_FAILED`; a failed `force` only warns (`:254-258`) |
| failure mid-append | an `OSError` leaves whatever was written and answers 503 (`:338-341`) | an exception in the positional loop leaves a partial line, with no truncate (`:249-253`, `:261-263`) |

## Data

| Path | Written by | Read by |
|---|---|---|
| `reports.jsonl` — live `/var/lib/slums-feedback/reports.jsonl`, TEST `/var/lib/slums-report-test/reports.jsonl`, fallback `data/reports/reports.jsonl` | site `Store.add` (`report_service.py:329-339`); server `submit` (`ReportStore.java:249-253`) | site `Reader` (`:138`), `report_export` (`:16`); server `find` / `recentInGame` (`:440-492`) |
| `context/<B\|I>-<id>.json` beside it | server `writeContext` (`ReportStore.java:356-364`), amended by `updateContext` for client logs (`:517-536`) | site `Reader.context` (`report_service.py:190-208`), server `readContext` (`:496-511`) |
| `/etc/slums-report/salt` (0640, root:`slums-report`) | owner, once: `head -c 32 /dev/urandom \| base64` | `read_salt` (`:452-457`), min 16 bytes |
| `/srv/slums-site/world/online.json`, `.../assets/build.json` | World timer, site build | `Versions.now`, read per report (`:97-110`) |

Record fields — the site line and the in-game line differ only in the tail:

| Field | Site line | In-game line | Notes |
|---|---|---|---|
| `id` | yes | yes | one shared counter: lock + tail re-read |
| `time` | yes | yes | `yyyy-MM-dd'T'HH:mm:ss+00:00` (`report_service.py:336`; `ReportStore.java:76`) |
| `type` | `bug`/`feature` | `bug`/`feature` | `@idea` passes `feature` (`ReportBugCommand.java:70`) |
| `title`, `details` | from the form | title = first 80 code points of the text (`:597-601`) | |
| `character`, `where` | typed / page context | name / `mapName (id) ch<n>` (`:95-97`) | |
| `source` | `"site"` (`:438`) | `"ingame"` (`:75`) | pre-131 lines say `"game"` and are accepted (`:583-586`) |
| `versions` | `server`, `server_commit`, `site`, `launcher`, `client` | `server`, `server_commit`, `client`, `clientBuild` | both omit unknown keys |
| `ip_hash` | sha256(salt + ip), 64 hex | absent | never the address |
| `ref` | absent | `B-12` / `I-12` | `refFor` (`:178-180`) |
| `accountId`, `world`, `channel`, `mapId`, `mapName`, `x`, `y`, `clientBuild` | absent | yes | field order fixed at `:609-631` |
| `context` | absent | short summary with `file` (`BugContext.summary`, `:457-491`) | full object in `context/<ref>.json` |

Rotation and retention: **none on either side.** Nothing in `report_service.py`, `report_export.py` or
`INSTALL-report.md` rotates, truncates or prunes; the only write that removes a line is the QA cleanup
`ReportStore.removeForQa` (`:542-581`, truncate at `:562`). Reads are capped at the newest 16 MB
(`report_service.py:118`) and the writer's id scan at the last 256 KB (`:66`, `ReportStore.java:74`), so growth past
those windows silently hides history and weakens id numbering.

## Switches

| Switch | Default | Where |
|---|---|---|
| `SLUMS_REPORT_FILE` / `--file` | `/var/lib/slums-feedback/reports.jsonl` | `report_service.py:517`; unit `:10` |
| `SLUMS_REPORT_SALT_FILE` / `--salt-file` | `/etc/slums-report/salt` | `:518`; unit `:11` |
| `SLUMS_REPORT_PORT` / `--port` | 8093, bound to 127.0.0.1 only | `:519`, `:507-511` |
| `SLUMS_REPORT_PROXY_HOPS` / `--proxy-hops` | 1 (one front proxy); TEST uses 0 | `:520`; `tools/site/test164/slums-report-test.service:13` |
| `SLUMS_REPORT_WORLD_FILE` / `--world-file` | empty, so no server version is stamped | `:521`; unit `:15` |
| `SLUMS_REPORT_SITE_BUILD` / `--site-build-file` | empty | `:522`; unit `:16` |
| `SLUMS_REPORT_STAFF_PORT` / `--staff-port` | 0 = reader off | `:523`; unit `:17` (8097) |
| `-Dsolomapling.reportsFile` | unset → `data/reports/reports.jsonl` + WARN | `ReportStore.java:115-120`; live `docs/release/1.6.4.conf:271`; TEST `docs/ops/qa-suite.md:622` |
| nginx `rate=6r/m`, `burst=5`, `client_max_body_size 8k` | hard-coded | `tools/site/slums-report.nginx.conf:11-18` |

## Invariants

- **One report, one line.** Both writers append a single serialized object plus `"\n"`
  (`report_service.py:337-338`, `ReportStore.java:249-253`).
- **Ids are unique across both writers.** Every write holds an exclusive lock on the file and numbers from
  `max(last_id, max_id(fd)) + 1` (`report_service.py:333-334`, `ReportStore.java:234-236`); the class doc says the site
  must keep doing the same (`ReportStore.java:50-53`).
- **No descriptor may close mid-write.** POSIX locks drop when *any* descriptor of the file closes, so the server
  funnels every read through the writer thread (`ReportStore.java:232-234`, `:424-437`).
- **The raw address is never written.** `hash_ip` is the only use of the address (`report_service.py:301-302`, `:311`);
  a test asserts neither the visitor nor the proxy address appears in the file (`tools/site/test_report.py:215-225`).
- **Staff output is allow-listed.** `Reader` rebuilds each row from `STAFF_FIELDS`, and `scrub()` drops keys matching
  `HIDDEN` (`:113-128`); `accountId` is not in the whitelist, so the reader never shows it
  (`tools/site/test_report.py:318-328`).
- **Context files stay in their folder.** Regex plus realpath prefix check (`report_service.py:193-197`); the test
  feeds `"../salt"` and asserts nothing leaks (`test_report.py:353-357`).
- **Server log lines with secrets never enter a report.** `WarnLogRing.linesFor` filters each line through
  `BugPrivacy.serverLineAllowed` (`WarnLogRing.java:121-131`, `BugPrivacy.java:16-29`); auto-QA stages a warning
  containing the word "password" and asserts it is absent (`Probes160.java:2747`, `:2813-2814`).
- **Uploaded client logs are scrubbed server-side whatever the client did.** `toContext` runs
  `BugPrivacy.scrubClientText` and re-caps at 32 KB from the front (`BugClientLogs.java:147-164`).
- **Bots own no trail.** `PlayerTrail.of` returns null for bots (`PlayerTrail.java:37-42`).

## Origin

**custom** — the base v83 repack ships no site and no report store; `ReportBugCommand` started as an upstream
HeavenMS/OdinMS stub (`03d13cb21`, "Send in a bug report.") and was rewritten.

| File | First commit | Date | Subject |
|---|---|---|---|
| `tools/site/report_service.py`, `report_export.py`, `slums-report.service`, `slums-report.nginx.conf`, `test_report.py` | `5122b392d` | 2026-10-25 | Add a public report-a-bug / request-a-feature form and its receiver service |
| `tools/site/report_service.py`, versions stamping | `6f63483f4` | 2026-10-28 | 1.6.1: versions on reports |
| `tools/site/slums-report.nginx.conf`, visitor-keyed limit | `96bee8e1e` | 2026-10-30 | Website audit MEDIUM fixes |
| `docs/site/assets/reports.js`, staff tab | `0af96cae4` | 2026-10-28 | Staff Reports tab behind the GM 3 guard |
| `src/main/java/server/reports/ReportStore.java`, `ReportStoreTest.java` | `927c215a3` | 2026-10-25 | Log @bug and @idea reports to a durable JSONL file |
| `BugContext`, `BugPrivacy`, `BugClientLogs`, `WarnLogRing`, `PlayerTrail` | `3d66a79be` | 2026-10-28 | @bug files one site report with versions, context, trail and log lines |
| `PlayerTrail` petpot event | `2a3d92ab5` | 2026-11-01 | pet potion QA |

Design record: `docs/tickets/131-bug-reports.md:95-166` (file contract, lock rationale, no-retry rule, triage flow),
`:168-190` (client-log handshake).

## Tests and QA

| Test | Covers |
|---|---|
| `tools/site/test_report.py:71`, `:83`, `:163` | ids follow lines the game appended; the write waits for a lock another process holds; ids survive a restart |
| `tools/site/test_report.py:101`, `:119`, `:123`, `:132` | versions stamped by the service, not the browser (`<script>` and 40-char values dropped); missing version files; trimming; validation |
| `tools/site/test_report.py:145`, `:151`, `:157`, `:246`, `:258` | bad bodies, honeypot stores nothing, 8 KB cap, other endpoints and methods, loopback-only bind |
| `tools/site/test_report.py:171`, `:187`, `:195`, `:203`, `:209`, `:215` | per-IP hour/day limits, limits survive restart, global limit, spoofed left-most XFF ignored, `client_ip`, no raw address, mode 0640 |
| `tools/site/test_report.py:227`, `:241`, `:261`, `:267`, `:382` | form posts HTML-escaped, JSON answers, a short salt aborts, export drops `ip_hash`, the page builds and passes `check.py` |
| `tools/site/test_report.py:318`, `:330`, `:342`, `:353`, `:359`, `:368` | staff list without private fields, filters and paging, one report with its scrubbed context, context confined to its folder, unknown paths, public port has no staff routes |
| `ReportStoreTest.java:50`, `:63`, `:87`, `:100`, `:115`, `:124`, `:160`, `:168`, `:204`, `:217`, `:263`, `:284` | ids across restart and across site-appended lines, length checks, both rate limits, rejected reports use no id, escaping and field set, write failure, context file plus summary, rate-limited report captures nothing, read/find/update/remove, refs, line shape matches the site, odd versions dropped |
| `ReportCommandTest.java:50`, `:84`, `:103`, `:119`; `BugsCommandTest.java:20` | `@bug` files, replies and broadcasts; a caps client is asked for logs; too-short message; registration; list and describe |
| `BugPrivacyTest.java:12`, `:30`, `:43` | sensitive server lines dropped, ordinary errors kept, client text redacted rather than dropped |
| `BugContextTest.java:115`, `:169`, `:178`, `:206` | agreed shape, one broken section does not stop the rest, the cap keeps the report under 64 KB |
| `BugClientLogsTest.java:47`, `:70`, `:84`, `:99`, `:114`, `:133` | only caps-bit-1 clients asked, packet layout, nonce once per character, expiry and 1/minute, size checks, scrubbed sections |
| `WarnLogRingTest.java:12`, `:23`, `:52`, `:60`; `PlayerTrailTest.java:12`, `:43` | ring bound, filter by name/id/time/privacy, install once, no stack traces; bounded rings, bots get nothing |
| `src/main/java/soloMapling/autoqa/Probes160.java:2737-2825` | auto-QA t-br-1 end to end: `@bug`, ref reply, line on disk, context present, staged password line absent, cleanup |

This review ran `cd tools/site && python3 -m unittest test_report` → 29 tests, OK (18.5 s).

Not covered: a partial or garbage line at the 16 MB window edge, a read concurrent with a write or with
`removeForQa`, a single line near the window size, the `{"tooBig"}`/`{"unreadable"}` context sentinels
(`report_service.py:203-208`), the nginx rate limit itself, and any client that actually sends `CP 0x373B`.

## Known issues

- `b280` / live ticket B-10 — pet auto-potion bug filed as `B-10`; nearest card, and no card tracks this service
  (`/root/work/STATUS-1.6.4.md:63`).
- **NEW-FINDING:** `GET /api/report/health` returns `store.count()`, which is the highest id, not a count
  (`report_service.py:349-351`, `:409-410`), so the number only grows and `INSTALL-report.md:44`'s expected
  `{"ok": true, "reports": 0}` is true only on an empty store — a health check cannot tell a working writer from a
  stopped one.
- **NEW-FINDING:** `report_export.py` removes only `ip_hash` (`:23`) while the staff reader deliberately withholds
  `accountId` (`report_service.py:113-114`), so copying reports onto the tracker with `--since` carries the reporter's
  account id out of the server; the two privacy rules disagree.
- **NEW-FINDING:** the site reads with no lock at all (`report_service.py:138`), so a staff list during
  `removeForQa`'s truncate-and-rewrite (`ReportStore.java:562-567`) can return a partial list; id de-duplication
  (`:161`) hides duplicate copies but not missing rows.
- **NEW-FINDING:** nothing rotates or prunes the reports file, and the reader sees only the newest 16 MB
  (`report_service.py:118`, `:146-147`) — older reports are unreachable from the staff UI, and the only deletion path
  is a QA helper (`ReportStore.java:542-581`).
- **NEW-FINDING:** the id scan reads only the last 256 KB (`report_service.py:70-79`, `ReportStore.java:367-393`), so a
  line longer than that window would make both writers reuse ids; no test bounds line size and no code enforces it.
- **NEW-FINDING:** free text is never scrubbed. `title`/`details` from the form and the `@bug` text go into the file
  verbatim (`report_service.py:259-262`, `ReportStore.java:207`), and `BugContext.surroundings` records up to 60 other
  players' character names (`BugContext.java:45`, `:317-371`) — intended for the text, undocumented for the
  surroundings.
- Client crash text is a separate path: `crash_scrub.h` masks Windows profile paths, dialog lines and IPv4 addresses
  (`src/crash_scrub.h:43-75`) but only for `LATEST_CRASH.txt` (`src/crash_report.cpp:80-93`); its header claims a
  `tests/crash_scrub_test.cpp` and a second Rust scrub, both already recorded false
  (`docs/architecture/client/crash-report.md:289-290`), and no client in this worktree sends `CP 0x373B`
  (`HOLES.md:384`).

## State

**working** for the form, the in-game writer and the staff reader: both writers are pinned by `ReportStoreTest.java:50-284`
and `test_report.py:71-225`, the reader by `test_report.py:318-378`, the end-to-end path by auto-QA t-br-1
(`Probes160.java:2737-2825`), and the flag is set on live (`docs/release/1.6.4.conf:271`).
**partial** for client log upload: the server half is complete and tested (`BugClientLogsTest.java:47-160`) but no
client sets caps bit 1 (`HOLES.md:384`), so that branch never runs. Live behaviour on the host was **not observed** for
this review.

## Open questions

- Live can never stamp `server_commit`: `world/online.json` carries a version string only, by design
  (`tools/site/sections/world.py:379-384`), so `Versions.now` takes the string branch (`report_service.py:103-105`).
  Keep the dict branch for a future private file, or drop it?
- `ReportStore.find` matches in-game lines only (`:484`), so a GM cannot look up a site-filed report by ref; is triage
  fine with refs existing only for in-game reports? Ticket 131 does not say.
- The `X-Slums: 1` header sent by `docs/site/assets/reports.js:10` is checked nowhere in `report_service.py`; the only
  gate is nginx `auth_request`. Deliberate (reads need no CSRF token) or an omission?
- Whether the deployed units and nginx on the live host match `tools/site/slums-report.service` and
  `slums-report.nginx.conf` was not verified — the live configuration is **unknown** for this review.
