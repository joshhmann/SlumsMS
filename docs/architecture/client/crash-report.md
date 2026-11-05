# Crash reporting and log upload

Client paths are relative to `/root/work/client/src-164-data`; server paths to
`/root/work/SlumsMS-164-archreview`. Two chains exist and they do **not** meet: an in-client crash report that is
written to disk and read by nobody, and a server-driven log-upload handshake (`@bug` → LP 0x373A → CP 0x373B) that no
client implements. Sibling doc `crash-guards.md` covers the guard hooks that prevent crashes; this one covers what
happens after one and what happens to the text.

**Are reports enabled for players by default? No.** The client reporter is behind `MAPLEROOT_CRASH_REPORT`, default
`OFF` (`CMakeLists.txt:17`), and the shipping build script never passes it (`scripts/build.ps1:11` passes twelve
`-D` switches — `MAPLEROOT_INTEGRATION=ON` plus the addons — but not `MAPLEROOT_CRASH_REPORT`), so a Release
`slums.dll` carries no reporter at all. There is no minidump of any kind (see Known issues). The server half of the
upload is enabled but dormant: no client ever sends caps bit 1, so no upload is ever requested from a real client
(`src/bypass.cpp:399-419`).

## Purpose

| Side | Goal | Evidence |
|---|---|---|
| Client | Turn an unhandled exception into a plain-language, privacy-scrubbed text file next to the game, and *never* send it | `src/crash_report.cpp:1-11` (header comment: "Nothing is sent anywhere: the launcher shows the file and sends it only when the player asks") |
| Client (VEH) | Keep a first-chance `crash.log` of AVs, illegal instructions, div0, stack overflows and C++ throws, so the report above can name the last C++ error | `src/crash_diagnostics.cpp:27-103` |
| Client (scrub) | Strip personal data before anything is written to `LATEST_CRASH.txt` | `src/crash_scrub.h:2-8`, applied at `src/crash_report.cpp:88` |
| Server | After `@bug`, ask a capable client for its log tail and merge it into the report's context file | `src/main/java/server/reports/BugClientLogs.java:22-38` |

Ticket 121 (client report) and ticket 131 (bug reports with automated context) are the two sources; 131 is the only
one with a server implementation.

## Entry points

| # | Where | What it is |
|---|---|---|
| 1 | `src/system.cpp:31-34` | `SetUnhandledExceptionFilter_hook` wraps the game's filter with `CrashReport_Wrap` — inside `#ifdef MAPLEROOT_CRASH_REPORT` |
| 2 | `src/crash_report.cpp:170-174` | `CrashReport_Wrap(requested)` remembers `requested` as `g_next` and returns `&SlumsTopLevelFilter` |
| 3 | `src/crash_report.cpp:155-163` | `SlumsTopLevelFilter(EXCEPTION_POINTERS*)` — the last-chance filter |
| 4 | `src/system.cpp:311-315` | `InstallCrashDiagnostics()` under `MAPLEROOT_INTEGRATION` (always in the shipping build) → VEH |
| 5 | `src/packetdispatcher.cpp:32` | `ProcessPacketHook` — peeks the opcode; the place an LP 0x373A handler *would* live (none does) |
| 6 | server `src/main/java/client/command/CommandsExecutor.java:273` | `@bug` / `@reportbug` registered; `@idea` at `:274` |
| 7 | server `src/main/java/net/PacketProcessor.java:194` | `RecvOpcode.BUG_LOG_UPLOAD` → `BugLogUploadHandler` |

There is **no entry point that ships a report anywhere**: no crash source reads `config.ini`, and no crash source
opens a socket (`grep GetPrivateProfile|ReadSwitch` in `src/crash_report.cpp`, `src/crash_diagnostics.cpp`,
`src/crash_scrub.h`, `src/crash_classify.h` = 0 hits).

## How it works

### A. Capture in the client

1. At attach, `AttachSystemHooks` installs `InstallCrashDiagnostics()` (`src/system.cpp:312`) and hooks
   `SetUnhandledExceptionFilter` (`src/system.cpp:316`). `InstallCrashDiagnostics()` calls
   `AddVectoredExceptionHandler(1, OnException)` (`src/crash_diagnostics.cpp:105-109`) — first-chance, every throw.
2. When the game calls `SetUnhandledExceptionFilter` (its `ZExceptionHandler` constructor, `src/system.cpp:26-29`),
   the hook swaps in `CrashReport_Wrap`, which stores the game's filter in `g_next` and returns `SlumsTopLevelFilter`
   (`src/crash_report.cpp:170-174`, `src/system.cpp:33`).
