# Game config (`config.ini` merge and game options)

## Purpose

`config.ini` beside the game executable is the client's only configuration file: it carries the login server address
(`[config] host`/`port`) and every runtime switch. The launcher owns the *file*, not every *key*: it merges the
channel's server address into the player's copy at install time, and it writes the twelve game options the panel shows
every time the panel opens, the game starts, or an install finishes. Everything else in the file — comments, unknown
keys, line endings, non-UTF-8 bytes — is carried through byte-for-byte, because the whole file is read and written as
latin1 (`tauri/core/src/game_options.rs:1-17`).

The player-facing contract is "your settings survive updates, and your server follows the channel unless you set it
yourself"; b301 is the 1.6.5 change that made the second half true.

## Entry points

| Entry | What it writes | File:line |
|---|---|---|
| install/repair, player-owned file already present | `[config] host`/`port` from the channel layer | `tauri/core/src/install.rs:557-577` (`commit_locked`, merge at `:569`) |
| Play, before the process starts | all 12 options, explicit | `tauri/app/src/launcher.rs:737-738` → `write_options_explicitly` `:896` |
| end of any install/update/repair | all 12 options, explicit (spawned, not awaited) | `tauri/app/src/launcher.rs:678-680` |
| opening **Game options** | all 12 options, explicit, then the state | `tauri/app/src/launcher.rs:1327-1332`; `tauri/ui/renderer.mjs:188-199` |
| toggling a switch | one change (plus pending ones) | `tauri/ui/renderer.mjs:163-166` → `saveGameOptions` `tauri/ui/bridge.js:17` → command `tauri/app/src/launcher.rs:1335-1338` |
| **Restore defaults** | the 12 client defaults | `tauri/ui/renderer.mjs:571`; command `tauri/app/src/launcher.rs:1341-1343`; defaults `tauri/core/src/game_options.rs:110-113` |
| self-test `game-options` step | flips `attackwalk`, saves, reads back | `tauri/app/src/launcher.rs:1016-1020` |
| legacy Electron settings migration | carries `pendingGameOptions` over | `tauri/core/src/settings.rs:178-180` |

The core entry points are `slums_core::game_config::write_game_config` (`game_config.rs:142`),
`merge_server` (`game_config.rs:120`), `slums_core::game_options::explicit_game_options`
(`game_options.rs:530`) and `apply_game_options` (`game_options.rs:503`). There is no timer and no file watcher: the
file is only touched from the rows above, and the client re-reads it once per process
(`docs/architecture/client/config-ini.md` "Entry points").

## How it works

1. **Install decides whether the layer's copy is needed.** `inspect` (`install.rs:83`) reads
   `.slumsms-launcher/state.json` and, for a `playerOwned` path (`manifest.rs:179`, default `['config.ini']` at
   `tools/publish.mjs:14`), keeps the player's file when the recorded `sha256` equals the layer file's
   (`install.rs:112-117`). Otherwise the file is put on the pending list, and for `config.ini`
   (`is_server_file`, `install.rs:65`) the recorded server is remembered (`:119-120`, field `recorded_server`
   `install.rs:76`).
2. **Hand-edit detection.** `commit_locked` (`install.rs:557`) downloads the layer copy to a temp path, takes
   `server_of(&layer)` (`:562`) and, when the folder already had the file, compares the player's current
   `server_of(&player)` with the server the launcher last left there
   (`hand_edited = recorded_server.is_some() && server_of(&player) != Some(recorded)`, `:568`).
3. **Merge or keep.** `keep = if hand_edited { player } else { merge_server(&player, &layer) }` (`:569`), the bytes are
   written back into the temp file (`:570`) and renamed over `config.ini` (`:572`), and the state entry records both the
   new hash and `server = server_of(&layer)` (`:576`). `merge_server` (`game_config.rs:120-129`) parses both files and,
   for each key of `SERVER_KEYS = ["host", "port"]` (`game_config.rs:112`), calls `set_profile_value`
   (`game_options.rs:432`) only when the layer actually sets it: a key the layer omits keeps the player's value.
4. **Precedence on the server keys.** player hand edit > channel layer > whatever was in the file before. A folder
   installed by 1.6.4 or older has no recorded server, so `recorded_server` is `None`, `hand_edited` is `false`, and the
   channel's address is taken once even over a hand edit (`install.rs:117-120`; tested at `tauri/core/tests/feed.rs:175`).
