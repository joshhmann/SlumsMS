# Release manifest, layering and client-build metadata

## Purpose

Turns one HTTPS feed URL into a verified, fully resolved release: a top-level manifest, one verified manifest per
layer, one merged file list with absolute URLs, and the hashes that the installer later enforces on every byte. It also
owns the three small bookkeeping files that survive across runs (`state.json`, `installed.json`, the layer cache) and
the client-build metadata the UI uses to say "your client is older than the server accepts". Nothing here downloads or
writes game files — that is `install-and-packs.md`.

## Entry points

- `slums_core::release::load_release(feed_url, install_dir, access, cancel)` (`tauri/core/src/release.rs:133`) — the
  only path that produces a `Release`. Called from the app by Check/Update/Repair/Play
  (`tauri/app/src/launcher.rs:638-682`, `:695-757`).
- `slums_core::release::check_available_release(feed_url, access, timeout)` (`release.rs:255`) — Play's cheap
  pre-flight; reads the top manifest only, never fails. Called at `launcher.rs:699` and on a 10-minute timer
  (`AVAILABLE_INTERVAL`, `launcher.rs:32`).
- `slums_core::manifest::validate_manifest` (`tauri/core/src/manifest.rs:354`) and `validate_layer_manifest`
  (`manifest.rs:379`) — pure validators, also used directly by the `tools/publish.mjs`-produced fixtures in tests.
- `slums_core::client_build::read_client_build(install_dir)` (`tauri/core/src/client_build.rs:72`) — read by the app
  when it builds the "installed" status object; surfaced through `tauri/ui/version-status.mjs:44-60`.
- Config that starts any of this: `feedUrl` in `tauri/launcher.config.json:2` (or `--feed-url=`, a saved
  `settings.json` override, or the channel derivation in `channels-and-auth.md`), plus an access key
  (`config.rs:51-63`). No `-D` switch exists; see §Switches.
- No packet, opcode, tick, scheduler or in-game script touches this code; the only "server side" is the static file
  host the feed lives on and the login gate that reads `MIN_CLIENT_BUILD` (see §Invariants).

## How it works

### Release load (`release.rs:133-196`)

1. **Fetch the top manifest.** `read_bytes(network_url(feed_url)?…, "release manifest", Some(access), cancel, None)`
   (`release.rs:134`) — one GET, redirects followed by `http.rs:124-184`, body capped at `MAX_MANIFEST_BYTES` = 128 MiB
   (`http.rs:20`). The access key is attached as Basic auth only while the request stays on the feed's origin
   (`http.rs:133-135`); a cross-origin hop drops it.
2. **Parse and validate.** `parse_json(&top_bytes, "release manifest")` (`release.rs:135`) then
   `validate_manifest` (`manifest.rs:354-377`): `schemaVersion` must be `1` or `2` (`manifest.rs:356`), `version`
   non-empty (`:359`), `launch` via `validate_launch`, `news` and `playerOwned` optional, `minClientBuild` optional.
   Schema 1 needs `files` + `check_tree` + the launch executable listed with identical spelling (`:368-373`);
   schema 2 needs `layers` (`:375`).
3. **Resolve schema 1 file URLs** against the *final* top-manifest URL (`release.rs:139-145`,
   `top_final = top_url.to_string()` at `:135`), so a redirected feed still resolves relative URLs correctly.
4. **Per layer (schema 2).** For each `LayerRef` (`manifest.rs:165-171`): `request_url = resolve(top_final,
   reference.manifest_url)` (`release.rs:148`); try `cached_layer` (`release.rs:85-98`) which accepts a cached body
   only if its recorded `requestUrl` matches, the body still hashes to the reference SHA-256, and its `finalUrl` passes
   `network_url`; otherwise `read_bytes` (`:155`) and an immediate `sha256_hex(&bytes) != reference.sha256` →
   permanent `"Layer manifest {name}@{version} failed SHA-256 verification."` (`:156-158`).
5. **Validate the layer body against its reference.** `validate_layer_manifest(…, Some((&name,&version)))`
   (`release.rs:162` → `manifest.rs:379-397`): schema must be exactly 2, and name/version must equal the top-level
   reference, else `"Layer manifest {name}@{version} does not match the release reference {en}@{ev}."` (`:390`).
   File URLs then resolve against that layer's own **final** URL (`:165`).
6. **Merge.** `merge_layers` (`manifest.rs:406-429`) — later layer wins; a case-folded path alias between layers is
   fatal; a JS-`Map`-style insertion order is preserved; `check_tree` runs again over the merged set. The launch
   executable must appear in the merge (`release.rs:178`).
