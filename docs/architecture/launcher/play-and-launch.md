# Play and launch

## Purpose

What the Play button does: re-read the patch feed, decide whether this folder is the release the server is offering,
install or fast-verify if not, write the game's `config.ini` in the folder that is about to run, and then start the
client executable with that folder as the working directory so the client and its DLL find their relative files.
The launcher owns the *start*; it does not own the game process afterwards — no handle, no exit code, no restart.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| Play button | `#play` click → `primaryAction().run()` | tauri/ui/index.html:83; tauri/ui/renderer.mjs:574 |
| `primaryAction` → `perform('play', …)` | phase `ready` and `canPlay` (or `update-available`) | tauri/ui/renderer.mjs:298-311 |
| `bridge.play()` | `window.launcher.play` → Tauri invoke `play` | tauri/ui/bridge.js:16 |
| `#[tauri::command] play` | invoked from the page | tauri/app/src/launcher.rs:1319-1324 |
| `Launcher::play_flow` | the command's body | tauri/app/src/launcher.rs:695 |
| `slums_core::play::launch` | blocking task inside `play_flow` | tauri/core/src/play.rs:76 |
| self-test step `play` | `--self-test-steps=…,play,…` | tauri/app/src/launcher.rs:1006-1013 |
| background timer | re-checks the feed every 10 min, skipped while busy | tauri/app/src/launcher.rs:32, :396-407 |
| `--self-updated` / `--update-parent` | deferred single-instance registration | tauri/app/src/launcher.rs:1156-1157; tauri/app/src/single_instance.rs:47 |

## How it works

1. **Click.** `#play` is `disabled` until the state says `canPlay` (tauri/ui/index.html:83). The click runs
   `primaryAction()`, which returns `perform('play', () => bridge.play())` when `state.phase === 'ready' && state.canPlay`
   (tauri/ui/renderer.mjs:305); the same call is used for the `update-available` variant labelled "Update"
   (tauri/ui/renderer.mjs:303). `perform` refuses to start when a bridge is missing, the page is not initialized, an
   operation is already running, or the phase is in `busyPhases = {checking, updating, self-updating, launching}`
   (tauri/ui/renderer.mjs:35, :468-470).
2. **Busy gate.** `bridge.play()` invokes the Tauri command (tauri/ui/bridge.js:9). `launcher::play` takes the exclusive
   `busy` flag via `begin("checking")`, which returns `A launcher operation is already running.` on a second concurrent
   call (tauri/app/src/launcher.rs:569-576, command at :1319-1324). `end` clears it and, on error, emits
   `phase: "error"` (tauri/app/src/launcher.rs:578-590).
3. **Settings.** `play_flow` requires a feed URL and an install folder (`require_settings`, tauri/app/src/launcher.rs:620)
   and sends `"Checking for updates…"` (tauri/app/src/launcher.rs:697). The access key is resolved next (:698).
4. **Fresh look at the server.** `check_available_release(feed_url, Some(&access), AVAILABLE_TIMEOUT)` — 15 s timeout
   (tauri/core/src/release.rs:253) — and `read_installed_record` of `.slumsms-launcher/installed.json`
   (tauri/app/src/launcher.rs:699-700; the file is written by `record_installed`, tauri/core/src/release.rs:407-417).
5. **Plan.** `play_plan(offered, record)` (tauri/core/src/play.rs:17-24): no manifest hash from the server → `Offline`;
   the folder's recorded `manifestSha256` equals the offered one → `Verify`; otherwise `Update`. A republish under the
   same version number is a new hash, so it still plans `Update`.
6. **Offline branch.** The launcher starts whatever is installed: this session's `last_verified` launch config, else
   the record's `launch`, else a fallback `Launch { executable: clientExecutable || "SlumsMS.exe", args: [] }`
   (tauri/app/src/launcher.rs:705-709). If that executable is not in the folder it refuses with "Couldn't check for
   updates (…) and the game is not installed in this folder yet." (:710-712) and the UI says the offline line (:745-746).
7. **Update branch.** `install_files(Mode::Fast, Some("Updating to <v> before starting the game…"))` runs the normal
   installer and returns the fresh release; `install_files` ends by marking the release verified and re-writing game
   options (tauri/app/src/launcher.rs:719, :658-681).
