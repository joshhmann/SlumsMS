# Client and launcher publishing (server side)

## Purpose

The workstation scripts in this repo that take a Windows client build, prove it differs from the shipped client only
where someone said it may, and put it on the patch feed as an immutable layer with a release manifest pointing at it —
plus the host/nginx layout that feed lives in. The launcher's own pipeline and the gates both pipelines share (news
rules, layer immutability inside `publish.mjs`, `--launcher` mode) are in `../launcher/publishing.md`; this page is the
operator side: which command on which channel, in what order, and what refuses.

## Entry points

| Command (workstation → host unless noted) | Arguments | Does |
|---|---|---|
| `tools/release/stage-client <client-version>` | `--build-dir 'Z:\..\Release'` (required), `--wz F`, `--data 'Z:\..\x.img=Data/..'` (repeatable), `--from-layer V`, `--dry-run`, `--no-verify` (`stage-client:5-6,27-33`) | builds `/srv/slums-staging/client-<v>` on the host (`:43`) |
| `tools/release/publish-client <v>` | `--source DIR` (required), `--base V`, `--release REL`, `--news FILE`, `--no-packs`, `--sync-publisher`, `--no-announce`, `--dry-run` (`:5-6,73-76`) | hash-diff, layer, release manifest (`:87-156`) |
| `tools/release/publish-client --restore-manifest FILE` | `[--dry-run]` (`:42-66`) | puts a saved `manifest.json` back |
| `tools/release/release <v>` | `--client-version`, `--source`, `--news`, `--no-packs`, `--no-client`, `--host-env`, `--inject`, `--yes`, `--dry-run` (`release:50-56`) | step 3 runs `publish-client … --no-announce` with the server down (`:170-182`) |
| `tools/release/set-min-client-build <config.yaml> <build>` | `--dry-run`, `--allow-lower`; `--show` (`set-min-client-build:4-5`) | rewrites the `MIN_CLIENT_BUILD:` line under `server:` |
| `tools/release/publish-launcher <build dir>` | `--version X.Y.Z`, `--out DIR`, `--sync-publisher`, `--dry-run` (`publish-launcher:4-18`) | launcher channel; see the sibling page |
| `slumsms-monitor client-ready live <v>` (host) | target, release (`publish-client:152-155`) | Cody's "client is in the launcher" post |

## How it works

The operator path, in order; each step is one command and each guard is cited.

1. **Set the gate value before staging.** `MIN_CLIENT_BUILD` is a release-conf key (`docs/release/1.6.4.conf:284`),
   read by `gen-deploy` into the generated deploy script (`tools/release/gen-deploy:19,85-88`) and written into live's
   config by the apply step (`tools/release/templates/deploy.body.sh:243`). Stage the client whose build number is that
   value; nothing cross-checks the two (see `../client/build-stamp-and-version.md:358`).
2. **Stage on the workstation.** `stage-client` hashes `slums.dll`, `slums-build.txt` and `Custom.wz` on the build
   desktop over SSH (`stage-client:47,50-63`), requires Windows-shaped paths (`:46`), scp's them to a temp dir and
   re-hashes (`:79-84`) — any mismatch dies "changed in transit" (`:82-84`) — then parses the stamp
   (`:91-92`, `stage_client_lib.py:16-24`) and only warns if the release prefix differs from `<client-version>`
   (`:93`).
3. **Build the staging tree.** It copies the published client layer players have (`$PATCH_DIR/layers/client/<from>/files`,
   default from the current `manifest.json`, `:96-99`) with `cp -a --no-preserve=links -T` — a full copy, not hardlinks
   (`:118`) — then unlinks `slums.dll Custom.wz slums-build.txt` before copying the new ones over (`:120`), so nothing is
   ever written through to another file. `--data` paths are unlinked, copied and re-hashed one by one (`:126-130`).
   Guards: `$OUT` must be absent or empty (`:101`) and `<v>` must not already be published (`:102`); both path shapes are
   re-checked by regex (`:116-117`).
