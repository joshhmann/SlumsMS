# Install, packs, hashing and atomic apply

## Purpose

Takes a verified release manifest and makes a game folder match it: hashes what is already there, downloads only what is
missing or changed (in parallel, resumable, from loose files or from large immutable "packs"), stages every byte outside
the game tree, and moves each file into place only after its SHA-256 matches. It leaves behind the bookkeeping files
that make the next check cheap (`.slumsms-launcher/state.json`, `installed.json`).

## Entry points

- Commands (`tauri/app/src/launcher.rs`): Check (:1283), Update (:1303), Repair (:1311, `Mode::Full`), Play (:1319), all
  under `begin()` (:569-573), which refuses a second concurrent operation with "A launcher operation is already running."
- `check_release` (launcher.rs:638-655) calls `inspect_release` only; `install_files` (launcher.rs:658-682) calls
  `install_release` with `InstallOptions { mode, concurrency: config.download_concurrency, ..Default::default() }`
  (launcher.rs:666) — the only field the app overrides. `play_flow` (:695-757) sends a newer release through
  `install_files(Mode::Fast)` (:720), else `recently_verified` or `check_release` + repair (:722-732).
- Nothing else calls the installer: no CLI, no tick, no server-side component.

## How it works

1. **Bounds, then inspect in `spawn_blocking`.** `install_release` refuses concurrency or pack concurrency outside 1..32
   (install.rs:674-680), then runs `inspect` (install.rs:681-684 → install.rs:83-206) over every manifest file, resolving
   `destination` first (install.rs:104):
   - absent → pending (install.rs:107);
   - **player-owned** (`release.player_owned`, default `config.ini`): keep the player's copy and mark it verified when
     `state.json` records the same manifest hash (install.rs:112-116); else, for `config.ini`, remember the recorded
     server and make it pending so the channel's server can be merged (install.rs:118-121);
   - **size differs** from the manifest → pending (install.rs:122-124);
   - **Fast mode** and `state.json` size+mtime match → trust the recorded hash (install.rs:126-131);
   - otherwise queue for hashing (install.rs:133).

   Queued files are SHA-256'd by 4 scoped threads (install.rs:147-180); `digest` returns `None` if a file changed
   mid-read (fsutil.rs:198-219). `kept_bytes = manifest total − pending bytes` (install.rs:202) is what a resume counts.
