# Publishing (client layers and the launcher exe)

## Purpose

Turn a staged client tree and a built launcher into immutable, hash-pinned layers on the patch feed the launcher reads,
without shipping a file nobody reviewed. One publisher (`/root/work/launcher-165-server/tools/publish.mjs`) serves both
pipelines; the workstation scripts add the review rules — a hash-diff against the untouched reference client and a
Live-endpoint check — and every version folder is written before the JSON that points at it, so an aborted run is
invisible to players. This page covers the two pipelines and their gates; the host/nginx side is
`../ops/client-and-launcher-publishing.md`, layer consumption in the sibling pages.

## Entry points

| Start it with | Runs on | Does |
|---|---|---|
| `tools/release/stage-client <v> --build-dir 'Z:\...\Release'` | workstation → build desktop → host | builds the staging tree (client flow step 1) |
| `tools/release/publish-client <v> --source DIR [--release V --news F --no-packs] [--dry-run]` | workstation → host | hash-diff, client layer, release manifest (`publish-client:5-27`); `--restore-manifest FILE` puts a saved one back (`:42-66`) |
| `tools/release/publish-launcher <build dir> [--version X.Y.Z] [--dry-run]` | workstation → host | launcher channel (`tools/release/publish-launcher:14-35`) |
| `tools/release/release <v>` | workstation → host | step 3 runs `publish-client --no-announce` between stop-backup and apply-start (`tools/release/release:171-181`) |
| `node tools/publish.mjs --layer/--release/--launcher/--packs/--tauri-launcher` | host | the publisher itself (`publish.mjs:555-655`); its two pre-checks (`publish_checks.py:127-147`) are fed on stdin as `python3 -I - news FILE` / `same-layer SRC PUB LAYER VER` |

No opcode or scheduler starts any of this: it is release-time tooling. The one runtime consumer is
`src/main/java/net/server/handlers/login/ClientVersionGate.java:110`, which refuses a login below `MIN_CLIENT_BUILD`.

## How it works

**Client flow — staging tree to the file nginx serves.**

1. `stage-client` copies the published client layer `$PATCH_DIR/layers/client/$FROM_LAYER/files` and overlays only
   `slums.dll`, `slums-build.txt`, `Custom.wz` and any `--data` image (`stage-client:96-129`). Each is hashed on the build
   desktop (`:54-57`), re-hashed after `scp` (`:79-92`), and lands via `cp -a --no-preserve=links -T` once the paths match
   (`:116-117`); old files are unlinked first (`:120`), never written through. Provenance sits beside the tree,
   `client-<v>.STAGED.txt` (`:44`), because every file *inside* it is published.
2. `publish-client` checks the version (`publish-client:68`), the option combinations (`:73-76`) and that the source is on
   the host (`:80`). News goes first: `checks news` (`publish_checks.py:48`) and a bad file dies with "would be refused by
   the publisher: nothing published" (`publish-client:82-85`) — the b052 fix.
3. Rerun safety: if `$PATCH_DIR/layers/client/$CV` exists, `checks same-layer` (`:88-91`, `publish_checks.py:104-120`)
   recomputes the manifest this tree would publish, compares bytes and re-verifies each listed size — identical is reused,
   different is `die "layers are immutable; pick a new version"` (`:93`).
4. Base layer from the current `manifest.json` unless `--base` (`:96-100`); no `launcher.config.json` in the source
   (`:101`); the host publisher must hash the same as the launcher repo (`publish-lib.sh:16-27`). `client-allow.txt` goes
   to the host (`:103`) — the allow list is a review artefact, not host state.
5. Hash-diff: when `PATCH_DIR` equals `LIVE_PATCH_DIR` (default `/srv/slums-patch`) the run adds `--live-feed`
   (`:108-114`), and `ALLOW_LIVE_ENDPOINT_CHANGE=1` adds `--allow-endpoint-change`. `verify --client-dir "$SRC"`
   (`:116,120`) runs `verify-client.py` on the effective set (base manifest overlaid by the staging tree): every reference
   file must match, `--previous` adds the `removed` check (`verify-client.py:172-236`), and an unlisted difference dies
   before anything is published (`publish-client:120`).