3. Side facts are recorded continuously so the report can name them: current map id via
   `CWvsContext::GetCurFieldID` 0x00A1238B (`src/resolution.cpp:123-131`, called from `set_stage_hook` at
   `src/resolution.cpp:139`), the last C++ HRESULT (`src/crash_diagnostics.cpp:32-43` → `CrashReport_NoteCxx`,
   `src/crash_report.cpp:178`), and the last unresolved data lookup (`src/resman.cpp:39-43` →
   `CrashReport_NoteMissing`/`NoteFound`, `src/crash_report.cpp:179-192`).
4. A *real* crash reaches `SlumsTopLevelFilter` (`src/crash_report.cpp:155-163`):
   `InterlockedCompareExchange(&g_inFilter, 1, 0)` guards re-entry (`:156`), `WriteReport(ep)` runs inside
   `__try/__except(EXCEPTION_EXECUTE_HANDLER)` (`:157-160`), and the exception is then always handed to `g_next`
   (`:162`) — the addon never swallows or reclassifies a crash.
5. `WriteReport` (`src/crash_report.cpp:96-153`) fills `CrashClassify::CrashFacts` (`:97-117`): code, AV address and
   read/write flag, faulting module basename via `GetModuleHandleExA`+`GetModuleFileNameA` (`:102-108`),
   `moduleIsMain`→`"MapleStory.exe"` (`:109-110`), `g_lastCxx` (`:111`), `ullAvailVirtual` from
   `GlobalMemoryStatusEx` (`:112-113`), missing asset (`:114-117`).

### B. Classification (pure, no Windows)

6. `CrashClassify::Classify` (`src/crash_classify.h:77-99`) applies 17 rules in order, first match wins:
   low memory < 64 MB / `0xC0000017` / `0x8007000E` / `0x22000003` → `OutOfMemory` (`:81`); `0xC00000FD` →
   `StackOverflow` (`:83`); `0xC0000006` → `DiskRead` (`:84`); missing asset or HRESULT `0x22000004..6` →
   `MissingData` (`:85`); AV in a `ModuleClass::Graphics` module (`:86`); Overlay/Sound/DataLibrary/Addon by module
   class (`:87-90`); `0xC0000374` or ntdll → `HeapCorruption` (`:91`); `0xC000001D`/`0xC0000096` → `InvalidCode`
   (`:92`); `0xC0000094`/`0xC000008E`/`0xC0000090` → `MathError` (`:93`); HRESULT `0x21000000..6` → `Disconnected`
   (`:94`); any C++ throw → `InternalError` (`:95`); AV in the main module with `avAddr < 0x10000` → `NullObject`
   (`:96`); AV → `AccessViolation` (`:97`); else `Unknown` (`:98`).
7. `ModuleClass` comes from `ClassOfModule` (`src/crash_classify.h:58-75`): prefix lists for graphics
   (d3d9/d3d8/dxgi/nvd3dum/…/gr2d_dx9, `:61-62`), overlays (rtsshooks/graphics-hook32/discordhook/…, `:63-64`),
   sound (sound_dx8/mss32/dsound, `:65`), the v83 data libraries (pcom.dll/namespace.dll/resman.dll/shape2d.dll,
   exact, `:66`), `slums.dll`→`Addon` (`:71`), `ntdll.dll`→`System` (`:72`), `MapleStory.exe`→`Main` (`:73`).
8. `Headline` (`:101-121`) and `Advice` (`:123-136`) give the two player-facing sentences; `FormatHeadline`
   (`:139-150`) appends the module in brackets only for `GraphicsDriver`/`Overlay`/`AccessViolation` (`:142`) and the
   asset path for `MissingData` (`:143`).

### C. Scrub — what is removed before the text is written

9. The only path that carries free text is the `integration.log` tail: `PutLogTail` (`src/crash_report.cpp:52-94`)
   opens the **relative** `integration.log` (`:53`), reads the last 16 KB (`:58-64`), drops the partial first line
   (`:68-71`), keeps at most the last 40 lines (`:72-78`), writes the header
   `--- integration.log (last lines, personal data removed) ---` (`:79`) and passes every line through
   `CrashScrub::ScrubLine` (`:88`).
10. `CrashScrub::ScrubLine` (`src/crash_scrub.h:43-75`) applies three rules:

