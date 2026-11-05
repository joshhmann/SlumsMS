# Release manifest, layering and client-build metadata

## Purpose

Turns one HTTPS feed URL into a verified, fully resolved release: a top-level manifest, one verified manifest per layer, one merged file list with
absolute URLs, and the hashes the installer later enforces on every byte. It also owns the three bookkeeping files that survive across runs and the
client-build metadata the UI turns into "your client is older than the server accepts". Nothing here downloads or writes game files — that is
`install-and-packs.md`.

## Entry points

- `slums_core::release::load_release(feed_url, install_dir, access, cancel)` (`tauri/core/src/release.rs:133`) — the only path that produces a `Release`; called by
  Check/Update/Repair/Play (`tauri/app/src/launcher.rs:638-682`, `:695-757`).
- `check_available_release(feed_url, access, timeout)` (`tauri/core/src/release.rs:255`) — Play's cheap pre-flight; top manifest only, never fails. Called at
  `tauri/app/src/launcher.rs:699` and on a 10-minute timer (`:32` `AVAILABLE_INTERVAL`).
- `manifest::validate_manifest` (`tauri/core/src/manifest.rs:354`) / `validate_layer_manifest` (`:379`) — pure validators, also driven by fixtures mirroring
  `tools/publish.mjs`; `client_build::read_client_build` (`tauri/core/src/client_build.rs:72`) feeds the "installed" status object (`tauri/ui/version-status.mjs:44-60`).
- Config: `feedUrl` in `tauri/launcher.config.json:2` (or `--feed-url=`, a saved `settings.json` override, or the channel derivation in
  `channels-and-auth.md`) plus an access key (`tauri/core/src/config.rs:51-63`). No packet, opcode, tick or in-game script reaches this code; the only
  server side is the static host and the login gate.

## How it works

### Release load (`tauri/core/src/release.rs:133-196`)

1. **Fetch and validate the top manifest.** `read_bytes(network_url(feed_url)?, "release manifest", Some(access), cancel,
   None)` (`:134`) — one GET, ≤5 redirects (`tauri/core/src/http.rs:124-184`), body capped at `MAX_MANIFEST_BYTES` = 128 MiB
   (`:20`); the key rides as Basic auth only on the feed origin (`:133-135`). Then `parse_json` (`release.rs:135`) +
   `validate_manifest` (`tauri/core/src/manifest.rs:354-377`): `schemaVersion` 1 or 2 (`:356`), non-empty `version` (`:359`),
   `launch` (`:254`), optional `news` (`:209-232`) / `playerOwned` (`:239`) / `minClientBuild`; schema 1 needs `files` +
   `check_tree` + the executable spelled identically (`:368-373`), schema 2 `layers` (`:375`); schema-1 file URLs resolve
   against the *final* top URL (`release.rs:139-145`).
2. **Per layer (schema 2).** `request_url = resolve(top_final, reference.manifest_url)` (`release.rs:148`); try `cached_layer`
   (`:85-98`), which accepts a cached body only when the recorded `requestUrl` matches, the body still hashes to the reference
   SHA-256 and its `finalUrl` passes `network_url`; otherwise `read_bytes` (`:155`) plus an immediate
   `sha256_hex(&bytes) != reference.sha256` → permanent `"Layer manifest {name}@{version} failed SHA-256 verification."`
   (`:156-158`). `validate_layer_manifest(…, Some((&name,&version)))` (`:162` → `manifest.rs:379-397`) then binds body to
   reference (schema 2, name and version equal, else `:390`); file URLs resolve against the layer's **final** URL.
3. **Merge and pin.** `merge_layers` (`tauri/core/src/manifest.rs:406-429`) — later layer wins, a case-folded alias between
   layers is fatal, JS-`Map` order preserved, `check_tree` re-run; the executable must appear (`release.rs:178`). Then
   `manifest_sha256 = sha256_hex(&top_bytes)` (`:185`) is the identity Play compares with `installed.json` (never the ETag) and
   `min_client_build` is copied in (`:190`).

### What the hashes cover

| Hash | Covers | Enforced at |
|---|---|---|
| top `manifestSha256` | exact bytes of the top manifest | `tauri/core/src/release.rs:185`; compared `tauri/core/src/play.rs:17-21` |
| per-layer `sha256` | exact bytes of that layer's manifest JSON | `tauri/core/src/release.rs:156-158`, cache `:95-97` |
| pack index `sha256` | the pack *index* JSON | `tauri/core/src/install.rs:615-616` |
| per-file `sha256` + `size` | every individual game file | `tauri/core/src/install.rs:339-341`, `:411` |
| `PackSource.sha256` | only the digest's shape — pack bytes are **never** hashed | 64-hex check `tauri/core/src/packs.rs:84`, never compared |

