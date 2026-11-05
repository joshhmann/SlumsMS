# Starter exe (SlumsMS.exe) and process creation

## Purpose

`SlumsMS.exe` is a ~36-line Win32 stub: it starts `MapleStory.exe` **suspended**, has Detours inject `slums.dll`
into the child's import table, resumes it, waits for it and exits with a 0/1 translation of the game's exit code
(`src/launcher.cpp:7-36`). It is the only supported way to get `slums.dll` into the 2008-era client binary; the
DLL cannot be loaded by the game itself, and the stock exe is never modified on disk (the injection is an in-memory
import-table edit on a suspended process).

The starter owns exactly three things: process creation + injection, waiting, and the exit-code translation. It
does **not** own the server address (`slums.dll` reads it), the window, the working directory, or installation —
see "How it works" and "Invariants".

## Entry points

| Entry | Where | Note |
|---|---|---|
| `WinMain(HINSTANCE, HINSTANCE, LPSTR, int)` | `src/launcher.cpp:7` | Only entry point; no `main`, no CLI parser, no message pump |
| UAC elevation | `src/launcher.manifest:5-11` | `requireAdministrator` — every double-click raises the prompt |
| Icon + version resource | `src/launcher.rc:9-37,39-40`, `src/launcher.ico` | 6 frames, 16/24/32/48/64/256 px, 32 bpp, 132,415 bytes |
| Build target | `src/CMakeLists.txt:150-168` | `add_executable(launcher WIN32 launcher.manifest launcher.rc launcher.cpp debug.cpp)`, `OUTPUT_NAME SlumsMS` |
| Who starts it | Tauri launcher `ShellExecuteExW` (`/root/work/launcher/tauri/core/src/play.rs:87-97`, default exe `SlumsMS.exe` at `tauri/app/src/launcher.rs:671`); QA driver `CreateProcessW` (`SlumsMS-164-archreview:tools/ops/client-mcp-win/src/windows.py:308-311`); a player's Explorer | Starter has no self-update, no arguments of its own |

## How it works

Launch sequence, double-click to game window:

1. **UAC.** Explorer (or the Tauri launcher) starts `SlumsMS.exe`; the embedded manifest demands elevation
   (`src/launcher.manifest:8`), so Windows shows the consent prompt. Declined → the caller sees
   `ShellExecuteEx` error `1223` (`/root/work/launcher/tauri/core/src/play.rs:100-102`) or `CreateProcessW` error
   `740` (`SlumsMS-164-archreview:tools/ops/client-mcp-win/src/windows.py:314-315`). The QA driver deliberately
   refuses to auto-elevate; the server must be started from an elevated owner terminal.
2. **`STARTUPINFO` setup.** `WinMain` zeroes `STARTUPINFOA`/`PROCESS_INFORMATION` and sets `si.cb`
   (`src/launcher.cpp:8-12`). Nothing else is configured — no `hStdInput`, no window flags, no environment block.
