# Self-update and signing

## Purpose

The launcher replaces its own exe with a newer build from the patch feed it already reads, and nothing else. Trust is
one minisign (ed25519) key: the bytes must verify against the public key compiled into the binary, and the signature's
trusted comment must name exactly the version the feed announced. The swap is a rename plus a health check, so a build
that is signed but broken still cannot strand a player — the old exe goes back.

## Entry points

| Start it with | Where | Does |
|---|---|---|
| "Check for updates" / startup | `tauri/ui/renderer.mjs:483-486`, `:594` | `bridge.check()`, only with a configured feed |
| Tauri command `check` | `tauri/app/src/launcher.rs:1283-1298` | `self_update()` first, then the game-release check |
| `Launcher::self_update()` | `tauri/app/src/launcher.rs:753-785` | once-per-session guard, skip gates, error handling |
| `Launcher::try_self_update()` | `tauri/app/src/launcher.rs:787-852` | check, download, verify, swap |
| `--self-updated=<v>` / `--update-parent=<pid>` | `tauri/core/src/selfupdate.rs:117-118` | how the new build is told it was swapped in |
| healthy marker write | `tauri/app/src/launcher.rs:1145-1155` | first page ready, when self-updated or handed over |
| `selfupdate::cleanup()` | `tauri/app/src/launcher.rs:248` | clears old stage/marker files at startup |
| `node tools/publish.mjs --tauri-launcher …` | `tools/publish.mjs:549`, dispatch `:588-591` | publishes a signed build to a channel |
| `plugins.updater` | `tauri/app/tauri.conf.json:39-45` | pubkey, `requireSignedVersion`, endpoint list |

Two bins exist. `slumsms-launcher` is the product (`tauri/app/Cargo.toml:8-10`). The other,
`tauri/core/src/bin/fake-launcher.rs`, is a 23-line test double bound as `CARGO_BIN_EXE_fake-launcher`
(`tauri/core/tests/selfupdate.rs:10`); it never ships.

## How it works

Citations written `/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:N` / `config.rs:N` are the vendored crate
`/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/`. Signing/verifying inside
that crate is upstream code we do not modify.

**Consumer — check, verify, swap.**

1. Endpoint: `update_manifest_url` joins the channel feed with `tauri/launcher.json`
   (`tauri/core/src/channels.rs:58-62`) — Live `/tauri/launcher.json`, Test `/launcher-test/tauri/launcher.json`, RC
   `/launcher-rc/tauri/launcher.json` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:36-42`). The 60 s timeout is set on the builder
   (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:791`).
2. Credentials for the feed origin: `Authorization: Basic` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:792-794`) and `X-Slums-Pass`
   (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:795-796`; header name at `tauri/core/src/http.rs:18`).
3. "Is it an update?" is upstream: `release.version > current_version` (`/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:712-720`). Nothing newer →
   `"Self-update: no newer launcher on this channel."` and the game check proceeds (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:798-801`).
4. Policy re-check in our code: `update_decision` refuses a non-`MAJOR.MINOR.PATCH` version, a lower one, an equal one,
   and a version that already failed this session (`tauri/core/src/selfupdate.rs:31-39`).
5. Origin pin: `access.same_origin(&update.download_url)` must hold, else
   `"the update {v} is not on the patch server ({url}); refusing it"` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:807-810`; `/root/work/launcher-165-server/tauri/core/src/http.rs:104-106`).
6. Download and verify: `update.download(...)` buffers the body and calls `self.verify(&buffer)` before returning
   (`/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:881-893`). `verify_signature` decodes the base64 pubkey into a minisign `PublicKey`, decodes the
   base64 signature into a `Signature`, calls `public_key.verify(data, &signature, true)`, and only then reads the
   trusted comment (`/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:1893-1918`).
7. Version binding: `verify_signed_version` reads `version:` out of the trusted comment (`/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:1963-1967`);
   absent plus `requireSignedVersion` → `Error::MissingSignedVersion` (`:1930-1939`); unequal to the announced version
   → `Error::SignedVersionMismatch` (`:1951-1958`).
8. Shape gate: `looks_like_exe` requires `len > 1024` and an `MZ` prefix on Windows (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:42-48`), so a
   signed-but-wrong artifact is refused again (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:827-829`).