4. **Prove the tree.** `SlumsMS.exe` must still be the published bytes (`:134`), and the file count must equal
   `lcnt + 1 - had_btxt + added` (`:135-136`) — one new `slums-build.txt`, one per new `--data` file, nothing else. The
   provenance note `client-<v>.STAGED.txt` is written *beside* the tree (`:44,138-157`) because every file inside it is
   published.
5. **Dry-run the publish.** Step 5 runs `publish-client <v> --source $OUT --dry-run` (`:159-161`) unless `--no-verify`.
6. **Publish to a channel.** The channel *is* `PATCH_DIR` (`publish-lib.sh:2`, overridable) — there is no `--channel`
   flag. Live is `/srv/slums-patch`; Test is `/srv/slums-patch/launcher-test`; RC is
   `/srv/slums-patch/launcher-rc` (`AGENTS.md:189,200-201`, `docs/ops/lxc125-architecture.md:102`). RC/Test runs are
   `PATCH_DIR=… publish-client <v> --source … [--release …]`; the ban on rehearsal injections is keyed to the live path
   (`publish-client:39`) and the live-endpoint check only fires when `PATCH_DIR` equals `LIVE_PATCH_DIR` (`:110`).
7. **What publish-client does, in order.** Version regex (`:68`) → option combinations (`:75-76`) → workstation check
   (`:77`) → source exists on the host (`:80`) → **news first** (`:83-85`) → rerun reuse (`:87-95`, below) → base layer
   must be published (`:96-100`) → no `launcher.config.json` in the tree (`:101`) → host publisher must match the
   launcher repo (`:102`, `publish-lib.sh:16-27`) → `client-allow.txt` copied to the host (`:103`) → hash-diff
   (`:115-120`) → publish the layer (`:135`) → hash-diff again on the published manifest (`:137-138`) → release manifest
   with the old one backed up (`:140-146`) → `/launcher-test/` mirror (`:147-150`) → Cody post (`:152-156`).
8. **Live only: the endpoint gate.** Because `PATCH_DIR` equals `LIVE_PATCH_DIR`, `verify` carries `--live-feed`
   (`:110-114`); `verify-client.py:129-150` compares the new layer's `config.ini [config] host/port` with the layer live
   players have now. Anything else — or a missing `config.ini` — prints `LIVE ENDPOINT FAIL` and the run dies
   "nothing published" (`publish-client:120`).
9. **Set the gate.** After the client is published and before the server starts, the apply step runs
   `set-min-client-build "$C" "$MIN_CLIENT_BUILD"` while the server is stopped (`deploy.body.sh:242-243`); the pre-check
   phase validates the same edit with `--dry-run` while live is still up (`:123`). The script rewrites one line, keeps
   indent/CRLF/owner/mode (`set-min-client-build:41-55`), treats the same value as a no-op (`:35`) and refuses a decrease
   without `--allow-lower` (`:34`). The runtime consumer is `ClientVersionGate.java:110-111`
   (`build >= config.MIN_CLIENT_BUILD` passes; `0` = off, `ServerConfig.java:61`).
10. **Republish after a mistake.** Same command, same tree: if the layer dir exists, `checks same-layer`
    (`publish_checks.py:104-120,145`) recomputes the manifest the tree *would* publish and compares bytes; identical
    means `LAYER_DONE=1` and "reusing it, not publishing it again" (`publish-client:88-91`), otherwise
    "layers are immutable; pick a new version" (`:93`). `--dry-run` prints the same verdict before anything runs
    (`:124-128`). Only the manifest step is retryable on the live feed once the layer landed; a *changed* tree needs a
    new client version (`tools/release/release:178`, `docs/release/README.md:51`).
11. **Undo a published manifest.** `--restore-manifest` verifies every layer it names is still published
    (`publish-client:47-57`), saves the manifest it replaces to `$STAGING_DIR/manifest-replaced-<UTC>.json`, writes
    atomically through `manifest.json.restore` + `mv`, mirrors `/launcher-test/` when `layers` is a symlink, and posts
    nothing (`:59-63`).