8. **Verify branch.** `recently_verified` reuses this session's last full check when it is the same folder and feed,
   younger than `VERIFIED_REUSE_MS = 30 min`, the executable is still present, and the server still offers the same
   manifest hash (tauri/app/src/launcher.rs:685-689; tauri/core/src/play.rs:34-48). Otherwise `check_release(Mode::Fast)`
   walks the folder by size+mtime and, if anything is pending, `install_files(…, "Repairing changed files before
   starting the game…")` (tauri/app/src/launcher.rs:722-731).
9. **Write `config.ini` first.** `write_options_explicitly(&install_dir)` runs before the process starts, under the
   `options_lock`, so the pending option values land in the same folder the game will read: the comment says exactly
   that — "The folder the game starts in is the folder whose config.ini it reads: write that one."
   (tauri/app/src/launcher.rs:737-738, :896-915). It is a no-op while the game is running or `config.ini` is missing
   (tauri/core/src/game_config.rs:147-151).
10. **Tell the UI it is launching.** `{"phase": "launching", "message": "Starting the client. Accept its Windows prompt
    if shown.", "canPlay": false}` (tauri/app/src/launcher.rs:739); the button label becomes "Launching…"
    (tauri/ui/renderer.mjs:376-377). `last_verified` is cleared (:740) so the next Play does a full check.
11. **Start the process.** `tokio::task::spawn_blocking(|| slums_core::play::launch(&dir, &launch.executable,
    &launch.args))` (tauri/app/src/launcher.rs:742). `launch` (tauri/core/src/play.rs:76-120) joins `install_dir` with
    the executable (`/` normalised to the platform separator, :77) and on Windows calls `ShellExecuteExW` with
    `lpVerb="open"`, `lpFile=<install_dir>/<exe>`, `lpParameters` built by quoting each argument with
    `windows_argument` and joining with one space (:85), `lpDirectory = install_dir` (:96), `nShow = SW_SHOWNORMAL`,
    `fMask = SEE_MASK_NOASYNC` (:92, :98). `ShellExecuteEx` is used so the client's own UAC manifest is honoured
    (doc comment :74-75). Error 1223 (the user declined the elevation prompt) becomes "The game's Windows prompt was
    declined, so it did not start." (:100-102); anything else becomes "Could not start the game: {error}". On unix the
    function instead spawns `Command` with `.current_dir(install_dir)` and all three stdio null (:107-119) — development
    only.
12. **After the start.** The launcher sends `{"phase": "ready", "canPlay": true, "offlinePlay": …, "message": "Client
    process started."}` (tauri/app/src/launcher.rs:745-746). That is the whole "running" model: `launch` returns
    `Ok(())` as soon as the shell accepted the request and drops every handle. Nothing polls the child, nothing reads
    its exit code, nothing restarts it.
13. **The client side of the same command line.** The starter `SlumsMS.exe` receives the launcher's arguments as
    `lpCmdLine` and forwards them verbatim to `DetourCreateProcessWithDllExA("MapleStory.exe", lpCmdLine, …)` with
    `lpCurrentDirectory = NULL` and `CREATE_SUSPENDED`, injecting `slums.dll` (client `src/launcher.cpp:14`); it resumes
    the thread (:22), waits forever (:23) and, if `MAPLEROOT_INTEGRATION` is on, logs
    `SESSION_PROCESS_EXIT pid=… code=…` (:31). `slums.dll` reads the same command line as `"<host> <port>"` when
    `CONSTANTS_USE_COMMAND_LINE` is TRUE (`src/injector.cpp:17-27`, constant at `src/constants.h:10`), and otherwise
    falls back to `.\config.ini` `[config] host`/`port` (`src/injector.cpp:29-43`).
14. **What the launcher actually passes.** Only the release manifest's `launch` object: `executable` plus an `args`
    array (tauri/core/src/manifest.rs:136-137, validated as an array of NUL-free strings at :254-265 and stored in
    `installed.json`). Published releases carry `args: []` — the publisher defaults to no arguments and `--arg` is
    opt-in (tools/publish.mjs:86, :357, :547-561; test fixtures at tauri/core/tests/feed.rs:201). No environment
    variable is set (no `env(` / `var(` in `play.rs` or `launcher.rs`), no resolution flag, no window flag, no build
    number.