5. **Options are written explicitly, not merged selectively.** `write_options_explicitly` (`launcher.rs:896`) takes the
   pending change set from the settings (`pending_for`, `:860`), calls `write_game_config`
   (`game_config.rs:142`) and clears the pending set on success or on `CONFIG_MISSING` (`:899-905`). Inside,
   `explicit_game_options` (`game_options.rs:530`) computes `read_game_options(text).values` — the value the client
   would use now, including the legacy `moveattack` fallback — overlays the changes, and calls `apply_game_options_map`
   → `apply_game_options` (`:540`, `:503`), which walks the 12 options in `GAME_OPTIONS` order and `set_value`s each.
6. **Per-key edit.** `set_value` (`:417`) returns untouched when the key is a single line the client already reads as
   the wanted boolean; otherwise `set_key` (`:442`) rewrites the first matching line in place (keeping its spelling,
   spacing and inline comment, `rewrite_value` `:382`), deletes repeats and continuation lines, or inserts a new key
   after the last key of the section's first block, or appends a missing section with the file's own EOL.
7. **Move-attack coupling.** Any change to one of `MOVE_ATTACK_IDS` (`:100`) first writes all three at their current
   values (`:506-511`), so the file stops depending on `moveattack`; `moveattack` itself is read only
   (`LEGACY_MOVEATTACK`, `:97`).
8. **Atomic write.** If the bytes change, `write_game_config` writes `.{config.ini}.{uuid}.tmp` in the same folder,
   `write_all` + `sync_all` + `rename` over `config.ini`, and removes the temp file on any failure
   (`game_config.rs:153-164`). Unchanged bytes mean no write at all (`:152`; test `game_config.rs:351`).
9. **Refusals.** Empty folder → `Choose a game folder first.`; game process running → `Close SlumsMS to change game
   options. They apply the next time the game starts.` (`GAME_RUNNING`); file missing →
   `config.ini is missing from the game folder. Install or repair the game first.` (`CONFIG_MISSING`)
   (`game_config.rs:143-151`, codes at `tauri/core/src/error.rs:10-11`). "Running" is a name match against
   `SlumsMS.exe` / `MapleStory.exe` (`game_config.rs:15`) from `tasklist`-style output or `ps -A -o comm=`
   (`:170-233`); an unreadable process list counts as *not* running (`:191-193`), the atomic rename being the guard.
10. **While the game runs, changes wait.** `save_game_options` (`launcher.rs:917`) catches `GAME_RUNNING`, stores the
    merged change set as `PendingGameOptions { install_dir, changes }` (`tauri/core/src/settings.rs:16-19`), persists
    the settings and reports `saved: "pending"`; the UI says so and the next panel open or Play writes them
    (`renderer.mjs:183`, `:179-186`).

## Data

File: `<install dir>/config.ini` (`config_path`, `game_config.rs:58`), latin1, BOM-aware, per-line EOL preserved
(`game_options.rs:191-268`). Template shipped in the client layer: `config.example.ini`, `[config] host=127.0.0.1`
`port=8485` (`/root/work/client/src-164-data/config.example.ini:84-86`).

The 12 keys the launcher writes (default = what the launcher shows when the key is absent, pinned to the client by
`fallbacks_match_the_client`, `game_options.rs:725`):

