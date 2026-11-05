# Launcher UI (`tauri/ui`)

## Purpose

The page inside the launcher window: one HTML document with five plain ES modules and one classic script, no
framework, no bundler, no network of its own. It renders the state the Rust side pushes on `launcher:state` and it is
the only place settings, the sign-in dialog and the game-option switches are edited. Everything it can do arrives as a
named Tauri command; it holds no credentials, no tokens and no files.

## Entry points

- `tauri/ui/index.html:9-15` — one stylesheet, `bridge.js` as a classic script, then `renderer.mjs`, `mascot.mjs`,
  `account.mjs`, `stickers.mjs`, `runner.mjs` as modules. There are no other pages.
- `tauri/ui/bridge.js:14-19` — the command map (24 names) built at load; `/root/work/launcher-165-server/tauri/ui/bridge.js:21-28` — `onState()`.
- Event `launcher:state`, emitted at `tauri/app/src/launcher.rs:363`, subscribed at `tauri/ui/bridge.js:24`.
- Commands registered at `tauri/app/src/main.rs:64-89`; the entry points that start work are the buttons wired at
  `tauri/ui/renderer.mjs:506-575` (Play `:574`, settings `:563`, repair `:559`, feed save `:511-527`, access key
  `:531-541`, channel `:544-547`, options `:569-571`) and the account row at `tauri/ui/account.mjs:105-126`.
- Schedulers in the page: mascot rotation every 7 s (`tauri/ui/mascot.mjs:11,43`), the sticker/runner observers
  (`tauri/ui/stickers.mjs:91-92`, `tauri/ui/runner.mjs:27-28`), the 2.5 s "Saved" reset (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:207-212`).
- Nothing in `tauri/ui` is scriptable from a config switch; see **Switches**.

## How it works

1. **Load.** `index.html` parses; `bridge.js` checks for `window.__TAURI__.core.invoke` + `event.listen` and, when
   present, builds `window.launcher` frozen with `Object.defineProperty(..., {writable:false, configurable:false})`
   (`tauri/ui/bridge.js:5-6,20,29`). Every bridge method is `invoke(command, value === undefined ? {} : { value })`
   with the error normalised to a plain `Error` (`/root/work/launcher-165-server/tauri/ui/bridge.js:7-13`).
2. **Subscribe, then ask.** `initialize()` (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:577-595`) subscribes `onState` **before** calling
   `bridge.settings()` (`:583-584`), so no state broadcast can be missed between the snapshot and the stream.
   `bridge.settings()` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1134-1162`) waits for startup, records `page_ready_ms`, sends one empty patch and
   returns `settings_json()`.
3. **Adopt settings.** `applySettings()` copies only keys whose `typeof` matches the default (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:55-61`);
   the feed field is forced to `liveFeedUrl` when `forceFeed` or the field is not dirty (`:60`).
4. **First check.** `if (state.phase !== 'error') await checkNow()` (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:594`) → `bridge.check()` →
   `check` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1283`) → `begin("checking")` + `check_release(Mode::Fast)`.
5. **Render.** Each broadcast runs `receiveState` (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:63-68`): `applySettings`, `state = nextState`,
   `errorMessage`, `render()`. `render()` (`:382-466`) drives the badge, headings, message, version panel, news,
   channel, migration question, settings fields and help line; `renderProgress()` (`:88-121`) drives the bar.
6. **Act.** Clicks go through `perform(name, action)` (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:468-481`), which refuses while
   `operation`, a busy phase or an uninitialised page, so the button cannot double-fire; the Rust command then takes
   the exclusive `busy` flag (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:569-576`) and answers on the event stream.
7. **Settings round trip.** `changeSettings()` (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:489-494`) snapshots `feedUrl`+`installDir` before the
   call, because the broadcast can land before the promise resolves, then re-checks when either actually changed.
8. **Auxiliary modules.** `mascot.mjs` rotates `body[data-mascot]` between the six mascots and the month's seasons
   (`/root/work/launcher-165-server/tauri/ui/mascot.mjs:14-16,28-53`); `stickers.mjs` observes `#phase-badge[data-phase]` and swaps `#status-sticker`
   (`/root/work/launcher-165-server/tauri/ui/stickers.mjs:79-94`); `runner.mjs` mirrors `<progress value|max>` onto the track's `--at`/`data-mode`
   (`/root/work/launcher-165-server/tauri/ui/runner.mjs:17-30`) and CSS animates the three frames (`/root/work/launcher-165-server/tauri/ui/style.css:217-223`); `account.mjs` renders the account row
   and dialog from the `account` key (`/root/work/launcher-165-server/tauri/ui/account.mjs:76-82`).

