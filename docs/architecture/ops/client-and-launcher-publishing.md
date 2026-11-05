# Client and launcher publishing (server side)

## Purpose

The workstation scripts that take a Windows client build, prove it differs from the shipped client only where someone
said it may, and put it on the patch feed as an immutable layer with a release manifest pointing at it. The launcher's own
pipeline and the gates both share (news rules, layer immutability in `publish.mjs`, `--launcher` mode) are in
`../launcher/publishing.md`; this page is the operator side.

## Entry points

| Command (workstation → host unless noted) | Arguments | Does |
|---|---|---|
| `tools/release/stage-client <client-version>` | `--build-dir 'Z:\..\Release'` (required), `--wz F`, `--data 'Z:\..\x.img=Data/..'` (repeatable), `--from-layer V`, `--dry-run`, `--no-verify` (`stage-client:5-6,27-33`) | builds `/srv/slums-staging/client-<v>` (`:43`) |
| `tools/release/publish-client <v>` | `--source DIR` (required), `--base V`, `--release REL`, `--news FILE`, `--no-packs`, `--sync-publisher`, `--no-announce`, `--dry-run` (`:5-6,73-76`) | hash-diff, layer, release manifest (`:87-156`) |
| `tools/release/publish-client --restore-manifest FILE` | `[--dry-run]` (`:42-66`) | puts a saved `manifest.json` back |
| `tools/release/release <v>` | `--client-version`, `--source`, `--news`, `--no-packs`, `--no-client`, `--host-env`, `--inject`, `--yes`, `--dry-run` (`release:50-56`) | step 3 = `publish-client … --no-announce` with the server down (`:170-182`) |
| `tools/release/set-min-client-build <config.yaml> <build>` | `--dry-run`, `--allow-lower`, `--show` (`:4-5`) | rewrites the `MIN_CLIENT_BUILD:` line under `server:` |
| `tools/release/publish-launcher <build dir>` | `--version X.Y.Z`, `--out DIR`, `--sync-publisher`, `--dry-run` (`:4-18`) | launcher channel; see the sibling page |
| `slumsms-monitor client-ready live <v>` (host) | target, release (`publish-client:152-155`) | Cody's "client is in the launcher" post |

## How it works

Numbered operator path; each step is one command, each guard cited.

1. **Pick the gate first.** `MIN_CLIENT_BUILD` is a release-conf key (`docs/release/1.6.4.conf:284`) that `gen-deploy`
   copies into the deploy script (`gen-deploy:19,85-88`) and the apply step writes into live's config
   (`templates/deploy.body.sh:243`). Stage the client with that build number; nothing cross-checks the pair.
2. **Stage.** `stage-client` hashes `slums.dll`, `slums-build.txt`, `Custom.wz` on the build desktop
   (`stage-client:47,50-63`), scp's and re-hashes ("changed in transit", `:79-84`), parses the stamp (`:91-92`) and only
   *warns* on a release-prefix mismatch (`:93`) — see `../client/build-stamp-and-version.md:358`.
3. **Build the tree.** A full copy, not hardlinks (`cp -a --no-preserve=links -T`, `:118`), of the layer players have
   (`:96-99`); `slums.dll`, `Custom.wz`, `slums-build.txt` and `--data` targets are unlinked before the new bytes land so
   nothing is written through to another file (`:120,126-130`). `$OUT` must be empty (`:101`), `<v>` unpublished
   (`:102`), and both path shapes re-checked after the 1.4.0 incident (`:116-117`).
4. **Prove it.** `SlumsMS.exe` must still be the published bytes (`:134`) and the count must be
   `lcnt + 1 - had_btxt + added` (`:135-136`); the provenance note goes *beside* the tree (`:44,138-157`), then
   `publish-client <v> --source $OUT --dry-run` (`:159-161`).
5. **Choose the channel.** The channel *is* `PATCH_DIR` (`publish-lib.sh:2`) — there is no `--channel`. Live
   `/srv/slums-patch`, Test `…/launcher-test`, RC `…/launcher-rc` (`AGENTS.md:189,200-201`). An RC/Test run is
   `PATCH_DIR=… publish-client <v> --source …`: the injection ban keys off the live path (`publish-client:39`) and the
   endpoint check fires only when `PATCH_DIR` equals `LIVE_PATCH_DIR` (`:110`).