Verification is chained: top manifest → layer manifest → (pack index) → per file; a pack archive is transport only, its per-entry digests are the
enforcement (`install-and-packs.md` §5-6).

### Writing to disk

4. **Only three writes exist**, all under `.slumsms-launcher/` (§Data for shapes): the layer cache via `write_layer_cache`
   (`release.rs:100-124`; temp+rename `tauri/core/src/fsutil.rs:240-252`, stale `.json` deleted `:117-124`, schema 1 returns
   early `:102-104`); `state.json` via `write_install_state` (`:319-334`); `installed.json` via `write_installed_record`
   (`:408-419`), skipped when unchanged (`:421-433`) and never for an empty `manifest_sha256` — the launcher's own channel
   (`:422-424`). Game files are never written here.

### Client build metadata (`tauri/core/src/client_build.rs`)

5. `read_client_build` reads `slums-build.txt` beside `slums.dll` (`BUILD_FILE`, `:17`), ≤4096 bytes (`:19`), strips a BOM, tolerates CRLF/blank
   lines/extra keys (`:50-70`). `release` ≤32 printable ASCII, `build` ≤9 digits and >0; a missing half is derived (`:60-66`) and `build` wins over a
   disagreeing `release`. Pre-1.6.0 clients have no file, so the UI falls back to the installed `client` layer version (`tauri/ui/version-status.mjs:44-50`).
6. `build_number("1.6.0") = 1*10_000 + 6*100 + 0 = 10600` (`:30-41`); anything not `MAJOR.MINOR.PATCH` with major ≤9999, minor ≤99, patch ≤99 is `None`.

### `minClientBuild` end to end

7. Server: the login gate refuses `clientBuild < MIN_CLIENT_BUILD` (`src/main/java/net/server/handlers/login/ClientVersionGate.java:47-53`, `:110-118`);
   live 1.6.4 sets `MIN_CLIENT_BUILD=10740` (`docs/release/1.6.4.conf:284`).
8. Launcher: **optional and non-fatal.** `min_client_build` (`tauri/core/src/manifest.rs:347-352`) accepts only `1..=99_999_999`; its comment reads "Only a
   display hint (the game server enforces it at login), so a missing or malformed value means "no check" and never makes the release unusable."
   (`:347-348`). `"10600"`, `-1`, `0`, `1.5`, `null`, `[10600]` → `None` (test `:504-516`).
9. The field rides on the availability object (`tauri/core/src/release.rs:207-208`, `tauri/app/src/launcher.rs:1121`, `:1126`); `clientUpdateNote`
   (`tauri/ui/version-status.mjs:52-60`) shows "Client update needed: the server needs build N or newer" only when it is a positive integer, the folder is
   installed, and the installed build is known and older.
10. **When the server's minimum exceeds the installed client, nothing is blocked and nothing is updated out of band.** The renderer relabels the ready
    phase (`tauri/ui/renderer.mjs:388-392`) and Play follows the normal plan: `play_plan` compares only `manifest_sha256`
    (`tauri/core/src/play.rs:17-21`), so an unchanged manifest means `Verify`, the old exe starts, "Client process started." appears
    (`tauri/app/src/launcher.rs:746`) and the **server** refuses the login. The launcher never reads `MIN_CLIENT_BUILD`; only
    `tauri/devtools/fake-feed.mjs:68-88` ever writes the field.

### Rejecting a bad manifest

All permanent, i.e. never retried (`tauri/core/src/error.rs:31`, `:38`): unsupported `schemaVersion` (`tauri/core/src/manifest.rs:356`), missing version
(`:359`), unsafe or reserved path (`:49-73`), Windows alias / collision (`:293-313`, `:406-429`), non-relative or cross-origin URL (`:103-126`), malformed
digest (`:128-134`), duplicate layer names (`:314-348`), executable not listed (`:370-373`, `release.rs:178`), layer hash or name/version mismatch
(`:156-158`, `tauri/core/src/manifest.rs:390`). HTTP: 401/403 on the feed origin is the access-key error, 5xx/408/425/429 retry, else permanent
(`tauri/core/src/http.rs:163-175`).

## Data