## Data

| Item | Where | Written by |
|---|---|---|
| `launch { executable, args }` | manifest → `.slumsms-launcher/installed.json` | `record_installed` / `write_installed_record`, tauri/core/src/release.rs:407-417 |
| `manifestSha256`, `verifiedAt`, `layers` | `.slumsms-launcher/installed.json` | same |
| fast-verify cache (size+mtime) | `.slumsms-launcher/state.json` | installer, tauri/core/src/release.rs:17 |
| `slums-build.txt` (display only) | game folder, beside `slums.dll` | the client install; read at tauri/core/src/client_build.rs:72-81 |
| game options | `<install_dir>/config.ini` | `write_game_config`, tauri/core/src/game_config.rs:142-166 |
| launcher settings | `%APPDATA%\com.slumsms.launcher\settings.json` | tauri/app/src/launcher.rs:178; path documented tauri/README.md:125 |
| launcher log | `launcher.log`, rotated at 1 MiB | tauri/app/src/launcher.rs:179, :264-278 |
| crash note | `slumsms-launcher-crash.log` in the data dir (or temp) | panic hook, tauri/app/src/main.rs:21-24 |

In-memory state that matters to Play: `busy` (one operation at a time), `last_verified: Option<(RecentVerify, Arc<Release>)>`
and `pending` game options. None of it survives a restart; only `installed.json` and `state.json` do.

## Switches

- No `-D` surface. The launcher's own flags are start-up only and none carries a key: `--version`, `--self-test=`,
  `--self-test-steps=`, `--self-test-install`, `--self-updated=`, `--update-parent=`, `--self-update-failed=`,
  `--handover-from=`, `--no-self-update` (or env `SLUMSMS_NO_SELF_UPDATE=1`), `--feed-url=`, `--install-dir=`,
  `--data-dir=`, `--legacy-dir=`, `--site-url=` (loopback HTTP only) — tauri/app/src/launcher.rs:37-75.
- Build-time defaults live in `tauri/launcher.config.json` (`feedUrl`, `clientExecutable: "SlumsMS.exe"`,
  `downloadConcurrency: 12`, `accessKey: ""`), embedded with `include_str!` and overridable by a file beside the exe
  (tauri/app/src/launcher.rs:30, :188-192).
- Behaviour switches that affect Play: `launch.args` and `launch.executable` (manifest), `minClientBuild` (manifest,
  display only), the 30-minute `VERIFIED_REUSE_MS` constant, and the 15 s `AVAILABLE_TIMEOUT`.
- Live server-side gate for comparison: `MIN_CLIENT_BUILD=10740` (docs/release/1.6.4.conf:284) — note the same file's
  header comment says "No new client: MIN_CLIENT_BUILD stays 10721" (docs/release/1.6.4.conf:10), so the conf
  contradicts itself; the live `-D` set it is generated into is docs/release/1.6.4.conf:271. The launcher reads none of
  this. TEST: unknown (start-test.sh was not readable for this review).

## Invariants

1. **The working directory is the installed game folder.** `lpDirectory = install_dir` on Windows
   (tauri/core/src/play.rs:96), `.current_dir(install_dir)` on unix (:112), and `config.ini` is written to that same
   folder in the same flow (tauri/app/src/launcher.rs:737-738). Enforced by the pair; `game_config.rs:55-59` states the
   reason (every client reader opens a relative path).
2. **One launcher operation at a time.** `busy.compare_exchange` (tauri/app/src/launcher.rs:570); the UI also guards
   (tauri/ui/renderer.mjs:469). Closing the window mid-operation asks first (`confirm_close`, launcher.rs:959-990).
3. **One launcher window per user.** Tauri single-instance plugin forwards a second launch to the running window
   (`unminimize/show/set_focus`, tauri/app/src/single_instance.rs:4-12), installed only for a normal start
   (tauri/app/src/main.rs:46-47) — a self-updated child registers later, after reporting healthy
   (tauri/app/src/launcher.rs:1156-1157; tauri/core/src/instance.rs:9-19, 120 s budget).