## Data

| UI module | Renders | Fed by (UI side → Rust side) |
|---|---|---|
| `index.html` | Static shell: 63 ids the renderer writes | loaded at `/root/work/launcher-165-server/tauri/app/src/main.rs:56-61` (window `main`, `/root/work/launcher-165-server/tauri/app/tauri.conf.json:7` points `frontendDist` at `../ui`) |
| `bridge.js` | nothing | 24 invokes `/root/work/launcher-165-server/tauri/ui/bridge.js:14-19` → `/root/work/launcher-165-server/tauri/app/src/main.rs:64-89`; `onState` `/root/work/launcher-165-server/tauri/ui/bridge.js:21-28` → emit `/root/work/launcher-165-server/tauri/app/src/launcher.rs:363` |
| `renderer.mjs` | status card, versions, news, channel, dialogs' fields, help | `receiveState` `/root/work/launcher-165-server/tauri/ui/renderer.mjs:63-68` ← `send()` `/root/work/launcher-165-server/tauri/app/src/launcher.rs:349-364`; every button → its command |
| `version-status.mjs` | SERVER/INSTALLED/client/launcher rows, footer, `actionHelp` | pure; inputs `state.installed`/`state.available` (`/root/work/launcher-165-server/tauri/ui/version-status.mjs:62-103`) ← `refresh_installed` `/root/work/launcher-165-server/tauri/app/src/launcher.rs:514-535`, `available_json` `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1124-1129` |
| `account.mjs` | `#account-row` + `#account-dialog` | `state.account` ← `send_account` `/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:99-105`; `account_sign_in/up/out`, `open_market/forgot/join` `/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:220-275` |
| `game-options.mjs` | the 12 switches build at `/root/work/launcher-165-server/tauri/ui/renderer.mjs:133-176` from `GAME_OPTIONS` (`/root/work/launcher-165-server/tauri/ui/game-options.mjs:23-49`) | `game_options` / `save_game_options` / `reset_game_options` `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1327-1343` → `game_options_state` `/root/work/launcher-165-server/tauri/app/src/launcher.rs:873-891` |
| `stickers.mjs` | `#status-sticker`, `#sticker-note` | no command: follows `#phase-badge[data-phase]` set at `/root/work/launcher-165-server/tauri/ui/renderer.mjs:391` |
| `mascot.mjs` | banner picture (`/root/work/launcher-165-server/tauri/ui/index.html:22-41`, 18 `img`) | no command; local clock only (`/root/work/launcher-165-server/tauri/ui/mascot.mjs:29`) |
| `runner.mjs` | runner position/frame on the progress track | follows `<progress>` written at `/root/work/launcher-165-server/tauri/ui/renderer.mjs:114-120` |
| `style.css` | all layout, `data-*` hooks (`/root/work/launcher-165-server/tauri/ui/style.css:89-107,123-128,172-179,217-223,301-304`) | — |
| `assets/` | 34 sticker files (`chibi-*`, `holiday-*`, `runner-*`), 12 seasons, 6 mascots, fonts, `henesys.webm` | — |

**Invoke contract** (name — args — Rust handler):