2. **Short-circuit when nothing is pending**: write `state.json`, the layer cache and `installed.json`, return
   (install.rs:690-695); `inspect_release` does the same for a bare Check (install.rs:221-239, "the state is only a
   cache" at install.rs:226).
3. **Free-space check** before a byte is fetched: `needed = pending + min(pending, 512 MiB)` (install.rs:696-700,
   `ensure_disk_space` install.rs:641-655); failure carries `DISK_FULL` (install.rs:651). `remove_stale_stages` then
   deletes `.mapleroot-stage-*` older than one hour (install.rs:657-671; `STALE_STAGE` install.rs:32) and this run's
   `.mapleroot-stage-<6 hex>` is created inside the game folder (install.rs:702-703).
4. **Decide packs vs per-file.** Packs need `options.packs` (always true from the app), `pending.files.len() >= 32`
   (`PACK_MIN_FILES`, install.rs:31) and a `packs` reference on some layer (install.rs:740). Indexes are then fetched and
   validated (`load_pack_sources`, install.rs:609-631): the bytes must hash to the SHA-256 pinned in the top manifest
   (install.rs:615-616), and `validate_pack_index` re-checks schema, layer/version, the layer manifest SHA-256, unique
   URLs, non-repeated file indexes and each pack's declared size (packs.rs:47-87). An unusable index is downgraded to a
   note — "Packs for {layer} are unavailable (…); downloading its files one by one." (install.rs:630) — except
   access-key errors and cancellation, which propagate (install.rs:629).
5. **Plan.** `plan_packs` (packs.rs:145-209) prices a span as `end − start + 128 KiB` per request
   (`DEFAULT_REQUEST_COST`, packs.rs:11) against one request per file, keeps only packs with a positive saving, merges
   entries whose gap is under one request (packs.rs:128-143) and keys content by `sha256:size` so an identical file
   served by two layers is fetched once (packs.rs:89-91, packs.rs:148).
6. **Download packs**, 4 at a time (`for_each_concurrent(options.pack_concurrency)`, install.rs:755-759). Each span is
   an HTTP `Range: bytes=<pos>-<end-1>` request (install.rs:435) validated by `range_start` (install.rs:346-374). Bytes
   stream into per-file temporaries in the stage dir, hashed as they arrive; a file completes when its SHA-256 matches
   (install.rs:411), then `on_file` installs it — duplicates are `fs::copy`'d from that same temporary
   (install.rs:774-779). A dropped connection resumes at the exact byte; the retry budget of 8 refills to 1 whenever an
   attempt moved at least 1 MiB (install.rs:484-489). A damaged pack is abandoned and what it owed moves to the per-file
   list ("A pack could not be used (…); downloading its files one by one.", install.rs:799-810); the gate at
   install.rs:795 aborts the install only for cancellation, non-permanent errors, a missing access key or a local error
   other than the SHA-256 mismatch, so a member failing its hash (install.rs:410-412) counts as a damaged pack.
7. **Download the rest per file**, `options.concurrency` at a time (install.rs:822-827), each into its own stage
   temporary with the same stall detection and backoff. `download` (install.rs:320-343) has three verdicts: an oversized
   body is permanent "Download exceeds declared size" (install.rs:326-328), a short body is transient "Download ended
   early" (install.rs:335-337), a full-size body with the wrong SHA-256 is permanent "Downloaded file failed size/SHA-256
   verification" (install.rs:338-340). A discarded attempt removes its temporary and subtracts its received bytes from
   the progress counter (install.rs:843-845); permanent and local errors end that file's attempt at once (install.rs:846-848).
8. **Commit.** Only now are replacements applied: every file that existed before was deferred (install.rs:583-585) and is
   renamed into place here, with the comment "No existing file is replaced until every download has passed verification."
   (install.rs:875-881). `commit_locked` (install.rs:557-578) is the only writer: it resolves the target, merges the
   channel's server into a player `config.ini` unless the player hand-edited the server (install.rs:563-571), `fs::rename`s
   the temporary (install.rs:573) and records the post-install size/mtime in `state.json` (install.rs:575-576).
9. **Persist** `state.json`, the layer cache and `installed.json` (install.rs:882-884). On failure `state.json` is saved
   anyway so a resume need not re-hash committed files (install.rs:886-891); the stage dir is always removed (install.rs:892).

### On-disk layout produced

```
<game folder>/
  SlumsMS.exe, slums.dll, Data/...   manifest files, renamed in from the stage directory
  config.ini                         player-owned; only host/port come from the channel
  .slumsms-launcher/                 launcher bookkeeping (manifest.rs:11)
    state.json                       {stateVersion, release, layers, files:{path:{size,mtimeMs,sha256}}} (release.rs:277-289)
    installed.json                   {recordVersion, release, manifestSha256, layers, verifiedAt, launch} (release.rs:408-418)
    layers/<sha>.json                verified layer manifest bodies, pruned on write (release.rs:100-124)
  .mapleroot-stage-<6 hex>/          per-run temporaries; deleted at the end, swept after 1 h (install.rs:657-671)
```

## Data

- `state.json` — per-managed-file `{size, mtimeMs, sha256, server}` (release.rs:279-289), read at release.rs:298,
  written by `write_install_state` (release.rs:319) after an install and checkpointed every 5 s during downloads
  (`STATE_CHECKPOINT` install.rs:34; checkpoint install.rs:589-592). A cache: losing it costs a re-hash only (install.rs:226).
- `installed.json` — the record Play trusts; written only by a clean install/verify (`record_installed`,
  release.rs:421-433), read at Play (launcher.rs:700). The layer cache beside it keys manifest bodies by SHA-256 so
  unchanged layers are not re-fetched (release.rs:85-97).
- Pack index — `layers/<name>/<version>/packs/index.json`,
  `{schemaVersion:1, layer, version, manifestSha256, packs:[{url,size,sha256,files:[index…]}]}`; a pack concatenates
  those manifest files, so each offset is the sum of the sizes before it (packs.rs:47-87, README.md:134).
- Nothing about an install lives outside the game folder except the launcher's own settings (download concurrency,
  install dir) in its app-data config (config.rs:14).