| File (game folder) | Shape | Written by | When |
|---|---|---|---|
| `.slumsms-launcher/state.json` | `stateVersion:1, release, layers[], files{path:{size,mtimeMs,sha256,server?}}` (`release.rs:280-290`, `:319-334`) | `write_install_state` | every install/verify |
| `.slumsms-launcher/installed.json` | `recordVersion:1, release, manifestSha256, layers[], verifiedAt, launch{executable,args}` (`release.rs:339-347`, `:408-419`) | `record_installed` | clean full verify/install (`tauri/core/src/install.rs:884`) |
| `.slumsms-launcher/layers/{sha256}.json` | `requestUrl, finalUrl, body` (`release.rs:69-75`) | `write_layer_cache` | `tauri/core/src/install.rs:883` |
| `slums-build.txt` (beside `slums.dll`) | `release=` / `build=` (`tauri/core/src/client_build.rs:50-70`) | the game client's build | read-only here |

`mtimeMs` is `f64` to match Node's `stat.mtimeMs` bit for bit (`float_roundtrip`, `tauri/core/Cargo.toml:10-11`, `tauri/docs/checkpoint.md:32`);
digests come from the manifest except in Fast mode, which trusts size+mtime (`tauri/core/src/install.rs:126-131`). A `Release`
(`tauri/core/src/release.rs:54-67`) also holds `files: Vec<FileEntry>` (path, size, sha256, URL, layer), `layer_sources` (raw bytes)
and `layer_parts` (files + optional `PacksSource`).

## Switches

No `-D` surface. The knobs are `tauri/launcher.config.json` (shipped) and the player's `settings.json`.

| Key | Default | Where |
|---|---|---|
| `feedUrl` | `https://slumspatch.asslorde.com/manifest.json` | `tauri/launcher.config.json:2`; `tauri/core/src/config.rs:25-35` (https, or http on loopback only) |
| `clientExecutable` | `SlumsMS.exe` | `tauri/launcher.config.json:3`; bare name only (`tauri/core/src/config.rs:88-93`) |
| `downloadConcurrency` | `12` | `tauri/launcher.config.json:4`, `config.rs:8`; range 1..=32 (`:100-104`) |
| `accessKey` | `""` — none shipped | `tauri/launcher.config.json:5`; saved wins, built-in key only on the built-in origin (`config.rs:51-63`) |
| `minClientBuild` (manifest) | absent | `tauri/core/src/manifest.rs:347-352`; live feed does not set it — no publisher writes it (`tools/publish.mjs:357-400`) |
| `MIN_CLIENT_BUILD` (server) | `0` = off | `src/main/java/config/ServerConfig.java:61`; live 1.6.4 `10740` (`docs/release/1.6.4.conf:284`) |

Live, RC and Test differ only by feed path (`channels-and-auth.md`); these code paths are identical on all three. TEST's `MIN_CLIENT_BUILD`: unknown
(`start-test.sh` was not read). Of the 69-flag `UNIT_EDITS` set (`docs/release/1.6.4.conf:271`), only `MIN_CLIENT_BUILD` reaches the launcher.

## Invariants

1. **A `Release` exists only if every layer manifest hashed to its reference** (`tauri/core/src/release.rs:156-158`); the cache path re-checks that digest
   on every read (`:95-97`) and refuses a body from another URL — `requestUrl` must match and `finalUrl` must still pass `network_url` (`:86-98`).
2. **Relative URLs resolve against the URL that actually answered** (`tauri/core/src/release.rs:135`, `:165`; test `tauri/core/tests/feed.rs:268`).
3. **No two manifest paths may collide on Windows** — NFC-normalised case-folded trie, UTF-16 lengths (`tauri/core/src/manifest.rs:16-24`, `:293-313`),
   re-checked after merge (`:427`); no path may enter `.slumsms-launcher/` or a staging directory (`:66-73`), and every launcher write lands under it
   (`tauri/core/src/release.rs:100-124`).
4. **The access key never leaves the feed origin** (`tauri/core/src/http.rs:133-135`; tests `tauri/core/tests/feed.rs:293`, `:316`).
5. **Malformed optional metadata degrades, never blocks** — `minClientBuild` (`tauri/core/src/manifest.rs:347-348`), `news` (`:209-232`), `state.json`
   (`tauri/core/src/release.rs:298-300`), `installed.json` (`:357-362`), `slums-build.txt` (`tauri/core/src/client_build.rs:1-11`, `:72-78`).
6. **`manifest_sha256` is the identity of a release**, so republishing identical files with new news forces a re-check (`tauri/core/src/play.rs:20`).

## Origin

**custom** throughout — the launcher post-dates the base server and the repo's oldest commit is `76c42a8` (2026-10-18). Per file
(`git log --follow --format=%h | tail -1`):