12. **One-command release.** `tools/release/release` does 5, 7 and 11 for you: pre-flight dry-run (`release:125`), step 3
    while the server is down (`:170-182`), and on failure `restore_manifest` then `--phase restart-old` (`:176-177`);
    `--inject publish-fail` only works with a scratch `PATCH_DIR` and `DEPLOY_ROOT` (`:61-65`). The rule it enforces is
    "old server + old client, or new server + new client, never new client + old server"
    (`docs/release/README.md:44`).

## Data

- **Feed, Live** `/srv/slums-patch` (`publish-lib.sh:2`): `manifest.json` (release pointer, served no-cache),
  `layers/base/<v>/`, `layers/client/<v>/` (each a `files/` tree plus a `manifest.json` at `schemaVersion: 2`,
  `publish_checks.py:93-101`). Served by nginx site `slums-patch` on `:8090` behind basic auth
  (`docs/ops/lxc125-architecture.md:100`). Keys and htpasswd files are referred to by path only
  (`AGENTS.md:28-31`).
- **Feed, Test / RC** `/srv/slums-patch/launcher-test/` and `/srv/slums-patch/launcher-rc/`
  (`docs/ops/lxc125-architecture.md:102`, `AGENTS.md:201`); the Test folder's `layers` is a symlink to `../layers`, which
  is why the live manifest can be copied in as-is (`publish-client:147-149`).
- **Staging** `/srv/slums-staging` (`publish-lib.sh:3`): `client-<v>/` trees, `client-<v>.STAGED.txt`, `news-<v>.json`,
  `manifest-before-<release>.json` (the way back, never silently overwritten, `publish-client:142-144`),
  `manifest-replaced-<UTC>.json`, and `reference-client.hashes.json` (`publish-lib.sh:8`, a `(size, mtime)` cache,
  `verify-client.py:43-68`). `stage-client` writes nothing else (`stage-client:22`).
- **On the host, transient**: `/tmp/slums-client-allow.txt` (`publish-client:103,117`), a `.mapleroot-publish-*` temp dir
  inside the target layer while hashing, and `/tmp/slums-launcher-dryrun.*` for a launcher dry-run
  (`publish-launcher:28-29`).
- **Logs**: a `release` run tees everything to `dist/release-runs/release-<v>-<UTC>.log` (`release:68`); `publish-client`
  itself writes no log file, it prints.

## Switches

| Switch | Default | Effect |
|---|---|---|
| `PATCH_DIR` | `/srv/slums-patch` (`publish-lib.sh:2`) | the channel being published to |
| `LIVE_PATCH_DIR` | `/srv/slums-patch` (`publish-client:108`) | the one feed where the endpoint check applies |
| `ALLOW_LIVE_ENDPOINT_CHANGE` | `0` (`publish-client:112`) | allow a deliberate live host/port move |
| `STAGING_DIR` | `/srv/slums-staging` (`publish-lib.sh:3`) | trees, news, manifest backups |
| `PUBLISHER`, `NODE` | `/opt/slums-tools/launcher`, `/opt/slums-tools/node/bin/node` (`:4-5`) | host publisher |
| `LAUNCHER_REPO`, `REFERENCE`, `REF_CACHE` | `/root/work/launcher`, `/opt/mapleroot-control/…/Game Files`, `$STAGING_DIR/reference-client.hashes.json` (`:6-8`) | identity check, hash-diff source, its cache |
| `ANNOUNCE_TARGET` | `live` (`publish-client:70`) | channel the `client-ready` post names |
| `PUBLISH_TEST_FAIL` | empty (`:38`) | `after-layer`/`after-manifest`, refused on the live feed (`:39`) |
| `BUILD_HOST`, `BUILD_KEY` | `crims@192.168.0.186`, `$HOME/.ssh/id_rsprox_desktop` (`stage-client:40-41`) | the Windows build desktop |
| `STAGING_ROOT` | `/srv/slums-staging` (`stage-client:42`) | where the staging tree is built |
| `MIN_CLIENT_BUILD` | `0` = off (`ServerConfig.java:61`); 1.6.4 draft `10740` (`docs/release/1.6.4.conf:284`) | login gate |
| `RELEASE_HOST`, `DEPLOY_ROOT` | `root@192.168.0.164`, `/opt/mapleroot-deployments` (`lib.sh:4-5`) | SSH target, release folders |