9. Stage: `stage()` writes `.slumsms-launcher-update/stage-<v>/<exe name>` beside the launcher, same volume
   (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:51-62`).
10. Swap: `swap_in()` renames the running exe to `rollback/<name>.<from>`, then the staged file into place; if the
    second rename fails the first is undone (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:71-85`).
11. Relaunch: window hidden, `start_and_confirm(…, 90 s)` spawns the new exe with `--self-updated=<v>` and
    `--update-parent=<pid>`, retrying `ETXTBSY` 20×50 ms (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:833-837`; `/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:114-130`).
12. Health: the new process writes `healthy-<v>` (contents: its pid) when its page first becomes ready
    (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1148-1154`); `wait_for` polls every 100 ms (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:148-166`).
13. Outcome: marker seen → the old process calls `app.exit(0)` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:839-843`). New process exited or the
    deadline passed → it is killed, `Rollback::restore` moves the backup back and keeps the failure as
    `failed-<v>-<name>` (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:132-137`, `:88-103`), the window is shown and that version is skipped for the
    session (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:844-850`).
14. Housekeeping: next start, `cleanup()` deletes `stage-*` at or below the running version, `healthy-*`, `started-*`
    and the Electron leftovers `swap-helper.mjs`/`swap-job.json`, and reports each `failed-*` as
    `"A launcher update failed and was rolled back: …"` (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:169-193`).

**Publisher — sign, publish, order.**

1. Sign on the dev box, binding the version:
   `cargo tauri signer sign -f /root/.config/slumsms-launcher/tauri-signing.key -p "" --app-version <v> <exe>`
   (`AGENTS.md:10-12`; `tauri/README.md:159-174`). Key path only; never read, printed or committed here.
2. `publishTauriLauncher` parses the `.sig` text for its tab-separated trusted comment (`tools/publish.mjs:477-484`)
   and refuses a signature whose `version:` differs from `--version`:
   `"The signature is for version X, not Y; sign with --app-version Y."` (`:503`).
3. It refuses a non-Windows file (`:505`) and a base URL that is not HTTPS (loopback allowed) or carries
   credentials/query/fragment (`:496-499`).
4. It refuses a version not newer than the channel's current one —
   `"Launcher {previous} is already published here; {v} is not newer (launchers never downgrade)."` (`:512-514`) — and
   an existing version directory (`:517`).
5. Order: `tauri/<v>/SlumsMS-Launcher.exe` via temp dir + rename (`:516-524`), *then* `tauri/launcher.json` via temp +
   rename (`:531-535`), so an aborted run leaves the old manifest pointing at a complete directory.
6. Channel JSON is `{version, notes, pub_date, platforms: {"windows-x86_64": {signature, url}}}` (`:525-530`). No
   `--dry-run`; a trial publishes into a throwaway folder (`tauri/docs/test-channel-runbook.md:22-24`).

## Data

| Location | Content | Written when |
|---|---|---|
| `<launcher dir>/.slumsms-launcher-update/stage-<v>/` | downloaded exe before the swap | each update (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:51-62`) |
| `…/rollback/<name>.<from>` | previous exe | each swap (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:74-79`) |
| `…/rollback/failed-<v>-<name>` | the build that never became healthy | on rollback (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:90-95`) |
| `…/healthy-<v>` | new process's pid | first page ready (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1149-1150`) |
| `…/swap-helper.mjs`, `…/swap-job.json` | Electron-era leftovers | removed by `cleanup` (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:178`) |
| `<feed>/tauri/launcher.json` | version, notes, pub_date, per-platform url + signature | publisher, last (`/root/work/launcher-165-server/tools/publish.mjs:531-535`) |
| `<feed>/tauri/<v>/SlumsMS-Launcher.exe` | immutable published exe | publisher, first (`:516-523`) |
| `<exe>.sig` beside a built exe | base64 of the 4-line minisign text | the signing step |
| `tauri/app/tauri.conf.json:41` | base64 of the minisign **public** key text | committed constant |

Signature shape, decoded from the shipped 1.6.5 artifacts: four lines — untrusted comment, a 74-byte packet with
algorithm `ED` (prehashed BLAKE2b-512) and key id `79FEB767F5F1D862`, `trusted comment: timestamp:…\tfile:…\t
version:1.6.5`, and a 64-byte global signature. `minisign-verify 0.3.0` enforces those lengths and the
`trusted comment: ` prefix (`/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/minisign-verify-0.3.0/src/lib.rs:235-267`), and verifies the
artifact signature *and* the global signature over signature‖trusted comment (`:334-346`), refusing
`UnexpectedKeyId` when key ids differ (`:357-359`). The conf key decodes to the same key id, a 42-byte `Ed` packet
(`:288-303`).

Nothing about the update is persisted: `skip_version` is memory only (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:105`, `:232`), and `Settings` has no
update field at all (`tauri/core/src/settings.rs:34-60`). **What is signed is the exe bytes plus the trusted comment —
not the `url` and not the `version` field of `launcher.json`.** That gap is what `requireSignedVersion` closes; the
plugin's own docs say so (`/root/work/launcher-165-server/tauri/core/src/config.rs:117-134`).