6. **Order inside `publish-client`**: version regex `:68` → option combos `:75-76` → workstation `:77` → source `:80` →
   **news first** `:83-85` → rerun reuse `:87-95` → base layer `:96-100` → no `launcher.config.json` `:101` → publisher
   identity `:102` → allow list to the host `:103` → hash-diff `:115-120` → layer `:135` → re-verify `:137-138` →
   manifest + backup `:140-146` → `/launcher-test/` mirror `:147-150` → Cody post `:152-156`.
7. **Live endpoint gate.** With `PATCH_DIR` equal to `LIVE_PATCH_DIR`, `verify` carries `--live-feed` (`:110-114`);
   `verify-client.py:129-150` compares the new layer's `config.ini` host/port with the layer live players have. Anything
   else prints `LIVE ENDPOINT FAIL` and dies "nothing published" (`publish-client:120`).
8. **Set the gate.** `set-min-client-build "$C" "$MIN_CLIENT_BUILD"` runs with the server stopped
   (`deploy.body.sh:242-243`), pre-validated `--dry-run` while live is up (`:123`); it keeps indent/CRLF/owner/mode
   (`:41-55`), treats the same value as a no-op (`:35`) and refuses a decrease without `--allow-lower` (`:34`). Consumer:
   `ClientVersionGate.java:110-111` (`0` = off, `ServerConfig.java:61`).
9. **Republish after a mistake.** Same command, same tree: `checks same-layer` (`publish_checks.py:104-120,145`) compares
   bytes — identical gives "reusing it, not publishing it again" (`publish-client:88-91`), different dies "layers are
   immutable; pick a new version" (`:93`); `--dry-run` prints that verdict first (`:124-128`). Only the manifest step is
   retryable (`release:178`, `docs/release/README.md:51`).
10. **Undo a manifest, or do it all at once.** `--restore-manifest` verifies every layer it names is still published
    (`:47-57`), keeps the replaced file as `manifest-replaced-<UTC>.json`, writes atomically via `.restore` + `mv`,
    mirrors `/launcher-test/` when `layers` is a symlink, and posts nothing (`:59-63`). `tools/release/release` wraps
    steps 4-7 and this one: pre-flight dry run (`:125`), step 3 with the server down (`:170-182`), and on failure
    `restore_manifest` then `--phase restart-old` (`:176-177`); `--inject` needs a scratch feed (`:61-65`). "Old server +
    old client, or new server + new client, never new client + old server" (`docs/release/README.md:44`).

**Manual vs automated.** What no script does, and therefore never checks:

| Manual | Automated |
|---|---|
| Building the Windows client/launcher on the desktop (`stage-client:40-41`; `AGENTS.md:189-191`) | hashing, transit re-hash, staging tree, hash-diff, layer + manifest publish |
| The owner's "go" for any Live feed write (`AGENTS.md:16-20`), and approving the Cody post | the Cody call itself (`publish-client:152-155`; `--no-announce` skips) |
| Keys: player patch key and dev key, by path only (`AGENTS.md:28-31`) | publisher identity, endpoint check, backup/restore, gate edit, mirror |

## Data

- **Live feed** `/srv/slums-patch` (`publish-lib.sh:2`): `manifest.json` (release pointer, no-cache), `layers/base/<v>/`,
  `layers/client/<v>/` — a `files/` tree plus a `manifest.json` at `schemaVersion: 2` (`publish_checks.py:93-101`); nginx
  site `slums-patch` on `:8090`, basic auth (`docs/ops/lxc125-architecture.md:100`), key and htpasswd by path only.
- **Test / RC feeds** `…/launcher-test/`, `…/launcher-rc/` (`docs/ops/lxc125-architecture.md:102`, `AGENTS.md:201`);
  Test's `layers` symlinks `../layers`, which is why the live manifest can be copied in as-is (`publish-client:147-149`).