| Command | Args | Rust | Returns |
|---|---|---|---|
| `settings` | — | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1134` | settings map |
| `save_feed` / `reset_feed` | `value: String` / — | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1165` / `:1181` | settings map |
| `save_access_key` / `clear_access_key` | `value: String` / — | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1213` / `:1220` | settings map |
| `choose_folder` / `reset_folder` | — | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1230` / `:1260` | settings map |
| `open_folder` | — | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1271` | `()` |
| `check` / `update` / `repair` / `play` | — | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1283` / `:1303` / `:1311` / `:1319` | `()` |
| `game_options` / `reset_game_options` | — | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1327` / `:1341` | options state |
| `save_game_options` | `value: Value` (`{id: bool}`) | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1335` | options state + `saved` |
| `set_channel` | `value: String` | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1346` | settings map |
| `answer_migration` | `value: Option<String>` | `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1383` | settings map |
| `account_sign_in` / `account_sign_up` | `value: Credentials{code?,user?,pass?}` | `/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:220` / `:225` | account map |
| `account_sign_out` | — | `/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:230` | account map |
| `open_market` / `open_forgot` / `open_join` / `open_report` | — | `/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:255` / `:236` / `:246` / `:266` | `()` |

Persisted: nothing in the page. No `localStorage`, `sessionStorage`, `indexedDB`, cookie or `fetch` call exists in
`tauri/ui` (grep, zero hits). Settings live in the launcher's settings file (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:279-283`), the session token
in the token store (`/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:154-168`), options in `config.ini` (`/root/work/launcher-165-server/tauri/core/src/game_config.rs:58-60`).

## Switches

The launcher has **no** entry in the live conf: `docs/release/1.6.4.conf:271` is `UNIT_EDITS`, server `-D` properties.
Page behaviour is fixed at build time:

- `tauri/launcher.config.json:2-5` — `feedUrl`, `clientExecutable`, `downloadConcurrency: 12`, `accessKey: ""`
  (empty in the repo; a friends build bakes one in). Read via `include_str!` at `/root/work/launcher-165-server/tauri/app/src/launcher.rs:31`.
- `tauri/app/tauri.conf.json:10` `withGlobalTauri: true` — required, else `/root/work/launcher-165-server/tauri/ui/bridge.js:6` returns and the page shows
  "The secure launcher connection is unavailable…" (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:578-580`).
- `/root/work/launcher-165-server/tauri/app/tauri.conf.json:25` CSP — `script-src 'self'`, `connect-src ipc: http://ipc.localhost`, `form-action 'none'`;
  `freezePrototype: true` (`:26`). No inline script or style exists in `index.html` (grep), so the policy holds.
- Launcher CLI flags affect the page indirectly (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:55-79`): `--feed-url=`, `--install-dir=`,
  `--site-url=` (loopback HTTP only, `:77`), `--no-self-update`/`SLUMSMS_NO_SELF_UPDATE=1`.
- `AVAILABLE_INTERVAL` = 10 min (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:32`) — the background refresh that keeps the page's versions fresh.
- TEST build: unknown (no launcher entry in a release conf and `start-test.sh` was not read).

## Invariants

- **The page never sees the access key** — module doc `/root/work/launcher-165-server/tauri/app/src/launcher.rs:1-4`, `effective_json` emits only
  `accessKeySource` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:320-326`), `/root/work/launcher-165-server/tauri/ui/bridge.js:1-3`. The field is blanked before the invoke
  (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:533-536`) and never written back.
- **Every bridge name is a registered command** — `test/tauri-account.test.mjs:30-37` parses `bridge.js` and asserts
  each maps into `main.rs`'s `generate_handler!`; a rename on either side fails the suite.
- **The event is a full snapshot** — `send()` merges `effective_json()` + patch into `inner.state` and emits the whole
  map (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:349-364`), which is why `receiveState` may replace `state` wholesale (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:65`).
- **All strings are text, never HTML** — `/root/work/launcher-165-server/tauri/ui/renderer.mjs:250-274` and `:286-291` use `textContent`/`createElement`;
  no `innerHTML`/`insertAdjacentHTML`/`eval` exists in `tauri/ui` (grep, zero hits), and CSP forbids inline script.
- **Busy exclusivity is double-gated** — UI `perform` refuses while busy (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:469`, advisory only) and Rust
  `begin()` CASes the flag (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:569-576`), returning "A launcher operation is already running."
- **Passwords never persist in the page** — `/root/work/launcher-165-server/tauri/ui/account.mjs:120-125` clears all `input[type=password]` *before* the
  bridge call and before validation; `Credentials` is `drop`ped to wipe the field (`/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:29-34,180`).
- **Phase vocabulary is duplicated, not shared** — 8 strings in `phaseNames` (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:15-24`) must match what
  Rust writes (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:574,583,643,651,677,739,813`); there is no shared constant and no test for it.

## Origin

