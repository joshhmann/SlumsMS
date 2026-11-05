# Publishing (client layers and the launcher exe)

## Purpose

Turn a staged client tree and a built launcher into immutable, hash-pinned layers on the patch feed the launcher reads,
without shipping a file nobody reviewed. One publisher (`/root/work/launcher-165-server/tools/publish.mjs`) serves both
pipelines; two workstation scripts add the review rules — a hash-diff against the untouched reference client and a
Live-endpoint check. Version folders are always written before the JSON that points at them, so an aborted run is
invisible to players. This page covers the two pipelines and their gates; the host/nginx side is
`../ops/client-and-launcher-publishing.md`, and layer consumption is in the sibling pages.

## Entry points

| Start it with | Runs on | Does |
|---|---|---|
| `tools/release/stage-client <v> --build-dir 'Z:\...\Release'` | workstation → build desktop → host | builds the staging tree (client flow step 1) |
| `tools/release/publish-client <v> --source DIR [--release V --news F --no-packs] [--dry-run]` | workstation → host | hash-diff + client layer + release manifest (`tools/release/publish-client:5-27`) |
| `publish-client --restore-manifest FILE` | workstation → host | puts a saved release manifest back (`publish-client:42-66`) |
| `tools/release/publish-launcher <build dir> [--version X.Y.Z] [--dry-run]` | workstation → host | launcher channel (`tools/release/publish-launcher:14-35`) |
| `tools/release/release <v>` | workstation → host | step 3 runs `publish-client --no-announce` between stop-backup and apply-start (`tools/release/release:171-181`) |
| `node tools/publish.mjs --layer/--release/--launcher/--packs/--tauri-launcher` | host | the publisher itself (`publish.mjs:555-655`) |
| `python3 -I - news FILE` / `same-layer SRC PUB LAYER VER` | host | the two pre-checks, fed on stdin (`tools/release/publish_checks.py:127-147`) |

No opcode or scheduler starts any of this: it is release-time tooling. The one runtime consumer is
`src/main/java/net/server/handlers/login/ClientVersionGate.java:110`, which refuses a login below `MIN_CLIENT_BUILD`.

## How it works

**Client flow — staging tree to the file nginx serves.**

1. `stage-client` copies the published client layer `$PATCH_DIR/layers/client/$FROM_LAYER/files` and overlays the
   changed artefacts (`stage-client:96-129`): `slums.dll`, `slums-build.txt`, `Custom.wz`, any `--data` images. Each is
   hashed on the build desktop (`stage-client:54-57`), re-hashed after `scp` (`:79-92`), and written with
   `cp -a --no-preserve=links -T` only once the paths match `:116-117`. Old files are unlinked before new ones arrive
   (`:120`), never written through. Provenance goes beside the tree, `client-<v>.STAGED.txt` (`:44`), because every file
   *inside* it gets published.
2. `publish-client` checks the version (`publish-client:68`), option combinations (`:73-76`), and that the source is on
   the host (`:80`). News goes first: `checks news` runs `publish_checks.py:48`, and a bad file dies with "would be
   refused by the publisher: nothing published" (`publish-client:82-85`) — the b052 fix.
3. Rerun safety: if `$PATCH_DIR/layers/client/$CV` exists, `checks same-layer` (`publish-client:88-91`,
   `publish_checks.py:104-120`) recomputes the manifest this tree would publish, compares bytes with the published
   `manifest.json` and re-verifies each listed file's size. Identical → reuse; different → `die "layers are immutable;
   pick a new version"` (`publish-client:93`).
4. Base layer from the current `manifest.json` unless `--base` (`:96-100`); the source must not carry
   `launcher.config.json` (`:101`); the host publisher must be byte-identical to the launcher repo
   (`check_publisher`, `publish-lib.sh:16-27`). `client-allow.txt` is then pushed to the host (`:103`) — the allow list
   is a review artefact, not host state.