- **Staging** `/srv/slums-staging` (`publish-lib.sh:3`): `client-<v>/`, `client-<v>.STAGED.txt`, `news-<v>.json`,
  `manifest-before-<release>.json` (the way back; a differing one is never overwritten, `publish-client:142-144`),
  `manifest-replaced-<UTC>.json`, `reference-client.hashes.json` (`:8`, a `(size, mtime)` cache, `verify-client.py:43-68`).
- **Transient on the host**: `/tmp/slums-client-allow.txt` (`publish-client:103,117`), a `.mapleroot-publish-*` temp dir
  inside the target layer while hashing, `/tmp/slums-launcher-dryrun.*` (`publish-launcher:28-29`). `release` tees its
  log to `dist/release-runs/release-<v>-<UTC>.log` (`release:68`); `publish-client` writes no log, it prints.

## Switches

| Switch | Default | Effect |
|---|---|---|
| `PATCH_DIR` | `/srv/slums-patch` (`publish-lib.sh:2`) | the channel being published to |
| `LIVE_PATCH_DIR` | `/srv/slums-patch` (`publish-client:108`) | the one feed where the endpoint check applies |
| `ALLOW_LIVE_ENDPOINT_CHANGE` | `0` (`publish-client:112`) | allow a deliberate live host/port move |
| `ANNOUNCE_TARGET` | `live` (`publish-client:70`) | channel the `client-ready` post names |
| `PUBLISH_TEST_FAIL` | empty (`:38`) | `after-layer`/`after-manifest`, refused on the live feed (`:39`) |
| `BUILD_HOST`, `BUILD_KEY` | `crims@192.168.0.186`, `$HOME/.ssh/id_rsprox_desktop` (`stage-client:40-41`) | the Windows build desktop |
| `STAGING_ROOT` | `/srv/slums-staging` (`stage-client:42`) | where the staging tree is built |
| `MIN_CLIENT_BUILD` | `0` = off (`ServerConfig.java:61`); 1.6.4 draft `10740` (`docs/release/1.6.4.conf:284`) | login gate |
| `RELEASE_HOST`, `DEPLOY_ROOT` | `root@192.168.0.164`, `/opt/mapleroot-deployments` (`lib.sh:4-5`) | SSH target, release folders |

Flags: `--dry-run` (checks and prints, writes nothing, `publish-client:124-128`), `--no-packs`, `--no-announce`,
`--sync-publisher` (`publish-lib.sh:24-26`), `--allow-lower`/`--show` (`set-min-client-build:23,30`). Whether an RC layer
must carry a different `config.ini` is **unknown** — no script sets or checks it.

## Invariants

- **A Live client layer must point at the live host and port.** `verify-client.py:141-148`: `if None in got or got !=
  want` → `LIVE ENDPOINT FAIL: this client would send live players to another server. Nothing may be published to the
  live feed with it`; reached from `publish-client:110-114` and again at `:138`. Host comparisons strip and lowercase
  (`verify-client.py:96`); a missing `config.ini` on either side fails (`:132-138`).
- **An RC/Test layer must never become what Live points at.** Only the operator-set `PATCH_DIR` enforces that: host/port
  equality is the sole check, so an RC build that keeps the live endpoint *passes*. An RC publish must also pass
  `--no-announce` or set `ANNOUNCE_TARGET`, else it announces on the live channel (`publish-client:70,152-155`).
- Layer version folders are append-only — differing tree for an existing `<v>` aborts (`:93`), an identical one is reused
  (`:88-91`); the release manifest is written after the layer and the second hash-diff (`:138,140-146`).
- News is validated before anything is published (`:83-85`; rules `publish_checks.py:42-60`), and what is staged is what
  was hashed: transit re-hash (`stage-client:82-87`), post-copy re-hash (`:122-124`), published manifest re-verified
  (`publish-client:138`).
- `SlumsMS.exe` is not reproducible, so a build never replaces it — only the published bytes are allowed
  (`stage-client:134`; `AGENTS.md:189-191`), and every difference from the reference is named path by path in
  `client-allow.txt:1-11`. `routing-required.txt:5` (map `211080100`) is enforced by `gen-deploy:46-52`, not here.