Custom (this stack's own launcher); the base v83 server has no launcher at all. Oldest commits
(`git log --follow --format=%h -- <path> | tail -1`, repo `/root/work/launcher-165-server`):

| Path | Oldest | Commit |
|---|---|---|
| `tauri/ui/bridge.js` | `bd3df88` | 2026-10-21 "Start the Tauri launcher: Rust core, ported UI and app shell" |
| `tauri/ui/index.html`, `renderer.mjs`, `style.css` | `fcd6e08` | 2026-10-19 "Add SlumsMS MapleStory launcher and static release publisher" (Electron original) |
| `tauri/ui/version-status.mjs` | `7fdf4da` | 2026-10-21 "Show the installed and available game versions" |
| `tauri/ui/game-options.mjs` | `76527ee` | 2026-10-21 "Add a Game options panel and steady, resumable install progress" |
| `tauri/ui/account.mjs` | `75bfebc` | 2026-10-26 "Launcher: website sign-in, 'signed in as', invite sign-up" |
| `tauri/ui/mascot.mjs` | `d96a8c7` | 2026-10-26 "Launcher: the two mascots take turns" |
| `tauri/ui/stickers.mjs` | `358b2d9` | 2026-11-04 "The launcher in the website's look, with Slums-chan stickers" |
| `tauri/ui/runner.mjs` | `750b491` | 2026-11-04 "Little Slums-chan runs along the progress bar" |

The Electron tree in `src/` still exists: `src/game-options.mjs` is byte-identical, but `src/version-status.mjs` (88
lines) is an older sibling of `tauri/ui/version-status.mjs` (174).

## Tests and QA

Node suite (`npm test` = `node --test`), all DOM-free:

| File | Covers |
|---|---|
| `test/tauri-ui.test.mjs:7-20` | `actionHelp` branches (ready/update/offline/fresh/resume/error/access-key/busy/no-feed/uninitialised) |
| `test/tauri-ui.test.mjs:22-47` | mascot table + every rotor picture has a `pic-*` class, a `body[data-mascot]` CSS rule and an on-disk asset |
| `test/tauri-ui.test.mjs:49-82` | `stickerFor` per phase, holiday override, error suppression, 23 holiday ids resolve to files |
| `test/tauri-ui.test.mjs:84-148` | `startRotation` with a fake page: 7 s order, wrap, pause on `document.hidden`, reduced motion |
| `test/tauri-ui.test.mjs:150-165` | `runnerFor` table, `progress` markup shape, runner frames exist |
| `test/tauri-versions.test.mjs:9-94` | `buildNumber`, client label source, `minClientBuild` note, `versionStatus`/footer/labels |
| `test/tauri-account.test.mjs:6-28` | `accountRow` and `formProblem` |
| `test/tauri-account.test.mjs:30-37` | **the contract test**: every `bridge.js` command is in `main.rs` |
| `test/game-options.test.mjs:150-162` | `src`↔`tauri/ui` identity, fallbacks/readers match the client |
| `tauri/core/src/game_options.rs:762-773` | Rust table ↔ page table parity via `include_str!("../../ui/game-options.mjs")` |

Not covered: `renderer.mjs` and `bridge.js` have no test that executes them (no test imports either; only the id
string is parsed). News rendering, the rail, channel picker, migration dialog, options panel, optimistic save and
every `perform` gate are unexecuted. Server-side auto-QA has just `t-la-1`, a MANUAL check
(`src/main/java/soloMapling/autoqa/AutoQaCatalog.java:783`) that the headless runner always SKIPs
(`src/main/java/soloMapling/autoqa/ClientScriptVerdicts.java:17`); no `!qa` scenario touches the launcher.

## Known issues

- **b270** (S3, Client) — the Live client publish wrote `manifest.json` without `news[]`, so `slumsms-news@live`
  failed; the news panel and the Megaphone rail have stayed empty (`/root/work/STATUS-1.6.4.md:75`). Mechanism: `news` reaches
  the page only from `check_release` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:643`) and `install_files` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:664`), i.e. only when
  `load_release` ran; the 10-minute background refresh patches `available` alone (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:537-559`). The
  publisher treats `--news` as optional (`tools/publish.mjs:547,558,583-585,647`), the validator only bounds it
  (`tauri/core/src/manifest.rs:209-236`).
- **b017** (S3, Launcher) — Defender false positive on each new exe; process, not code (`/root/work/STATUS-1.6.4.md:70`).
- **o04** (Ops) — move client/launcher builds off Silvia later (`/root/work/STATUS-1.6.4.md:130`).
- Ticket 27 (`docs/tickets/27-launcher-extras.md`) is closed and obsolete (Electron era); the "server online/offline"
  and "Source code link" items were never built.
- `NEW-FINDING:` `/root/work/launcher-165-server/tauri/ui/renderer.mjs:104` decides whether to hide the byte/speed line by testing the message text
  (`/^Downloading \d/u`) against the string built in `tauri/core/src/packs.rs:316-323`. Rewording that message silently
  brings back a duplicate "12.3 MiB / 21.7 GiB" line under the same information.
- `NEW-FINDING:` `/root/work/launcher-165-server/tauri/ui/renderer.mjs:393,397` index `phaseNames[phase]` / `phaseHeadings[phase]` with no fallback, so a
  phase string from Rust that the page does not know writes the literal text "undefined" into the badge and H1.
- `NEW-FINDING:` `/root/work/launcher-165-server/tauri/ui/renderer.mjs:57-59` copies a settings key only when `typeof` matches the default; if Rust ever sends
  `installDir: null` the field silently keeps the previous value and the page shows a stale folder with no warning.
- `NEW-FINDING:` `/root/work/launcher-165-server/tauri/ui/renderer.mjs:511-527` re-implements feed validation but omits the userinfo rejection of
  `tauri/core/src/config.rs:31-33`, so `https://user:pass@host/manifest.json` passes the page check and only then fails
  in `save_feed` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1168`) — a rejection the UI has already committed to.
- `NEW-FINDING:` three payload fields are written and never read: `version` in the release patches
  (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:643,664,1171,1185,1204,1373`; grep finds no reader), `account.checking` (`/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:133`), and
  `options.defaults` / `options.legacyMoveAttack` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:884`, `/root/work/launcher-165-server/tauri/core/src/game_config.rs:49`) — the page restores defaults
  by calling `reset_game_options` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1341`) instead.