5. Live gate: when `PATCH_DIR` equals `LIVE_PATCH_DIR` (default `/srv/slums-patch`) the run adds `--live-feed`
   (`publish-client:108-114`); `ALLOW_LIVE_ENDPOINT_CHANGE=1` adds `--allow-endpoint-change`.
6. `verify --client-dir "$SRC"` (`:116,120`) runs `verify-client.py`: effective set = base manifest overlaid by the
   staging tree, every reference file must match, `--previous` adds the `removed` check (`verify-client.py:181-236`).
   An unlisted difference dies before anything is published (`publish-client:120`).
7. Publish the layer: `node tools/publish.mjs --layer client --source SRC --out $PATCH_DIR --version $CV`
   (`:122,135`). `writeLayer` (`publish.mjs:224-255`) copies into a `.mapleroot-publish-*` temp dir while hashing,
   writes `manifest.json` with `flag:'wx'`, reserves the version dir with `fs.mkdir` (`:245`), renames `files/` and
   `manifest.json` in (`:247-248`), and drops the reservation on failure (`:251-254`). The hash-diff then runs again
   against the *published* layer manifest (`publish-client:138`).
8. With `--release`: back up `manifest.json` to `$STAGING_DIR/manifest-before-$REL.json`, refusing to overwrite a
   different existing backup because it is the way back (`:142-144`), then `--release REL --use base@B --use client@CV`
   (`:145`). `publishLayeredRelease` (`publish.mjs:357-401`) re-reads each layer manifest, merges files, requires
   `launch.executable` in some layer (`:383`), refuses a Windows alias for `manifest.json` (`:385`), refuses
   re-publishing the live version (`:393`), and writes the new `manifest.json` by temp file + rename last (`:395-399`).
9. `/launcher-test/` mirrors the live manifest when its `layers` is a symlink, WARN otherwise (`publish-client:149-150`);
   then `slumsms-monitor client-ready live <REL>` unless `--no-announce` (`:152-156`).
10. End of pipeline, served by the nginx site `/etc/nginx/sites-enabled/slums-patch` on 8090 behind basic auth
    (`docs/ops/lxc125-architecture.md:100-102`): `/srv/slums-patch/manifest.json`, the file every launcher fetches,
    pointing at `layers/client/<v>/manifest.json` (feed `manifest.json` no-cache, layer folders immutable).

**Launcher flow — build to channel JSON.**

1. `publish-launcher <SRC>` requires `"SlumsMS Launcher.exe"` (`publish-launcher:21`) and refuses a
   `launcher.config.json` in the build (`:22`) — that file belongs to the player. `check_publisher "$SYNC"` requires the
   host copy at `/opt/slums-tools/launcher` to hash the same as `$LAUNCHER_REPO` (`publish-lib.sh:16-27`);
   `--sync-publisher` rsyncs `tools/`, `src/`, `package.json`.
2. `--dry-run` publishes into `mktemp -d` seeded with the current `launcher.json` and prints it
   (`publish-launcher:26-31`); the real run is `--launcher SRC --out $OUT` (`:34`).
3. `publishLauncher` (`publish.mjs:433-471`) reads `resources/app.asar`: the version is the build's own `package.json`
   and `--version` must match (`:443`); no built-in access key is refused unless `--allow-no-access-key` (`:445`); the
   exe and `launcher.config.json` rules are re-checked (`:447-449`). No downgrade:
   `compareVersions(built, previous.version) <= 0` is refused, "launchers never downgrade" (`:455-460`).
4. `writeLayer` publishes `launcher/<platform>/<version>/` (`:462`), then the channel JSON
   `{schemaVersion:1, version, platform, executable, manifestUrl, sha256}` is renamed into place *last* (`:463-467`),
   validated by `validateLauncherChannel` (`src/updater.mjs:646-660`).
5. End of pipeline: `$OUT/launcher.json` at the root of `/srv/slums-patch` (or `/srv/slums-patch/launcher-test`), served
   beside the game manifest, `manifestUrl` = `launcher/<platform>/<version>/manifest.json` (`publish.mjs:425`). Tauri is
   the same shape under `tauri/launcher.json` (`publish.mjs:474,535`).