7. **Pin the top hash and hand over.** `manifest_sha256 = sha256_hex(&top_bytes)` (`release.rs:185`) — this is the
   identity Play compares against `installed.json`, and it is *not* the feed's ETag. `min_client_build` is copied from
   the parsed manifest (`release.rs:190`).

### What the hashes cover

| Hash | Covers | Enforced at |
|---|---|---|
| top-level `manifestSha256` | the exact bytes of the top manifest | `release.rs:185`; compared to `installed.json` in `play.rs:17-21` |
| per-layer `sha256` (from the top manifest) | the exact bytes of that layer's manifest JSON | `release.rs:156-158` for downloads, `release.rs:95-97` for the cache |
| pack index `sha256` (from the top manifest) | the pack *index* JSON | `install.rs:615-616` |
| per-file `sha256` + `size` | every individual game file | `install.rs:339-341`, `install.rs:411` |
| `PackSource.sha256` for a pack archive | only the file name/index binding — the downloaded pack bytes themselves are **not** hashed | validated as 64-hex at parse (`packs.rs:82`), never compared to the archive |
| layer-cache file | re-hashed on read | `release.rs:95-97` |

So verification is layered: manifest → layer manifest → (per file) → optionally pack index → per file again. A pack
archive is trusted as a transport; per-entry digests inside it are the enforcement (`install-and-packs.md` §5-6).

### Writing to disk

8. **Layer cache.** `write_layer_cache(root, &release)` (`release.rs:100-124`) writes
   `.slumsms-launcher/layers/{sha256}.json` per non-cached layer as `{requestUrl, finalUrl, body}` via
   `write_small_file` (temp + rename, `fsutil.rs:240-252`), then deletes every `.json` in that directory not in the
   keep set (`release.rs:117-124`). Empty `layer_sources` (schema 1) returns early (`:102-104`).
9. **`state.json`** — `.slumsms-launcher/state.json` (`release.rs:17`), `{stateVersion:1, release, layers, files:{path:
   {size, mtimeMs, sha256, server?}}}`, written by `write_install_state` (`release.rs:319-334`) after an install or
   verify. `read_install_state` (`release.rs:298-317`) treats anything unreadable as empty — it is a cache only.
10. **`installed.json`** — `.slumsms-launcher/installed.json` (`release.rs:18`), `recordVersion:1, release,
    manifestSha256, layers, verifiedAt, launch`, written by `write_installed_record` (`release.rs:408-419`) and skipped
    when unchanged (`record_installed`, `release.rs:417-433`). An empty `manifest_sha256` means "the launcher's own
    channel", which is never recorded (`:422-424`). `read_installed_record` returns `None` for anything malformed, so
    the UI shows "Unknown" rather than guessing (`release.rs:358-386`).
11. **Game files** are never written here. `load_release` is side-effect-free apart from the layer cache.

### Client build metadata (`client_build.rs`)

12. `read_client_build` reads `slums-build.txt` beside `slums.dll` (`BUILD_FILE`, `client_build.rs:17`), at most 4096
    bytes (`:19`), strips a BOM, tolerates CRLF/blank lines/extra keys (`parse_build_file`, `:50-70`). Values are
    `release` (≤32 printable ASCII) and `build` (≤9 digits, >0); a missing half is derived (`:60-66`), and `build` wins
    over a `release` that disagrees.
13. `build_number("1.6.0") = 1*10_000 + 6*100 + 0 = 10600` (`client_build.rs:30-41`). Anything that is not
    `MAJOR.MINOR.PATCH` with major ≤ 9999, minor ≤ 99, patch ≤ 99 returns `None`.
14. Older clients have no build file; the UI then falls back to the installed record's `client` layer version
    (`tauri/ui/version-status.mjs:44-50`).

### The `minClientBuild` end-to-end behaviour (acceptance item 4)

15. Server side, the login gate refuses `clientBuild < MIN_CLIENT_BUILD`
    (`src/main/java/net/server/handlers/login/ClientVersionGate.java:47-53`, `:110-118`); live 1.6.4 sets
    `MIN_CLIENT_BUILD=10740` (`docs/release/1.6.4.conf:284`).