6. Publish the layer (`:122,135`): `node tools/publish.mjs --layer client --source SRC --out $PATCH_DIR --version $CV`.
   `writeLayer` (`publish.mjs:224-255`) copies into a `.mapleroot-publish-*` temp dir while hashing, writes the manifest
   with `flag:'wx'`, reserves the version dir with `fs.mkdir` (`:245`), renames `files/` then `manifest.json` in
   (`:247-248`), and drops the reservation on failure (`:251-254`). The hash-diff then runs again against the *published*
   layer manifest (`publish-client:138`).
7. With `--release`: back up `manifest.json` to `$STAGING_DIR/manifest-before-$REL.json`, refusing to overwrite a
   different existing backup because it is the way back (`:142-144`), then `--release REL --use base@B --use client@CV`
   (`:145`). `publishLayeredRelease` (`publish.mjs:357-401`) re-reads each layer manifest, merges files, requires
   `launch.executable` in some layer (`:383`), refuses a `MANIFEST.JSON` alias (`:385`) or re-publishing the live version
   (`:393`), and writes the new `manifest.json` by temp file + rename last (`:395-399`).
8. `/launcher-test/` mirrors the live manifest when its `layers` is a symlink, WARN otherwise (`:149-150`); then
   `slumsms-monitor client-ready live <REL>` unless `--no-announce` (`:152-156`).
9. End of pipeline: `/srv/slums-patch/manifest.json`, the file every launcher fetches, pointing at
   `layers/client/<v>/manifest.json` — served by nginx `slums-patch` on 8090 behind basic auth
   (`docs/ops/lxc125-architecture.md:100-102`), feed manifest no-cache, layer folders immutable.

**Launcher flow — build to channel JSON.**

1. `publish-launcher <SRC>` requires `"SlumsMS Launcher.exe"` (`publish-launcher:21`) and refuses a
   `launcher.config.json` in the build (`:22`) — that file belongs to the player. `check_publisher "$SYNC"` (`:23`) makes
   the host copy at `/opt/slums-tools/launcher` hash the same as `$LAUNCHER_REPO` (`publish-lib.sh:16-27`);
   `--sync-publisher` rsyncs `tools/`, `src/`, `package.json`.
2. `--dry-run` publishes into `mktemp -d` seeded with the current `launcher.json` and prints it (`:26-31`); the real run is
   `--launcher SRC --out $OUT` (`:34`).
3. `publishLauncher` (`publish.mjs:433-471`) reads `resources/app.asar`: the version is the build's own `package.json` and
   `--version` must match (`:443`); no built-in access key is refused unless `--allow-no-access-key` (`:445`); the exe and
   `launcher.config.json` rules are re-checked (`:447-449`). No downgrade: `compareVersions(built, previous.version) <= 0`
   is refused, "launchers never downgrade" (`:455-460`).
4. `writeLayer` publishes `launcher/<platform>/<version>/` (`:462`), then the channel JSON
   `{schemaVersion:1, version, platform, executable, manifestUrl, sha256}` is renamed into place *last* (`:466-467`),
   validated by `validateLauncherChannel` (`src/updater.mjs:646-660`).
5. End of pipeline: `$OUT/launcher.json` at the root of `/srv/slums-patch` (or of `…/launcher-test`), served beside the
   game manifest with `manifestUrl` = `launcher/<platform>/<version>/manifest.json` (`publish.mjs:425`). Tauri is the same
   shape under `tauri/launcher.json` (`publish.mjs:474,531-534`).

**Gates — what refuses to publish.**