| Removed / masked | Rule | Line |
|---|---|---|
| Whole line containing `Dialog:` or `dialog text` (NPC/player dialog bodies) → `[dialog line removed]` | `strstr` test before anything else, so the rest of the line is never copied | `src/crash_scrub.h:49-53` |
| Windows profile folder `<drive>:\Users\<name>\` → `<user>\` (case-insensitive, forward slashes too, must follow a non-alnum) | skips the 9-char prefix, then skips to the next separator | `src/crash_scrub.h:55-61` |
| IPv4 address → `<ip>`, but only with ≥ 5 total digits and no following digit | keeps four-part versions like `1.6.0.2` | `src/crash_scrub.h:25-39`, `:63-69` |

    Everything else (including full module paths from `crash_diagnostics.cpp`, machine names, account names, port
    numbers, chat text that is not a `Dialog:` line) is copied verbatim; truncation is to `cap-1`
    (`src/crash_scrub.h:46-47`).
11. Why the `Dialog:` rule exists: `MessageBoxA/W` are hooked under `MAPLEROOT_INTEGRATION` and log
    `DebugMessage("Dialog [%s]: %s")` (`src/system.cpp:295-302`), and `DebugMessage` appends every line to
    `integration.log` (`src/debug.cpp:8-23`) — so the tail can carry dialog text by construction.
12. `crash.log` from the VEH does **not** go through the scrubber at all: `AppendAddress` writes the full module path
    from `GetModuleFileNameA` (`src/crash_diagnostics.cpp:17-26`, format at `:23-24`), which contains
    `C:\Users\<name>\…`. That file is a first-chance log, not part of the report.

### D. Write the report, and the end of the client chain

13. The report buffer is assembled with `sprintf_s`/`Put` into static buffers — `g_report[24 KiB]`, `g_tail[16 KiB]`,
    `g_line[1 KiB]` (`src/crash_report.cpp:29-32`), kernel32 only, by design (`:7-8`): "the heap may be what broke".
    Header: `SlumsMS crash report`, local time, `SLUMS_RELEASE (build SLUMS_BUILD_NUMBER)` (`:125-126`); then
    `What happened:` + headline (`:128-129`), `What to try:` + advice (`:130-131`), `Details: code … in … at offset …`
    (`:132-133`), the AV operand and address (`:135-138`), `Map / Free address space / Last C++ error` (`:139-140`),
    the scrubbed log tail (`:142`).
14. `CreateDirectoryA("crash_reports")` (`:144`), write `crash_reports\LATEST_CRASH.tmp`
    (`:145-146`), `WriteFile`+`FlushFileBuffers` (`:149-150`), then `MoveFileExA(…, MOVEFILE_REPLACE_EXISTING)`
    (`:152`) so a half-written file never replaces a good one. Paths are relative to the process CWD.
15. **Chain ends here.** Nothing in either repo reads `crash_reports/LATEST_CRASH.txt`: the starter exe
    `src/launcher.cpp` (35 lines) creates the process (`:14`), waits (`:23`), reads the exit code (`:26`) and only logs
    `SESSION_PROCESS_EXIT` (`:31`) — no report read, no backstop write, no send; the Rust launcher
    `/root/work/launcher-160-join` has no crash reader (`grep LATEST_CRASH|crash_report|crash_reports` over
    `*.rs`/`*.mjs`/`*.html` = 0 hits; the only crash file it writes is its own
    `slumsms-launcher-crash.log`, `tauri/app/src/main.rs:18-24`).
16. `InstallCrashReport()` is an **empty function** (`src/crash_report.cpp:167`) with no caller anywhere (declared
    `src/crash_report.h:8`). The research plan's `InstallCrashReport` (paths, writer thread,
    `SetThreadStackGuarantee`, pruning) was never written, so a stack overflow has no usable stack when
    `SlumsTopLevelFilter` runs.

### E. Upload packet (server side specified, client side absent)

17. Player types `@bug <text>` → `ReportBugCommand.report` (`src/main/java/client/command/commands/gm0/ReportBugCommand.java:108`),
    `ReportStore.submit` assigns id+ref under the file lock (`src/main/java/server/reports/ReportStore.java:228-259`),
    the reply `Thanks! Bug #B-.. sent.` goes out (`src/main/java/client/command/commands/gm0/ReportBugCommand.java:125`), and for `type=="bug"` the server calls
    `BugClientLogs.get().request(c, player, r.ref(), now)` (`src/main/java/client/command/commands/gm0/ReportBugCommand.java:126-128`).
18. `request` returns immediately unless `supports(c)` — `getClientStampFormat() >= 2 && (caps & 0x2) != 0`
    (`src/main/java/server/reports/BugClientLogs.java:66-69`, `:73`) — and for bots (`:73`). Otherwise it issues a `SecureRandom` nonce
    (`:79-88`) and sends LP **0x373A `BUG_LOG_REQUEST`**:
    `short format(1); int nonce; string ref("B-12"); int maxBytes(32768)` (`src/main/java/server/reports/BugClientLogs.java:90-97`,
    opcode at `src/main/java/net/opcodes/SendOpcode.java:33`).
19. The client **never answers**: no client source contains 0x373A or 0x373B (`grep 0x373A|373A|373B` over
    `src/` = 0 hits; `src/packetdispatcher.cpp:32-99` dispatches 0x3739/0x373D/0x373F and its addon opcodes but has
    no branch for 0x373A), and the caps the client sends are computed in `ClientDataCaps()` which can only ever set
    bit 0 `kCapCustomNpcData` (`src/bypass.cpp:399-419`, `src/buildstamp_proto.h:25`). Consequently
    `supports()` is false for every shipping client and step 18's early return is always taken.