## Switches

- `downloadConcurrency` — `launcher.config.json`, integer 1..32, default 12 (`DEFAULT_CONCURRENCY`, install.rs:26;
  validated at config.rs:100-105; default at config.rs:149). Per-file pool only.
- `InstallOptions.packs` — code-level, default `true` (install.rs:257-266); nothing in config or UI sets it, so live
  launchers always try packs. Pack parallelism (4, install.rs:29), retries (4 file / 8 pack), stall timeout (60 s,
  install.rs:28) and `STATE_CHECKPOINT` are likewise invisible to players.
- Publishing side: `publish.mjs --packs NAME@VERSION [--pack-size MiB]` (1..4096, publish.mjs:605-621) builds `packs/`;
  `--no-packs` is valid only with `--release` (publish.mjs:626) and is forwarded by `tools/release/publish-client:73-75,
  :123`.
- The server repo's live switch set (`docs/release/1.6.4.conf:271`) says nothing about installs — the launcher is
  configured by its own files, not `-D` or `config.yaml`. TEST: unknown (start-test.sh not read). Secrets
  (`launcher.config.json`, `config.yaml` access key) are named by path only, never read here.

## Invariants

- **Writes stay inside the game folder** — every path goes through `destination` (fsutil.rs:161-182) after
  `installation_root` (fsutil.rs:125-153); both refuse symlinks and Windows case aliases (`check_spelling`,
  `directory_child`, fsutil.rs:73-118). Manifest paths are pre-validated as relative, alias-free and outside
  `.slumsms-launcher`/`.mapleroot-stage-*` (manifest.rs:49-73).
- **A file only lands after its hash matches** — per-file size and SHA-256 streamed and compared (install.rs:337-340),
  per pack member an entry SHA-256 before `on_file` (install.rs:410-412).
- **No existing file is replaced until every download verified** — replacements are deferred in `place`
  (install.rs:583-585) and applied after the whole download phase (install.rs:875-881).
- **Pack index and layer manifest are bound to each other** — index SHA pinned in the top manifest (install.rs:615),
  `manifestSha256` compared to the layer manifest (packs.rs:55-58), layer+version compared (packs.rs:52-54).
- **Player-owned files are never overwritten from the layer** — `inspect` keeps them (install.rs:112-121);
  `commit_locked` re-merges only host/port for `config.ini` (install.rs:563-571), preserving every other byte
  (game_config.rs:120).
- **One install at a time** — `begin()` (launcher.rs:569-573) plus a single-instance process guard
  (tauri/app/src/single_instance.rs:5).
- **Disk errors are not retried** — `Error::io` marks local+permanent (error.rs:52-55); both download loops return
  immediately on `local` (install.rs:480-481, :846-847). There is no separate permission path: parents are created lazily
  inside `destination(…, create=true)` (fsutil.rs:161-175), so an unwritable folder surfaces as the first write's
  `Error::io` — permanent, reported, not retried; a path component that is a file or symlink is refused outright
  (fsutil.rs:112-113, fsutil.rs:176-178).