**Gates — what refuses to publish.**

| Gate | Verifies | Refuses | Where |
|---|---|---|---|
| News rules | ≤10 entries; title ≤120, body ≤1000 UTF-16 units; no control chars; no `NaN`/`Infinity` | the run, before the layer | `publish_checks.py:29-31,44-60,123-136`; called `publish-client:83-84` |
| Layer immutability / rerun | published layer `manifest.json` byte-identical to what this tree would produce, files at listed sizes | an existing layer with different bytes | `publish_checks.py:104-120`; `publish-client:88-94` |
| Hash-diff | base + client effective set vs `$REFERENCE`: no unexpected missing/different/extra | any difference not in `client-allow.txt` | `verify-client.py:181-236`; run `publish-client:116,120` and `:138` |
| Removed files | every file of the current release still present | a silently dropped overlay (1.2.0 Arcane-maps crash) | `verify-client.py:211-222` |
| Live endpoint | new layer's `config.ini` `[config] host`/`port` equals the live layer's | a test-server client on `/srv/slums-patch` | `verify-client.py:129-150`; wired `publish-client:108-114` |
| Allow list | every overlay path named explicitly | an unlisted extra/missing/different file | `tools/release/client-allow.txt:1-6`; `verify-client.py:172-204` |
| Publisher identity | sha256 of `tools/ src/ package.json` on both sides, first 16 hex | a host publisher that is not the reviewed one | `publish-lib.sh:16-27` |
| Source hygiene | no symlinks, no non-regular files, output not equal to/inside source | weird trees | `publish.mjs:42-56,173-186`; `publish_checks.py:67-82` |
| Immutable names, one publisher | `assertNoAlias` on NFC-uppercased names, `wx` writes, `mkdir` version dir, exclusive `.mapleroot-publish.lock` | a collision, an overwrite or a concurrent publish | `publish.mjs:15,188-203,244-248` |
| Release manifest | `launch.executable` in some layer; no `MANIFEST.JSON` alias; version not already live | a manifest pointing at nothing | `publish.mjs:383-393` |
| Backup / restore | `manifest-before-<rel>.json` never overwritten with different bytes; every layer a restored manifest names exists | losing the way back, restoring into a hole | `publish-client:47-58,142-144` |
| Launcher build | asar version = `--version`; built-in access key; exe present; no `launcher.config.json`; no downgrade | a build that locks friends out or regresses | `publish.mjs:443-449,455-460` |
| CLI shape | `--launcher` rejects layer/release/news; `--platform` needs `--launcher`; `--no-packs` needs `--release`; `--layer` takes no release opts | mixing modes in one run | `publish.mjs:597,604,626,630` |
| Client paths | `--data` spec is `Z:\...\x.img=Data/.../x.img`, no `..` | odd paths reaching shell/manifest | `stage_client_lib.py:12-31`; `stage-client:46` |
| Client build | `MIN_CLIENT_BUILD` in the deployed config | a login from an older client | `ClientVersionGate.java:110-120` |

## Data

- **Published** (`/srv/slums-patch`, `publish-lib.sh:2`): `manifest.json` (release entry), `launcher.json`,
  `layers/base/<v>/`, `layers/client/<v>/`, `launcher/<platform>/<v>/`, `tauri/<v>/`. A layer version dir holds
  `files/…` plus `manifest.json` whose `files[]` entries are `{path,size,sha256,url:"files/…"}` at `schemaVersion: 2`
  (`publish_checks.py:93-101`, `publish.mjs:242-244`).
- **Staging** (`/srv/slums-staging`, `publish-lib.sh:3`): `client-<v>/` trees, `client-<v>.STAGED.txt` beside them,
  `news-<v>.json`, `manifest-before-<release>.json`, `manifest-replaced-<UTC>.json`, and the reference hash cache
  `reference-client.hashes.json` (`publish-lib.sh:8`). In memory only: the file list and hashes being published
  (`publish.mjs:42-84`) and `verify-client.py`'s `(size, mtime)` cache (`verify-client.py:43-70`).
