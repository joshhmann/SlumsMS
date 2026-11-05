# Self-update and signing

## Purpose

The launcher replaces its own exe with a newer build from the patch feed it already reads, and nothing else. Trust is
one minisign (ed25519) key: the bytes must verify against the public key compiled into the binary, and the signature's
trusted comment must name exactly the version the feed announced. The swap is a rename plus a health check, so a build
that is signed but broken still cannot strand a player — the old exe goes back.

## Entry points

| Start it with | Where | Does |
|---|---|---|
| "Check for updates" / startup | `tauri/ui/renderer.mjs:483-486`, `:594` | `bridge.check()`, guarded on a configured feed URL |
| Tauri command `check` | `tauri/app/src/launcher.rs:1283-1298` | `self_update()` first, then the game-release check |
| `Launcher::self_update()` | `tauri/app/src/launcher.rs:753-785` | once-per-session guard, skip gates, error handling |
| `Launcher::try_self_update()` | `tauri/app/src/launcher.rs:787-852` | the actual check, download, verify, swap |
| `--self-updated=<v>` `--update-parent=<pid>` | `tauri/core/src/selfupdate.rs:117-118` | the new build's marker that it was started by an updater |
| healthy marker write | `tauri/app/src/launcher.rs:1145-1155` | first window ready, `--self-updated`/`--handover-from` only |
| `selfupdate::cleanup()` | `tauri/app/src/launcher.rs:248` | clears old stage/rollback evidence at startup |
| `node tools/publish.mjs --tauri-launcher …` | `tools/publish.mjs:549`, dispatch `:588-591` | publishes a signed build to a channel |
| `plugins.updater` | `tauri/app/tauri.conf.json:39-45` | pubkey, `requireSignedVersion`, empty endpoint list |

Both binaries in the workspace ship from `tauri/app` (`tauri/app/Cargo.toml:8-10`, bin `slumsms-launcher`). The only
other bin is a test double: `tauri/core/src/bin/fake-launcher.rs` (23 lines, `tauri/core/tests/selfupdate.rs:10` binds
it as `CARGO_BIN_EXE_fake-launcher`); it is not part of a release.

## How it works

**Consumer flow — check, verify, swap.**

1. Endpoint. `update_manifest_url(&e.feed_url)` joins the channel feed with `tauri/launcher.json`
   (`tauri/core/src/channels.rs:58-62`), so Live is `/tauri/launcher.json`, Test `/launcher-test/tauri/launcher.json`,
   RC `/launcher-rc/tauri/launcher.json` (`channels.rs:36-42`). The 60 s timeout is set on `check()`
   (`launcher.rs:791`).
2. Credentials. The same feed origin gets `Authorization: Basic` (`launcher.rs:792-794`) and `X-Slums-Pass`
   (`launcher.rs:795-796`, `tauri/core/src/http.rs:18`).
3. The plugin decides "is this an update": `release.version > current_version` (`tauri-plugin-updater 2.14.0`
   `src/updater.rs:712-720`). Nothing newer → `"Self-update: no newer launcher on this channel."` and the game check
   runs (`launcher.rs:798-801`).
4. Policy re-check in our code: `update_decision` refuses a non-`MAJOR.MINOR.PATCH` version, a lower one, an equal one,
   and a version that already failed this session (`tauri/core/src/selfupdate.rs:31-39`).
5. Origin pin. `access.same_origin(&update.download_url)` must hold, else
   `"the update {v} is not on the patch server ({url}); refusing it"` (`launcher.rs:807-810`;
   `http.rs:104-106`).
6. Download and verify. `update.download(...)` buffers the body and calls `self.verify(&buffer)` before returning
   (plugin `updater.rs:881-893`). `verify_signature` decodes the base64 pubkey into a minisign `PublicKey`, decodes the
   base64 signature into a `Signature`, calls `public_key.verify(data, &signature, true)`, and only then reads the
   trusted comment (plugin `updater.rs:1893-1918`).
7. Version binding. `verify_signed_version` takes the `version:` field out of the trusted comment
   (plugin `updater.rs:1963-1967`); missing and `requireSignedVersion` → `Error::MissingSignedVersion`
   (`:1930-1939`); unequal to the announced version → `Error::SignedVersionMismatch` (`:1951-1958`).
8. Shape gate. `looks_like_exe` requires `len > 1024` and an `MZ` prefix on Windows (`selfupdate.rs:42-48`), so a
   signed-but-wrong artifact is refused again (`launcher.rs:827-829`).