16. Launcher side the field is **optional and non-fatal**: `min_client_build` (`manifest.rs:347-352`) accepts only
    integers in `1..=99_999_999`, and the doc comment says so explicitly — "Only a display hint (the game server enforces
    it at login), so a missing or malformed value means \"no check\" and never makes the release unusable."
    (`manifest.rs:347-348`). `"10600"`, `-1`, `0`, `1.5`, `null`, `[10600]` all become `None` (test `manifest.rs:504-516`).
17. It reaches the UI as `minClientBuild` on the availability object
    (`release.rs:207-208`, `launcher.rs:1121`, `:1126`), and `clientUpdateNote`
    (`tauri/ui/version-status.mjs:52-60`) returns `"Client update needed: the server needs build N or newer"` only when
    the field is a positive integer, the folder is installed, and the installed build is known and older.
18. Consequence: an installed client below `MIN_CLIENT_BUILD` is **not blocked and not specially updated**. The
    renderer relabels the ready state to "Client update needed" (`tauri/ui/renderer.mjs:388-392`) and Play takes its
    normal path — `play_plan` compares only `manifest_sha256` (`tauri/core/src/play.rs:17-21`), so if the client layer
    version did not change the launcher starts the old exe and the **server** refuses the login. The launcher never
    reads `MIN_CLIENT_BUILD` from anywhere; the only producer of the field is the dev fixture
    `tauri/devtools/fake-feed.mjs:68-88`.

### Rejecting a bad manifest

Permanent errors, i.e. no retry (`error.rs:31`, `:38`): unsupported `schemaVersion` (`manifest.rs:356`), missing
version (`:359`), unsafe/reserved path (`manifest.rs:49-73`), Windows case-alias or path collision (`manifest.rs:293-313`,
`manifest.rs:406-429`), URL not relative/same-origin (`manifest.rs:103-126`), malformed digest (`manifest.rs:128-134`),
duplicate layer names (`manifest.rs:314-348`), executable not listed (`manifest.rs:370-373`, `release.rs:178`), layer
hash mismatch (`release.rs:156-158`), layer name/version mismatch (`manifest.rs:390`). Retryable network/5xx/408/425/429
and 401/403-on-feed-origin-→-access-key are distinguished in `http.rs:163-175`.

## Data

| File (relative to the game folder) | Shape | Written by | When |
|---|---|---|---|
| `.slumsms-launcher/state.json` | `stateVersion:1, release, layers[], files{path:{size,mtimeMs,sha256,server?}}` (`release.rs:280-290`, `:319-334`) | `write_install_state` | after every install/verify pass |
| `.slumsms-launcher/installed.json` | `recordVersion:1, release, manifestSha256, layers[], verifiedAt, launch{executable,args}` (`release.rs:339-347`, `:408-419`) | `record_installed` | only a clean full verify/install (`install.rs:884`) |
| `.slumsms-launcher/layers/{sha256}.json` | `requestUrl, finalUrl, body` (`release.rs:69-75`) | `write_layer_cache` | end of `load_release`'s caller, `install.rs:883` |
| `slums-build.txt` (beside `slums.dll`) | `release=` / `build=` lines (`client_build.rs:50-70`) | the game client's build, not the launcher | read-only here |

`mtimeMs` is `f64` because it must match Node's `stat.mtimeMs` bit-for-bit; `serde_json`'s `float_roundtrip` feature is
enabled for exactly this (`tauri/core/Cargo.toml:10-11`, reason recorded in `tauri/docs/checkpoint.md:32`). Sizes and
digests come from the manifest, never from the local file, except in Fast mode where size+mtime are trusted
(`install.rs:126-131`).

In memory a `Release` (`release.rs:54-67`) carries the merged `files: Vec<FileEntry>` (path, size, sha256, absolute URL,
layer), the per-layer `layer_sources` (raw bytes, kept so the cache can be written later) and `layer_parts` (per-layer
file list + optional `PacksSource`) for the pack planner. Nothing is memoised across calls except the files above.

## Switches

The launcher has no `-D` surface. Its knobs are `tauri/launcher.config.json` (shipped, edited only by a build) and the
player's `settings.json`.

| Key | Default | Where |
|---|---|---|
| `feedUrl` | `https://slumspatch.asslorde.com/manifest.json` | `tauri/launcher.config.json:2`; validated `config.rs:25-35` (https, or http only on loopback, no credentials) |
| `clientExecutable` | `SlumsMS.exe` | `tauri/launcher.config.json:3`; must be a bare name (`config.rs:88-93`) |
| `downloadConcurrency` | `12` | `tauri/launcher.config.json:4`, `config.rs:8`, range 1..=32 (`config.rs:100-104`) |
| `accessKey` | `""` — none shipped | `tauri/launcher.config.json:5`; a saved key wins, a built-in key only for the built-in origin (`config.rs:51-63`) |
| `minClientBuild` (manifest) | absent | `manifest.rs:347-352`; the **live feed does not set it** — no publisher writes it (`tools/publish.mjs:357-400`) |
| `MIN_CLIENT_BUILD` (server) | `0` off | `src/main/java/config/ServerConfig.java:61`; live 1.6.4 = `10740` (`docs/release/1.6.4.conf:284`) |