Flags: `--dry-run` (checks and prints, writes nothing, `publish-client:124-128`), `--no-packs`/`--no-announce`,
`--sync-publisher` (`publish-lib.sh:24-26`), `--allow-lower`/`--show` (`set-min-client-build:23,30`). Everything else —
which channel a Test or RC publish uses, and whether an RC layer must carry a different `config.ini` — is **unknown**;
no script sets it.

## Invariants

- **A Live client layer must point at the live host and port.** Enforced at `verify-client.py:141-148` — `if None in got
  or got != want` → `LIVE ENDPOINT FAIL: this client would send live players to another server. Nothing may be published
  to the live feed with it` — reached from `publish-client:110-114` and again at `:138`; host comparison strips and
  lowercases (`verify-client.py:96`), and a missing `config.ini` on either side fails (`:132-138`).
- **An RC/Test layer must never be the thing live points at.** Two mechanical consequences: an RC build published with
  `PATCH_DIR=/srv/slums-patch` is only stopped if its `config.ini` differs (so an RC layer that keeps the live endpoint
  *will* pass — the check is about the server, not about being an RC); and an RC publish must pass `--no-announce` or set
  `ANNOUNCE_TARGET`, because otherwise it announces on the live channel (`publish-client:70,152-155`). The gate that
  keeps RC and Test apart is the operator-set `PATCH_DIR`, not a check.
- Layer version folders are append-only: a differing tree for an existing `<v>` aborts (`publish-client:93`), an identical
  one is reused (`:88-91`).
- The release `manifest.json` is written after the layer and after the second hash-diff (`:138,140-146`); the old one is
  saved first and a differing backup is never overwritten (`:142-144`).
- The news file is validated before anything is published (`:83-85`; rules `publish_checks.py:42-60`).
- What is staged is what was hashed: transit re-hash (`stage-client:82-87`), post-copy re-hash (`:122-124`), published
  manifest re-verified against the reference (`publish-client:138`).
- `SlumsMS.exe` is not reproducible, so it is never replaced by a build — only the published bytes are allowed
  (`stage-client:134`; `AGENTS.md:189-191`).
- Every difference from the reference is named path by path in `client-allow.txt` (`:1-11`); a new unexpected file must
  fail. `routing-required.txt:5` (map `211080100`) is enforced by `gen-deploy:46-52`, not by any publishing script.

### The hash-diff rules (`verify-client.py`, table)

| Rule | Refuses / reports | Line |
|---|---|---|
| reference file absent from the effective set | `missing` (allowed: `SERVERNAME.exe`, `log.txt`) | `:193-196,203-204`; allow list `:13-14` |
| reference file with a different sha256 | `different` (allowed: the overlay `.img`s, `config.ini`, `ijl15.dll`) | `:197-198`; allow list `:15-48` |
| path present with no reference counterpart | `extra` (allowed: `Custom.wz`, `SlumsMS.exe`, …) | `:200`; allow list `:49-102` |
| file the current release has and the new set lacks | `removed` (via `--previous`) | `:211-213,222,236` |
| any difference not matched in `client-allow.txt` | `bad += 1` → `HASH-DIFF FAIL: N unexpected differences` | `:202-204,233,236` |
| Live feed: `config.ini` missing on either side | `LIVE ENDPOINT FAIL` | `:132-138` |
| Live feed: host/port differ after strip+lowercase | `LIVE ENDPOINT FAIL` unless `--allow-endpoint-change` | `:96,141-148` |
| published layer re-check (`--client-manifest`) | `published layer <v> fails the hash-diff; manifest.json NOT updated` | `publish-client:137-138` |
| a symlink or non-regular file in the tree | `same-layer`/manifest build raises, never skips | `publish_checks.py:74-75,80-81,109-118` |