9. Stage. `stage()` writes `.slumsms-launcher-update/stage-<version>/<exe name>` on the same volume
   (`selfupdate.rs:51-62`).
10. Swap. `swap_in()` renames the running exe to `rollback/<name>.<from_version>`, then the staged file into place; if
    the second rename fails the first is undone (`selfupdate.rs:71-85`).
11. Relaunch. The window is hidden, and `start_and_confirm(..., 90 s)` spawns the new exe with `--self-updated=<v>` and
    `--update-parent=<pid>`, retrying `ETXTBSY` 20×50 ms (`launcher.rs:833-837`; `selfupdate.rs:114-130`).
12. Health. The new process writes `healthy-<version>` (contents: its pid) when its page first becomes ready
    (`launcher.rs:1148-1154`). `wait_for` polls every 100 ms (`selfupdate.rs:148-166`).
13. Outcome. Marker seen → the old process calls `app.exit(0)` (`launcher.rs:839-843`). New process exited or the
    deadline passed → it is killed, `Rollback::restore` moves `rollback/<name>.<from>` back and keeps the failure as
    `failed-<version>-<name>` (`selfupdate.rs:132-137`, `:88-103`), the window is shown again and the version is
    skipped for this session (`launcher.rs:844-850`).
14. Housekeeping. Next start, `cleanup()` deletes `stage-*` at or below the running version, every `healthy-*` and
    `started-*`, and the Electron leftovers `swap-helper.mjs` / `swap-job.json`; it reports each `failed-*` back as
    `"A launcher update failed and was rolled back: …"` (`selfupdate.rs:169-193`).

**Publisher flow — sign, publish, order.**

1. Sign on the dev box with the private key, binding the version:
   `cargo tauri signer sign -f /root/.config/slumsms-launcher/tauri-signing.key -p "" --app-version <v> <exe>`
   (`AGENTS.md:10-12`, `tauri/README.md:159-174`). Key path only; it is never read, printed or committed.
2. `publishTauriLauncher` parses the `.sig` text for its tab-separated trusted comment (`tools/publish.mjs:477-484`)
   and refuses a signature whose `version:` is not the `--version` given:
   `"The signature is for version X, not Y; sign with --app-version Y."` (`:503`).
3. It refuses a non-Windows file (`:505`), a base URL that is not HTTPS (loopback HTTP allowed) or carries
   credentials/query/fragment (`:496-499`).
4. It refuses a version that is not newer than what the channel already announces:
   `"Launcher {previous} is already published here; {v} is not newer (launchers never downgrade)."` (`:512-514`), and
   a version directory that already exists (`:517`).
5. Order: write `tauri/<version>/SlumsMS-Launcher.exe` via a temp dir + rename (`:516-524`), *then* write
   `tauri/launcher.json` via temp + rename (`:531-535`). An aborted run leaves the old manifest pointing at a complete
   directory.
6. Resulting channel JSON: `{version, notes, pub_date, platforms: {"windows-x86_64": {signature, url}}}` (`:525-530`).
   There is no `--dry-run`; trials publish into a throwaway folder (`tauri/docs/test-channel-runbook.md:22-23`).

## Data

| Location | Content | Written when |
|---|---|---|
| `<launcher dir>/.slumsms-launcher-update/stage-<v>/` | downloaded exe before the swap | each update (`selfupdate.rs:51-62`) |
| `…/rollback/<name>.<from version>` | the previous exe | each swap (`selfupdate.rs:74-79`) |
| `…/rollback/failed-<v>-<name>` | the build that failed to become healthy | on rollback (`selfupdate.rs:90-95`) |
| `…/healthy-<v>` | the new process's pid | first page ready (`launcher.rs:1149-1150`) |
| `…/swap-helper.mjs`, `…/swap-job.json` | Electron-era leftovers | deleted by `cleanup` (`selfupdate.rs:178`) |
| `<feed>/tauri/launcher.json` | version, notes, pub_date, per-platform url + signature | publisher, last (`publish.mjs:531-535`) |
| `<feed>/tauri/<v>/SlumsMS-Launcher.exe` | immutable published exe | publisher, first (`:516-523`) |
| `<exe>.sig` beside a built exe | base64 of the 4-line minisign text | signing step |
| `tauri/app/tauri.conf.json:41` | base64 of the minisign **public** key text | committed constant |

