# Play and launch

## Purpose

The Play button re-reads the patch feed, decides whether this folder is the release the server offers, installs or
fast-verifies if not, writes the game's `config.ini` in the folder about to run, then starts the client executable with
that folder as its working directory so the client and its DLL find their relative files. The launcher owns the *start*
only — no handle, no exit code, no restart.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| Play button | `#play` click → `primaryAction().run()` | tauri/ui/index.html:83; tauri/ui/renderer.mjs:574 |
| `primaryAction` → `perform('play', …)` | phase `ready` + `canPlay`, or `update-available` | tauri/ui/renderer.mjs:298-311 |
| `bridge.play()` | `window.launcher.play` → Tauri invoke `play` | tauri/ui/bridge.js:16 |
| `#[tauri::command] play` | invoked from the page | tauri/app/src/launcher.rs:1319-1324 |
| `Launcher::play_flow` | the command's body | tauri/app/src/launcher.rs:695 |
| `slums_core::play::launch` | blocking task inside `play_flow` | tauri/core/src/play.rs:76 |
| self-test step `play` | `--self-test-steps=…,play,…` | tauri/app/src/launcher.rs:1006-1013 |
| background timer | re-checks the feed every 10 min, skipped while busy | tauri/app/src/launcher.rs:32, :396-407 |
| `--self-updated` / `--update-parent` | deferred single-instance registration | tauri/app/src/launcher.rs:1156-1157; tauri/app/src/single_instance.rs:47 |

## How it works

1. **Click.** `#play` stays `disabled` until the state says `canPlay` (tauri/ui/index.html:83). The click runs
   `primaryAction()`, returning `perform('play', () => bridge.play())` when `state.phase === 'ready' && state.canPlay`
   (tauri/ui/renderer.mjs:305); the `update-available` variant labelled "Update" makes the same call (:303). `perform`
   refuses when the bridge is missing, the page is uninitialized, an operation is running, or the phase is one of
   `busyPhases = {checking, updating, self-updating, launching}` (:35, :468-470).
2. **Busy gate.** `bridge.play()` invokes the command (tauri/ui/bridge.js:9). `play` takes the exclusive `busy` flag
   through `begin("checking")`, which returns `A launcher operation is already running.` on a concurrent call
   (tauri/app/src/launcher.rs:569-576, :1319-1324); `end` clears it and emits `phase: "error"` on failure (:578-590).
3. **Settings.** `require_settings` demands a feed URL and an install folder (:620); the access key is resolved at :698.
4. **Fresh look at the server.** `check_available_release(feed_url, Some(&access), AVAILABLE_TIMEOUT)` with a 15 s
   timeout (tauri/core/src/release.rs:253), then `read_installed_record` of `.slumsms-launcher/installed.json`
   (tauri/app/src/launcher.rs:699-700; written by `record_installed`, tauri/core/src/release.rs:407-417).
5. **Plan.** `play_plan(offered, record)` (tauri/core/src/play.rs:17-24): no manifest hash from the server → `Offline`;
   recorded `manifestSha256` equals the offered one → `Verify`; otherwise `Update`. A republish under the same version
   is a new hash, so it still plans `Update`.
6. **Offline branch.** Start whatever is installed: this session's `last_verified` launch config, else the record's
   `launch`, else `Launch { executable: clientExecutable || "SlumsMS.exe", args: [] }`
   (tauri/app/src/launcher.rs:705-709). A missing executable refuses with "Couldn't check for updates (…) and the game
   is not installed in this folder yet." (:710-712); the UI says "Couldn't check for updates. Started the installed
   version." (:745-746).
7. **Update branch.** `install_files(Mode::Fast, Some("Updating to <v> before starting the game…"))` runs the normal
   installer and returns the fresh release; it ends by marking the release verified and re-writing game options (:719,
   :658-681).
8. **Verify branch.** `recently_verified` reuses this session's last full check when folder and feed match, it is
   younger than `VERIFIED_REUSE_MS = 30 min`, the executable is still present and the server offers the same manifest
   hash (:685-689; tauri/core/src/play.rs:34-48). Otherwise `check_release(Mode::Fast)` walks the folder by size+mtime
   and, if anything is pending, `install_files(…, "Repairing changed files before starting the game…")` (:722-731).
9. **Write `config.ini` first.** `write_options_explicitly(&install_dir)` runs before the process starts, holding
   `options_lock`, so pending options land in the folder the game will read — comment: "The folder the game starts in is
   the folder whose config.ini it reads: write that one." (:737-738, :896-915). It is a no-op while the game runs or
   `config.ini` is missing (tauri/core/src/game_config.rs:147-151).