## Switches

| Switch | Value here | Effect |
|---|---|---|
| `plugins.updater.requireSignedVersion` | `true` (`/root/work/launcher-165-server/tauri/app/tauri.conf.json:42`) | signed version must equal the announced one |
| `plugins.updater.endpoints` | `[]` (`:43`) | endpoint supplied at runtime, so the feed follows the channel |
| `plugins.updater.pubkey` | the minisign key (`:41`) | the only trust root in the binary |
| `--no-self-update` / `SLUMSMS_NO_SELF_UPDATE=1` | off | skips the whole path (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:72`, `:761`) |
| `--self-test=<dir>` | none | skips self-update (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:60`, `:761`) |
| `cfg!(debug_assertions)` | — | debug builds never self-update (`:761`) |
| `--self-updated=<v>` | none | mislabelled-build guard: `v != VERSION` skips updates this session (`:764-769`) |
| `--self-update-failed=<v>` | none | merged into `skip` (`:802`); only the legacy Electron helper passes it (`src/self-update-helper.mjs:98,114`) |
| `--feed-url=`, `--install-dir=`, `--data-dir=` | from settings | passed through to the new process (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:81-96`) |

Channels: Live `/srv/slums-patch/` (player key), Test `/launcher-test/`, RC `/launcher-rc/` (dev key only)
(`AGENTS.md:13-16`); the built-in default feed is `https://slumspatch.asslorde.com/manifest.json`
(`tauri/launcher.config.json:2`, compiled in via `include_str!` at `/root/work/launcher-165-server/tauri/app/src/launcher.rs:31`). No launcher switch lives in the
server's live conf — `docs/release/1.6.4.conf:271` is the server `UNIT_EDITS` line. TEST: unknown (`start-test.sh` is
on mapletest, not read here). `dangerousInsecureTransportProtocol` is absent, so release builds reject a non-HTTPS
endpoint (`/root/work/launcher-165-server/tauri/core/src/config.rs:198-217`).

## Invariants

- **One trust root, public only, inside the binary.** Private key `/root/.config/slumsms-launcher/tauri-signing.key`,
  chmod 600, no password, outside the repo; "the key never leaves the dev box and is never printed"
  (`AGENTS.md:10-12`; `tauri/README.md:33`, `:159-162`). Losing it ends every future update for installed launchers
  (`tauri/README.md:161-162`).
- **Same key on every channel and in the hand-over build.** `tauri/app/tauri.conf.json:41` and
  `/root/work/launcher-tauri/tauri/app/tauri.conf.json:41` are byte-identical (compared by decoding both), and the
  release record relies on it: "Updater public key is the same as 1.5.2's, so Live 1.5.2 and the Electron hand-over
  can update to it" (`/root/work/launcher-tauri-release/RUNBOOK.md:101-103`).
- **The signature must be bound to the announced version.** Enforced by the publisher before publishing
  (`/root/work/launcher-165-server/tools/publish.mjs:503`) and by the consumer before the swap — the latter only because `requireSignedVersion` is `true`
  (`/root/work/launcher-165-server/tauri/app/tauri.conf.json:42` → `/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:1934-1939`). Turning it off would let a hostile feed pair a new version number
  with an older, validly signed build.