Signature shape, checked against the shipped 1.6.5 artifacts: four lines (untrusted comment, 74-byte signature packet
with algorithm `ED` = prehashed BLAKE2b-512 and key id `79FEB767F5F1D862`, `trusted comment: timestamp:…\tfile:…\t
version:1.6.5`, 64-byte global signature). `minisign-verify 0.3.0` enforces exactly those lengths and that the trusted
comment starts `trusted comment: ` (`/root/.cargo/registry/src/*/minisign-verify-0.3.0/src/lib.rs:236-263`) and checks
the artifact signature *and* the global signature over signature‖trusted comment (`:333-347`), with `UnexpectedKeyId`
when key ids differ (`:352-371`). The conf key decodes to the same key id and a 42-byte `Ed` public packet.

Nothing about the update is persisted to `settings.json` or `state.json`: `skip_version` is memory only
(`launcher.rs:105`), and no record of a verified signature survives the process. **What is signed is the exe bytes and
the trusted comment — not the `url` and not the `version` field of `launcher.json`.** That gap is exactly what
`requireSignedVersion` closes, and the plugin's own config docs say so
(`/root/.cargo/registry/src/*/tauri-plugin-updater-2.14.0/src/config.rs:117-134`).

## Switches

| Switch | Default | Effect |
|---|---|---|
| `plugins.updater.requireSignedVersion` | `true` (`tauri.conf.json:42`) | signed version must equal the announced one |
| `plugins.updater.endpoints` | `[]` (`:43`) | the endpoint is supplied at runtime, so the feed URL follows the channel |
| `plugins.updater.pubkey` | the minisign key (`:41`) | the only trust root in the binary |
| `--no-self-update` / `SLUMSMS_NO_SELF_UPDATE=1` | off | skips the whole path (`launcher.rs:72`, `:761`) |
| `--self-test=<dir>` | none | skips self-update (`launcher.rs:60`, `:761`) |
| `cfg!(debug_assertions)` | — | debug builds never self-update (`:761`) |
| `--self-updated=<v>` | none | mislabelled-build guard: if `v != VERSION`, updates are skipped for the session (`:764-769`) |
| `--self-update-failed=<v>` | none | merges into `skip` (`:802`); only the legacy Electron helper passes it (`src/self-update-helper.mjs:98,114`) |
| `--feed-url=`, `--install-dir=`, `--data-dir=` | from settings | passed through to the new process (`launcher.rs:81-96`) |

Channels are Live `/srv/slums-patch/` (player key), Test `/launcher-test/` and RC `/launcher-rc/` (dev key only)
(`AGENTS.md:13-16`); `tauri/launcher.config.json:2` defaults the feed to `https://slumspatch.asslorde.com/manifest.json`.
No launcher switch lives in the server's live conf: `docs/release/1.6.4.conf:271` is the server `UNIT_EDITS` line.
TEST: unknown (`start-test.sh` is on mapletest and was not read here). `dangerousInsecureTransportProtocol` is absent,
so release builds refuse a non-HTTPS endpoint outright
(`tauri-plugin-updater-2.14.0/src/config.rs:198-217`).

## Invariants

- **One trust root, public only, in the binary.** Private key `/root/.config/slumsms-launcher/tauri-signing.key`,
  chmod 600, no password, outside the repo; "the key never leaves the dev box and is never printed"
  (`AGENTS.md:10-12`; `tauri/README.md:159-162`). Losing it ends all future updates for installed launchers
  (`tauri/README.md:161-162`).
- **Same key on every channel and in the hand-over build.** Live, RC, Test and the Electron hand-over all verify
  against the same public key, so one signature works everywhere and an installed launcher can cross channels
  (`/root/work/launcher-tauri-release/RUNBOOK.md:101-103`; the pubkey in `/root/work/launcher-tauri/tauri/app/
  tauri.conf.json:41` is byte-identical to `tauri/app/tauri.conf.json:41`).
- **The signature must be bound to the announced version.** Enforced on the publisher side before publishing
  (`publish.mjs:503`) and on the consumer side before the swap, only because `requireSignedVersion` is `true`
  (`tauri.conf.json:42` → plugin `updater.rs:1934-1939`). Turning it off would let a hostile feed pair a new version
  number with an old, validly signed build.
- **Never downgrade.** Three independent refusals: `selfupdate.rs:34`, plugin `updater.rs:714`, publisher
  `publish.mjs:512-514`.