- **Persisted by a run**: layer `manifest.json`, release `manifest.json`, `launcher.json`, the `manifest-before-*`
  backup, the `.STAGED.txt` note. No DB, no migration; the only server-side fact a publish changes is what the launcher
  downloads next. The server gate is written separately by `set-min-client-build`, which rewrites the single
  `MIN_CLIENT_BUILD:` line under `server:` keeping CRLF/indent, refuses a decrease without `--allow-lower` and treats an
  identical value as a no-op (`set-min-client-build:28-35,42-52`); live carries `10740`
  (`docs/release/1.6.4.conf:284`), read at `src/main/java/config/ServerConfig.java:58-64`.

## Switches

| Switch | Default | Effect |
|---|---|---|
| `PATCH_DIR` | `/srv/slums-patch` (`publish-lib.sh:2`) | feed being published to; also the channel choice |
| `LIVE_PATCH_DIR` | `/srv/slums-patch` (`publish-client:108`) | the one feed where the endpoint check applies |
| `ALLOW_LIVE_ENDPOINT_CHANGE` | `0` (`publish-client:112`) | allow a deliberate live host/port move |
| `STAGING_DIR` | `/srv/slums-staging` (`publish-lib.sh:3`) | trees, news, manifest backups |
| `PUBLISHER` / `NODE` | `/opt/slums-tools/launcher`, `/opt/slums-tools/node/bin/node` (`publish-lib.sh:4-5`) | host publisher and its node |
| `LAUNCHER_REPO` / `REFERENCE` | `/root/work/launcher`, `/opt/mapleroot-control/…/Game Files` (`publish-lib.sh:6-7`) | what the publisher and the hash-diff compare against |
| `REF_CACHE` | `$STAGING_DIR/reference-client.hashes.json` (`publish-lib.sh:8`) | reference hash cache; delete to re-hash |
| `ANNOUNCE_TARGET` | `live` (`publish-client:70`) | channel the `client-ready` post names |
| `PUBLISH_TEST_FAIL` | empty (`publish-client:37-40`) | `after-layer`/`after-manifest` rehearsal injection, refused on the live feed |
| `--no-packs` / `--no-announce` | off (`publish-client:73-76`) | no first-install packs in the manifest / no Cody post |
| `--allow-no-access-key` | off (`publish.mjs:445`) | publish a launcher with no built-in key |
| `MIN_CLIENT_BUILD` | `0` = off (`ServerConfig.java:58-64`) | live value `10740` (`docs/release/1.6.4.conf:284`) |

Channels are Live `/srv/slums-patch` (player key, endpoint check on), Test `/srv/slums-patch/launcher-test` (`layers`
symlinks to the live layers), RC `/srv/slums-patch/launcher-rc` (`docs/ops/lxc125-architecture.md:101-102`; see
`channels-and-auth.md`). TEST: unknown — the TEST server's own feed path was not read, so which of these a 1.6.4
RC/Test publish uses is unconfirmed.

## Invariants

- **A Live client layer must point at the live host and port.** Enforced at `verify-client.py:141-148` — `if None in got
  or got != want` → "this client would send live players to another server. Nothing may be published to the live feed
  with it" — reached from `publish-client:110-114,120` and again at `:138`. A missing `config.ini` on either side is also
  a FAIL (`verify-client.py:132-138`); the host comparison lowercases and strips (`:96`), so case and spacing cannot
  smuggle a different server past it.
- **Layer version directories are append-only.** `publish.mjs:245` reserves with `mkdir` (EEXIST fails), `assertNoAlias`
  (`:198-203`) blocks case variants, and the shell refuses a differing layer for the same version (`publish-client:93`).
  That is what makes reruns safe — an identical tree is detected and skipped (`publish-client:88-94`), matching the retry
  advice at `tools/release/release:178`.
- **The pointer file is written last.** Layer `manifest.json` before the directory rename (`publish.mjs:247-248`), release
  `manifest.json` after all layers validate (`:395-399`), `launcher.json` last (`:464-468`).