Live vs TEST: the launcher's Live/RC/Test feeds differ only by channel path (see `channels-and-auth.md`); this doc's
code paths are identical on all three. Server-side `MIN_CLIENT_BUILD` for TEST: unknown (`start-test.sh` was not read).
The live 1.6.4 flag set is the 69-flag enumeration in `docs/release/1.6.4.conf:271`; nothing there reaches the launcher
except through `MIN_CLIENT_BUILD`.

## Invariants

1. **A `Release` only exists if every layer manifest hashed to the reference in the top manifest.** Enforced at
   `release.rs:156-158`; the cache path re-checks the same digest on every read (`release.rs:95-97`).
2. **The layer cache can never leak a body from another URL.** `request_url` must match and `final_url` must still pass
   `network_url` (`release.rs:86-98`).
3. **Relative URLs are always resolved against the URL that actually answered**, not the requested one
   (`release.rs:135`, `:165`; test `tauri/core/tests/feed.rs:268`).
4. **No two manifest paths may collide on Windows** — case-folded trie, NFC-normalised, UTF-16 length accounting
   (`manifest.rs:16-24`, `:293-313`), re-checked after merge (`manifest.rs:427`).
5. **Nothing may write into `.slumsms-launcher/` or a staging directory**: `managed_path` rejects those first
   components (`manifest.rs:66-73`), so a manifest cannot overwrite the bookkeeping that validates it.
6. **The access key never leaves the feed's origin** (`http.rs:133-135`; tests `feed.rs:293`, `:316`, `:362`).
7. **`load_release` never mutates the game folder.** Every write is to `.slumsms-launcher/` (`release.rs:100-124`).
8. **Malformed optional metadata degrades, it never blocks.** `minClientBuild` (`manifest.rs:347-348`), `news`
   (`manifest.rs:209-232`), `state.json` (`release.rs:298-300`) and `installed.json`
   (`release.rs:358-362`) all fall back rather than error.
9. **`manifest_sha256` is the identity of a release**, so a re-published manifest with identical files but different
   news forces a re-check (Play compares it in `play.rs:20`).
10. **`slums-build.txt` reading never fails and never blocks Play** (`client_build.rs:1-11`, `:72-78`).

## Origin

The whole launcher is custom: it post-dates the base v83 server, and the oldest commit in the repo is
`76c42a8` (2026-10-18) for the launcher/publisher work. Per-file (`git log --follow --format=%h | tail -1`):

| Path | First commit |
|---|---|
| `tauri/core/src/manifest.rs`, `release.rs`, `config.rs`, `error.rs`, `fsutil.rs`, `http.rs`, `lib.rs`, `packs.rs`, `settings.rs` | `bd3df88` "Start the Tauri launcher: Rust core, ported UI and app shell" (2026-10-21) |
| `tauri/core/src/client_build.rs` | `76a0648` "Read slums-build.txt and the optional minClientBuild manifest field in core" (2026-10-25) |
| `tauri/core/src/instance.rs` | `5c91c86` "Fix launcher folder isolation, update ownership, migration and metadata hangs" |
| `tauri/app/src/launcher.rs` | `bd3df88` |
| `tauri/launcher.config.json` | `80a3be1` "feat: simplify launcher flow with built-in defaults and release news" (2026-10-18) |
| `tauri/docs/checkpoint.md`, `tauri/docs/handover-plan.md` | `fdc98a0` "Docs, owner QA script, and wait for the channel probe before a Test/RC check" |

`manifest.rs`/`release.rs` are ports of the Electron launcher's `manifest.mjs`/`releases.mjs` (header comment
`release.rs:1-4` states the on-disk formats are byte-compatible), which is why the Rust and Node suites assert the same
fixtures. There is no base/repack lineage to diff against, so `docs/architecture/base-vs-custom.md` needs no entry here
beyond "custom".

## Tests and QA

Rust unit tests (`cargo test -p slums-core`, 87 tests total in that crate):