4. **No writes while the game runs.** `write_game_config` refuses with `GAME_RUNNING` when a process named
   `SlumsMS.exe` or `MapleStory.exe` exists (tauri/core/src/game_config.rs:15, :147-148); the launcher then keeps the
   change as `PendingGameOptions` and reports `{"saved":"pending"}` (tauri/app/src/launcher.rs:917-931). Detection is by
   process *name* because the elevated client's path cannot be read (:195-199).
5. **`config.ini` writes are atomic.** Temp file beside the target, `fsync`, then `rename` (tauri/core/src/game_config.rs:154-161).
6. **Player-owned files are never overwritten by installs.** `config.ini` is in the default `playerOwned` list
   (tools/publish.mjs:357 `DEFAULT_PLAYER_OWNED`; consumed at tauri/core/src/install.rs:87).
7. **Play never blocks on the build number.** `slums-build.txt` is read display-only and a missing/garbage file is
   "unknown" (tauri/core/src/client_build.rs:11, :72-81); `minClientBuild` is a hint, `Only a display hint (the game
   server enforces it at login)` (tauri/core/src/manifest.rs:347).
8. **Deliberately not invariant: how many game processes exist.** Nothing in the launcher counts or caps them
   (tauri/app/src/launcher.rs:742 keeps no handle). Multi-client is a client property: `slums.dll` rewrites the
   `WvsClientMtx` mutex name to `WvsClientMtx-<pid>` (client `src/system.cpp:44`).

## Origin

The whole Play path is **custom** — there is no upstream to diff against in this repo (the launcher is not in the
repack baseline). Evidence (launcher repo, `git log --follow --format=%h <path> | tail -1`):

| Path | Oldest commit | Meaning |
|---|---|---|
| `tauri/core/src/play.rs` | `bd3df88` (2026-10-21) | written with the Tauri launcher, not ported byte-for-byte |
| `tauri/core/src/instance.rs`, `tauri/app/src/single_instance.rs` | `5c91c86` (2026-10-21) | the deferred-registration fix |
| `tauri/app/src/launcher.rs`, `main.rs`, `tauri/ui/bridge.js` | `bd3df88` | initial Tauri shell |
| `tauri/ui/renderer.mjs` | `fcd6e08` (2026-10-19) | the ported Electron UI |
| `tauri/ui/runner.mjs` | `750b491` (2026-11-04) | cosmetic sprite, added last |
| `tauri/core/src/client_build.rs` | `76a0648` (2026-10-25) | `slums-build.txt` + `minClientBuild` |