| Path | First commit |
|---|---|
| `tauri/core/src/manifest.rs`, `release.rs`, `config.rs`, `error.rs`, `fsutil.rs`, `http.rs`, `lib.rs`, `packs.rs`, `settings.rs`, `tauri/app/src/launcher.rs` | `bd3df88` "Start the Tauri launcher: Rust core, ported UI and app shell" (2026-10-21) |
| `tauri/core/src/client_build.rs` | `76a0648` "Read slums-build.txt and the optional minClientBuild manifest field in core" (2026-10-25) |
| `tauri/core/src/instance.rs` | `5c91c86` "Fix launcher folder isolation, update ownership, migration and metadata hangs" |
| `tauri/launcher.config.json` | `80a3be1` "feat: simplify launcher flow with built-in defaults and release news" (2026-10-18) |
| `tauri/docs/checkpoint.md`, `handover-plan.md` | `fdc98a0` "Docs, owner QA script, and wait for the channel probe before a Test/RC check" |

`manifest.rs`/`release.rs` port the Electron launcher's `manifest.mjs`/`releases.mjs`; `tauri/core/src/release.rs:1-4` says the
formats are byte-compatible, which is why both suites assert the same fixtures.

## Tests and QA

`cargo test -p slums-core` — 87 unit tests; this file's modules:

| Module (count) | Tests |
|---|---|
| `manifest.rs` (7) | `unsafe_paths_are_rejected` `:463`, `relative_urls_only` `:473`, `manifests_validate_layers_packs_news_and_player_owned` `:483`, `min_client_build_is_optional_and_unknown_fields_are_ignored` `:504`, `schema_one_requires_the_executable_with_identical_spelling` `:521`, `merge_overrides_and_rejects_aliases` `:532`, `versions_compare_numerically` `:540` |
| `release.rs` (2) | `iso_dates_look_like_javascript` `:438`, `available_serializes_like_the_electron_status_objects` `:445` |
| `client_build.rs` (6) | `parses_the_exact_file_the_client_build_writes` `:92`, `tolerates_crlf_blank_lines_spaces_bom_and_extra_keys` `:97`, `derives_the_missing_half` `:104`, `rejects_garbage` `:112`, `build_numbers_round_trip` `:121`, `reads_from_the_game_folder_and_never_fails` `:132` |
| `config.rs` (7), `settings.rs` (9) | incl. `shipped_defaults_point_at_the_live_https_feed_without_a_key` `tauri/core/src/config.rs:209`, `settings_round_trip_and_malformed_fields_are_dropped` `tauri/core/src/settings.rs:392` |
| `http`/`fsutil`/`instance` (2 each), `packs` (5) | `urls_require_https_except_loopback` `tauri/core/src/http.rs:230`, `the_key_is_basic_auth_for_the_given_user_and_bound_to_the_origin` `:242`, `spelling_aliases_and_symlinks_are_refused` `tauri/core/src/fsutil.rs:291`, `mtime_matches_node_formula` `:316`, `updated_child_reacquires_ownership…` `tauri/core/src/instance.rs:50`, `a_stuck_parent_never_leaves_an_unregistered_full_instance` `:78`, `index_validation_binds_to_the_layer` `tauri/core/src/packs.rs:357` (rest: `install-and-packs.md` §5) |

`tauri/core/tests/feed.rs` (34 `#[tokio::test]`), the ones covering this file: `schema_one_manifests_still_install` `:194`, `hosted_layers_with_unsafe_or_reserved_paths_are_refused`
`:210`, `a_tampered_layer_manifest_is_refused` `:219`, `redirected_feeds_resolve_relative_to_the_final_manifest` `:268`, `the_key_is_sent_on_every_feed_request_and_never_without_one` `:293`,
`a_cross_origin_redirect_drops_the_key_even_when_it_bounces_back` `:316`, `http_401_is_the_access_key_error_and_is_never_retried` `:377`, `installed_record_is_written_only_by_a_clean_install_or_verify` `:423`,
`min_client_build_and_unknown_top_level_fields_pass_through` `:697`.

Node (`npm test`, 13 suites): `test/tauri-versions.test.mjs:9` (formula), `:16` (client from `slums-build.txt`, else the `client` layer), `:33` (note only when a
minimum is named and the client is older; `'10600'`, `-1`, `0`, `1.5`, `null` → no note), `:46`; manifest fixtures in `test/layers.test.mjs`,
`test/releases.test.mjs`, `test/packs.test.mjs`, `test/versions.test.mjs`.