- `NEW-FINDING:` `available_json` drops `manifestSha256` from `Available::Ok` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1124-1129` vs
  `/root/work/launcher-165-server/tauri/core/src/release.rs:205-206`), so the page cannot tell "same release" from "different build of the same version"; it
  compares version strings only (`/root/work/launcher-165-server/tauri/ui/version-status.mjs:63-76`).

## State

**Working, with one starved surface.** The settings/versions/progress/play path is driven by a full-snapshot event and
a registered-command test guards the contract; the 12 options switches are pinned to the Rust table on both sides
(`/root/work/launcher-165-server/tauri/core/src/game_options.rs:762-773`, `test/game-options.test.mjs:150-162`). Evidence: 24 commands both declared
(`/root/work/launcher-165-server/tauri/app/src/main.rs:64-89`) and listed (`/root/work/launcher-165-server/tauri/ui/bridge.js:14-19`); `receiveState`/`render` cover every key the launcher sends except
the three above; CSP has no inline violations to relax. **Partial:** the news panel and rail — correct code
(`/root/work/launcher-165-server/tauri/ui/renderer.mjs:244-293`) fed by a manifest that today carries no `news[]` (b270), so on a normal start it is simply
hidden (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:275,292`). **Unverified by any test:** `renderer.mjs` as a whole, and nothing here was run.

## Open questions

- Does anything outside the page depend on the `version` key the launcher keeps sending in release patches, or can it
  be dropped with `NEW-FINDING` above?
- Is b270 fixed by publishing with `--news` again, or does the publisher need a default news file so a publish can
  never ship an empty list? The card reads "so slumsms-news@live failed", which suggests an expectation outside the
  launcher; no code in this repo generates news.
- Is the "undefined" fallback at `/root/work/launcher-165-server/tauri/ui/renderer.mjs:393` reachable in practice, i.e. can Rust emit a phase outside the
  eight? Every write site inspected uses one of the eight, but nothing enforces it.
- `docs/HANDOFF.md:73` says the Tauri launcher "1.5.2 … unchanged"; the crate is at `1.6.5`
  (`tauri/Cargo.toml:6`) while `package.json:3` still says `1.3.3` for the retired Electron shell — which number is
  meant to be authoritative for the page's `launcher` row (`/root/work/launcher-165-server/tauri/ui/renderer.mjs:365`)?