10. **Tell the UI.** `{"phase": "launching", "message": "Starting the client. Accept its Windows prompt if shown.",
    "canPlay": false}` (:739); the button becomes "Launching…" (tauri/ui/renderer.mjs:376-377). `last_verified` is
    cleared (:740) so the next Play does a full check.
11. **Start the process.** `tokio::task::spawn_blocking(|| slums_core::play::launch(&dir, &launch.executable,
    &launch.args))` (:742). `launch` (tauri/core/src/play.rs:76-120) joins `install_dir` with the executable (`/`
    normalised to the platform separator, :77) and on Windows calls `ShellExecuteExW` with `lpVerb="open"`,
    `lpFile=<install_dir>/<exe>`, `lpParameters` = each argument quoted by `windows_argument` and joined with one space
    (:85), `lpDirectory = install_dir` (:96), `nShow = SW_SHOWNORMAL`, `fMask = SEE_MASK_NOASYNC` (:92, :98).
    `ShellExecuteEx` is used so the client's own UAC manifest is honoured (:74-75). Error 1223 (prompt declined) becomes
    "The game's Windows prompt was declined, so it did not start." (:100-102); anything else "Could not start the game:
    {error}". On unix it instead spawns `Command` with `.current_dir(install_dir)` and all three stdio null (:107-119),
    development only.
12. **After the start.** The launcher sends `{"phase": "ready", "canPlay": true, "offlinePlay": …, "message": "Client
    process started."}` (:745-746). `launch` returns `Ok(())` as soon as the shell accepted the request and drops every
    handle: nothing polls the child, reads its exit code, or restarts it.
13. **The client side of the same command line.** The starter `SlumsMS.exe` receives the launcher's arguments as
    `lpCmdLine` and forwards them verbatim to `DetourCreateProcessWithDllExA("MapleStory.exe", lpCmdLine, …)` with
    `lpCurrentDirectory = NULL` and `CREATE_SUSPENDED`, injecting `slums.dll` (client `src/launcher.cpp:14`); it resumes
    the thread (:22), waits forever (:23) and, under `MAPLEROOT_INTEGRATION`, logs `SESSION_PROCESS_EXIT pid=… code=…`
    (:31). `slums.dll` reads the same command line as `"<host> <port>"` when `CONSTANTS_USE_COMMAND_LINE` is TRUE
    (client `src/injector.cpp:17-27`, constant `src/constants.h:10`), else `.\config.ini` `[config] host`/`port`
    (`src/injector.cpp:29-43`).
14. **What the launcher actually passes.** Only the manifest's `launch` object: `executable` plus an `args` array
    (tauri/core/src/manifest.rs:136-137, validated as strings without NUL at :254-265, stored in `installed.json`).
    Published releases carry `args: []` — the publisher defaults to none and `--arg` is opt-in (tools/publish.mjs:86,
    :357, :547-561; fixture tauri/core/tests/feed.rs:201). No environment variable is set (no `env(`/`var(` in `play.rs`
    or `launcher.rs`), no resolution flag, no window flag, no build number.

## Data

| Item | Where | Written by |
|---|---|---|
| `launch { executable, args }`, `manifestSha256`, `verifiedAt`, `layers` | `.slumsms-launcher/installed.json` | `write_installed_record`, tauri/core/src/release.rs:407-417 |
| fast-verify cache (size+mtime) | `.slumsms-launcher/state.json` | installer, tauri/core/src/release.rs:17-18 |
| `slums-build.txt` (display only) | game folder beside `slums.dll` | read at tauri/core/src/client_build.rs:72-81 |
| game options | `<install_dir>/config.ini` | tauri/core/src/game_config.rs:142-166 |
| launcher settings | `%APPDATA%\com.slumsms.launcher\settings.json` | tauri/app/src/launcher.rs:178; tauri/README.md:125 |
| launcher log | `launcher.log`, rotated at 1 MiB | tauri/app/src/launcher.rs:179, :264-278 |
| crash note | `slumsms-launcher-crash.log`, data dir or temp | panic hook, tauri/app/src/main.rs:21-24 |

In-memory only: `busy`, `last_verified: Option<(RecentVerify, Arc<Release>)>`, `PendingGameOptions`. None survives a
restart; only `installed.json` and `state.json` do.

## Switches

- No `-D` surface. Start-up flags, none carrying a key: `--version`, `--self-test=`, `--self-test-steps=`,
  `--self-test-install`, `--self-updated=`, `--update-parent=`, `--self-update-failed=`, `--handover-from=`,
  `--no-self-update` (or env `SLUMSMS_NO_SELF_UPDATE=1`), `--feed-url=`, `--install-dir=`, `--data-dir=`,
  `--legacy-dir=`, `--site-url=` (loopback HTTP only) — tauri/app/src/launcher.rs:37-75.