| Gate | Verifies | Refuses | Where |
|---|---|---|---|
| News rules | ≤10 entries; title ≤120, body ≤1000 UTF-16 units; no control chars; no `NaN`/`Infinity` | the run, before the layer | `publish_checks.py:29-31,44-60,123-136`; called `publish-client:83-84` |
| Layer immutability / rerun | published layer `manifest.json` byte-identical to what this tree would produce, files at listed sizes; NFC-uppercased `assertNoAlias`, `wx` writes, `mkdir` version dir, `.mapleroot-publish.lock` | an existing layer with different bytes, a case variant, a concurrent publish | `publish_checks.py:104-120`, `publish.mjs:15,188-203,244-248`; `publish-client:88-94` |
| Hash-diff | base + client effective set vs `$REFERENCE`: no unexpected missing/different/extra, and `--previous` keeps every file of the current release (a dropped overlay was the 1.2.0 Arcane-maps crash) | any difference not in `client-allow.txt` | `verify-client.py:172-236`; run `publish-client:116,120` and `:138` |
| Live endpoint | new layer's `config.ini` `[config] host`/`port` equals the live layer's | a test-server client on `/srv/slums-patch` | `verify-client.py:129-150`; wired `publish-client:108-114` |
| Allow list | every overlay path named explicitly | an unlisted extra/missing/different file | `tools/release/client-allow.txt:1-6`; `verify-client.py:172-222` |
| Publisher identity | sha256 of `tools/ src/ package.json` on both sides, first 16 hex | a host publisher that is not the reviewed one | `publish-lib.sh:16-27` |
| Source hygiene | no symlinks or non-regular files; output not equal to or inside source | weird trees | `publish.mjs:42-56,173-186`; `publish_checks.py:67-82` |
| Release manifest | `launch.executable` in some layer; no `MANIFEST.JSON` alias; version not already live | a manifest pointing at nothing | `publish.mjs:383-393` |
| Backup / restore | `manifest-before-<rel>.json` never overwritten with different bytes; every layer a restored manifest names exists | losing the way back, restoring into a hole | `publish-client:47-58,142-144` |
| Launcher build | asar version = `--version`; built-in access key; exe present; no `launcher.config.json`; no downgrade | a build that locks friends out or regresses | `publish.mjs:443-449,455-460` |
| CLI shape | `--launcher` rejects layer/release/news; `--platform` needs `--launcher`; `--no-packs` needs `--release`; `--layer` takes no release opts | mixing modes in one run | `publish.mjs:597,604,626,630` |
| Client paths | `--data` spec is `Z:\...\x.img=Data/.../x.img`, no `..` | odd paths reaching shell/manifest | `stage_client_lib.py:12-31`; `stage-client:46` |
| Client build (post-publish) | `MIN_CLIENT_BUILD` in the deployed config — not consulted by either publisher | a login from an older client, at connect time only | `ClientVersionGate.java:110-120` |

## Data

- **Published** (`/srv/slums-patch`, `publish-lib.sh:2`): release `manifest.json`, `launcher.json`, `layers/base/<v>/`,
  `layers/client/<v>/`, `launcher/<platform>/<v>/`, `tauri/<v>/`. A layer version dir holds `files/…` plus a
  `manifest.json` with `files[]` entries `{path,size,sha256,url:"files/…"}` at `schemaVersion: 2`
  (`publish_checks.py:93-101`, `publish.mjs:242-244`).
- **Staging** (`/srv/slums-staging`, `publish-lib.sh:3`): `client-<v>/` trees with `client-<v>.STAGED.txt` beside them,
  `news-<v>.json`, `manifest-before-<release>.json`, `manifest-replaced-<UTC>.json`, the reference hash cache (`:8`). In
  memory only: the file list and hashes being published (`publish.mjs:42-84`) and `verify-client.py`'s `(size, mtime)`
  cache (`verify-client.py:43-70`).
- **Persisted by a run**: the two `manifest.json` files, `launcher.json`, the `manifest-before-*` backup, the
  `.STAGED.txt` note. No DB, no migration: a publish only changes what the launcher downloads next. The server-side gate
  is written separately by `set-min-client-build`, rewriting the single `MIN_CLIENT_BUILD:` line under `server:`, keeping
  CRLF/indent, refusing a decrease without `--allow-lower`, treating an identical value as a no-op
  (`set-min-client-build:27-35,41-53`). The 1.6.4 draft proposes `10740` (`docs/release/1.6.4.conf:284`) while its header
  says live carries `10721` (`:10`); the field is `public int MIN_CLIENT_BUILD = 0`
  (`src/main/java/config/ServerConfig.java:61`), 0 meaning off.

## Switches