- **What is published is what was hashed.** The source is re-stat'ed before and after the copy and the publish fails
  with "Source changed while publishing" (`publish.mjs:64-81`); the manifest is re-verified against disk after the layer
  lands (`publish-client:138`).
- **Every difference from the reference is named** in `client-allow.txt`; the all-clear is `HASH-DIFF PASS`
  (`verify-client.py:233`).
- **The publisher that runs is the reviewed one** (`publish-lib.sh:16-27`), **a launcher never downgrades**
  (`publish.mjs:458-460`), and **`launcher.config.json` is the player's** — refused in a client tree
  (`publish-client:101`) and in a launcher build (`publish-launcher:22`, `publish.mjs:449`).

## Origin

**custom.** Every file post-dates the repack baseline `03d13cb21`, which is an ancestor of `9d437b372` — the shell side
is not derived from the base tree at all (method: `../base-vs-custom.md`):

| Path | Oldest commit |
|---|---|
| `tools/release/publish-client`, `publish-launcher`, `publish-lib.sh`, `verify-client.py`, `client-allow.txt` | `9d437b372` "Script the release pipeline: build, stage, rehearse, deploy/rollback, publish (ticket 44)" |
| `tools/release/publish_checks.py` | `fb8286344` (b052) |
| `tools/release/stage-client` / `stage_client_lib.py` | `6a863e7be` / `e0bde38af` |
| `tools/release/set-min-client-build` | `82d4831b4` |
| `tools/release/routing-required.txt` | `3482b808c` |
| `/root/work/launcher-165-server/tools/publish.mjs` | `fcd6e08` (launcher repo, 8 commits) |

The four later `publish-client` commits are `0ff53df38` (one release command), `23eae9ffb` (a release without
`--no-packs` stopped silently), `aee6f180d` (live-endpoint refusal), `fb8286344` (news before publish + layer reuse). The
publisher's `--launcher` mode is a hand-written Electron/asar reader (`publish.mjs:405-431`), not upstream tooling.

## Tests and QA

`python3 -m unittest discover -s tools/release -p 'test_*.py'` (`tools/release/README.md:126`):

- `tools/release/test_live_endpoint.py` — `LiveEndpointTest`: live endpoint passes (`:69`), a test-server host or port is
  refused (`:74`), case/spacing do not matter (`:80`), a missing `config.ini` on either side is refused (`:84`), a
  published layer manifest is checked rather than a tree (`:88`), a deliberate move needs `--allow-endpoint-change`
  (`:94`), `main` fails on the endpoint even when the hash-diff passes (`:101`), and `publish-client` wires the live feed
  only for the live `PATCH_DIR` (`:122`).
- `tools/release/test_publish_client_rerun.py` — `NewsRulesTest`: eleven entries refused with the publisher's own message
  (`:65`), verdict identical to the publisher running `validateManifest` (`:71`, skipped without node);
  `PublishClientRerunTest`: bad news stops the dry run and the real run before the layer (`:138`), a rerun reuses an
  identical layer (`:147`), a changed tree still aborts (`:170`); `LayerManifestMirrorTest` (`:183`) pins odd-name
  manifest bytes against `publish.mjs`. ssh/scp are stubbed, so nothing leaves the workstation.
- `tools/release/test_stage_client_lib.py` — `ParseTest` (exact bytes, CRLF, missing build) and `DataSpecTest`
  (Windows/client path validation) (`:6-25`).

Launcher repo (`npm test`): `test/layers.test.mjs` — immutable layer folders with a top manifest pinning layer hashes
(`:62`), later layer overrides (`:101`), only changed layers download (`:122`), fast verify trusts size+mtime (`:147`),
player-owned `config.ini` (`:205`), `schemaVersion 1` still loads (`:231`), traversal/reserved-path rejection (`:248`),
first install with bounded concurrency (`:288`); `test/releases.test.mjs` — nested install/repair (`:45`), a corrupt
payload cannot partially replace a release (`:67`), publishing is immutable, exposes no linked files, rejects traversal
and Windows aliases (`:80`), redirected feeds (`:96`), news round-trip and bad-news rejection (`:103`);
`test/tauri-publish.test.mjs` — signed Tauri publish immutable with the updater JSON last (`:32`), HTTPS-only
credential-free base URL and a real Windows program (`:54`), both skipped without `cargo tauri`.