- Build-time defaults in `tauri/launcher.config.json` (`feedUrl`, `clientExecutable: "SlumsMS.exe"`,
  `downloadConcurrency: 12`, `accessKey: ""`), embedded via `include_str!` and overridable by a file beside the exe
  (tauri/app/src/launcher.rs:30, :187-194).
- Play behaviour switches: manifest `launch.args` / `launch.executable`, manifest `minClientBuild` (display only), the
  30-minute `VERIFIED_REUSE_MS` constant, the 15 s `AVAILABLE_TIMEOUT`.
- Server side for comparison: `MIN_CLIENT_BUILD=10740` (docs/release/1.6.4.conf:284) — the same file's header says
  "No new client: MIN_CLIENT_BUILD stays 10721" (docs/release/1.6.4.conf:10), so the conf contradicts itself; the live
  `-D` set it is rendered into is docs/release/1.6.4.conf:271. The launcher reads none of it. TEST: unknown
  (start-test.sh was not readable for this review).

## Invariants

1. **The working directory is the installed game folder.** `lpDirectory = install_dir` (tauri/core/src/play.rs:96),
   `.current_dir(install_dir)` on unix (:112), and `config.ini` is written to that same folder in the same flow
   (tauri/app/src/launcher.rs:737-738); reason at tauri/core/src/game_config.rs:55-59 — every client reader opens a
   relative path.
2. **One launcher operation at a time.** `busy.compare_exchange` (tauri/app/src/launcher.rs:570); the UI guards too
   (tauri/ui/renderer.mjs:469); closing mid-operation asks first (`confirm_close`, tauri/app/src/launcher.rs:959).
3. **One launcher window per user.** The Tauri single-instance plugin forwards a second launch to the running window
   (`unminimize/show/set_focus`, tauri/app/src/single_instance.rs:4-12), installed only for a normal start
   (tauri/app/src/main.rs:46-47); a self-updated child registers later, after reporting healthy
   (tauri/app/src/launcher.rs:1156-1157; tauri/core/src/instance.rs:9-19, 120 s budget). This locks the *launcher*, not
   the game.
4. **No writes while the game runs.** `write_game_config` refuses with `GAME_RUNNING` when a process named `SlumsMS.exe`
   or `MapleStory.exe` exists (tauri/core/src/game_config.rs:15, :147-148); the launcher keeps the change as
   `PendingGameOptions` and reports `{"saved":"pending"}` (tauri/app/src/launcher.rs:917-931). Detection is by process
   *name* because the elevated client's path cannot be read (:195-199).
5. **`config.ini` writes are atomic.** Temp file beside the target, `sync_all`, then `rename` (:154-161).
6. **Player-owned files survive installs.** `config.ini` is the default `playerOwned` entry (tools/publish.mjs:14, :357;
   consumed at tauri/core/src/install.rs:87).
7. **Play never blocks on the build number.** `slums-build.txt` is display-only and a missing or malformed file is
   "unknown" (tauri/core/src/client_build.rs:11, :72-81); `minClientBuild` is "Only a display hint (the game server
   enforces it at login)" (tauri/core/src/manifest.rs:347).
8. **Deliberately not an invariant: how many game processes exist.** Nothing counts or caps them (tauri/app/src/launcher.rs:742
   keeps no handle). Multi-client is a client property: `slums.dll` rewrites the mutex name to `WvsClientMtx-<pid>`
   (client `src/system.cpp:44`).

**What the launcher does not control, and therefore cannot fix.**

- **The client build number.** Compiled into `slums.dll` (`SLUMS_BUILD_NUMBER`/`SLUMS_RELEASE`, client
  `src/buildstamp_proto.h:17-27`, from `cmake/BuildStamp.cmake`) and sent client→server in CP `0x3738` as format 2,
  build, release, caps (`src/buildstamp_proto.h:2-15, :42`). The launcher only displays it — `slums-build.txt` →
  `clientBuild` (tauri/app/src/launcher.rs:525-527) plus a note when below `minClientBuild`
  (tauri/ui/version-status.mjs:55-59). Refusal is server-side
  (`src/main/java/net/server/handlers/login/ClientVersionGate.java:110`); see `../client/build-stamp-and-version.md`.