20. Were a client to answer, CP **0x373B `BUG_LOG_UPLOAD`** would be parsed by `BugClientLogs.parse`
    (`:100-116`: `short format; int nonce; int totalLength; byte truncated; int length; byte[length]`) and accepted by
    `accept` (`:119-144`) only if: caps still set (`:120-122`), nonce matches the pending one for that character
    (`:126-129`), younger than `NONCE_TTL_MS` 5 min (`:130-133`), and ≥ `MIN_INTERVAL_MS` 60 s since the last accepted
    upload (`:134-137`). The nonce is consumed whatever follows (`:138`); > 32 KiB is refused, not cut (`:112-114`,
    `:139-141`). `BugLogUploadHandler.handle` then logs the verdict and returns without a reply
    (`src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:29-37`).
21. Accepted text is scrubbed a second time and stored: `BugClientLogs.toContext` (`:147-164`) calls
    `BugPrivacy.scrubClientText(raw, accountName)` (`:149`), re-caps to 32 KiB after redaction may have grown lines
    (`:152-155`), and splits the `[name]` header lines into `sections` (`:167-183`).
    `store.updateContext(ref, ctx -> ctx.put("clientLogs", merged))` writes it on the writer thread
    (`src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:38-42` → `src/main/java/server/reports/ReportStore.java:517-536` → `writeContext` at `src/main/java/server/reports/ReportStore.java:356`).

### F. Privacy rule enforcement (both sides)

| Rule | Enforced at |
|---|---|
| Client: dialog lines dropped whole, `C:\Users\<name>` → `<user>`, IPv4 → `<ip>` before anything is written | `src/crash_scrub.h:49-53`, `:55-61`, `:63-69`; sole call site `src/crash_report.cpp:88` |
| Server: a *server* log line is dropped whole if it mentions passwords/PIN/PIC/tokens/salts/hashes/e-mail/whispers/IPs(4+6)/remote addr/session/cookie/authorization/api key/account or login names, or carries a ≥ 32-hex string | `src/main/java/server/reports/BugPrivacy.java:16-24` (`SENSITIVE`), `:27-29` (`serverLineAllowed`); the only caller is `src/main/java/server/reports/WarnLogRing.java:125` — a rejected line never enters the ring, so it cannot enter a report |
| Server: an *uploaded client* line is `[line removed]` when it mentions password/pwd/pin/pic/secret/token/cookie/authorization/api key/serial/license key | `src/main/java/server/reports/BugPrivacy.java:31-32` (`DROP_CLIENT_LINE`), applied `:52-55` |
| Server: uploaded text is redacted (not dropped) for `C:\Users\<name>`/`Documents and Settings`, `computer name=`/`hostname:`/`username=`-style pairs, e-mail, MAC, IPv6, IPv4, and the reporter's own account name (quoted, case-insensitive) | `src/main/java/server/reports/BugPrivacy.java:33-39`, applied `:56-64`; account name at `:62-64` |
| Server: control characters except `\n`/`\t` stripped from uploaded text | `src/main/java/server/reports/BugPrivacy.java:65` |
| Explicit non-secret: the slums.dll sha256 is kept on purpose | `src/main/java/server/reports/BugPrivacy.java:41-45` (javadoc); asserted at `src/test/java/server/reports/BugPrivacyTest.java:68` |