Client side, for the same flow: `src/launcher.cpp` oldest `7d36862` (2025-08-27, the repack's starter, modified since),
`src/system.cpp` oldest `6003540` (2025-08-27, base), `src/buildstamp_proto.h` oldest `66026a3` (2026-10-21, custom),
`src/buildshow.cpp` oldest `9a1ebea` (2026-10-25, custom). The launcher half of the repo is a rewrite of an Electron
launcher that still sits in `src/` and is no longer shipped (ticket 67, closed 2026-10-25).

## Tests and QA

Rust unit tests (no integration test touches Play or single-instance):

- `tauri/core/src/play.rs:121-170` — `play_updates_first_when_the_server_offers_another_release` (Update/Verify matrix
  incl. empty recorded hash), `play_starts_the_installed_client_when_the_server_cannot_be_asked` (offline / access-key /
  error), `reuse_only_for_the_same_folder_server_and_manifest` (folder, feed, age, executable, hash, offline),
  `windows_arguments_are_quoted_like_the_electron_launcher` (`windows_argument("C:\\dir\\")` → `"C:\dir\\"`).
- `tauri/core/src/instance.rs:42-83` — `updated_child_reacquires_ownership_and_later_launches_are_secondary`,
  `a_stuck_parent_never_leaves_an_unregistered_full_instance`.
- `tauri/app/src/single_instance.rs:78-130` — Windows-only native mutex test.
- `tauri/core/src/game_config.rs` — the `GAME_RUNNING` refusal (`:323`), atomic-rename/no-extra-files (`:338-368`),
  process-name matching (`:421-423`).

JS (`npm test` = `node --test`): `test/play-gate.test.mjs:3` imports `playPlan` from `../src/version-status.mjs` (the
*Electron* copy), not `tauri/ui/version-status.mjs`; `test/packs.test.mjs:421-428` covers `reuseVerified`;
`test/tauri-versions.test.mjs:3` covers `clientVersion`/`clientUpdateNote`; `test/tauri-ui.test.mjs` pins strings in
`tauri/ui/index.html`.

Server-side QA: the only launcher probe in `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:783` is
`Check.manual("t-la-1", "The Tauri launcher's own checklist", "a desktop app with its own page")`, and
`src/main/java/soloMapling/autoqa/ClientScriptVerdicts.java:17` confirms it is manual. There is no `!qa` scenario in
`src/main/java/soloMapling/qa/` for the launcher — the Play path cannot be exercised headlessly.

Not covered: nothing asserts that `play_flow` reaches `launch` end-to-end (no fake-feed integration test — the
`tauri/core/tests` directory has `feed.rs`, `selfupdate.rs`, `site.rs`, `test_site_paths.rs` and none of them call
`launch`); the `args` → `lpParameters` join has no test (only single-argument quoting); `ShellExecuteExW` failure paths,
the UAC-declined branch, and the exit-code behaviour are untestable here; the launcher's single-instance plugin is only
tested on Windows; `tauri/ui/renderer.mjs` `primaryAction` has no unit test, only the string checks in
`test/tauri-ui.test.mjs`. Nothing on this host runs Windows, so "working" below is a reading of code plus one recorded
log line.

## Known issues

- **b017 (S3, Launcher)** — Windows Defender can flag a new launcher exe (Phonzy false positive); process, not code
  (/root/work/STATUS-1.6.4.md:70). The owner submits each exe to Microsoft.
- **b270 (S3, Client)** — launcher news: a Live client publish wrote `manifest.json` without `news[]`, so
  `slumsms-news@live` failed (/root/work/STATUS-1.6.4.md:75). Manifest/publishing, not Play.
- **o04 (Ops, later)** — move client and launcher builds from Silvia to tarkov2 (/root/work/STATUS-1.6.4.md:130);
  blocked on the D3D9/Parsec problem above.
- Ticket 106 (per-account download pass) and ticket 20 (packed first install) are open but do not touch this flow;
  tickets 27 and 67 are closed (Electron launcher and repo-main move).
- **NEW-FINDING:** `README.md:59` says Play "does not rewrite client INIs", while `play_flow` writes `config.ini`
  before every launch (tauri/app/src/launcher.rs:738) and `README.md:268` describes exactly that deferred write. The
  sentence is stale and contradicts the code.
- **NEW-FINDING:** the launcher cannot see the game end, so a crashed client is indistinguishable from a running one:
  `launch` returns as soon as the shell accepts the request (tauri/core/src/play.rs:98-104) and the starter's exit code
  is collapsed to 0/1 and read by nobody (client `src/launcher.cpp:35`; `HOLES.md` already notes the same point in
  `exe-and-starter.md`). The UI keeps `phase: ready` either way.
- **NEW-FINDING:** `test/play-gate.test.mjs:3` tests the Electron copy `src/version-status.mjs`, and
  `tauri/ui/version-status.mjs:145` is a hand-kept twin of `tauri/core/src/play.rs:17`. Nothing fails when the two
  drift; the Rust side is what ships.
- **NEW-FINDING:** nothing in the launcher caps game instances and nothing in `docs/release/1.6.4.conf` mentions
  multi-client, yet `WvsClientMtx-<pid>` (client `src/system.cpp:44`) makes unlimited clients possible. Whether that is
  intended for players (vs. the owner's alt accounts) is not written down anywhere.

## What the launcher does not control

- **The client build number.** The launcher never passes it. `launch.args` comes only from the manifest
  (tauri/core/src/manifest.rs:136-137) and published releases use `args: []`. The number is compiled into `slums.dll`
  (`SLUMS_BUILD_NUMBER`/`SLUMS_RELEASE`, client `src/buildstamp_proto.h:17-27`, from `cmake/BuildStamp.cmake`) and sent
  client→server in CP `0x3738` with format 2, build, release and caps (`src/buildstamp_proto.h:2-15, :42`). The launcher
  only *displays* it: `slums-build.txt` → `clientBuild` (tauri/app/src/launcher.rs:525-527) and a note when it is below
  the manifest's `minClientBuild` (tauri/ui/version-status.mjs:55-59). Refusal happens server-side
  (`src/main/java/net/server/handlers/login/ClientVersionGate.java:110`); see
  `../client/build-stamp-and-version.md`.
- **Resolution and window mode.** No flag exists on either side: resolution is the client's stored
  `soScreenResolution` option (client `src/resolution.cpp:179, :184`, sizes 0..4) and `borderless`/`dpiaware` are
  `config.ini` keys the launcher *does* write (tauri/core/src/game_options.rs:69-92), not start-up arguments.
- **Elevation.** `ShellExecuteEx` triggers the client's own UAC manifest; the launcher only reports a declined prompt
  (tauri/core/src/play.rs:100-102).
- **Whether the client can render at all.** On a virtual primary display (Parsec, Sunshine, Steam Link, Virtual
  Desktop) the client dies at startup with `error code -2147467259 (Unspecified error)`, because the shipped
  `d3d8.dll` (d3d8to9) creates its device on the primary display (/root/work/164-all/AGENTS.md:183-186;
  /root/work/164-all/docs/tickets/39-client-dgvoodoo-wrapper.md:8-12, closed and folded into ticket 90 item 2). The
  working sequence is: disconnect Parsec, launch, reconnect (/root/work/164-all/docs/live-handoff-20261019.md:75-83).
  No launcher change can fix this; the launcher has no display code at all.
- **The server address.** It comes from `config.ini` `[config] host`/`port`, which the launcher *reads*
  (`read_game_server`, tauri/core/src/game_config.rs:91) and never rewrites — `config.ini` is player-owned.
- **WebView2.** If it is missing the launcher never reaches Play; it shows the download prompt or writes
  `{"ok":false,"error":"WebView2 runtime missing"}` (tauri/app/src/main.rs:36-44; tauri/app/src/platform.rs:17-64).

## State

**Working** for the desktop path described above, with the caveat that nothing on this review host executes it.
Evidence: the flow is complete and single-purpose in code (`play_flow` → write options → `launch`), the plan/reuse
logic and Windows quoting are unit-tested (tauri/core/src/play.rs:121-170), the `GAME_RUNNING` guard is tested
(tauri/core/src/game_config.rs:323), single-instance ownership has Rust and Windows tests
(tauri/core/src/instance.rs:42-83; tauri/app/src/single_instance.rs:78-130), and the shipped launcher is live for
players since 2026-10-24 (ticket 67). **Partial** on everything after the start: exit detection, crash handling and
instance accounting do not exist, and no automated test covers `launch()` itself.

## Open questions

- Is the `README.md:59` "does not rewrite client INIs" line simply stale, or does it describe a policy that was dropped
  without updating the README? The code and `README.md:268` both say otherwise.
- `docs/release/1.6.4.conf` says `MIN_CLIENT_BUILD=10740` at :284 but "stays 10721" at :10 — which one was deployed,
  and is that the reason a 1.6.3 client may still be accepted (or refused) in the field?
- Should a crashed client surface anything in the launcher (`phase: error`, a "the game closed" line, a log bundle)? The
  starter already logs `SESSION_PROCESS_EXIT … code=…` under `MAPLEROOT_INTEGRATION` (client `src/launcher.cpp:30-31`)
  but nothing reads it.
- Multi-client: is `WvsClientMtx-<pid>` (client `src/system.cpp:44`) meant for players, or only for the owner's alt
  farm? No card, ticket or doc states the intent.
- The finish of the doc `../client/exe-and-starter.md:41` says the game "inherits the starter's working directory"
  because `lpCurrentDirectory = NULL`; the launcher is what makes that directory the game folder. If a player ever
  starts `SlumsMS.exe` by double-click from Explorer, the inherited directory is the exe's folder — is that checked
  anywhere? No launcher-side guard exists.