- **Resolution and window mode.** No flag on either side: resolution is the client's stored `soScreenResolution` option
  (client `src/resolution.cpp:179, :184`, sizes 0..4); `borderless`/`dpiaware` are `config.ini` keys the launcher writes
  (tauri/core/src/game_options.rs:69-92), not start-up arguments.
- **Elevation.** `ShellExecuteEx` triggers the client's own UAC manifest; the launcher only reports a declined prompt
  (tauri/core/src/play.rs:100-102).
- **Whether the client can render at all.** On a virtual primary display (Parsec, Sunshine, Steam Link, Virtual
  Desktop) the client dies at startup with `error code -2147467259 (Unspecified error)`, because the shipped `d3d8.dll`
  (d3d8to9) creates its device on the primary display (/root/work/164-all/AGENTS.md:183-186;
  /root/work/164-all/docs/tickets/39-client-dgvoodoo-wrapper.md:8-12, closed and folded into ticket 90 item 2). Working
  sequence: disconnect Parsec, launch, reconnect (/root/work/164-all/docs/live-handoff-20261019.md:75-83). The launcher
  contains no display code at all.
- **The server address.** It comes from `config.ini` `[config] host`/`port`, which the launcher reads for display
  (`read_game_server`, tauri/core/src/game_config.rs:91) and never rewrites, because `config.ini` is player-owned.
- **WebView2.** Without it the launcher never reaches Play: download prompt, or `{"ok":false,"error":"WebView2 runtime
  missing"}` (tauri/app/src/main.rs:36-44; tauri/app/src/platform.rs:17-64).

## Origin

**Custom** throughout — the launcher is not in the repack baseline, so there is no upstream to diff. Oldest commits
(launcher repo, `git log --follow --format=%h <path> | tail -1`):

| Path | Oldest commit | Note |
|---|---|---|
| `tauri/core/src/play.rs` | `bd3df88` (2026-10-21) | written with the Tauri launcher |
| `tauri/core/src/instance.rs`, `tauri/app/src/single_instance.rs` | `5c91c86` (2026-10-21) | deferred-registration fix |
| `tauri/app/src/launcher.rs`, `main.rs`, `tauri/ui/bridge.js` | `bd3df88` | initial Tauri shell |
| `tauri/ui/renderer.mjs` | `fcd6e08` (2026-10-19) | ported from the Electron UI |
| `tauri/ui/runner.mjs` | `750b491` (2026-11-04) | cosmetic progress-bar sprite, not launch code |
| `tauri/core/src/client_build.rs` | `76a0648` (2026-10-25) | `slums-build.txt` + `minClientBuild` |

Client side of the same flow: `src/launcher.cpp` oldest `7d36862` (2025-08-27, base starter, modified since),
`src/system.cpp` oldest `6003540` (2025-08-27, base), `src/buildstamp_proto.h` oldest `66026a3` (2026-10-21, custom),
`src/buildshow.cpp` oldest `9a1ebea` (2026-10-25, custom). The Electron launcher in `src/` is no longer shipped
(ticket 67, closed 2026-10-25).

## Tests and QA

Rust unit tests — no integration test touches Play or single-instance:

- `tauri/core/src/play.rs:121-170` — `play_updates_first_when_the_server_offers_another_release` (Update/Verify matrix
  including an empty recorded hash), `play_starts_the_installed_client_when_the_server_cannot_be_asked`
  (offline/access-key/error), `reuse_only_for_the_same_folder_server_and_manifest` (folder, feed, age, executable, hash,
  offline, none), `windows_arguments_are_quoted_like_the_electron_launcher` (`windows_argument("C:\\dir\\")` →
  `"C:\dir\\"`).
- `tauri/core/src/instance.rs:42-83` — `updated_child_reacquires_ownership_and_later_launches_are_secondary`,
  `a_stuck_parent_never_leaves_an_unregistered_full_instance`.
- `tauri/app/src/single_instance.rs:78-131` — Windows-only native mutex test.
- `tauri/core/src/game_config.rs` — `GAME_RUNNING` refusal (:323), no stray files after an atomic write (:338-368),
  process-name matching (:421-426).

JS (`npm test` = `node --test`): `test/play-gate.test.mjs:3` imports `playPlan` from `../src/version-status.mjs` (the
Electron copy), not `tauri/ui/version-status.mjs`; `test/packs.test.mjs:421-428` covers `reuseVerified`;
`test/tauri-versions.test.mjs:3` covers `clientVersion`/`clientUpdateNote`; `test/tauri-ui.test.mjs` pins strings in
`tauri/ui/index.html`.