## Origin

Every path here post-dates the repack baseline `03d13cb21` (method: `../base-vs-custom.md`); the shell side is **custom**
and shares nothing with the base tree.

| Path | First commit |
|---|---|
| `tools/release/publish-client`, `publish-launcher`, `publish-lib.sh`, `verify-client.py`, `client-allow.txt` | `9d437b372` (ticket 44: "Script the release pipeline") |
| `tools/release/publish_checks.py`, `test_publish_client_rerun.py` | `fb8286344` (b052) |
| `tools/release/stage-client` / `stage_client_lib.py` | `6a863e7be` / `e0bde38af` |
| `tools/release/set-min-client-build` | `82d4831b4` |
| `tools/release/routing-required.txt` | `3482b808c` |

Later commits that shaped this path: `aee6f180d` (live-endpoint refusal), `23eae9ffb` (`--no-packs` no longer stopped the
run silently), `fb8286344` (news before layer, layer reuse), `ae3c91cb7` (`/launcher-test/` mirror), `a50fe655f` (Cody
post), `0ff53df38` (one release command).

## Tests and QA

`python3 -m unittest discover -s tools/release -p 'test_*.py'` (`tools/release/README.md:126`) — all offline, with `ssh`
and `scp` stubbed (`test_publish_client_rerun.py:29-37`) and a scratch feed.

- `tools/release/test_publish_client_rerun.py` — `NewsRulesTest:55` runs 23 fixtures (`:56-63`) against both the Python
  checker and the publisher's `validateManifest` (`:71`); `PublishClientRerunTest:84` shows bad news stops the dry run
  *and* the real run before the layer (`:138-145`), a rerun reuses an identical layer (`:147-168`), and a changed tree
  still aborts (`:170-178`); `LayerManifestMirrorTest:182` pins odd file names against `publish.mjs` bytes (`:183-196`).
- `tools/release/test_live_endpoint.py` — endpoint passes (`:69`); a test host, port 7575 or `192.168.0.164:8484` is
  refused (`:74`); case/spacing ignored (`:80`); no `config.ini` refused (`:84`); a published manifest checked too
  (`:88`); a move needs the flag (`:94`); `main` fails on the endpoint even when the hash-diff passes (`:101`); and
  `publish-client` passes `--live-feed` only for the live `PATCH_DIR` (`:122-125`).
- `tools/release/test_release_config.py` — the gate helper: value written and a lower value refused unchanged (`:40-45`),
  idempotent with inode/mode/CRLF preserved (`:47-59`), no-final-newline preserved (`:61-65`), `--dry-run` writes nothing
  (`:75-78`); plus the ordering asserts that step 3 precedes apply-start and that the deploy carries
  `"$MIN_CLIENT_BUILD"` (`:271,283-288`) and the `--no-packs` regression (`:289-299`).
- `tools/release/test_stage_client_lib.py` — `parse` (exact bytes, CRLF, missing build) and `data_spec` mapping.

**Not covered:** `stage-client` end to end (every step is ssh/scp), `publish-launcher`, the `/launcher-test/` mirror
(`publish-client:147-150`), `--restore-manifest` failure paths beyond the dry-run, `set-min-client-build --show`, and any
real 22 GB reference tree. Auto-QA has no publishing scenario: nothing under `src/main/java/soloMapling/qa/` reads the
patch feed, so no `!qa` run can see a bad layer.

## Known issues

- **b052** (S4, Ops) — layer published before the news list was checked; the row says "pushed, not merged" but
  `fb8286344` is an ancestor of this tree, so the fix (`publish-client:83-95`) is live here. Card text is stale.