- **Free space is checked once, best effort** — `ensure_disk_space` returns `Ok` when the platform cannot answer
  (`let Some(free) = free else { return Ok(()) }`, install.rs:642) and otherwise demands `bytes + min(bytes, 512 MiB)`
  (install.rs:643); `free_disk_bytes` walks up to the nearest existing ancestor (fsutil.rs:255-263).

## Origin

Custom — the whole launcher is new work for this project, ported from its own earlier Electron code (`//! Port of
inspect/inspectRelease/installRelease from updater.mjs`, install.rs:1-6; packs.rs:1-2 "Port of packs.mjs"). Git evidence
in `/root/work/launcher-165-server`:

- `tauri/core/src/install.rs` — oldest `bd3df88` (2026-10-21, "Start the Tauri launcher: Rust core, ported UI and app
  shell"), 3 commits, latest `f14e813` (2026-11-05, "Keep a server the player set by hand"). `packs.rs`, `fsutil.rs` and
  `manifest.rs` are single-commit `bd3df88`.
- JS origin: `src/packs.mjs` oldest `5f22974` (2026-10-21, "Install big downloads from resumable packs");
  `tools/publish.mjs` oldest `fcd6e08` (2026-10-19, the repo's first commit).
- Server repo: `tools/release/stage-client` oldest `6a863e7be` (2026-10-21); `publish-client` and `publish-lib.sh`
  oldest `9d437b372` (2026-10-20).

## Tests and QA

Rust (`cargo test -p slums-core`, `tauri/core/tests/feed.rs`, harness in `tauri/core/tests/support/mod.rs`):

- Install/verify (:53, :74, :91, :121, :136, :423): `first_install_layers_override_and_record_installed`,
  `only_layers_whose_version_changed_are_downloaded`, `fast_verify_trusts_size_and_mtime_and_repair_hashes_everything`,
  `player_owned_config_installs_once_survives_updates_and_repair`,
  `config_ini_takes_its_server_from_the_channel_and_keeps_the_players_options`,
  `installed_record_is_written_only_by_a_clean_install_or_verify`.
- Refusals (:227, :255, :210, :219): `per_file_downloads_retry_transient_failures_and_stop_on_404`,
  `a_corrupt_payload_never_replaces_an_existing_file`, `hosted_layers_with_unsafe_or_reserved_paths_are_refused`,
  `a_tampered_layer_manifest_is_refused`.
- Packs (:462-604): duplicate and zero-byte files (:462), resume at the exact byte after a cut (:476), stall-timeout
  resume (:489), server without Range support (:500), damaged pack falls back per file (:510), interrupted first install
  (:542), disk-space refusal before any request (:564), small update stays per-file while shadowed base content comes
  from base packs (:576), cancellation keeps finished files (:596).
- Unit: packs.rs:351/357/391/408/424 (grouping, index binding, planner, rate meter); fsutil.rs:291 (spelling aliases and
  symlinks refused), fsutil.rs:316 (mtime formula). JS: `node --test` (package.json:12) with `test/packs.test.mjs`,
  `test/tauri-publish.test.mjs`, `test/layers.test.mjs`.

Auto-QA: only `t-la-1` "The Tauri launcher's own checklist", a MANUAL check
(`src/main/java/soloMapling/autoqa/AutoQaCatalog.java:783`, listed MANUAL in
`src/main/java/soloMapling/autoqa/ClientScriptVerdicts.java:17`). **Not covered anywhere automated:** a real Windows
folder with locked files or a running client, a non-writable install directory, a >4 GiB single file, packs served
through a redirect/CDN, and the live patch host itself.

## Known issues

- b017 (S3, Launcher) — Windows Defender can flag a new launcher exe; a submission process, not installer code
  (`/root/work/STATUS-1.6.4.md:70`).
- b052 (S4, Ops) — `publish-client` publishes the layer before checking the news list and then cannot retry
  (`/root/work/STATUS-1.6.4.md:71`).
- b270 (S3, Client) — a Live client publish wrote `manifest.json` without `news[]`; the publish path around the
  installer's feed (`/root/work/STATUS-1.6.4.md:75`).
- o04 (Ops, later) — move client and launcher builds from Silvia to tarkov2 (`/root/work/STATUS-1.6.4.md:130`).
- Ticket 20 (open, target 1.6.0) — packed first install for remote friends; the mechanism exists, the server repo still
  publishes with `--no-packs`.
- Ticket 106 (open, 1.6.1) — replace the shared patch key with a per-account download pass
  (`docs/tickets/106-launcher-download-pass.md`).
- NEW-FINDING: the pack-level `sha256` from the index is never verified. `validate_pack_index` stores it (packs.rs:84)
  and `plan_packs` carries it, but nothing compares downloaded bytes to it — only each member file's SHA-256
  (install.rs:411) and the index's own hash (install.rs:615). The manifest still guarantees the *set* of files, so this
  is a hardening gap, not a corruption path.
- NEW-FINDING: no "game is running" guard. `GAME_RUNNING` exists (error.rs:10) but only guards writes of game options
  (`game_config.rs:142-148`, used at launcher.rs:899/922); `install_release` will rename over `slums.dll` in a folder a
  client is using and rely on Windows sharing violations — which `Error::io` turns into a permanent, non-retried
  failure (error.rs:52-55).
- NEW-FINDING: a hand-set server is recognised only from `state.json`'s `server` field, so a player who edits
  `config.ini`'s host/port on a fresh machine (no state entry) has it replaced by the channel's value once
  (install.rs:563-569; `recorded_server` defaults to `None`).
- NEW-FINDING: `BACKLOG-1.6.0.md:83` still says ticket 20 is "not started | no chunk/pack code in server repo" — stale,
  packs exist in the launcher repo.

## State

**Partial.** The installer is complete and covered: 34 tests in `tauri/core/tests/feed.rs` drive first install, update,
repair, resume, cancellation, pack fallback and disk-space refusal against a local server that reproduces the
publisher's layout (`tauri/core/tests/support/mod.rs:53`, packed layers at `:232`), and the publisher re-validates its
own index (publish.mjs:326). What is missing is the operational half: every live publish passes `--no-packs`
(`docs/release/README.md:18-23`, `docs/release/RUNBOOK.md:33`, `docs/release/1.6.3-deploy-steps.md:92`) and no `--packs`
invocation exists anywhere in `docs/release/` or `tools/release/`, so Live serves loose files and the pack path has never
run against the real host. No Windows run over a folder with a locked or running client is recorded either; the launcher
README notes the same gap for self-update (README.md:313-314).

## Open questions

- Who runs `publish.mjs --packs base@<version>` for Live, and when? The recipe exists only in the launcher README
  (README.md:97-98); the server repo's release scripts have no step for it, yet `publish-client` carries `--no-packs`
  "for a client release that goes out before the launcher/packs release" (tools/release/publish-client:16). Until that
  lands, packs are dead code on Live.
- Should `config.ini`'s channel server be merged on a fresh install with no `state.json`? The current rule ("none
  recorded: 1.6.4 or older, whose folders all take their channel's server once", install.rs:563-569) is a deliberate
  one-release migration; nobody has written down when it is removed.
- Is a 60 s stall timeout right for a home connection on a 256 MiB pack span? The test asserts resume works
  (`tauri/core/tests/feed.rs:489`) but no real-network data backs the value.
- Should the free-space check account for the stage copy plus the final files separately? It adds 512 MiB flat
  (install.rs:643), which on a nearly full 23 GB install leaves little headroom for the deferred-replace pass.
- `docs/architecture/launcher/README.md:77` promises the launcher never writes outside its app data directory and the
  game folder; in practice the stage directory is inside the game folder (install.rs:702), so the promise holds — but it
  reads as a code claim where the enforcement is `fsutil.rs:161-182`. Worth rewording.