Server-side QA: the only launcher probe is `Check.manual("t-la-1", "The Tauri launcher's own checklist", "a desktop app
with its own page")` (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:783), confirmed manual at
src/main/java/soloMapling/autoqa/ClientScriptVerdicts.java:17. No `!qa` scenario in `src/main/java/soloMapling/qa/`
covers the launcher.

Not covered: nothing drives `play_flow` to `launch` (the `tauri/core/tests` set — `feed.rs`, `selfupdate.rs`, `site.rs`,
`test_site_paths.rs` — never calls it); the multi-argument `lpParameters` join is untested (only single-argument
quoting); `ShellExecuteExW` failure paths, the UAC-declined branch and exit-code behaviour are untestable here;
single-instance is tested on Windows only; `primaryAction` has no unit test (only string checks). Nothing on this host
runs Windows.

## Known issues

- **b017 (S3, Launcher)** — Windows Defender can flag a new launcher exe (Phonzy false positive); process, not code:
  the owner submits each exe to Microsoft (/root/work/STATUS-1.6.4.md:70).
- **b270 (S3, Client)** — launcher news: a Live client publish wrote `manifest.json` without `news[]`, so
  `slumsms-news@live` failed (/root/work/STATUS-1.6.4.md:75). Publishing, not Play.
- **o04 (Ops, later)** — move client and launcher builds from Silvia to tarkov2 (/root/work/STATUS-1.6.4.md:130),
  blocked on the D3D9/Parsec problem above.
- Tickets 106 (per-account download pass) and 20 (packed first install) are open but do not touch this flow; 67
  (Electron launcher retired) closed 2026-10-25.
- **NEW-FINDING:** `/root/work/launcher-165-server/README.md:59` says Play "does not rewrite client INIs", but `play_flow` writes `config.ini` before
  every launch (tauri/app/src/launcher.rs:738) and `/root/work/launcher-165-server/README.md:268` describes exactly that deferred write.
- **NEW-FINDING:** the launcher cannot see the game end, so a crashed client looks identical to a running one —
  `launch` returns as soon as the shell accepts the request (tauri/core/src/play.rs:98-104) and the starter collapses
  every non-zero exit code to 1, read by nobody (client `src/launcher.cpp:35`; same point logged in
  `docs/architecture/HOLES.md` under `exe-and-starter.md`). The UI keeps `phase: ready` either way.
- **NEW-FINDING:** `test/play-gate.test.mjs:3` tests the Electron copy `src/version-status.mjs`, while
  `tauri/ui/version-status.mjs:145` is a hand-kept twin of `tauri/core/src/play.rs:17`; nothing fails when they drift,
  and the Rust side is what ships.
- **NEW-FINDING:** nothing in the launcher caps game instances and no release doc mentions multi-client, yet
  `WvsClientMtx-<pid>` (client `src/system.cpp:44`) makes unlimited clients possible. Intent is written down nowhere.

## State

**Working** for the desktop path above, with the caveat that this review host cannot execute it. Evidence: the flow is
complete and single-purpose in code (`play_flow` → write options → `launch`), plan/reuse and Windows quoting are
unit-tested (tauri/core/src/play.rs:121-170), the `GAME_RUNNING` guard is tested (tauri/core/src/game_config.rs:323),
single-instance ownership has Rust and native Windows tests (tauri/core/src/instance.rs:42-83;
tauri/app/src/single_instance.rs:78-131), and the Tauri launcher has been live since 2026-10-24 (ticket 67). **Partial**
on everything after the start: exit detection, crash handling and instance accounting do not exist, and nothing
automated covers `launch()` itself.

## Open questions

- Is `/root/work/launcher-165-server/README.md:59`'s "does not rewrite client INIs" stale, or does it describe a policy dropped without updating the
  README? The code and `/root/work/launcher-165-server/README.md:268` both contradict it.
- `docs/release/1.6.4.conf` says `MIN_CLIENT_BUILD=10740` at :284 and "stays 10721" at :10 — which was deployed, and
  does the difference explain a field report either way?
- Should a crashed client surface anything in the launcher (`phase: error`, a "the game closed" line, a log bundle)?
  The starter already logs `SESSION_PROCESS_EXIT … code=…` under `MAPLEROOT_INTEGRATION` (client `src/launcher.cpp:30-31`)
  and nothing reads it.
- Multi-client: is `WvsClientMtx-<pid>` (client `src/system.cpp:44`) meant for players or only the owner's alt farm?
- `../client/exe-and-starter.md:41` notes the game inherits the starter's working directory because
  `lpCurrentDirectory = NULL`; the launcher makes that directory the game folder. A player double-clicking
  `SlumsMS.exe` in Explorer inherits the exe's folder instead — is that guarded anywhere? No launcher-side guard exists.