- **b270** (S3, Client) — a Live publish wrote `manifest.json` without `news[]`, failing `slumsms-news@live`; cause was
  `--news` not passed. `docs/release/RUNBOOK.md:49` now passes it; deferred to 1.6.3, still open (`STATUS-1.6.4.md:75`).
- **b251** (S2, Client) — Rose Garden (`211080100`) crash; the routing guard exists only because the client fix is not
  live (`routing-required.txt:5`, `gen-deploy:46-52`).
- **b300** (S2, Server) — Crack in Time trap; together with b251 it is why the 1.6.4 gate is `10740`
  (`docs/release/1.6.4.conf:282-283`).
- **b017** (S3, Launcher) — a new launcher exe can be flagged by Defender; a submission process, not code.
- NEW-FINDING: the rehearsal guard compares `PATCH_DIR` as a raw string (`publish-client:39`) while the live-feed
  detection normalises a trailing slash (`:110`). `PATCH_DIR=/srv/slums-patch/ PUBLISH_TEST_FAIL=after-manifest` passes
  the "rehearsal feeds only" test and can then simulate a failure *after* a live manifest was written.
- NEW-FINDING: the RC channel named by `AGENTS.md:189` has no code path. No script defaults to or creates
  `launcher-rc`, `publish-client` never re-checks that `PATCH_DIR` is a feed (`:80` only tests `--source`), and
  `publish-launcher`'s `--out` is unvalidated (`:15`) — a typo publishes into a fresh, empty folder that answers 404.
- NEW-FINDING: `client-allow.txt` is copied to one fixed host path, `/tmp/slums-client-allow.txt`
  (`publish-client:103,117`). Two overlapping publishes (an RC dry-run beside a live run) overwrite each other's review
  artefact mid-run.
- NEW-FINDING: `publish-client --restore-manifest` re-checks that layers exist but not the endpoint (`:47-57`), so
  restoring a manifest that points at an older client with another `config.ini` passes silently.

## State

**Working**, offline-proven, not runtime-verified from this repo. `test_publish_client_rerun.py` and
`test_live_endpoint.py` exercise the real `publish-client` and `publish_checks.py` against a scratch feed. The pipeline
has run for real: `client-allow.txt:5` was captured from the published 1.3.0 set, and ticket 44 recorded
`publish-client` as `--dry-run` only with the host publisher out of sync (`docs/tickets/44-release-pipeline.md:58-61`),
which is the state the identity check exists for. What cannot be read from here: `/srv/slums-patch` does not exist on
this workstation, so the live feed's current `manifest.json`, layer list and whether `launcher-rc` exists at all are
unverified. Live's layer is `layers/client/1.2.1` per `docs/ops/lxc125-architecture.md:101` — a doc, not a read.

## Open questions

- **Which `PATCH_DIR`.** No script or doc records the exact command for a 1.6.4 RC publish; `AGENTS.md:189` says
  `launcher-rc`, `../launcher/publishing.md` says the same, and nothing else names it. TEST: unknown.
- **Three values for the 1.6.4 gate.** `docs/release/1.6.4.conf:284` sets `10740`; that same file's header says "No new
  client: MIN_CLIENT_BUILD stays 10721" (`:10-11`) and its in-game check still says "1.6.2 client, build 10721 or later"
  (`:297`), while `docs/release/1.6.4-playbook.md:17,28` says 10740. Four citations, two values — the conf header and the
  in-game line look stale, but nothing in the scripts cross-checks them.
- **Does the RC layer need its own `config.ini`?** An RC build pointed at a test server is not checked at all
  (`publish-client:105-107`), so nothing stops that layer later being published to the live feed *if* its `config.ini`
  happens to match live's. Whether anyone diffs RC and Live layers before promotion is unknown.
- **Is `client-allow.txt` current for 1.6.4?** It was captured from the 1.3.0 set (`:5`); a 1.6.4 overlay changing files
  inside the already-listed globs would pass, one outside them fails. Whether the list was refreshed for 1.6.4 is not
  recorded.