Note the asymmetry: the server trusts nothing the client did (`src/main/java/server/reports/BugClientLogs.java:36-37`, "the text is scrubbed again
here whatever the client did"), while the client's own scrubber is the only thing protecting `LATEST_CRASH.txt` on
disk — and `crash.log` has no scrubber at all.

## Data

| Item | Path / shape | Written by | Read by |
|---|---|---|---|
| `crash_reports\LATEST_CRASH.txt` | cwd-relative; `SlumsMS crash report` + time + build, headline, advice, details, map/free-MB/last-C++-error, scrubbed tail; named `LATEST_CRASH.tmp` until the rename | `src/crash_report.cpp:144-152` | **nothing** (steps 15-16) |
| `integration.log` | cwd-relative, `[SlumsMS] <line>` per record | `src/debug.cpp:16-19` | `PutLogTail` `src/crash_report.cpp:53` |
| `crash.log` | cwd-relative, append; `EXCEPTION pid= thread= code= first_chance=1`, `instruction address= module= offset=`, `memory operation= address=`, `exception_hresult=`, 12 EBP frames, ≤ 20 `stack_code_candidate`; capped 16 per exception class | `src/crash_diagnostics.cpp:50-99` | nothing in either repo |
| LP 0x373A `BUG_LOG_REQUEST` | `short 1, int nonce, string ref, int maxBytes(32768)` | `src/main/java/server/reports/BugClientLogs.java:90-97` | no client |
| CP 0x373B `BUG_LOG_UPLOAD` | `short 1, int nonce, int totalLength, byte truncated, int length, byte[len]` UTF-8 | no client | `BugClientLogs.parse` `:100-116` |
| `reports.jsonl` | one line per report, ids shared with the site's form; `-Dsolomapling.reportsFile`, default `data/reports/reports.jsonl` | `src/main/java/server/reports/ReportStore.java:110-126`, `:247-253` | `!bugs` / `!bug` (`src/main/java/client/command/commands/gm2/BugsCommand.java:57-101`), the site's report viewer/triage (`docs/tickets/131-bug-reports.md:101`) |
| `context/<ref>.json` | report context; `clientLogs` object = `{receivedAt, format, totalLength, truncated, bytes, sections{…}}` | `src/main/java/server/reports/ReportStore.java:238-245`, `src/main/java/server/reports/BugClientLogs.java:156-163` | `!bug <ref>` shows only `bytes` and `truncated` (`src/main/java/client/command/commands/gm2/BugsCommand.java:94-96`) and prints the file path (`:100`) |
| `crash_reports` dir | created unconditionally | `src/crash_report.cpp:144` | — |

Sizes and constants: `g_report` 24 KiB, `g_tail` 16 KiB (`src/crash_report.cpp:29-31`); 40 log lines (`:72-78`);
`MAX_BYTES` 32 KiB, `NONCE_TTL_MS` 5 min, `MIN_INTERVAL_MS` 60 s, `CAPS_LOG_UPLOAD` `1<<1`
(`src/main/java/server/reports/BugClientLogs.java:40-44`); `MIN_LENGTH` 5, `BUG_MAX_LENGTH` 500, per-account 3/10 min and 20/day, global 60/h and
300/day (`src/main/java/server/reports/ReportStore.java:60-70`).

## Switches

| Switch | Default | Read at | Effect |
|---|---|---|---|
| `MAPLEROOT_CRASH_REPORT` (CMake) | `OFF` | `CMakeLists.txt:17`; compiles `crash_report.cpp` + defines the macro at `src/CMakeLists.txt:76-79` | Enables the reporter, `CrashReport_Wrap` in the filter hook (`src/system.cpp:31-34`), the map/C++-HRESULT/data-miss notes (`src/resolution.cpp:123-131`, `src/crash_diagnostics.cpp:32-43`, `src/resman.cpp:39-43`) |
| `MAPLEROOT_INTEGRATION` (CMake) | `OFF` | `CMakeLists.txt:6`; `src/CMakeLists.txt:117-124`; on in `scripts/build.ps1:11` | The only switch that ships from the build script. Gates `crash_diagnostics.cpp` (VEH, `crash.log`) and the `MessageBoxA/W` dialog logging that the scrubber then has to remove (`src/system.cpp:292-303`) |
| `-Dsolomapling.reportsFile` (server JVM) | `data/reports/reports.jsonl` | `src/main/java/server/reports/ReportStore.java:115-120` | Where reports and `context/` live; unset logs a warning (`:116-117`) |
| caps bit 1 (`CAPS_LOG_UPLOAD`) | no client sets it | server reads it at `src/main/java/server/reports/BugClientLogs.java:68`; the client computes caps at `src/bypass.cpp:399-419` from `kCapCustomNpcData` only (`src/buildstamp_proto.h:25`) | Gates the whole LP 0x373A request |

There is **no `config.ini` key** for any of this: `/root/work/client/src-164-data/config.example.ini` has no `[crash]` section and no crash source
reads an ini. The research note proposed `[crash] report=1`, `[crash] firstchance=1`, `[crash] missingasset=warn`,
`[debug] crashtest=av|null|oom|stack|cxx|driver` and a separate `MAPLEROOT_CRASH_TEST` option
(`docs/research-121-crash-report.md:243-291`); none of them exists. No CMake preset turns the reporter on either
(`CMakePresets.json`: `debug-win32`, `release-win32`, no cache variables), and there is no
`FATAL_ERROR` tying `MAPLEROOT_CRASH_REPORT` to `MAPLEROOT_INTEGRATION` as the plan asked (`CMakeLists.txt:19-21`
has such a check only for `MAPLEROOT_SHUTDOWN_SMOKE_TEST`).

## Invariants

1. The addon filter never decides the outcome of a crash: `SlumsTopLevelFilter` always returns
   `g_next ? g_next(ep) : EXCEPTION_CONTINUE_SEARCH` (`src/crash_report.cpp:162`). Same for the VEH:
   `EXCEPTION_CONTINUE_SEARCH` at `src/crash_diagnostics.cpp:102`.
2. Writing the report cannot allocate or take a lock: static buffers, kernel32 only, documented at
   `src/crash_report.cpp:7-8`, realized in `:23-32` and `:34-41`.
3. A report file is never partially replaced: write `.tmp`, flush, `MoveFileExA(…REPLACE_EXISTING)`
   (`src/crash_report.cpp:145-152`).
4. Exactly one report attempt per process exception: `g_inFilter` compare-exchange (`src/crash_report.cpp:156`) and
   `reporting` in the VEH (`src/crash_diagnostics.cpp:44`).
5. A second scrub happens on the server regardless of the client (`src/main/java/server/reports/BugClientLogs.java:36-37`, `:149`); a refused
   upload is logged, never answered (`src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:34-37`, `src/main/java/server/reports/BugClientLogs.java:35-36`).
6. The upload nonce is single-use per character even on failure (`src/main/java/server/reports/BugClientLogs.java:138`), 5-minute TTL, one upload
   per minute, ≤ 32 KiB or refused outright (`:112-114`).
7. `LATEST_CRASH.txt` contains no `C:\Users\<name>`, no `Dialog:` body and no IPv4 address — *only* for text that
   went through `ScrubLine` (`src/crash_scrub.h:43-75`). Module paths are reduced to basenames in the report
   (`src/crash_report.cpp:107`) but stay whole in `crash.log` (`src/crash_diagnostics.cpp:23-24`).

## Origin

| Part | Verdict | Evidence |
|---|---|---|
| `src/crash_report.cpp/.h`, `src/crash_classify.h`, `src/crash_scrub.h` | **custom** (ours, ticket 121) | `git log --follow -- src/crash_report.cpp` = `3e77cc2 2026-10-28 Scaffold ticket 121: plain-language crash report`, `16521d2 2026-10-28 Crash report: the handler itself…`, `20a09b9 2026-10-28 Crash report: fill in the map, the last C++ error and the missing data path`. `crash_classify.h` and `crash_scrub.h` exist in no earlier commit (`20a09b9` is their first and only touch besides `3e77cc2`) |
| `src/crash_diagnostics.cpp` | **base/upstream (MapleRoot)** | `git log --follow` = `6119130 2026-10-18 Checkpoint accepted MapleRoot client integration and addons` (the MapleRoot companion import), then `20a09b9` added the `#ifdef MAPLEROOT_CRASH_REPORT` `NoteCxx` block (`src/crash_diagnostics.cpp:32-43`) — i.e. upstream file, modified by us |
| server `server/reports/BugClientLogs.java`, `BugPrivacy.java`, `net/server/channel/handlers/BugLogUploadHandler.java` | **custom** (ticket 131) | `src/main/java/server/reports/BugClientLogs.java:22-38`, `src/main/java/server/reports/BugPrivacy.java:6-10`, `src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:14-18` all carry the ticket id; design doc `docs/tickets/131-bug-reports.md` |
| `docs/research-121-crash-report.md` | **custom** research note, 2026-10-28, read-only | `docs/research-121-crash-report.md:1-5` |

`docs/PROVENANCE.md` and `docs/KAENTAKE-UPSTREAM.md` do not mention crash handling at all (`grep -i crash` = 0 hits
in both, and in `README.md`); the only status prose is `docs/PROJECT-STATUS.md:24` ("Character-entry crashes and
cleanup/exit crashes were corrected").

## Tests and QA

Client (`ctest` via `tests/CMakeLists.txt`):

| Test | Registered | Covers | Does not cover |
|---|---|---|---|
| `crash_report_classify_scrub` | `tests/CMakeLists.txt:169-170`, source `tests/crash_report_test.cpp` (76 lines, `int main()` at `:30`) | Classifier table: driver AV, Gr2D_DX9, RTSSHooks overlay, slums.dll, mss32, PCOM, stack overflow, heap corruption, `0x21000003` disconnect, missing-data HRESULT, C++ throw, null object, plain AV, OOM-before-driver precedence, `ClassOfModule("")`/unknown (`:32-51`); two exact `FormatHeadline` strings (`:53-60`); non-empty advice (`:61`); scrub of `C:\Users\Josh\…`, forward slashes, `192.168.0.164:8484`, `1.6.0.2` untouched, `Dialog:` line, truncation to 7 chars (`:63-69`) | The filter itself, `PutLogTail`, file write + rename, `NoteField`/`NoteCxx`/`NoteMissing`/`NoteFound`, `InstallCrashReport`, any packet, the server's `scrubClientText` |
| build script checks | `scripts/build.ps1:13-19` | cmake configure with the addon switches, `Release --parallel 4` build, `ctest -C Release --output-on-failure`, then prints "Built SlumsMS files (SlumsMS.exe, slums.dll, ijl15.dll, Custom.wz)" | Nothing crash-specific; since the script does not pass `MAPLEROOT_CRASH_REPORT`, the reporter and its test source are not exercised by a shipping build |
| release record | `release/build-verification.json` | `client_tests: 7`, `client_source_only_tests: 5`, companion+Kaentake build "passed from new repo paths", `live_deployed: false` | No statement that the crash reporter was ever built or run |

Server (JUnit, `src/test/java`):

| Test | Covers |
|---|---|
| `server/reports/BugClientLogsTest.java:47` `onlyClientsWithCapsBit1AreAsked` | format 1 / caps 1 / null refused, bots refused, caps 3 asked |
| `:70` `requestPacketLayout`, `:84` `aNonceWorksOnceForItsCharacterWhileFresh`, `:99` `expiryAndOncePerMinute`, `:114` `sizeAndShapeChecks`, `:133` `contextIsScrubbedAndSplitIntoSections` | packet layout, nonce/expiry/rate, size refusal, scrub + section split |
| `server/reports/BugPrivacyTest.java:12,30,43` | sensitive server lines dropped, ordinary errors kept, client text redacted not dropped (user path, PC name, account name, IP, e-mail, MAC, password line, dll hash kept, control chars stripped) |
| `net/server/channel/handlers/BugLogUploadHandlerTest.java:42` `mergesScrubbedLogsIntoTheReportOnce` | end-to-end: request → upload → `clientLogs.sections.crash` == `boom in C:\Users\<user>\x.dll for <account>` |
| `server/reports/ReportCommandTest.java:84` `aClientWithLogUploadIsAskedAfterABug` | request sent only to a caps-bit-1 client; an old client gets the reply and no packet |

Auto-QA `t-br-1` (`docs/ops/qa-suite.md:620-626`) covers `@bug` context and the password-line exclusion, not the
0x373B upload. **Not covered anywhere:** a real client ever sending 0x373A/0x373B; a real crash producing
`LATEST_CRASH.txt` (no test, no manual evidence — `MAPLEROOT_CRASH_REPORT` is off in every build that was recorded);
`crash.log` scrub behaviour (there is none to test); the empty `InstallCrashReport`.

What a real client must confirm by hand (none of it done yet): build with `-DMAPLEROOT_CRASH_REPORT=ON`, force an AV
and a stack overflow in a field, check `LATEST_CRASH.txt` names the right map and build and contains no
`C:\Users\`, no `Dialog:` body and no IP; rename `Custom.wz` and confirm the `MissingData` headline carries the path;
then decide how (or whether) the file ever leaves the machine.

## Known issues

- `NEW-FINDING: src/crash_report.cpp:167` — `InstallCrashReport()` is an empty stub with no caller. No report path
  setup, no writer thread, no `SetThreadStackGuarantee`, no pruning; a stack-overflow crash therefore runs
  `WriteReport` on the exhausted stack and the `__try` wrapper (`:157-160`) can only lose the report.
- `NEW-FINDING: src/crash_report.cpp:53` — `PutLogTail` and the write at `:145` use CWD-relative paths, so a client
  started with a different working directory silently produces no tail or writes the report somewhere else.
  `integration.log` itself is also CWD-relative (`src/debug.cpp:16`), so the two agree only by luck.
- `NEW-FINDING: src/crash_diagnostics.cpp:23-24` — `crash.log` records the full module path
  (`C:\Users\<name>\…`) and never goes through `CrashScrub`. Any future upload of `crash.log` would need the server
  scrub to catch it; `BugPrivacy.scrubClientText` would (`src/main/java/server/reports/BugPrivacy.java:33`, `:56`), `LATEST_CRASH.txt` would not.
- `NEW-FINDING: src/crash_scrub.h:49-53` — the dialog rule is a `strstr` over the whole line, so any `integration.log`
  line that merely *contains* `dialog text` is replaced entirely; conversely a dialog logged with another prefix
  (not `Dialog:`) survives. Broad on purpose, but it also means a report can lose the one line that explained the
  crash.
- `NEW-FINDING: src/crash_scrub.h:5` and `src/crash_classify.h:5` — both header comments point at
  `tests/crash_scrub_test.cpp` / `tests/crash_classify_test.cpp`, which do not exist; the cases live in
  `tests/crash_report_test.cpp`. Dead references mislead the next reader.
- `NEW-FINDING: src/main/java/server/reports/BugClientLogs.java:40` — client and server disagree about caps bit 1: `BugClientLogs.CAPS_LOG_UPLOAD`
  is `1<<1` (`src/main/java/server/reports/BugClientLogs.java:40`) and `BugContext.capsNames` names it `logUpload` (`src/main/java/server/reports/BugContext.java:151-153`),
  but `ClientDataCaps()` can only ever produce `kCapCustomNpcData` (`src/bypass.cpp:399-419`,
  `src/buildstamp_proto.h:25`). `docs/tickets/131-bug-reports.md:4-5` says so openly ("no client sets that bit yet").
- `NEW-FINDING: docs/tickets/131-bug-reports.md:184-187` — the server expects a `[crash]` section carrying the tail of
  `LATEST_CRASH.txt`; no client-side code builds such a section, so the contract is unimplemented on the sending side.
- `NEW-FINDING: src/crash_report.cpp:10` -- the header promises "the launcher shows the file and sends it
  only when the player asks". Neither launcher does: `src/launcher.cpp:23-31` only waits and logs the exit code, and
  the Rust launcher has no crash reader. The consent promise is unfulfilled because there is no UI.
- `NEW-FINDING: CMakeLists.txt:17` vs `src/crash_diagnostics.cpp:32` — the VEH's `NoteCxx` and `resman`/`resolution`
  notes are all `#ifdef MAPLEROOT_CRASH_REPORT`, i.e. the shipping build pays for neither; but `crash_diagnostics.cpp`
  itself ships (`src/CMakeLists.txt:118`), so `crash.log` is written on every player's machine by default, unfiltered
  and never collected.
- Card **b251** (`/root/work/STATUS-1.6.4.md:72`, S2, Client): "Client crash entering Rose Garden: Abandoned Garden
  (211080100), Von Leon's castle area", owner 1.6.4 "work on this in another patch" — a crash with no report path to
  diagnose it, which is the gap this subsystem was meant to close.
- Cards b270 (`:75`, launcher news manifest), b274 (`:76`, boss portals never send portal-enter) and L15 (`:106`,
  client fixes) are on the client lane but unrelated to crash reporting; e14 (`:124`, Slums Exchange window) likewise.

## State

| Piece | State | Evidence |
|---|---|---|
| Classifier + scrubber (headers) | **working** — unit-tested, pure C++ | `tests/crash_report_test.cpp:30-69` passes as `crash_report_classify_scrub` (`tests/CMakeLists.txt:169-170`) |
| Report writer + filter (`crash_report.cpp`) | **partial** — code complete but never compiled by the shipping build, never run against a real crash, and `InstallCrashReport` is empty | `CMakeLists.txt:17` (OFF) and `scripts/build.ps1:11` (absent); `src/crash_report.cpp:167` |
| Capture of context facts (map, C++ HRESULT, data miss) | **partial** — implemented, gated, unverified | `src/resolution.cpp:123-131`, `src/crash_diagnostics.cpp:32-43`, `src/resman.cpp:39-43` |
| First-chance `crash.log` | **working** (ships by default), but unscrubbed and read by nobody | `src/CMakeLists.txt:118`, `src/crash_diagnostics.cpp:50-99` |
| Delivery of a report to anyone | **broken** — no reader, no sender, no consent UI | steps 15-16 above |
| Server request/accept/storage (`@bug` → 0x373A → 0x373B → `clientLogs`) | **working on the server**, dormant in practice | `BugClientLogsTest`, `BugLogUploadHandlerTest`, `ReportCommandTest:84` green; caps bit unreachable from a client (`src/bypass.cpp:399-419`) |
| Client answering 0x373A | **absent** | 0 hits for 0x373A/0x373B in the client repo |
| Minidumps | **absent** | `grep -r MiniDumpWriteDump|minidump|dbghelp` matches only third-party Detours (`/root/work/client/src-164-data/mapleroot/ijl15/detours.h:371-408`) |

## Open questions

1. Is shipping a new `SlumsMS.exe` still off the table ("ship only slums.dll", `docs/research-121-crash-report.md:16-19`)?
   The only crash classes the in-process filter cannot catch (fast-fail `/GS` `0xC0000409`, CRT abort, hard stack
   overflow) need the starter-exe backstop described at `docs/research-121-crash-report.md:275-277`, which is not written.
2. Does anyone intend to turn `MAPLEROOT_CRASH_REPORT` on? Nothing in either build path does, and no preset exists
   (`CMakePresets.json`), so the code is currently dead weight in the tree.
3. Who is supposed to read `crash_reports/LATEST_CRASH.txt` — the Rust launcher (which has no reader) or a human
   asking the player for the file? The header comment (`src/crash_report.cpp:10`) and both launchers disagree.
4. Should the VEH's `crash.log` be scrubbed and uploaded, or dropped in favour of the already-scrubbed report? Its
   full module paths are the reason `BugPrivacy` has a `USER_PATH` rule in the first place.
5. `/root/work/client/src-164-data/src/crash_scrub.h:5-7` claims "the launcher scrubs again in Rust before showing or sending". No Rust scrubber exists
   on this line (`/root/work/launcher-160-join`: 0 hits for `LATEST_CRASH`). Was that written against an older
   launcher branch, or is it aspirational?
6. Should the client-side `[crash]`/`[integration]`/`[build]`/`[display]` sections specified in
   `docs/tickets/131-bug-reports.md:184-187` be built on top of `LATEST_CRASH.txt`, and does the 32 KiB cap apply to
   that file (24 KiB buffer) plus `integration.log` (40 lines) combined?
7. Is `t-br-1` (`docs/ops/qa-suite.md:620`) expected to ever see a `clientLogs` object, given no client sets caps bit 1?
   As written it does not assert one, which is consistent with the client side being absent.