- **The key and the pass go only to the feed origin.** `same_origin` is checked before the download
  (`launcher.rs:807-810`), and `http.rs:122-134` drops both headers once a redirect leaves the origin and refuses an
  HTTPS→HTTP hop (`:129-131`).
- **The running exe is never overwritten.** It is renamed aside (`selfupdate.rs:76-79`); the staged file takes its
  name. A failed second rename puts the first back (`:80-83`).
- **A build that does not report healthy is rolled back within 90 s** (`launcher.rs:837`, `selfupdate.rs:148-166`),
  and that version is skipped for the session (`launcher.rs:848`).
- **Immutable version directories, manifest written last** (`publish.mjs:516-535`).
- **The webview cannot drive the updater.** `tauri/app/capabilities/default.json` grants only
  `core:event:allow-listen` / `allow-unlisten` on window `main`, so updates happen through our own command.

## Origin

**Custom.** Every file in this path starts at `bd3df88` "Start the Tauri launcher: Rust core, ported UI and app shell"
(`git log --follow --format=%h tauri/core/src/selfupdate.rs | tail -1`; same for `channels.rs`, `manifest.rs`,
`tauri/app/src/launcher.rs`, `tauri/app/tauri.conf.json`). `tools/publish.mjs` is older, `fcd6e08`; the
`--tauri-launcher` mode arrived in `95ff73c` "Publish signed Tauri launcher builds per channel; fix the Windows core
build" (`git log --follow --format=%h test/tauri-publish.test.mjs | tail -1`). `fake-launcher.rs` entered at `f63518b`.
`selfupdate.rs` was later touched by `2805cf4` (ETXTBSY retry, hang test) and `5c91c86` (update ownership). The
signature *checking* is entirely upstream: `tauri-plugin-updater 2.14.0` (`tauri/Cargo.lock:3765-3767`) over
`minisign-verify 0.3.0` (`:2037-2039`). Our code adds policy, the swap, and the health check; it never touches
ed25519.

## Tests and QA

- `tauri/core/src/selfupdate.rs:195-264` — 4 unit tests, `#[cfg(all(test, unix))]` because they need shell-script
  exes: `decisions_never_downgrade_and_skip_a_failed_version` (:213), `a_healthy_new_build_stays_and_the_old_one_is_kept_for_rollback`
  (:224, also asserts `cleanup` removes the stage dir and marker), `a_build_that_exits_or_hangs_before_ready_is_rolled_back`
  (:241, both `exit 3` and `sleep 30`), `a_failed_swap_leaves_the_launcher_in_place` (:258).
- `tauri/core/tests/selfupdate.rs` — 2 real-process tests: `:46` renames a running exe aside and keeps a healthy build
  (asserts the rollback copy holds the old bytes); `:61` loops the failure modes and asserts a `failed-1.5.1` entry.
  Runs on Silvia's Windows too (`tauri/docs/checkpoint.md:83-89`).
- `test/tauri-publish.test.mjs:32`, `:54` — 2 tests, both skipped unless `cargo tauri` is installed (`:7-10`). They
  generate a throwaway key with `cargo tauri signer generate --ci -p ''` and a real `signer sign --app-version`
  (`:18-31`), then assert the exact channel JSON, the floor on republishing, the `/signature is for version 1\.5\.2/`
  refusal, HTTPS-only, no credentials, and `MAJOR.MINOR.PATCH`.
- `/root/work/launcher-handover/test/handover.test.mjs:65` — the signature matrix, against an **independent** verifier
  (`src/handover.mjs:76-100`, its own BLAKE2b because Electron lacks it: `7f385b0`): a real signature verifies; a
  flipped byte, another key, another version and a tampered comment each fail with a distinct message.
- Auto-QA: exactly one launcher check exists and it is manual —
  `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:783` `t-la-1` "The Tauri launcher's own checklist". Nothing
  under `src/main/java/soloMapling/qa/` mentions the launcher, so there is no `!qa` scenario.

**Not covered:** no test signs with a *wrong* key and asks the *plugin* to verify it — only the JS verifier is tested
for that case, so the plugin's key-id refusal is trusted from upstream code. No test asserts that the committed
`tauri.conf.json` pubkey still equals the hand-over's `TAURI_PUBLIC_KEY`; nothing in the repo re-verifies a published
`launcher.json` signature. There is no test for the case where the feed serves a valid signature for a *different*
version (covered for the publisher in `tauri-publish.test.mjs`, not for the consumer). The health check is tested with
shell scripts only, never with a real Tauri window on Windows in CI.