Auto-QA and `!qa`: nothing. `soloMapling/autoqa/AutoQaCatalog.java` and `src/main/java/soloMapling/qa` contain no launcher check, so a bad feed is caught only
by `npm test`, `cargo test` and `--self-test` (`tauri/app/src/launcher.rs:39-80`).

**Not covered:** `write_layer_cache` eviction (only reached indirectly); `Available` → UI wiring beyond the pure Node functions; "installed build <
`minClientBuild` still launches" (no test asserts the refusal path); `build_number` against a real 1.6.2+ `slums-build.txt`.

## Known issues

- **NEW-FINDING: `build_number` computes a number the client no longer emits.** `tauri/core/src/client_build.rs:30-41` implements
  `major*10000 + minor*100 + patch`, but the client's `cmake/BuildStamp.cmake:25` writes `(minor+1)*100 + patch*10` (1.6.2 → 10720). A real
  stamp supplies `build`, so the drift bites only the layer fallback (`tauri/ui/version-status.mjs:30-33`); same stale formula in
  `tauri/devtools/fake-feed.mjs:55-60`.
- **NEW-FINDING: nothing in the publish path writes `minClientBuild`.** `tools/publish.mjs:357-400` builds `{schemaVersion, version, launch, layers, news,
  playerOwned}` and stops; only `tauri/devtools/fake-feed.mjs:68-88` adds it, so `docs/roadmap/1.6.0-plan.md:82` has no live trigger while
  `MIN_CLIENT_BUILD=10740` is.
- **NEW-FINDING: a refused client is only a label.** With the manifest unchanged and the installed build below the minimum, `play_plan`
  (`tauri/core/src/play.rs:17-21`) returns `Verify`, Play launches, the gate refuses, and the launcher still says "Client process started."
  (`tauri/app/src/launcher.rs:746`).
- **NEW-FINDING: `PackSource.sha256` is validated as a digest but never compared** to the downloaded archive (`tauri/core/src/packs.rs:22-29`, `:84`; install
  relies on per-entry hashes, `tauri/core/src/install.rs:411`) — a guarantee the code does not provide.
- `tauri/docs/checkpoint.md:102` claims 85 tests (57 unit + 26 feed + 2 swap) — now 87 + 34 — and `:3` names the branch `launcher-tauri` while the repo is on
  `launcher-165-server`. `b017` (`STATUS-1.6.4.md:70`) — a new launcher exe can be flagged by Defender; process, not code. `o04` (`:130`) — move client
  and launcher builds off Silvia to tarkov2; operational.
- `b270` (`STATUS-1.6.4.md:75`) — a Live publish wrote `manifest.json` without `news[]`, so `slumsms-news@live` failed; that is the optional `news` field
  (`tauri/core/src/manifest.rs:209-232`). `docs/tickets/106-launcher-download-pass.md` (FEATURE 1.6.1) — download pass; code half
  `tauri/core/src/http.rs:14-18` (`channels-and-auth.md`).

## State

**Working.** `load_release` produces verified releases end to end, backed by 87 unit + 34 integration tests; a recorded clean first install put 306 files
down in 1.7 s with every file SHA-256-verified (`tauri/docs/checkpoint.md:18`); schema 1, 2, redirects, tampered layers, unsafe paths and 401 are exercised
(`tauri/core/tests/feed.rs:194-398`); the formats match the Electron launcher's (`tauri/core/src/release.rs:1-4`), so a migrated install keeps its fast
verify (`tauri/core/tests/feed.rs:91`).

**Partial** for the client-build story: the metadata is read and displayed correctly, but the decision it feeds is advisory
only (the first three NEW-FINDINGs).

## Open questions

1. Should the launcher hard-stop Play when the installed build is below a manifest `minClientBuild`? Today only the label changes
   (`tauri/core/src/play.rs:17-21`), so the player learns from the in-game popup.
2. Is `minClientBuild` meant to be published at all? If yes the producer belongs next to `publishLayeredRelease` (`tools/publish.mjs:357`); if no, the field
   and its two test suites are dead weight.
3. Which formula is canonical — the client's `(minor+1)*100 + patch*10` (`cmake/BuildStamp.cmake:25`) or the launcher's `minor*100 + patch`
   (`tauri/core/src/client_build.rs:30-41`)? Both are asserted (`client_build.rs:121`, `test/tauri-versions.test.mjs:9`), so not both can be right for a 1.6.2+ client.
4. `docs/architecture/HOLES.md:82` records the conf contradicting itself (`docs/release/1.6.4.conf:10` says 10721, `:284` sets 10740) — that belongs to
   `client/build-stamp-and-version.md`, but it changes the number the note prints.