| Id | Section | Key | Default (`game_options.rs`) | Client reader |
|---|---|---|---|---|
| `discord` | `discord` | `enableactivitystatus` | on (`:70`) | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:611` |
| `winkey` | `clienthax` | `enablewinkey` | on (`:72`) | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:655` |
| `nobulb` | `clienthax` | `nobulb` | on (`:74`) | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:616` |
| `customcolor` | `clienthax` | `customcolor` | on (`:76`) | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:620` |
| `attackwalk` | `clienthax` | `attackwalk` | off (`:78`) | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:636-641` → `src/attackstop_patch.h:112` |
| `attackjump` | `clienthax` | `attackjump` | on (`:80`) | `attackstop_patch.h:113` |
| `chargemove` | `clienthax` | `chargemove` | on (`:82`) | `attackstop_patch.h:114` |
| `doubletapjump` | `clienthax` | `doubletapjump` | off (`:84`) | `src/bounce.cpp:231` (Win32 reader) |
| `jumpshoot` | `clienthax` | `jumpshoot` | on (`:86`) | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:643` |
| `jumpmagic` | `clienthax` | `jumpmagic` | on (`:88`) | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:649` |
| `borderless` | `graphics` | `borderless` | off (`:90`) | `src/system.cpp:306` → `src/window_policy.h:26-31` |
| `dpiaware` | `graphics` | `dpiaware` | off (`:92`) | `src/system.cpp:307` |

Keys the launcher writes from the channel, never from the panel: `[config] host`, `[config] port`
(`SERVER_KEYS`, `game_config.rs:112`; client reader `src/injector.cpp:30-42`, and the address is only applied to the
stock addresses in `src/system.cpp:223-272`).

Keys the launcher must not touch and does not: `[general] resolution`, `[clienthax] moveattack`, `jumpkeydown`,
`invresize`, `lowdefensepenalty`, `mobattackfix`, `settingswindow`, `uitour`, `mapobjguard`, `nativehud`, all of
`[weather]` (e.g. `sway`, `ropesway` — `src/weathersway.cpp:869-884`), `[debug]`, and every unknown key and comment
(`apply_game_options` doc, `game_options.rs:501-502`; `explicit_game_options` doc `:528-529`).

In-memory state: `GameConfig { status, values, legacy_move_attack, parse_error_line }` (`game_config.rs:19-25`),
serialized to the page as camelCase and with only `status` when missing (`:39-57`). Persisted state:

- `.slumsms-launcher/state.json` → `files["config.ini"].server` — the server the launcher last left in the file
  (`tauri/core/src/release.rs:17`, field doc `:285-288`, parse `:309`); unreadable state reads as empty (`:298`).
- the launcher's settings → `pendingGameOptions { installDir, changes }` written when the game is running
  (`settings.rs:16-19`, `launcher.rs:867-870`).

## Switches

There is no `-D`/environment surface for this subsystem. The launcher's own settings are the settings file
(`tauri/core/src/settings.rs`), not flags. `docs/release/1.6.4.conf:271` (`UNIT_EDITS`) is the live server's whole
`-D` set (69 flags) and contains nothing about the launcher or `config.ini`; no launcher key is settable from the
server side. What *is* switchable here:

| Switch | Where | Default | Live vs TEST |
|---|---|---|---|
| the 12 option ids above | `game_options.rs:69-94`, written into `config.ini` | per the table | per player; the client layer's shipped `config.ini` decides the first run |
| `[config] host`/`port` | channel client layer | layer value | Live layer `slumsms.asslorde.com:8485`; Test/RC the test server (`game_config.rs:106-111`) |
| `playerOwned` list | `manifest.json`, default `['config.ini']` (`tools/publish.mjs:14`) | `config.ini` | same on all channels; TEST: unknown (`start-test.sh` not read) |

## Invariants

1. **Bytes the launcher does not own come back identical.** The file is latin1, one byte per character
   (`game_options.rs:120-127`); `parse_ini` keeps each line's own terminator so `serialize_ini` reproduces the input
   (`:191-280`); a no-op write touches nothing (`:152`; tests `game_config.rs:328,351`).
2. **The client's parser decides what a key means, not the launcher's.** Two readers exist: inih for most keys and the
   Win32 profile API for `doubletapjump`, `borderless`, `dpiaware` and the server keys — modelled by `Reader::Inih` vs
   `Reader::Profile` (`game_options.rs:39-44`, `:63-65`), with `get_value` joining repeats/continuations (`:291`) and
   `get_profile_value` taking the first `key=value` with no inline comment (`:311-334`).
3. **`config.ini` is player-owned but not player-exclusive.** Present in `playerOwned` (`manifest.rs:179`), yet
   `[config] host`/`port` follow the channel — documented at `game_config.rs:106-119` and at
   `install.rs:61-67`.
4. **Never created, never written while the game runs.** `CONFIG_MISSING` when absent (`game_config.rs:151`; test
   `:308`), `GAME_RUNNING` while a game process is up (`:148`; test `:320`), because the client reads the file once at
   start-up and some clients write it back (`:135-140`).
5. **A write is all-or-nothing.** Temp file in the same directory, `sync_all`, then `rename` (`:153-164`), leaving
   either the old or the new file and no `.tmp` behind (test `:328`).
6. **The panel shows what the game will do.** Every option is written explicitly at open/save/launch, so the file never
   depends on a client default that could differ between client versions (`game_options.rs:528-538`, test `:793`).
7. **Unknown option ids are rejected before anything is written** (`validate_changes`, `:549-560`; test `:836`), and a
   value must be a JSON boolean.
8. **The panel and the Rust core carry the same table.** `the_page_table_matches` compares against
   `include_str!("../../ui/game-options.mjs")` (`game_options.rs:764`), and a JS test asserts the `src/` and `tauri/ui`
   copies of the module are byte-identical (`test/game-options.test.mjs:161`).
9. **Every byte-level rule above holds for hand-edited files too**: case-insensitive section/key names, `;`/`#`
   comment lines, ` ;` inline comments, repeated keys (joined so the boolean no longer parses), continuations
   (`game_options.rs:1-17`; tests `:617,630,658,672`).