| Module | Tests |
|---|---|
| `manifest.rs` | `unsafe_paths_are_rejected` `:463`, `relative_urls_only` `:473`, `manifests_validate_layers_packs_news_and_player_owned` `:483`, `min_client_build_is_optional_and_unknown_fields_are_ignored` `:504`, `schema_one_requires_the_executable_with_identical_spelling` `:521`, `merge_overrides_and_rejects_aliases` `:532`, `versions_compare_numerically` `:540` |
| `release.rs` | `iso_dates_look_like_javascript` `:438`, `available_serializes_like_the_electron_status_objects` `:445` |
| `client_build.rs` | `parses_the_exact_file_the_client_build_writes` `:92`, `tolerates_crlf_blank_lines_spaces_bom_and_extra_keys` `:97`, `derives_the_missing_half` `:104`, `rejects_garbage` `:112`, `build_numbers_round_trip` `:121`, `reads_from_the_game_folder_and_never_fails` `:132` |
| `config.rs` | `:132`, `:147`, `:161`, `:171`, `:185`, `:195`, `shipped_defaults_point_at_the_live_https_feed_without_a_key` `:209` |
| `http.rs` | `urls_require_https_except_loopback` `:230`, `the_key_is_basic_auth_for_the_given_user_and_bound_to_the_origin` `:242` |
| `fsutil.rs` | `spelling_aliases_and_symlinks_are_refused` `:291`, `mtime_matches_node_formula` `:316` |
| `instance.rs` | `updated_child_reacquires_ownership_and_later_launches_are_secondary` `:50`, `a_stuck_parent_never_leaves_an_unregistered_full_instance` `:78` |
| `packs.rs` | `group_packs_is_deterministic_dedupes_and_isolates_big_files` `:351`, `index_validation_binds_to_the_layer` `:357`, `planner_takes_whole_packs_for_a_first_install_and_per_file_for_small_updates` `:391`, `planner_splits_ranges_at_big_gaps_and_takes_shadowed_content_once` `:408`, `rate_meter_and_messages` `:424` |
| `settings.rs` | `:279`, `:301`, `:327`, `:339`, `:357`, `:376`, `:386`, `settings_round_trip_and_malformed_fields_are_dropped` `:392`, `asar_files_are_read_from_the_packed_archive` `:413` |

Integration (`tauri/core/tests/feed.rs`, 34 `#[tokio::test]`) covering this doc directly:
`schema_one_manifests_still_install` `:194`, `hosted_layers_with_unsafe_or_reserved_paths_are_refused` `:210`,
`a_tampered_layer_manifest_is_refused` `:219`, `redirected_feeds_resolve_relative_to_the_final_manifest` `:268`,
`the_key_is_sent_on_every_feed_request_and_never_without_one` `:293`,
`a_cross_origin_redirect_drops_the_key_even_when_it_bounces_back` `:316`, `http_401_is_the_access_key_error_and_is_never_retried`
`:377`, `availability_reads_only_the_top_manifest_and_never_fails` `:398`,
`installed_record_is_written_only_by_a_clean_install_or_verify` `:423`,
`min_client_build_and_unknown_top_level_fields_pass_through` `:697`.

Node (`npm test`, 13 `test/*.test.mjs`): `tauri-ui` neighbour `test/tauri-versions.test.mjs:9` (the build-number
formula), `:16` (client from `slums-build.txt`, else the `client` layer), `:33` (the note is shown only when the server
names a minimum and the client is older; `'10600'`, `-1`, `0`, `1.5`, `null` → no note), `:46`; manifest shape fixtures
in `test/layers.test.mjs`, `test/releases.test.mjs`, `test/versions.test.mjs`, `test/packs.test.mjs`.

Auto-QA / `!qa` scenarios: none. `soloMapling/autoqa/AutoQaCatalog.java` and `src/main/java/soloMapling/qa` are server
sides and contain no launcher check (grep for "launcher"/"manifest" in them is empty), so a bad feed is caught only by
`npm test`/`cargo test` and by `--self-test` (`tauri/app/src/launcher.rs:39-80`).

**Not covered:** the layer-cache eviction and `write_layer_cache` itself (no test drives it — `scripts`/`tests` only
exercise `cached_layer` indirectly through a second `load_release`); `Available` → UI wiring beyond the pure Node
functions; the behaviour "installed build < `minClientBuild` and manifest unchanged" (nothing tests that Play still
launches); the Rust `build_number` against a real 1.6.2+ `slums-build.txt` (see Known issues).

## Known issues