- **Never downgrade**, three independent refusals: `/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:34`, `/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:714`, `/root/work/launcher-165-server/tools/publish.mjs:512-514`.
- **Key and pass go only to the feed origin.** Checked before the download (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:807-810`); `/root/work/launcher-165-server/tauri/core/src/http.rs:122-134`
  drops both headers once a redirect leaves the origin and refuses an HTTPS→HTTP hop (`:129-131`).
- **The running exe is never overwritten.** It is renamed aside (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:79`); the staged file takes its name,
  and a failed second rename puts the first back (`:80-83`).
- **A build that never reports healthy is rolled back within 90 s** (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:837`; `/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:148-166`) and
  skipped for the session (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:848`).
- **Version directories immutable, manifest written last** (`/root/work/launcher-165-server/tools/publish.mjs:516-535`).
- **The webview cannot drive the updater**: `tauri/app/capabilities/default.json` grants only
  `core:event:allow-listen`/`allow-unlisten` on window `main`.

## Origin

**Custom.** Everything in this path starts at `bd3df88` "Start the Tauri launcher: Rust core, ported UI and app shell"
(`git log --follow --format=%h tauri/core/src/selfupdate.rs | tail -1`; same for `channels.rs`, `manifest.rs`,
`tauri/app/src/launcher.rs`, `tauri/app/tauri.conf.json`). `tools/publish.mjs` is older (`fcd6e08`); the
`--tauri-launcher` mode arrived with `test/tauri-publish.test.mjs` in `95ff73c` "Publish signed Tauri launcher builds
per channel; fix the Windows core build". `fake-launcher.rs` entered at `f63518b`. `selfupdate.rs` was touched twice
since: `2805cf4` (ETXTBSY retry, hang test) and `5c91c86` (update ownership). All cryptography is upstream:
`tauri-plugin-updater 2.14.0` (`tauri/Cargo.lock:3765-3767`) over `minisign-verify 0.3.0` (`:2037-2039`). Our code adds
policy, the swap and the health check; it never touches ed25519.

## Tests and QA

- `tauri/core/src/selfupdate.rs:195-264` — 4 unit tests under `#[cfg(all(test, unix))]` (they need shell-script exes):
  `decisions_never_downgrade_and_skip_a_failed_version` (:213),
  `a_healthy_new_build_stays_and_the_old_one_is_kept_for_rollback` (:224, also asserts `cleanup` removes the stage dir
  and marker), `a_build_that_exits_or_hangs_before_ready_is_rolled_back` (:241, `exit 3` and `sleep 30`),
  `a_failed_swap_leaves_the_launcher_in_place` (:258).
- `tauri/core/tests/selfupdate.rs` — 2 real-process tests: `:46` renames a running exe aside and keeps a healthy build
  (asserts the rollback copy holds the old bytes); `:61` loops `exit`/`hang` and asserts the exe comes back and a
  `failed-1.5.1` entry exists. Runs on Silvia's Windows as well (`tauri/docs/checkpoint.md:87-89`).
- `test/tauri-publish.test.mjs:32`, `:54` — 2 tests, both skipped without `cargo tauri` (`:7-10`). They generate a
  throwaway key (`cargo tauri signer generate --ci -p ''`) and a real `signer sign --app-version` (`:18-31`), then
  assert the exact channel JSON, the no-republish floor, the `/signature is for version 1\.5\.2/` refusal, HTTPS-only,
  no credentials, and the `MAJOR.MINOR.PATCH` rule.
- `/root/work/launcher-handover/test/handover.test.mjs:65` — the signature matrix against an **independent** verifier
  (`/root/work/launcher-handover/src/handover.mjs:76-100`, with its own BLAKE2b because Electron has none: `7f385b0`):
  a real signature verifies; a flipped byte, another key, another version and a tampered comment each fail distinctly.
- Auto-QA: exactly one launcher check and it is manual — `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:783`
  `t-la-1` "The Tauri launcher's own checklist". Nothing under `src/main/java/soloMapling/qa/` mentions the launcher,
  so there is no `!qa` scenario.

**Not covered.** No test signs with a *wrong* key and asks the **plugin** to verify it — only the JS verifier is tested
for that. No test asserts the committed `tauri.conf.json` pubkey still equals the hand-over's `TAURI_PUBLIC_KEY`
(`/root/work/launcher-handover/src/handover.mjs:19`), so the two can drift silently. Nothing re-verifies an already
published `launcher.json` signature. The health check is exercised only with shell scripts, never with a real Tauri
window.