## Origin

| Path | Origin | Evidence |
|---|---|---|
| `tauri/core/src/game_config.rs` | **custom** (launcher repo) | `git log --follow --format=%h -- tauri/core/src/game_config.rs \| tail -1` → `bd3df88` 2026-10-21; `merge_server`/`SERVER_KEYS` added by `da13b09` "Switching channels switches the game server…" (2026-11-05); hand-edit record by `f14e813` |
| `tauri/core/src/game_options.rs` | **custom** | oldest `bd3df88` 2026-10-21 |
| `tauri/ui/game-options.mjs` | **custom** | oldest `76527ee` 2026-10-21; the Electron `src/game-options.mjs` copy is byte-identical (`test/game-options.test.mjs:161`) |
| `tauri/core/src/install.rs` (server-file branch) | **custom** | file oldest `bd3df88`; `is_server_file`/merge branch from `da13b09` |
| client `[config]` reader (`src/injector.cpp`) | **base** (upstream), unmodified | see `docs/architecture/client/config-ini.md` `## Origin` |

The channel-merge chain in this repo is `a1f31b2` (1.6.4, no touch of an existing `config.ini`) → `da13b09` (merge)
→ `94bc4b6` (version) → `f14e813` (record the server, keep hand edits), HEAD `f14e813`.

## Tests and QA

- `tauri/core/src/game_config.rs` `#[test]`: `merge_server_takes_only_host_and_port_from_the_channel` (`:240`, also
  asserts a second merge is byte-identical), `the_game_server_is_read_the_way_the_client_reads_it` (`:297`),
  `a_missing_config_ini_is_reported_and_never_created` (`:308`),
  `writing_refuses_while_the_game_runs_and_leaves_the_file_alone` (`:320`),
  `the_file_is_replaced_atomically_other_bytes_survive_and_no_temporary_is_left` (`:328`),
  `an_empty_change_set_writes_every_option_explicitly_and_only_once` (`:351`),
  `each_channel_reads_writes_and_launches_with_its_own_config_ini` (`:376`),
  `a_config_ini_that_is_not_a_file_is_an_error` (`:412`),
  `the_game_process_is_found_in_tasklist_and_ps_output` (`:419`).
- `tauri/core/src/game_options.rs` `#[test]` (16): `parse_and_serialize_give_back_the_exact_text` (`:598`),
  `reading_follows_the_client` (`:617`), `an_unparseable_line_is_reported` (`:630`),
  `editing_touches_only_the_owned_keys` (`:637`), `key_spelling_spacing_and_inline_comment_are_kept` (`:658`),
  `missing_keys_and_sections_are_added_with_the_files_line_endings` (`:663`),
  `repeats_and_continuations_of_an_edited_key_are_removed` (`:672`), `legacy_moveattack` (`:681`),
  `double_tap_jump` (`:702`), `fallbacks_match_the_client` (`:725`),
  `window_switches_join_an_existing_graphics_section` (`:749`), `the_page_table_matches` (`:764`),
  `double_tap_jump_is_read_like_get_private_profile_string` (`:776`),
  `every_option_is_written_explicitly_with_what_the_panel_shows` (`:793`),
  `only_known_options_with_on_off_values_are_accepted` (`:836`), `option_table_serializes_for_the_page` (`:847`).
- Install-level (`tauri/core/tests/feed.rs`): `player_owned_config_installs_once_survives_updates_and_repair` (`:121`),
  `config_ini_takes_its_server_from_the_channel_and_keeps_the_players_options` (`:136` — player's `mine=1 ; my note`
  survives, `read_game_server` reports the channel address, a hand-set address survives a later channel change),
  `an_install_from_an_older_launcher_takes_the_channel_server_once` (`:175`).
- JS: `test/game-options.test.mjs` (21 `test(` calls, `:27`-`:235`) mirrors the Rust rules and adds the
  `src/`↔`tauri/ui` identity check (`:161`).
- Auto-QA `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:733` (t-bk-1) and `QaScenarios.java:407` mention
  `[debug] attackdiag=1 in config.ini`, but that is a step instruction, not a check of this subsystem: **no auto-QA
  check reads or writes `config.ini`**, and no `!qa` scenario covers the merge or the panel.