- **NEW-FINDING: `build_number` computes a number the client no longer emits.** `client_build.rs:30-41` implements
  `major*10000 + minor*100 + patch`, but the client's `cmake/BuildStamp.cmake:25` now writes
  `major*10000 + (minor+1)*100 + patch*10` (1.6.2 → 10720). For a real stamp the launcher's own derivation is unused
  (the file supplies `build`), but `version-status.mjs:30-33` also derives from the installed *layer* version, so a
  pre-1.6.0 folder reports 10700 where the server expects 10720. Same drift in `tauri/devtools/fake-feed.mjs:55-60`.
- **NEW-FINDING: nothing in the publishing path ever writes `minClientBuild`.** `tools/publish.mjs:357-400` builds
  `{schemaVersion, version, launch, layers, news, playerOwned}` and never the field; the only producer is the fake feed
  (`tauri/devtools/fake-feed.mjs:68-88`). So `docs/roadmap/1.6.0-plan.md:82`'s "makes the launcher say client update
  needed" has no live trigger, while `MIN_CLIENT_BUILD=10740` is live.
- **NEW-FINDING: a refused client is only a label.** With the manifest unchanged and the installed build below the
  minimum, `play_plan` (`tauri/core/src/play.rs:17-21`) returns `Verify`, Play launches, and the login gate refuses —
  the launcher reports "Client process started." (`launcher.rs:746`). Worth an owner decision: fail before launch.
- **NEW-FINDING: `PackSource.sha256` is parsed and validated as a digest but never compared** to the downloaded pack
  archive (`packs.rs:22-29`, `packs.rs:82`; install trusts per-entry hashes, `install.rs:411`). Harmless today, but the
  field advertises a guarantee the code does not provide.
- `tauri/docs/checkpoint.md:102` still claims 85 tests (57 unit + 26 feed + 2 swap); the crate now has 87 unit and 34
  feed tests. `checkpoint.md:3` also names the branch `launcher-tauri`, while the repo is on `launcher-165-server`.
- `b017` (S3 Launcher, `STATUS-1.6.4.md:70`) — a new launcher exe can be flagged by Defender; process, not code.
- `b270` (S3 Client, `STATUS-1.6.4.md:75`) — a Live publish wrote `manifest.json` without `news[]` and the
  `slumsms-news@live` check failed; that is the `news` optional field on the release manifest.
- `o04` (`STATUS-1.6.4.md:130`) — client and launcher builds should move off Silvia to tarkov2; operational only.
- `docs/tickets/106-launcher-download-pass.md` (FEATURE 1.6.1) — access-key pass at download time; the code half is in
  place (`http.rs:14-18`) and is covered by `channels-and-auth.md`.

## State

**Working.** Evidence: `load_release` produces verified releases end to end with 87 unit + 34 integration tests and a
recorded clean first install of 306 files in 1.7 s with every file SHA-256-verified (`tauri/docs/checkpoint.md:18`);
schema 1, schema 2, redirected feeds, tampered layers, unsafe paths and 401 are all exercised by
`tauri/core/tests/feed.rs:194-398`; the on-disk formats are deliberately identical to the Electron launcher's
(`release.rs:1-4`), so a migrated install keeps its fast verify (`feed.rs:91`).

**Partial** for the client-build story: the metadata is read and displayed correctly, but the *decision* it feeds is
advisory only (Known issues, NEW-FINDINGs 1-2).

## Open questions

1. Should the launcher hard-stop Play when the installed build is below a manifest `minClientBuild`? Today the label
   changes and the launch proceeds (`tauri/core/src/play.rs:17-21`), so the player learns only from the in-game popup.
2. Is `minClientBuild` meant to be published at all? If yes, the producer must be added to `tools/publish.mjs` next to
   `publishLayeredRelease` (`tools/publish.mjs:357`); if no, the field and its two test suites are dead weight.
3. Which formula is canonical for `buildNumber` — the client's new `(minor+1)*100 + patch*10`
   (`cmake/BuildStamp.cmake:25`) or the launcher's `minor*100 + patch` (`client_build.rs:30-41`)? Both are asserted by
   tests today (`client_build.rs:121`, `test/tauri-versions.test.mjs:9`), so they cannot both be right for the 1.6.2+
   client.
4. `docs/architecture/HOLES.md:82` records the conf contradiction — header `:10` says `MIN_CLIENT_BUILD` stays 10721,
   `:284` sets 10740. That belongs to `client/build-stamp-and-version.md`; recorded here only because it changes the
   number the launcher's note would print.