Not covered: `set-min-client-build` and `publish-launcher` have no test at all (only `publishLayer` is);
`publish_checks.py`'s UTF-16 limit and control-character rule are not asserted directly; nothing exercises the real
`ssh`/`scp` path or the `/launcher-test/` mirror; `verify-client.py --previous` never runs against a real 22 GB
reference. Auto-QA has one launcher check and it is manual — `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:783`
(`t-la-1`) — and `src/main/java/soloMapling/qa/` has no publishing scenario, so no `!qa` run can see the patch feed.

## Known issues

- **b052** (S4, Ops) — layer published before the news list was checked, then no retry. The row is stale: the fix is in
  this tree at `publish-client:82-94`, and `publish_checks.py` exists only from `fb8286344`, the commit the card names.
- **b270** (S3, Client) — a Live publish wrote `manifest.json` without `news[]`, so `slumsms-news@live` failed; cause is
  `--news` not being passed (`docs/release/RUNBOOK.md:49` now passes it). Deferred to 1.6.3, still open.
- NEW-FINDING: `publish-client:149-150` mirrors the manifest into `/launcher-test/` only when `layers` there is a symlink
  and downgrades a miss to a stdout `WARN` — Live can publish successfully while the owner's launcher stays on the old
  release, exit status 0.
- NEW-FINDING: the endpoint check reads only `host` and `port` (`verify-client.py:96`), so a layer keeping the live
  host/port but changing other connection settings passes.
- NEW-FINDING: `check_publisher` hashes `tools/ src/ package.json` (`publish-lib.sh:20-21`) but no lockfile and no
  `node_modules`, so the same sources with different installed deps pass.
- NEW-FINDING: `--restore-manifest` checks that the named layers exist but never re-runs the endpoint check
  (`publish-client:47-58`), so an old manifest whose client layer points elsewhere would be accepted.
- NEW-FINDING: `tools/release/routing-required.txt:5` (map `211080100` held routed until client fix `3e640f1` is live) is
  enforced by `tools/release/gen-deploy:46-51`, not by `publish-client`: a client publish never consults it.

## State

**Working** for the client pipeline: the gates are implemented *and* reachable, and it ran for 1.3.0 —
`client-allow.txt:5-6` ("Captured 2026-10-20 from the published 1.3.0 set (base v83-2026.10.19 + client 1.2.1)") — with
client 1.2.1 being the layer live players have (`docs/ops/lxc125-architecture.md:101`). **Partial** for the launcher
pipeline: `launcher.json` is "none published yet at the root" (`docs/ops/lxc125-architecture.md:102`), so its real path
rests on `test/layers.test.mjs` and `test/releases.test.mjs`, not on a live channel file. Not runtime-verified here:
nothing was published and the feed on 8090 was not read — code and repo-doc evidence only.

## Open questions

- Which `PATCH_DIR` a 1.6.4 RC/Test publish uses is recorded nowhere I could read; `publish-client:108-114` only tests
  whether the path equals the live one. TEST: unknown.
- Is the Electron `--launcher` channel still used? `docs/release/RUNBOOK.md:56` publishes Tauri first "then the Electron
  hand-over", and no `launcher.json` sits at the root, so the Electron flow may be dead weight kept for the hand-over.
- `docs/architecture/launcher/README.md:73-74` states the endpoint invariant without naming the enforcing line; it is
  `verify-client.py:141-148`. `docs/architecture/ops/README.md:26,44,57,76` plans the ops sibling
  `client-and-launcher-publishing.md`; this page stops at the two pipelines so the two do not drift.