| Switch | Default | Effect |
|---|---|---|
| `PATCH_DIR` | `/srv/slums-patch` (`publish-lib.sh:2`) | feed being published to; also the channel choice |
| `LIVE_PATCH_DIR` | `/srv/slums-patch` (`publish-client:108`) | the one feed where the endpoint check applies |
| `ALLOW_LIVE_ENDPOINT_CHANGE` | `0` (`publish-client:112`) | allow a deliberate live host/port move |
| `STAGING_DIR` | `/srv/slums-staging` (`publish-lib.sh:3`) | trees, news, manifest backups |
| `PUBLISHER` / `NODE` | `/opt/slums-tools/launcher`, `/opt/slums-tools/node/bin/node` (`publish-lib.sh:4-5`) | host publisher and node |
| `LAUNCHER_REPO` / `REFERENCE` / `REF_CACHE` | `/root/work/launcher`, `/opt/mapleroot-control/…/Game Files`, `$STAGING_DIR/reference-client.hashes.json` (`publish-lib.sh:6-8`) | the publisher, the hash-diff and its cache |
| `ANNOUNCE_TARGET` | `live` (`publish-client:70`) | channel the `client-ready` post names |
| `PUBLISH_TEST_FAIL` | empty (`publish-client:37-40`) | `after-layer`/`after-manifest` rehearsal injection, refused on the live feed |
| `--no-packs` / `--no-announce` / `--allow-no-access-key` | off (`publish-client:73-76`, `publish.mjs:445`) | no packs / no Cody post / a launcher with no built-in key |
| `MIN_CLIENT_BUILD` | `0` = off (`ServerConfig.java:61`) | 1.6.4 draft `10740` vs live `10721` (`docs/release/1.6.4.conf:284,10`) |

Channels are Live `/srv/slums-patch` (player key, endpoint check on), Test `/srv/slums-patch/launcher-test` (`layers`
symlinks to the live layers), RC `/srv/slums-patch/launcher-rc` (`docs/ops/lxc125-architecture.md:101-102`; see
`channels-and-auth.md`). TEST: unknown — the TEST server's own feed path was not read, so which of these a 1.6.4 RC/Test
publish uses is unconfirmed.

## Invariants

- **A Live client layer must point at the live host and port.** Enforced at `verify-client.py:141-148` — `if None in got
  or got != want` → "this client would send live players to another server. Nothing may be published to the live feed
  with it" — reached from `publish-client:110-114,120` and again at `:138`. A missing `config.ini` on either side is also
  a FAIL (`verify-client.py:132-138`), and the comparison lowercases and strips the host (`:96`), so case and spacing
  cannot smuggle a different server past it.
- **Layer version directories are append-only.** `publish.mjs:245` reserves with `mkdir` (EEXIST fails), `assertNoAlias`
  (`:198-203`) blocks case variants, and the shell refuses a differing layer for a version (`publish-client:93`). That is
  what makes reruns safe — an identical tree is detected and skipped (`publish-client:88-94`, retry advice at
  `tools/release/release:178`).
- **The pointer file is written last.** Layer `manifest.json` before the directory rename (`publish.mjs:247-248`), release
  `manifest.json` after all layers validate (`:395-399`), `launcher.json` last (`:466-467`).
- **What is published is what was hashed.** The source is re-stat'ed before and after the copy, failing with "Source
  changed while publishing" (`publish.mjs:64-82`), and the manifest is re-verified against disk once the layer lands
  (`publish-client:138`).
- **Every difference from the reference is named** in `client-allow.txt`; the all-clear is `HASH-DIFF PASS`
  (`verify-client.py:233`). **The publisher that runs is the reviewed one** (`publish-lib.sh:16-27`), **a launcher never
  downgrades** (`publish.mjs:458-460`), and **`launcher.config.json` is the player's** — refused in a client tree
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

Seven commits follow: `a50fe655f` (Cody post), `ae3c91cb7` (`/launcher-test/` mirror), `66a2ae9e8` (1.4.1 deploy),
`0ff53df38` (one release command), `23eae9ffb` (a release without `--no-packs` stopped silently), `aee6f180d`
(live-endpoint refusal), `fb8286344` (news first + layer reuse). `--launcher` mode is a hand-written Electron/asar reader
(`publish.mjs:405-422`), not upstream tooling: 16-byte prefix, JSON directory, packed range at
`8 + headerSize + offset` (`:408-419`), and an unpacked or non-integer-size entry is refused (`:417`).

## Tests and QA

`python3 -m unittest discover -s tools/release -p 'test_*.py'` (`tools/release/README.md:126`):