### The hash-diff rules (`verify-client.py`)

| Rule | Refuses / reports | Line |
|---|---|---|
| reference file absent from the effective set | `missing` (allowed: `SERVERNAME.exe`, `log.txt`) | `:193-196,203-204`; list `:13-14` |
| reference file with a different sha256 | `different` (allowed: overlay `.img`s, `config.ini`, `ijl15.dll`) | `:197-198`; list `:15-48` |
| path with no reference counterpart | `extra` (allowed: `Custom.wz`, `SlumsMS.exe`, …) | `:200`; list `:49-102` |
| a file the current release has and the new set lacks | `removed` (via `--previous`) | `:211-213,222` |
| any difference not matched in `client-allow.txt` | `bad += 1` → `HASH-DIFF FAIL: N unexpected differences` | `:202-204,233,236` |
| Live feed: `config.ini` missing on either side | `LIVE ENDPOINT FAIL` | `:132-138` |
| Live feed: host/port differ after strip+lowercase | `LIVE ENDPOINT FAIL` unless `--allow-endpoint-change` | `:96,141-148` |
| published layer re-check (`--client-manifest`) | `published layer <v> fails the hash-diff; manifest.json NOT updated` | `publish-client:137-138` |
| symlink or non-regular file in the tree | `same-layer` raises, never silently skips | `publish_checks.py:74-75,80-81,109-118` |

## Origin

Every path here post-dates the repack baseline `03d13cb21` (method: `../base-vs-custom.md`); the shell side is **custom**.
Later commits that shaped it: `aee6f180d` (live-endpoint refusal), `23eae9ffb` (`--no-packs` silently stopped the run),
`ae3c91cb7` (`/launcher-test/` mirror), `a50fe655f` (Cody post), `0ff53df38` (one release command).

| Path | First commit |
|---|---|
| `tools/release/publish-client`, `publish-launcher`, `publish-lib.sh`, `verify-client.py`, `client-allow.txt` | `9d437b372` (ticket 44, "Script the release pipeline") |
| `tools/release/publish_checks.py`, `test_publish_client_rerun.py` | `fb8286344` (b052) |
| `tools/release/stage-client` / `stage_client_lib.py` | `6a863e7be` / `e0bde38af` |
| `tools/release/set-min-client-build` | `82d4831b4` |
| `tools/release/routing-required.txt` | `3482b808c` |

## Tests and QA

`python3 -m unittest discover -s tools/release -p 'test_*.py'` (`tools/release/README.md:126`) — all offline, `ssh`/`scp`
stubbed (`test_publish_client_rerun.py:29-37`), scratch feed.

- `tools/release/test_publish_client_rerun.py` — `NewsRulesTest:55` runs 25 fixtures (`:56-63`) against the Python
  checker *and* the publisher's `validateManifest` (`:71`); `PublishClientRerunTest:84` shows bad news stops both the dry
  run and the real run before the layer (`:138-145`), a rerun reuses an identical layer (`:147-168`), a changed tree
  aborts (`:170-178`); `LayerManifestMirrorTest:182` pins odd file names against `publish.mjs` bytes (`:183-196`).
- `tools/release/test_live_endpoint.py` — passes (`:69`); test host/port refused (`:74`); case and spacing ignored
  (`:80`); no `config.ini` refused (`:84`); published manifest checked too (`:88`); a move needs the flag (`:94`); `main`
  fails on the endpoint even when the hash-diff passes (`:101`); `publish-client` passes `--live-feed` only for the live
  `PATCH_DIR` (`:122-125`).
- `tools/release/test_release_config.py` — gate helper: value written, lower refused with bytes unchanged (`:40-45`),
  idempotent with inode/mode/CRLF kept (`:47-59`), no-final-newline kept (`:61-65`), `--dry-run` writes nothing
  (`:75-78`); plus the ordering asserts for step 3 before apply-start and the deploy's `"$MIN_CLIENT_BUILD"` call
  (`:271,283-288`) and the `--no-packs` regression (`:289-299`). `test_stage_client_lib.py` covers `parse`/`data_spec`.