## Known issues

- **b017** (S3, Launcher): Windows Defender can flag a newly built launcher exe; process, not code — the owner submits
  each exe to Microsoft.
- **b270** (S3, Client): a launcher news load failed because a published `manifest.json` carried no `news[]`.
- **o04** (Ops, later): move client and launcher builds off Silvia to tarkov2.
- **NEW-FINDING:** the 60 s timeout applies to `check()` only. `update_from_release` builds the `Update` with
  `timeout: None` (`/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:735`), and `Update::download` honours only that field (`:849-851`), so `/root/work/launcher-165-server/tauri/app/src/launcher.rs:791`
  does not bound the download: a feed that accepts the connection and stalls hangs the check with no deadline.
- **NEW-FINDING:** the download is buffered with no size cap (`/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:881-888`, `buffer.extend(chunk)`), where the
  Electron hand-over capped the same artifact at 64 MiB (`/root/work/launcher-handover/src/handover.mjs:22`). A feed
  serving a large body exhausts memory before the signature is ever checked.
- **NEW-FINDING:** `--self-update-failed=` is parsed and honoured (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:70`, `:802`) but this launcher never
  passes it: on a rollback it only sets the in-memory `skip_version` (`:848`). The Electron helper relaunches with the
  flag (`src/self-update-helper.mjs:98,114`); the Tauri path forgets the bad version when the process exits, so the
  next launch re-downloads and re-fails the same build.
- **NEW-FINDING:** a window exists with no launcher exe. `swap_in` renames the running exe away and only then renames
  the staged file in (`/root/work/launcher-165-server/tauri/core/src/selfupdate.rs:79-83`); the undo covers the *error* path only. If the machine dies or the process
  is killed between the two renames, the directory has no exe and no start-up code moves
  `rollback/<name>.<from>` back — `cleanup` merely reports `failed-*` entries (`:185-191`).

## State

**working**, on release-record evidence rather than a run here:

- 1.6.4 is Live since 2026-11-05 08:13 UTC, published after `verify-sig RESULT OK` and a hash match
  (`/root/work/launcher-tauri-release/RUNBOOK.md`, 1.6.4 section).
- 1.6.5 on RC and Test since 2026-11-05 08:43 UTC, signed `--app-version 1.6.5`, `verify-sig RESULT OK`, and the
  owner ran 1.6.4 → 1.6.5 on the RC channel.
- Publisher gates exercised per release: "trial publish into a temp folder OK, wrong version refused".
- `cargo test -p slums-core` 129 passed and `npm test` 109/109 at `f14e813` (RUNBOOK, 1.6.5 section).
- An unauthenticated client is refused upstream: without a key all three feeds and the RC exe answer 401.

## Open questions

- `tauri/docs/checkpoint.md:91-93` still lists "the plugin's own check and download against a real HTTPS channel" as
  not verified end to end, while the 1.6.5 RUNBOOK records exactly that happening (RC 1.6.4 → 1.6.5, owner's test).
  One of the two is stale; the checkpoint page should be corrected if it is.
- `verify-sig`, cited by every release record, lives in a skill and not in the repo
  (`/root/.claude/skills/launcher-release/scripts/verify-sig.mjs`); its default paths point at
  `/root/work/launcher-tauri` and `/root/work/launcher-handover`, different checkouts from `launcher-165-server`. Which
  checkout's `tauri.conf.json` did the 1.6.5 run read?
- The friends build injects the access key into `tauri/launcher.config.json` and rebuilds
  (`/root/work/launcher-tauri-release/friends/bake-tauri-key-164.ps1:6-14`), and the result is handed out **unsigned**
  (`RUNBOOK.md:57`), "never published". `verify-tauri-key.ps1` checks only the key, not the updater pubkey or a
  signature. Since it is never published, nothing verifies it — but nothing prevents that exe being baked from a source
  tree whose `pubkey` differs, and the pack script only greps for 1.5.2 leftovers
  (`/root/.claude/skills/launcher-release/scripts/make-bake-script.sh:26`).
- Live is 1.6.4 while 1.6.5 sits on RC/Test. Because all channels share one key and one pubkey, an RC exe can be pointed
  at the Live feed and will accept Live builds, and vice versa. Is that intended (channel is a setting, not a trust
  boundary)?