## Known issues

- **b017** (S3, Launcher): Windows Defender can flag a newly built launcher exe as a false positive; process, not code
  — the owner submits each exe to Microsoft.
- **b270** (S3, Client): a launcher news load failed because a published `manifest.json` carried no `news[]`.
- **o04** (Ops, later): move client and launcher builds off Silvia to tarkov2.
- **NEW-FINDING:** the 60 s timeout is set on the builder and applied to `check()` only. `update_from_release`
  constructs the `Update` with `timeout: None`
  (`tauri-plugin-updater-2.14.0/src/updater.rs:735`), and `Update::download` honours only *that* field (`:849-851`), so
  `launcher.rs:791` does not bound the download: a feed that accepts the connection and stalls hangs the check with no
  deadline.
- **NEW-FINDING:** the download is buffered with no size cap (`updater.rs:881-888` `buffer.extend(chunk)`), while the
  Electron hand-over capped the same artifact at 64 MiB (`/root/work/launcher-handover/src/handover.mjs:22`). A feed
  that serves a large body can exhaust memory before the signature is ever checked.
- **NEW-FINDING:** `--self-update-failed=` is parsed and honoured (`launcher.rs:70`, `:802`) but this launcher never
  passes it: on a rollback it only sets the in-memory `skip_version` (`:848`). The Electron helper relaunches with the
  flag (`src/self-update-helper.mjs:98,114`); the Tauri path forgets the bad version as soon as the process exits, so
  the next launch re-downloads and re-fails the same build.
- **NEW-FINDING:** there is a window with no launcher exe. `swap_in` renames the running exe away and only then renames
  the staged file in (`selfupdate.rs:76-83`); the undo exists for the *error* path only. If the machine dies or the
  process is killed between the two renames, the launcher directory has no exe and no start-up code moves
  `rollback/<name>.<from>` back — `cleanup` only reports `failed-*` entries (`:185-191`).

## State

**working**, with the end-to-end evidence gathered from the release record rather than from a run here:

- 1.6.4 is Live since 2026-11-05 08:13 UTC, published after `verify-sig RESULT OK` and a hash match
  (`/root/work/launcher-tauri-release/RUNBOOK.md`, 1.6.4 section).
- 1.6.5 is on RC and Test since 2026-11-05 08:43 UTC, signed `--app-version 1.6.5`, `verify-sig RESULT OK`; the
  1.6.4 → 1.6.5 update was run on the RC channel by the owner.
- Publisher gates exercised per release: "trial publish into a temp folder OK, wrong version refused".
- Tests: `cargo test -p slums-core` 129 passed and `npm test` 109/109 at `f14e813` (RUNBOOK 1.6.5 section).
- Untrusted feeds are refused by construction: without a key all three feeds and the RC exe answer 401.

## Open questions

- `tauri/docs/checkpoint.md:91-93` still says the plugin's own check and download against a real HTTPS channel is
  "not verified end to end". The 1.6.5 RUNBOOK records exactly that happening (RC 1.6.4 → 1.6.5, owner's test). Is the
  checkpoint stale, or was that update done another way? The checkpoint page should be corrected if it is stale.
- The independent verifier that every release record cites (`verify-sig`) lives in a skill, not in the repo:
  `/root/.claude/skills/launcher-release/scripts/verify-sig.mjs`. Its default paths point at `/root/work/launcher-tauri`
  and `/root/work/launcher-handover`, which are different checkouts from `launcher-165-server`. Which checkout's
  `tauri.conf.json` did the 1.6.5 run actually read?
- The friends build rewrites the access key inside the exe (`README.md:35-39`, `bake-tauri-key.ps1` /
  `verify-tauri-key.ps1` on the Windows box, not in this repo). Nothing here shows whether the bake re-checks that the
  updater pubkey survived. If it rewrites bytes before the exe is signed, the self-update signature would be for a
  different binary than the one handed out — worth confirming with a `verify-sig` run on the baked exe.
- Live is now 1.6.4 and 1.6.5 sits on RC/Test: what decides when 1.6.5 goes Live, and does anything stop a RC exe from
  self-updating onto the Live feed (they share one key, so switching channels is enough)?