- `tools/release/test_live_endpoint.py` — `LiveEndpointTest` (`:32`): the endpoint passes (`:69`); a test-server host or
  port (`:74`), a missing `config.ini` (`:84`) and a deliberate move without `--allow-endpoint-change` (`:94`) are
  refused; case/spacing do not matter (`:80`); a published layer manifest is checked rather than a tree (`:88`); `main`
  fails on the endpoint even when the hash-diff passes (`:101`); `publish-client` adds the live feed only for the live
  `PATCH_DIR` (`:122`).
- `tools/release/test_publish_client_rerun.py` — `NewsRulesTest` (`:55`): eleven entries refused with the publisher's own
  message (`:65`) and the same verdict as the publisher's `validateManifest` (`:71`, skipped without node);
  `PublishClientRerunTest` (`:84`): bad news stops the dry run and the real run before the layer (`:138`), a rerun reuses
  an identical layer (`:147`), a changed tree still aborts (`:170`); `LayerManifestMirrorTest` (`:182`) pins odd-name
  manifest bytes against `publish.mjs`. ssh/scp are stubbed, so nothing leaves the workstation.
- `tools/release/test_stage_client_lib.py` — `ParseTest` (`:6`) exact bytes, CRLF, missing build; `DataSpecTest` (`:19`)
  Windows-to-client path mapping.

Launcher repo (`npm test`): `test/layers.test.mjs:62` immutable layer folders with a top manifest pinning layer hashes,
`:101` later layers override, `:122` only changed layers download, `:147` fast verify trusts size+mtime, `:205`
player-owned `config.ini` survives repair, `:231` `schemaVersion 1` loads, `:248` traversal/reserved paths rejected,
`:288` first install with bounded concurrency and retries; `test/releases.test.mjs:45` nested install/repair, `:67` a
corrupt payload cannot partially replace a release, `:80` publishing is immutable and cannot expose linked files, `:96`
redirected feeds, `:103` news round-trip plus bad-news rejection; `test/tauri-publish.test.mjs:32` signed Tauri publish
immutable with the updater JSON last, `:54` HTTPS-only credential-free base URL — both skipped without `cargo tauri`.

Not covered: `set-min-client-build` and `publish-launcher` (only `publishLayer` is tested), the UTF-16 limit and the
control-character rule in `publish_checks.py`, the real `ssh`/`scp` path, the `/launcher-test/` mirror, and
`verify-client.py --previous` against a real 22 GB reference. Auto-QA has one launcher check and it is manual —
`src/main/java/soloMapling/autoqa/AutoQaCatalog.java:783` (`t-la-1`) — and `src/main/java/soloMapling/qa/` has no
publishing scenario, so no `!qa` run can see the patch feed.

## Known issues

- **b052** (S4, Ops) — layer published before the news list was checked, then no retry. The row is stale: the fix is in
  this tree at `publish-client:82-94`, and `publish_checks.py` exists only from `fb8286344`, the commit the card names.
- **b270** (S3, Client) — a Live publish wrote `manifest.json` without `news[]`, so `slumsms-news@live` failed; cause is
  `--news` not being passed (`docs/release/RUNBOOK.md:49` now passes it). Deferred to 1.6.3, still open.
- NEW-FINDING: `publish-client:149-150` mirrors `/launcher-test/manifest.json` only when `layers` there is a symlink and
  downgrades a miss to a stdout `WARN` — Live publishes, exit 0, owner's launcher stays on the old release.
- NEW-FINDING: the endpoint check reads only `host` and `port` (`verify-client.py:96`); a layer keeping them but changing
  other connection settings passes.
- NEW-FINDING: `check_publisher` hashes `tools/ src/ package.json` (`publish-lib.sh:20-21`), no lockfile and no
  `node_modules`, so the same sources with different installed deps pass.
- NEW-FINDING: `--restore-manifest` checks the named layers exist but never re-runs the endpoint check
  (`publish-client:47-58`), so an old manifest pointing elsewhere is accepted.
- NEW-FINDING: `tools/release/routing-required.txt:5` (map `211080100` routed until client fix `3e640f1` is live) is
  enforced by `tools/release/gen-deploy:46-51`, not by `publish-client`, which never consults it.

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