**Not covered:** `stage-client` end to end (every step is ssh/scp), `publish-launcher`, the `/launcher-test/` mirror
(`publish-client:147-150`), `--restore-manifest` failure paths beyond the dry run, and any real reference tree. Auto-QA
has no publishing scenario — nothing under `src/main/java/soloMapling/qa/` reads the feed, so `!qa` cannot see a bad layer.

## Known issues

- **b052** (S4, Ops) — layer published before the news list was checked; the row says "pushed, not merged", but
  `fb8286344` is an ancestor here, so the fix (`publish-client:83-95`) *is* in this tree. Card text is stale.
- **b270** (S3, Client) — a Live publish wrote `manifest.json` without `news[]`, failing `slumsms-news@live` because
  `--news` was not passed; `docs/release/RUNBOOK.md:49` now passes it. Deferred to 1.6.3, still open.
- **b251** (S2, Client) — Rose Garden (`211080100`) crash; the routing guard exists only because the client fix is not live
  (`routing-required.txt:5`, `gen-deploy:46-52`). **b300** (S2, Server) — Crack in Time trap. Together they set the
  1.6.4 gate to `10740` (`docs/release/1.6.4.conf:282-283`). **b017** (S3, Launcher) — Defender false positive, process
  not code.
- NEW-FINDING: the rehearsal guard compares `PATCH_DIR` as a raw string (`publish-client:39`) while the live-feed
  detection normalises a trailing slash (`:110`). `PATCH_DIR=/srv/slums-patch/ PUBLISH_TEST_FAIL=after-manifest` passes
  "rehearsal feeds only" and can then simulate a failure *after* a live manifest was written.
- NEW-FINDING: the RC channel named by `AGENTS.md:189` has no code path — nothing defaults to or creates `launcher-rc`,
  `publish-client` never re-checks that `PATCH_DIR` is a feed (`:80` only tests `--source`), and `publish-launcher`'s
  `--out` is unvalidated (`:15`), so a typo publishes into a fresh empty folder that answers 404.
- NEW-FINDING: `client-allow.txt` is copied to one fixed host path, `/tmp/slums-client-allow.txt`
  (`publish-client:103,117`); two overlapping publishes overwrite each other's review artefact mid-run.
- NEW-FINDING: `--restore-manifest` re-checks that layers exist but not the endpoint (`:47-57`), so restoring a manifest
  that names a client with another `config.ini` passes silently.

## State

**Working**, offline-proven, not runtime-verified from this repo. The tests drive the real `publish-client` and
`publish_checks.py` against a scratch feed, and the pipeline has run for real: `client-allow.txt:5` was captured from the
published 1.3.0 set, and ticket 44 recorded `publish-client` as `--dry-run` only with the host publisher out of sync
(`docs/tickets/44-release-pipeline.md:58-61`) — the state the identity check exists for. Not readable from here:
`/srv/slums-patch` is not on this workstation, so the live manifest, the layer list and whether `launcher-rc` exists are
unverified; live's client layer is `layers/client/1.2.1` per `docs/ops/lxc125-architecture.md:101` — a doc, not a read.

## Open questions

- **Which `PATCH_DIR` for a 1.6.4 RC?** Nothing records the exact command; `AGENTS.md:189` and
  `../launcher/publishing.md` say `launcher-rc`, nothing else names it.
- **Two values for the 1.6.4 gate.** `docs/release/1.6.4.conf:284` sets `10740` while that file's header says "No new
  client: MIN_CLIENT_BUILD stays 10721" (`:10-11`) and its in-game check still says "1.6.2 client, build 10721 or later"
  (`:297`); `docs/release/1.6.4-playbook.md:17,28` says 10740. Header and in-game line look stale; nothing cross-checks.
- **Is `client-allow.txt` current for 1.6.4?** It was captured from the 1.3.0 set (`:5`); a change inside an already
  listed glob passes, one outside fails. Whether the list was refreshed is not recorded.