**Not covered:** `install.rs` has no unit test of its own (the merge is covered only end-to-end through `feed.rs`);
nothing exercises the real Windows `GetPrivateProfileStringA`/`Toolhelp32` calls (the profile reader is simulated,
`game_options.rs:311`; the process list is faked per platform); `tauri-ui.test.mjs` does not touch the Game options
panel; and nothing verifies that a launched client actually connects to the server written into the file.

## Known issues

| Id | Source | One line |
|---|---|---|
| b301 | no ticket file; `docs/release/1.6.4-playbook.md:3,42`; `/root/work/launcher-tauri-release/RUNBOOK.md:1-18`; `/root/work/START-HERE.md:37` | Player-owned `config.ini` was never touched, so a folder kept the server it was first installed with (the RC folder still had the old test address after TEST moved). Fixed by 1.6.5 (`da13b09` + `f14e813`), on RC and Test 2026-11-05 08:43 UTC, **not on Live**. |
| b017 | `/root/work/STATUS-1.6.4.md:70` | New launcher exe flagged by Defender; process, not code — unrelated to this subsystem but the exe that carries it. |
| b270 | `/root/work/STATUS-1.6.4.md:75` | Launcher news `news[]` missing from a manifest; not `config.ini`. |
| — | `docs/tickets/104-launcher-login.md:42` | Auto-login was proposed "in `config.ini` like the other client patches" — not implemented; nothing in the client reads a stored password. |

`NEW-FINDING:` `/root/work/launcher-165-server/README.md:58` and `:230` in the launcher repo still say a player-owned file is "installed once and
never overwritten"; since `da13b09` that is false for `[config] host`/`port` (`install.rs:566-570`). A publisher reading
only the README would expect the old behaviour.

`NEW-FINDING:` `/root/work/launcher-165-server/README.md:251-264` lists 10 options and misses `borderless`/`dpiaware` (`game_options.rs:90-93`),
gives `doubletapjump` as "on" when the code fallback is `false` (`game_options.rs:84`) and calls it "Double-tap jump to
Flash Jump" while the panel says Ninja Bounce (`:85`); `/root/work/launcher-165-server/README.md:338` still says game-server editing "is not wired
yet … `config.ini` is player-owned by default", which `da13b09` superseded.

`NEW-FINDING:` the hand-edit branch drops the whole layer copy (`install.rs:569`), so a folder whose player once set
the server by hand never receives a *new key* a later client layer adds to its `config.ini` — only the missing-file
path copies the layer version wholesale.

`NEW-FINDING:` only one pending change set is kept (`Option<PendingGameOptions>`, `settings.rs:16-19`) and it is matched
by `install_dir` (`launcher.rs:860-862`). Saving options for a second channel folder while the game runs silently
replaces the first folder's pending changes, which are then never written.

## State

**partial.** The merge path is written, unit- and integration-tested, and shipped as launcher 1.6.5 on RC and Test
(`/root/work/launcher-tauri-release/RUNBOOK.md:1,17-18`; core `129 passed`, `npm test 109/109`); it is **not** on Live,
which still runs 1.6.4 where an existing `config.ini` is never touched (`docs/release/1.6.4-playbook.md:3,42`). The
option panel path is working end-to-end in code and tests but has no live/manual verification recorded in this repo.
The client side is unchanged by any of this: it still reads `[config] host`/`port` and the switches exactly as
documented in `docs/architecture/client/config-ini.md`.

## Open questions

- b301 has no ticket file in `docs/tickets/` (165 files searched; the only references are the playbook, the launcher
  README, `START-HERE.md` and the release runbook). Is the card tracked outside this repo, or was it only in chat?
- `RUNBOOK.md:5` says "the owner dropped `server.ini`; client rc3 reads `config.ini` only" and
  `/root/work/agent-runs/client/164-client.md:99-105` records a server-side `server.ini` branch (`d49a4ae35`) that was
  merged and then reverted (`ae1359307`). Which artefact is authoritative for the 1.6.4 client that ships on Live?
- Can an install run while the game is running? `install.rs` has no `game_running` check (only
  `write_game_config` has one, `game_config.rs:147`), so the merge could rewrite `config.ini` under a live client; the
  busy flag in `launcher.rs` covers the launcher's own operations, not a hand-started game.
- Should `[config] host`/`port` be exposed in Settings (the pending request at `/root/work/launcher-165-server/README.md:334-338`)? With the merge in
  place the panel would have to write into the same layer/record protocol, not just the file.
- TEST behaviour: unknown — `start-test.sh` was not read for this review, and no server-side switch influences this
  subsystem either way.