3. **Create + inject, one call.** `DetourCreateProcessWithDllExA("MapleStory.exe", lpCmdLine, NULL, NULL, FALSE,
   CREATE_SUSPENDED, NULL, NULL, &si, &pi, CONSTANTS_DLL_NAME, NULL)` (`src/launcher.cpp:14`).
   - `lpApplicationName` is the bare name `"MapleStory.exe"`, resolved by the Win32 `CreateProcess` search order
     (starter's own directory first) — the starter does no path building, no `GetModuleFileName`, no
     `SetCurrentDirectory`.
   - `lpCurrentDirectory = NULL`: the game inherits the starter's working directory. Every `config.ini` read in the
     client is relative (`".\\" CONSTANTS_CONFIG_NAME`), so the parent's CWD **is** the config location
     (`/root/work/launcher/tauri/core/src/game_config.rs:54-58` records the same contract from the launcher side).
   - `CONSTANTS_DLL_NAME` = `"slums.dll"` (`src/constants.h:4`) — also a bare name, so it resolves beside the exe.
   - Inside Detours: the child is created suspended (`external/Detours/src/creatwth.cpp:1561-1570`, pinned commit
     `9764cebcb1a75940e68fa83d6730ffaf0f669401`, `.gitmodules:1-3`; the submodule is not populated in this checkout,
     lines read from that commit's source), then `DetourUpdateProcessWithDll` enumerates modules, finds the EXE image,
     copies and rewrites its import descriptor to add the DLL (`external/Detours/src/creatwth.cpp:1576-1585`,
     `external/Detours/src/uimports.cpp:17-21,279-317`). Because the caller passed `CREATE_SUSPENDED`, Detours does
     not resume (`external/Detours/src/creatwth.cpp:1587-1589`). Failure → `TerminateProcess(child, ~0u)` and
     `FALSE` (`external/Detours/src/creatwth.cpp:1581-1584`).
4. **Failure dialog.** Non-zero return → `GetLastError` is formatted with `FormatMessageA`
   (`src/launcher.cpp:15-17`) and shown as `Could not start MapleStory.exe [<err>]` via `ErrorMessage`
   (`src/launcher.cpp:18`), which is a plain `MessageBoxA(nullptr, …, "Error", MB_ICONERROR)`
   (`src/debug.cpp:31`). `LocalFree` + `return 1` (`src/launcher.cpp:19-20`). Typical code: `2`
   `ERROR_FILE_NOT_FOUND` when `MapleStory.exe` is not in the search path.
5. **Resume.** `ResumeThread(pi.hThread)` (`src/launcher.cpp:22`); the loader now maps `slums.dll` as an import of
   `MapleStory.exe` before the game's entry point runs.
6. **`slums.dll` boot.** `DllMain` `DLL_PROCESS_ATTACH` (`src/injector.cpp:45-52`): `DisableThreadLibraryCalls`,
   `ProcessCommandLine` (`sscanf_s(GetCommandLineA(), "%s %d")` → host + port, `src/injector.cpp:17-27`), then
   `ProcessConfigFile` only if no host came from the command line (`src/injector.cpp:29-42`), then
   `AttachSystemHooks`.
7. **Hooks before the game needs them.** `AttachSystemHooks` reads `[graphics] borderless`/`dpiaware` from
   `.\config.ini` (`src/system.cpp:305-310`, reader at `:68-72`), installs the crash diagnostics and the
   `MessageBox`/`SetUnhandledExceptionFilter`/`CreateMutexA`/`CreateWindowExA`/`RegCreateKeyExA`/`WSPStartup`
   detours (`src/system.cpp:311-320`) and attaches the D3D9 host (`src/system.cpp:322`).
8. **First window.** The game calls `CreateWindowExA("MapleStoryClass", …)`; the hook renames it
   `SlumsMS <release> (<build>)` (`src/system.cpp:186-189`, format `src/buildshow_text.h:12-14`), forces the style
   (`g_bBorderless ? WS_POPUP : 0xCA0000`, `src/system.cpp:187`), swaps in `WndProc_hook`
   (`src/system.cpp:190`), stores the HWND and applies borderless placement (`src/system.cpp:191-192`, early-out
   at `:199` when the switch is off).
9. **Login connect.** The game dials a stock Nexon address; `WSPConnect_hook` matches `63.251.217.2/.3/.4/127.0.0.1`
   (`src/system.cpp:223-228`, `:256-273`) and rewrites destination + port to the configured host, resolved once
   through `InetPtonA`/`getaddrinfo` (`src/system.cpp:235-254`). A name that will not resolve raises
   `Could not find the server "<host>" …` (`src/system.cpp:250-253`).
10. **Wait.** `WaitForSingleObject(pi.hProcess, INFINITE)` (`src/launcher.cpp:23`) — the starter sits on a handle
    for the whole session; no timeout, no window, no tray icon, nothing to kill but the process.
11. **Exit translation.** `GetExitCodeProcess` (failure → `GetExitCodeProcess failed [<err>]` and `return 1`,
    `src/launcher.cpp:25-29`), optional `SESSION_PROCESS_EXIT pid=%lu code=%08lX`
    (`src/launcher.cpp:30-32`, only under `MAPLEROOT_INTEGRATION`, which writes to `integration.log`,
    `src/debug.cpp:14-20`), handles closed (`src/launcher.cpp:33-34`) and `return dwExitCode == 0 ? 0 : 1;`
    (`src/launcher.cpp:35`) — every non-zero game exit collapses to 1.

**"Game installed elsewhere."** The starter has no install concept: it never reads a registry key, an install
record or `%LOCALAPPDATA%`, and it cannot be pointed at another folder (there is no argument for it — `lpCmdLine`
is forwarded to the game, `src/launcher.cpp:14`). Resolution is whatever `MapleStory.exe` the Win32 search order
finds from the starter's directory/CWD. Choosing and remembering the install folder belongs to the Tauri launcher:
`detect_bundled_client` looks in the launcher's folder then its parent (`/root/work/launcher/tauri/core/src/config.rs:110-116`),
`migrate_legacy_install`/`holds_game` decide whether a folder already has the game
(`/root/work/launcher/tauri/core/src/settings.rs:154-159`), and `launch` sets `lpDirectory` to the chosen folder so
the inherited CWD is right (`/root/work/launcher/tauri/core/src/play.rs:76-97`). A starter copied into a folder
without `MapleStory.exe` simply fails at step 4 with error 2.

## Data

| File | Direction | Written/read where |
|---|---|---|
| `MapleStory.exe` | read (executed) | bare-name resolution, `src/launcher.cpp:14` |
| `slums.dll` | read (injected) | `CONSTANTS_DLL_NAME`, `src/constants.h:4`, `src/launcher.cpp:14`; must be in the search path of the child |
| `config.ini` | **read only** — the starter/DLL never write it | `[config] host`/`port` `src/injector.cpp:34,38`; `[graphics]` `src/system.cpp:70`, `src/d3d9host.cpp:43`; write side is the Tauri launcher (`/root/work/launcher/tauri/core/src/game_config.rs:100-120`) |
| `integration.log` | written, CWD-relative, `[SlumsMS] <line>`, append-only, no rotation | `src/debug.cpp:16-18`; starter's own exit line `src/launcher.cpp:31`; also written by `src/mapleroot.cpp:19`, `src/crash_report.cpp:53` |
| `crash.log` | written, CWD-relative | `src/crash_diagnostics.cpp:50-51` (path note: `docs/research-121-crash-report.md:110`) |
| `crash_reports\LATEST_CRASH.txt` | written | `src/crash_report.cpp:145-152` |
| `slums-ui-state.json` | written, CWD-relative (QA `[debug] uistate=1`) | `src/uilayout.cpp:430-436` |
| `ijl15.dll` | deployed beside the game, **not** replaced by us | stock Intel bytes copied from `vendor/ijl15/ijl15.dll` (352,256 bytes) by `src/CMakeLists.txt:125-130`; "the game loads the real Intel ijl15.dll again" `src/CMakeLists.txt:123`, `mapleroot/CMakeLists.txt:1-3`, `src/mapleroot.cpp:7` |
| `discord_game_sdk.dll`, `Custom.wz` | copied beside the exe/DLL | `src/CMakeLists.txt:128-129` (SDK), `:142-146` (Custom.wz, mounted as the `Custom` namespace at `src/resman.cpp:128-142`) |

Injection is in-memory only — no file on disk is patched, so the exe's own bytes and `ijl15.dll` stay stock
(`SlumsMS-164-archreview:tools/release/client-allow.txt:47-48` still lists `config.ini` and `ijl15.dll` as the only
allowed "different" file next to the data overlay).

## Switches

| Switch | Type | Default | Read at | Effect on this subsystem |
|---|---|---|---|---|
| `[config] host` | string | none → `127.0.0.1` (`src/constants.h:9`) | `src/injector.cpp:34`, default applied `src/system.cpp:236` | Destination of the rewritten connect |
| `[config] port` | int | 0 = keep the game's port | `src/injector.cpp:38-41`, applied only if non-zero `src/system.cpp:269-271` | Login port |
| `CONSTANTS_USE_COMMAND_LINE` | compile-time `TRUE` (`src/constants.h:10`) | on | `src/injector.cpp:18-20` | `<host> <port>` as arguments beat the ini (`:30` early return) |
| `CONSTANTS_USE_CONFIG_FILE` | compile-time `TRUE` (`src/constants.h:11`) | on | `src/injector.cpp:30` | ini is consulted at all |
| `[graphics] borderless` | bool `1/true/yes/on` (`src/window_policy.h:27-31`) | false | `src/system.cpp:306` | Window style/frame after `CreateWindowExA` |
| `[graphics] dpiaware` | bool | false | `src/system.cpp:307` | `SetProcessDPIAware()` before any window |
| `[graphics] d3d9host` | bool, default true | true | `src/d3d9host.cpp:41-45` (`integration::ParseIniBool`) | Start-up D3D9 diagnostics/fallback — the path that surfaces the Parsec failure |
| `[graphics] adapterfallback` | bool, default true | true | `src/d3d9host.cpp:41-45` | Retries `CreateDevice` on other adapters / software VP |
| `MAPLEROOT_INTEGRATION` | CMake option | OFF in `CMakeLists.txt:6`, forced ON from `scripts/build.ps1:11`, propagated to the starter at `src/CMakeLists.txt:166-168` | `src/launcher.cpp:30` | Enables the starter's only log line and the whole companion |

No key exists for: the injected DLL name, the game exe name, the working directory, a wait timeout, or the exit code
mapping. Those are compile-time constants or hardcoded.

## Invariants

- **Names are bare and fixed.** `"MapleStory.exe"` (`src/launcher.cpp:14`) and `"slums.dll"` (`src/constants.h:4`).
  Both must sit in the child's search path; renaming `slums.dll` silently disables every patch.
- **The child inherits the parent's CWD.** All client-side ini/diagnostic paths are relative
  (`src/injector.cpp:34`, `src/debug.cpp:16`), so the starter must be started with the game folder as CWD — the
  launcher guarantees it (`/root/work/launcher/tauri/core/src/play.rs:96`), the QA driver too
  (`SlumsMS-164-archreview:tools/ops/client-mcp-win/src/windows.py:308-311`).
- **Command line beats `config.ini`, per field.** `ProcessConfigFile` returns early when a host was parsed
  (`src/injector.cpp:30`), and a host without a readable port leaves `g_nServerPort = 0` — no host/port mixing
  between the two sources.
- **The starter never writes `config.ini`.** No `WritePrivateProfile*` anywhere in `src/` or `mapleroot/`; the write
  side is the launcher, which refuses while a game process exists (`/root/work/launcher/tauri/core/src/game_config.rs:103-105`).
- **Only the four stock addresses are redirected** (`src/system.cpp:223-228`); anything else the game dials passes
  through untouched, and `WSPGetPeerName` restores the original address to the caller (`src/system.cpp:277-281`).
- **Exit code is a boolean.** 0 → 0, everything else → 1 (`src/launcher.cpp:35`).
- **Elevation is mandatory.** Manifest `requireAdministrator` (`src/launcher.manifest:8`); no unelevated path exists,
  which is why the QA harness refuses automatic start (`SlumsMS-164-archreview:tools/ops/client-mcp-win/src/windows.py:314-315`).
- **The starter is not rebuilt per release.** `stage-client` copies only `slums.dll`, `slums-build.txt` and
  `Custom.wz` over the previous layer; "`SlumsMS.exe` and everything else stay the published bytes"
  (`SlumsMS-164-archreview:tools/release/stage-client:11-14`, `tools/release/README.md:28-30`).

## Origin

| Part | Verdict | Evidence |
|---|---|---|
| `src/launcher.cpp` | **base** (Kaentake stub) | first commit `7d36862` 2025-08-27 "WzLib dependency to v83_DX8to9 branch, add stub for AttachResolutionMod"; upstream usage doc "run Kaentake.exe as administrator" `docs/KAENTAKE-UPSTREAM.md:3`; lineage `docs/PROVENANCE.md:3` |
| Command-line / ini precedence | **modified** (ours) | `a72d741` 2026-03-16 "Add commandline/config parsing for client redirection" → `src/injector.cpp:17-42`; upstream documents the same precedence for Kaentake (`docs/KAENTAKE-UPSTREAM.md:11-23`) |
| App-name-from-cmdline experiment | **modified, reverted** | `4587400` 2025-09-02 "allow application name to be spcified with launcher commandline" — the current file hardcodes `"MapleStory.exe"` again (`src/launcher.cpp:14`) |
| `SlumsMS.exe` name, one-DLL starter | **custom** | `f1b64a6` 2026-10-19 "Ship the client as one slums.dll started by SlumsMS.exe" — renames in `src/constants.h:3-4`, `src/launcher.rc`, `src/launcher.manifest`, `OUTPUT_NAME` `src/CMakeLists.txt:164` |
| `src/launcher.rc`, `src/launcher.manifest` | **base then modified** | `91bccb5` 2025-12-09 (manifest+icon), `b4592f9` 2026-03-12 "Fix launcher manifest", `f1b64a6` (rename) |
| `src/launcher.ico` | **custom** | `4e83c4f` 2026-10-26 "SlumsMS.exe icon: the taped-leaf emblem (same ICO as the launcher, 16-256 px)" |
| `src/system.cpp`, `src/debug.cpp`, `src/debug.h` | **base then modified** | `6003540` 2025-08-27 "Add system hooks and helper functions and macros"; `6119130` 2026-10-18 checkpoint; `f1b64a6` (log prefix `[SlumsMS]`) |
| `scripts/build.ps1` | **modified** (ours) | `6119130` 2026-10-18 → `f1b64a6` → `019ebc0`, `59f300e`, … → `997a0fc` 2026-10-25 (per-addon flag added each time) |
| `ijl15.dll` proxy removal | **custom** | `mapleroot/CMakeLists.txt:1-3` "it used to ship as a separate ijl15.dll proxy" |

## Tests and QA

- **No ctest covers the starter.** `tests/CMakeLists.txt` holds 47 `add_test` lines (several are asset-gated pairs,
  e.g. `:26-29`); grep across `tests/` for `launcher`/`SlumsMS.exe` returns nothing. The only adjacent checks are pure policy headers:
  `window_borderless_place` (`tests/CMakeLists.txt:162`, `tests/window_policy_test.cpp:15-32`) and
  `d3d9host_adapter_fallback` (`tests/CMakeLists.txt:77`, `tests/d3d9host_policy_test.cpp:36`). Nothing tests
  process creation, injection, the wait, or the exit mapping.
- **`scripts/build.ps1` checks inputs and compiles, not behaviour.** `/root/work/client/src-164-data/scripts/prepare-dependencies.ps1:3-8` verifies each
  `dependencies.json` hash (incl. `vendor/ijl15/ijl15.dll`, `/root/work/client/src-164-data/mapleroot/ijl15/discord_game_sdk.dll`); `/root/work/client/src-164-data/scripts/build.ps1:5-6`
  requires the submodules; `:7-9` requires the VS x86 toolset; `:13-14` configure, `:15-16` build, `:17-18` ctest
  (`Client configuration failed` / `Client build failed` / `Native tests failed`). There is **no staging copy step**
  (any revision): staging is CMake `POST_BUILD` (`src/CMakeLists.txt:125-130,142-146`) into
  `build/<Config>` (`CMakeLists.txt:25-26`), and `:19` only prints the output list.
- **QA harness file check** (`SlumsMS-164-archreview:tools/ops/client-mcp-win/src/driver.py:292-296`): `<root>/client`
  must contain `SlumsMS.exe`, `MapleStory.exe`, `slums.dll`, `config.ini`; `display_guard` refuses to start on a
  virtual primary display unless an `allow-virtual-display` file exists (`:298-303`).
- **Recorded evidence of a real run:** one `SESSION_PROCESS_EXIT` line, `[Kaentake] SESSION_PROCESS_EXIT pid=396
  code=00000000` (`docs/evidence/cashshop-window-persistence-exit-20261018.json:23-24`) — a clean exit, from the
  pre-rename log prefix.
- **Not covered, must be confirmed by hand on Windows:** (a) an elevated double-click starts the game and the login
  screen appears with the `slums.dll` title; (b) the UAC prompt appears at all (manifest resource is really linked —
  `target_link_options(launcher PRIVATE "/MANIFEST:NO")` at `src/CMakeLists.txt:157` suppresses the *linker's* default
  manifest, relying on `launcher.rc`'s `RT_MANIFEST` resource, `src/launcher.rc:39`); (c) a wrong `[config] host` shows
  the resolve dialog rather than a silent exit; (d) the starter's exit code reaches the launcher; (e) starting from a
  folder whose CWD is elsewhere writes `integration.log` to the wrong place (a real hazard, see Known issues).

## Known issues

Client-lane cards from `/root/work/STATUS-1.6.4.md` — none is about the starter exe itself; they are listed because
this subsystem is where a client build ships:

| Card | Line | Relation to the starter |
|---|---|---|
| b017 | `:70` | Windows Defender can flag a **new launcher exe** (process fix: owner submits each new exe). Directly a starter-exe risk; no code mitigation |
| b270 | `:75` | Launcher news `manifest.json` written without `news[]` — that is the **Tauri** launcher feed, not `SlumsMS.exe` |
| b251 | `:72` | Client crash entering Rose Garden (211080100) — in-game, starter only relays the exit code |
| b274 | `:76` | Boss portals never send portal-enter — in-game |
| L15 | `:106` | Client-side fixes + bonds window, owner sends later — no starter work |
| e14 | `:124` | In-game Slums Exchange window — no starter work |

Found while reading this subsystem:

- `NEW-FINDING: src/launcher.rc:10-11,24,28` — the exe's `FILEVERSION`/`FileVersion`/`ProductVersion` are hardcoded
  `1.2.0.0` while `VERSION` is `1.6.4-rc3`; unlike `slums.dll`, whose resource is generated from `VERSION`
  (`src/slums.rc.in:1-6`, `src/CMakeLists.txt:32-40`), nothing regenerates the starter's. Explorer, the Tauri launcher
  and any antivirus reputation lookup see a permanently "1.2.0.0" exe.
- `NEW-FINDING: src/launcher.cpp:14` — `lpCmdLine` (which `WinMain` gives **without** `argv[0]`) is passed verbatim as
  the child's command line, so `MapleStory.exe` receives `"<host> <port>"` where a normal process has its own path as
  `argv[0]`; the DLL still parses correctly because `ProcessCommandLine` `sscanf`s the whole line
  (`src/injector.cpp:23`), but the game's own CRT args are shifted by one and v83 command-line switches (if ever
  passed through the starter) would be misread.
- `NEW-FINDING: src/launcher.cpp:35` — the exit code is discarded except under `MAPLEROOT_INTEGRATION`
  (`:30-32`). `docs/research-121-crash-report.md:16-19` proposes the starter as the only crash backstop for fast-fail
  /`/GS`/stack-overflow exits; today it cannot do that and its log line is compiled out of a non-integration build.
- `NEW-FINDING: src/debug.cpp:16-18` — `integration.log` is opened relative to the CWD, appended forever, with no size
  cap and no timestamp, so a long session on a wrong CWD or an unwritable folder silently loses every log line
  (`fopen_s` failure is ignored).
- `NEW-FINDING: src/launcher.cpp:14-20` — Detours terminates a half-created child (`external/Detours/src/creatwth.cpp:1581-1584`)
  and the starter then reports `Could not start MapleStory.exe`; nothing distinguishes "exe not found" from
  "injection failed", which are different operator fixes.
- `NEW-FINDING: src/launcher.manifest:8` — elevation is unconditional, though the documented need was Kaentake's
  in-folder file writes (`docs/KAENTAKE-UPSTREAM.md:3`); the QA harness therefore cannot start the client at all
  without an elevated owner terminal (`SlumsMS-164-archreview:tools/ops/client-mcp-win/src/windows.py:314-315`), and
  the Tauri launcher cannot read the client's path from an unelevated process
  (`/root/work/launcher/tauri/core/src/game_config.rs:151-152`).
- `NEW-FINDING: SlumsMS-164-archreview:tools/release/client-allow.txt:121` — `Start SlumsMS.cmd` is an allow-listed
  published file, but no source for it exists in the client, server or launcher repos; the shipped copy cannot be
  regenerated from this tree.

**Environment caveat — `-2147467259`.** `0x80004005` (`E_FAIL`). The starter is not the cause: on Silvia the main
display is a Parsec virtual display and the client fails at graphics start-up before login. Recorded as
"The `-2147467259` startup error on Silvia = Parsec main display, not your bug"
(`AGENTS.md:183-184` in `SlumsMS-164-archreview`, client `AGENTS.md:16-17`; workaround — disconnect Parsec, launch,
reconnect — `/root/work/client/src-164-data/README.md:148-150`, cause unconfirmed). Which D3D9 call fails is unknown
(`src/d3d9host_policy.h:7-12`); the host logs adapters and every failing call, and `d3d9host_adapter_fallback`
asserts the code maps to `E_FAIL` (`tests/d3d9host_policy_test.cpp:36`). The QA driver refuses this display by
default (`SlumsMS-164-archreview:tools/ops/client-mcp-win/src/driver.py:298-303`). Note the starter would report this
as a plain exit code 1 (`src/launcher.cpp:35`) with the game's own error dialog in between.

## State

| Aspect | Verdict | Evidence |
|---|---|---|
| Create + inject + wait + exit mapping | **working** | 35 lines, no conditionals; recorded clean run `docs/evidence/cashshop-window-persistence-exit-20261018.json:23-24`; shipped in every release |
| config.ini contract (host/port, precedence) | **working** | `src/injector.cpp:17-42`, `src/system.cpp:235-274`; read-only from the client side |
| ijl15 handling (stock DLL, no replacement) | **working** | `src/CMakeLists.txt:123-130`, `mapleroot/CMakeLists.txt:1-3`, 352,256-byte stock DLL in `vendor/ijl15/` |
| Starter version resource | **partial** (broken metadata) | hardcoded `1.2.0.0`, `src/launcher.rc:10-11,24,28`; see NEW-FINDING |
| Crash backstop over the exit code | **partial** (not implemented) | `docs/research-121-crash-report.md:16-19,229-231`; `src/launcher.cpp:35` |
| Test coverage of the starter | **broken** (none) | 47 `add_test`s, none mentions the starter; no launch smoke check in `scripts/build.ps1` |
| Shipped exe matches HEAD | **unknown** | `stage-client:14` keeps the published bytes; `docs/research-121-crash-report.md:18-19` calls starter builds non-reproducible |

Overall: **working** for the launch path every player uses; **partial** for the metadata and diagnostic roles the
surrounding docs assume.

## Open questions

1. **Which call returns `E_FAIL` under Parsec** — `src/d3d9host_policy.h:9-10` states it is unknown, and no failing
   `integration.log` is committed; until captured, the caveat stays environmental.
2. **Does the published `SlumsMS.exe` correspond to this source?** Release staging copies only the DLL/WZ
   (`SlumsMS-164-archreview:tools/release/stage-client:11-14`), and `docs/research-121-crash-report.md:18-19` calls
   the exe non-reproducible. Nothing in this repo pins a hash of the shipped exe (compare `release/baseline.json`,
   `/root/work/client/src-164-data/README.md:143`).
3. **Is `Start SlumsMS.cmd` still published, and from where?** Allow-listed at `tools/release/client-allow.txt:121`,
   no source in any repo.
4. **Would `MapleStory.exe` tolerate the argv shift** described above if a launcher ever forwards switches
   (e.g. `-window`)? Not exercised anywhere; the stock client's command-line parsing is not in this tree.
5. **Can the launcher's `config.ini` write race a start?** The launcher refuses while a game-named process exists
   (`/root/work/launcher/tauri/core/src/game_config.rs:103-105,147-149`) and writes via temp+rename
   (`:110-118`), but the starter reads the file with no lock — the window between check and launch is not closed on
   either side.
