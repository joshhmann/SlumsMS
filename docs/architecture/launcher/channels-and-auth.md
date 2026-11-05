# Channels and auth

## Purpose

Decides which of three patch-feed channels (Live, Test, RC) this launcher may read, with which credential, and keeps
that credential off every host that is not the feed's own origin. Two independent credentials exist: an HTTP Basic
access key for patch downloads (`slums` or `slumsdev` as the user name), and a short-lived per-account download pass
obtained from the website. The channel also picks the site the launcher signs in to, the game folder it installs into,
and the `host:port` that folder's client joins.

## Entry points

- Start-up (`tauri/app/src/launcher.rs:384`): `migrate` → `keep_builtin_key` → `refresh_channels` (`:494`) →
  `refresh_account` → a 10-minute loop (`AVAILABLE_INTERVAL`, `/root/work/launcher-165-server/tauri/app/src/launcher.rs:32`).
- Commands that re-probe: `save_access_key` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1213`, via `save_key` `:1194`), `set_channel` (`:1346`),
  `reset_feed` (`:1181`), `answer_migration` (`:1383`).
- CLI flags (`Args`, `/root/work/launcher-165-server/tauri/app/src/launcher.rs:38-52`): `--feed-url=`, `--site-url=` (loopback HTTP only, `:77`), `--data-dir=`,
  `--install-dir=`, `--no-self-update` (or `SLUMSMS_NO_SELF_UPDATE=1`, `:71`), `--self-test=`. None carries a key.
- Config file `tauri/launcher.config.json` (built into the exe as `BUILTIN_CONFIG`, `/root/work/launcher-165-server/tauri/app/src/launcher.rs:32`; a copy beside the
  exe overrides it, `/root/work/launcher-165-server/tauri/core/src/config.rs:76`): `feedUrl`, `accessKey`, `downloadConcurrency`, `clientExecutable`.
- Settings file in the data folder (`/root/work/launcher-165-server/tauri/core/src/settings.rs:34-52`): `channel`, `feedUrl`, `accessKey`, `builtinAccessKey`,
  `accessUser`, `folders`.
- Server side: nothing in this repo. nginx answers 401 and decides who is a developer (`tauri/docs/nginx-dev-tier.md:3-16`).

## How it works

1. **Pick the live feed.** `effective_of` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:285-298`) takes `settings.feedUrl`, else `config.feedUrl`; that
   URL is the *Live* feed, and every other channel is derived from it.
2. **Derive the channel URL.** `channel_feed` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:49-56`) keeps the Live URL as-is and otherwise takes its
   last path segment and re-joins it under the channel prefix: `launcher-test/`, `launcher-rc/` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:36-42`).
3. **Pick the key.** `effective_access_key` (`/root/work/launcher-165-server/tauri/core/src/config.rs:51-64`): the player's saved key wins; otherwise the built-in key,
   but only when the current feed's `origin()` equals the built-in feed's origin (`/root/work/launcher-165-server/tauri/core/src/config.rs:59-62`), so a redirected
   `--feed-url` never receives the shipped key.
4. **Probe all three channels.** `probe_channels` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:96-127`) asks each channel's top-level `manifest.json`
   with `check_available_release` (15 s, `/root/work/launcher-165-server/tauri/core/src/release.rs:253-274`). It tries the remembered Basic user first (normalised to
   `slums` unless already `slumsdev`, `:105`), retries Live under the other user if Live answered `AccessKey`
   (`:108-113`), probes Test and RC with the user that worked (`:114`), and if Live still refuses and the user is not
   `slumsdev`, re-probes Test and RC as `slumsdev` (`:119-124`). That last step is what lets a dev-only key open
   Test/RC while Live stays closed.
5. **Remember the user.** `refresh_channels` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:485-513`) stores the accepted user in `settings.accessUser`
   and logs only `Access key: the server accepts it as user "{user}".` (`:509`) — never the key.
6. **Decide the channel actually used.** `effective_channel` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:84-91`): Live is always Live; a Test/RC
   choice stands while its probe is `Ok` or merely `Offline`/unprobed; `AccessKey`, `Error` or a missing probe falls
   back to Live, and the UI shows "Live · {X} is not open for this key" (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:329`). The picker only appears
   when more than one channel is accessible (`/root/work/launcher-165-server/tauri/core/src/channels.rs:73-79`).
7. **Gate a manual switch.** `set_channel_inner` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1351-1357`) refuses a channel that is not in
   `accessible_channels` with "The {X} channel needs the developer access key.", and `change_folder`/`folder_owner`
   (`/root/work/launcher-165-server/tauri/core/src/channels.rs:175-201`) refuse two channels sharing a folder.
8. **Choose the folder.** `channel_install_dir` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:159-174`): Live = saved, else bundled, else standalone;
   Test/RC = their saved folder, else `{live}-test` / `{live}-rc`.
9. **Build the credential.** `access` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:304-308`) makes a `FeedAccess` from the effective feed URL, key and
   user, and attaches the download pass only when it was issued by *this channel's* site and has >60 s left.
10. **Send it, origin-bound.** `FeedAccess::new` (`/root/work/launcher-165-server/tauri/core/src/http.rs:78-87`) stores `Basic base64("{user}:{key}")` plus the feed's
    origin; an empty key yields an empty header. `request` (`/root/work/launcher-165-server/tauri/core/src/http.rs:123-183`) attaches `Authorization` and
    `X-Slums-Pass` (`/root/work/launcher-165-server/tauri/core/src/http.rs:18`) only while every hop stays on that origin: `trusted` starts as "has credentials"
    (`:126`), is cleared the moment `same_origin` fails (`:131-133`) and is never restored (`:139-146`). Redirects are
    followed by hand, at most five, and an HTTPS feed may not redirect to HTTP (`:128-129`, `:154-162`).
11. **Fetch the release.** `load_release` reads the channel manifest with the same `FeedAccess` and stores it in
    `Release.access` (`/root/work/launcher-165-server/tauri/core/src/release.rs:194`) so per-file downloads reuse it (`/root/work/launcher-165-server/tauri/core/src/release.rs:133-195`).
12. **Self-update.** `try_self_update` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:787-816`) targets `update_manifest_url(feed)` = `tauri/launcher.json`
    beside the channel manifest (`/root/work/launcher-165-server/tauri/core/src/channels.rs:60-63`), passes the same two headers to the Tauri updater (`:792,794`), and
    refuses a downloaded build whose `download_url` is not on the feed's origin ("the update {} is not on the patch
    server", `:803-806`). 401/403 resets the once-per-session flag so a key change retries (`:776-777`).
13. **Sign in, per site.** `site::site_for` (`/root/work/launcher-165-server/tauri/core/src/site.rs:25-30`) maps Live → `https://theslumsms.asslorde.com` (`:16`) and
    Test/RC → `http://192.168.0.164:8192` (`:18`). `account_call` posts JSON with `X-Slums: 1` and reads the
    `slums_session` cookie (`/root/work/launcher-165-server/tauri/core/src/site.rs:22,132-155`); the token is stored per site in `site-session-<tag>.bin`
    (`/root/work/launcher-165-server/tauri/core/src/site.rs:310-399`), sealed with the origin inside, so a token copied to the other site's file is refused
    (`/root/work/launcher-165-server/tauri/core/src/site.rs:351`, test `:454-462`).

**Where Test and RC differ from Live.** The feed path prefix (`/root/work/launcher-165-server/tauri/core/src/channels.rs:36-42`), the install folder
(`{live}-test`/`{live}-rc`, `/root/work/launcher-165-server/tauri/core/src/channels.rs:159-174`), the sign-in site (`/root/work/launcher-165-server/tauri/core/src/site.rs:25-30`: the LAN TEST site instead of the
public one), and the `host`/`port` the client joins — each channel's client layer carries its own `config.ini` server
and `merge_server` copies only those two keys from the layer into the player's file (`/root/work/launcher-165-server/tauri/core/src/game_config.rs:109-129`).

**What stops an RC client layer from reaching Live.** Only that `config.ini` merge, plus the server-side
`min_client_build` gate. If the player hand-edited the server after the launcher's last write
(`hand_edited`, `/root/work/launcher-165-server/tauri/core/src/install.rs:568-569`), the launcher keeps the player's value and the RC folder can be pointed at Live;
nothing in the launcher, the channel table, or the credential path prevents it. Both channels also share one exe
identity, one data folder and one settings file — the separation is folder and site, not process.

## Data

The channel table (base URL shapes come from `channel_feed`, `/root/work/launcher-165-server/tauri/core/src/channels.rs:49-56`, applied to the Live feed the build
ships in `tauri/launcher.config.json`; the key files are named, never quoted):

| Channel | Feed base URL | Launcher-update URL | Basic user the launcher tries | Key source | Who may publish |
| --- | --- | --- | --- | --- | --- |
| Live | `https://slumspatch.asslorde.com/manifest.json` (unchanged) | `<feed>/tauri/launcher.json` | `slums`, then `slumsdev` | player's own key in `settings.accessKey`; the baked key only for the built-in origin (`/root/work/launcher-165-server/tauri/core/src/config.rs:51-64`) | only the owner, after "GO"; hand-over build needs the friends key baked in (`AGENTS.md:13-16`) |
| Test | `https://slumspatch.asslorde.com/launcher-test/manifest.json` | `.../launcher-test/tauri/launcher.json` | `slums`, then `slumsdev` | dev key (the same key, whose nginx user is `slumsdev`) | dev box, `/opt/slums-tools/launcher-tauri-publisher`, no owner gate (`/root/work/launcher-tauri-release/RUNBOOK.md:116-124`) |
| RC | `https://slumspatch.asslorde.com/launcher-rc/manifest.json` | `.../launcher-rc/tauri/launcher.json` | `slums`, then `slumsdev` | dev key | dev box, same publisher, `--base-url .../launcher-rc/ --out /srv/slums-patch/launcher-rc` |

Key files, by path only: the exe's baked key lives in `tauri/launcher.config.json` (and, for a friends build, in the
build's `resources/app.asar`); the dev box keeps its key at `/root/slums-patch-access-key.txt`; nginx reads
`/etc/nginx/slums-patch.htpasswd` and `/etc/nginx/slums-patch-dev.htpasswd`. None of these is readable from this repo,
and none is reproduced here.

| Item | Where | Lifetime |
| --- | --- | --- |
| Channel enum / feed prefixes | `/root/work/launcher-165-server/tauri/core/src/channels.rs:13-42` (`live`/`test`/`rc`, `""`/`launcher-test/`/`launcher-rc/`) | compiled in |
| Channel + per-channel folders | `settings.channel`, `settings.folders` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:130-157`) | settings file |
| Download pass | `Inner.download_pass` `(base, DownloadPass)`, memory only | one session, re-requested when <1 h left (`/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:71-86`) |
| Site session | `data_dir/site-session-<live\|test\|dev>.bin` | until logout/refusal (`/root/work/launcher-165-server/tauri/core/src/site.rs:326,351`) |
| Probe results | `Inner.probes: BTreeMap<Channel, Available>` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:70`) | process lifetime, refreshed at start-up and on key/server change |
| Installed record | `<install>/installed.json`, `.slumsms-launcher/state.json` | per install (see `install-and-packs.md`) |

`Available` (`/root/work/launcher-165-server/tauri/core/src/release.rs:201-214`) serialises to the UI as `ok` / `access-key` / `offline` / `error` (`:219`).

## Switches

| Switch | Default | Effect |
| --- | --- | --- |
| `settings.channel` | `Live` (`/root/work/launcher-165-server/tauri/core/src/settings.rs:74`, `settings_version` 2 at `:69`) | chosen channel; gated by the probes |
| `settings.feedUrl` | empty | overrides the built-in Live feed; must be HTTPS or localhost HTTP (`/root/work/launcher-165-server/tauri/core/src/config.rs:25-36`) |
| `settings.accessKey` | empty | player key, trimmed, ≤256 chars, no control chars (`/root/work/launcher-165-server/tauri/core/src/config.rs:38-47`) |
| `settings.builtinAccessKey` | empty | key carried from a friends build, only for the built-in origin (`/root/work/launcher-165-server/tauri/core/src/settings.rs:42-45`) |
| `settings.accessUser` | empty → `slums` | Basic user; `slumsdev` only after a probe proves it |
| `config.feedUrl` / `config.accessKey` | shipped `launcher.config.json` | Live feed + baked key; repo copy has `accessKey: ""` |
| `--site-url=` | none | loopback-only sign-in site for self-tests (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:77`) |
| `--no-self-update` / `SLUMSMS_NO_SELF_UPDATE=1` | off | skips the launcher's own update (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:72,761`) |

Live set: `docs/release/1.6.4.conf:271` is the *server's* JVM switch line (`UNIT_EDITS`, `CHANNELS=5`,
`CHANNEL_LOAD=125`) — the game's five world channels, unrelated to patch channels; it says nothing about this
subsystem. The launcher's live configuration is the baked `launcher.config.json` plus the published
`tauri/launcher.json` per channel (`RUNBOOK.md` in `/root/work/launcher-tauri-release`, readable). TEST: unknown —
`start-test.sh` is not present in the review repo.

## Invariants

1. **A key never leaves its feed's origin.** Enforced in `/root/work/launcher-165-server/tauri/core/src/http.rs:131-146` (redirect drops) and again for the updater in
   `/root/work/launcher-165-server/tauri/app/src/launcher.rs:803-806`.
2. **A built-in key is only sent to the built-in origin.** `/root/work/launcher-165-server/tauri/core/src/config.rs:59-62`; also applies to the carried
   `builtinAccessKey` (`/root/work/launcher-165-server/tauri/core/src/config.rs:65-75`).
3. **A session token never crosses sites.** One token file per site, origin sealed inside (`/root/work/launcher-165-server/tauri/core/src/site.rs:310-352`);
   `site_url` only accepts HTTPS, loopback, or the exact TEST origin (`/root/work/launcher-165-server/tauri/core/src/site.rs:34-45`).
4. **Two channels never share a game folder.** `change_folder` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:175-186`) and `set_channel_inner`
   (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1360-1362`).
5. **A non-Live channel is only used after the server allows it.** `effective_channel` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:84-91`) plus the
   20 s wait in `wait_ready` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:370-378`).
6. **401/403 is permanent.** `access_denied` (`/root/work/launcher-165-server/tauri/core/src/http.rs:109-111`) sets `code = ACCESS_KEY_REQUIRED, permanent = true`, so
   nothing retries a wrong key (`/root/work/launcher-165-server/tauri/core/src/http.rs:166-167`; test `tauri/core/tests/feed.rs:377`).
7. **The key is never logged or displayed.** `log` is the only writer and says so (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:264-276`); `FeedAccess`
   and `DownloadPass` have hand-written `Debug` that print booleans/expiry only (`/root/work/launcher-165-server/tauri/core/src/http.rs:71-75`, `/root/work/launcher-165-server/tauri/core/src/site.rs:217-221`);
   self-test output masks `accessKey` as `(saved)` (`/root/work/launcher-165-server/tauri/app/src/launcher.rs:1089-1090`).
8. **Key and pass are mutually acceptable during the grace period.** Both headers are sent when both exist
   (`/root/work/launcher-165-server/tauri/core/src/http.rs:139-146`); the pass alone opens the feed (test `tauri/core/tests/feed.rs:340`).

## Origin

Custom. This repository has no upstream fork — it is the launcher written for this stack, 75 commits, HEAD `f14e813`
(2026-11-05). Oldest commit per file (`git log --follow --format=%h | tail -1`):

| File | First commit |
| --- | --- |
| `tauri/core/src/channels.rs` | `bd3df88` |
| `tauri/core/src/http.rs` | `bd3df88` |
| `tauri/core/src/config.rs`, `tauri/core/src/settings.rs` | `bd3df88` |
| `tauri/app/src/launcher.rs` | `bd3df88` |
| `tauri/core/src/site.rs`, `tauri/app/src/launcher/account.rs` | `75bfebc` |
| `tools/publish.mjs` | `fcd6e08` |

The channels split is *modified* in spirit but custom in code: the base v83 client has no patch channels, and the
ancient updater that does exist (`src/updater.mjs`, Electron) has one feed and no `slumsdev` retry
(`src/updater.mjs:265-271`), so it is not the ancestor of this logic.

## Tests and QA

- `tauri/core/src/channels.rs:262-346`: feed derivation, player vs dev key, refused-channel fallback, per-channel
  folders, folder-hint detection.
- `tauri/core/tests/feed.rs`: key on every feed request (`:293`), `slumsdev` as just another Basic user (`:306`),
  cross-origin redirect drops the key even when it bounces back (`:316`), pass alone or beside the key (`:340`),
  redirect drops the pass (`:362`), 401 is `ACCESS_KEY_REQUIRED` and never retried (`:377`), availability reads only
  the top manifest (`:398`), install gated on a clean verify (`:423`), channel `config.ini` server (`:136`), and
  `a_dev_only_key_still_opens_test_and_rc_when_live_refuses_it` (`:669`), which models nginx (Live accepts only
  `slums`, Test/RC only `slumsdev`) and asserts both the probe triple and the remembered user.
- `tauri/core/tests/site.rs:70-133`: site mapping per channel, token file per site, download-pass parsing.
- `tauri/core/tests/test_site_paths.rs:24-30`: `#[ignore]`d, needs `SLUMS_QA_INVITE`, asserts it is not pointed at the
  live site ("this test makes accounts").
- `test/access-key.test.mjs` (legacy Electron updater): Basic on every request, none without a key, cross-origin drop,
  401 not retried. `test/tauri-publish.test.mjs:32,54`: immutable publish, channel JSON last, HTTPS/credential checks.
  `test/tauri-account.test.mjs:30,39`: every page command registered; no password manager prompt.
- Auto-QA: `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:783` is a single manual card, `t-la-1` "The Tauri
  launcher's own checklist". Nothing automatic. `src/main/java/soloMapling/qa/` has no launcher scenario.
- Not covered: key rotation end-to-end against nginx, the 5-redirect cap, `has_rc_token`/`folder_channel` against real
  `installed.json` files, and any test that a *published* channel's `launcher.json` carries a signature bound to its
  version (only the publisher-side check at `/root/work/launcher-165-server/tools/publish.mjs:504-506`).

## Known issues

- b017 (`/root/work/STATUS-1.6.4.md:70`): Windows Defender flags a new launcher exe; process, not code.
- o04 (`/root/work/STATUS-1.6.4.md:130`): client and launcher builds should move from Silvia to tarkov2 "later".
- b270 (`/root/work/STATUS-1.6.4.md:75`): a Live client publish wrote `manifest.json` without `news[]` — publisher side,
  `publishing.md`.
- `docs/tickets/106-launcher-download-pass.md`: per-account download pass, open at 1.6.1; rules "No secret inside the
  launcher exe" and "Live and Test passes are separate" are satisfied by `/root/work/launcher-165-server/tauri/core/tests/site.rs:25-30` + `/root/work/launcher-165-server/tauri/app/src/launcher/account.rs:71-86`, but the
  shared key is still the only thing a signed-off player build ships.
- NEW-FINDING: `channel_feed` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:49-56`) rebuilds the path with `Url::join`, so a Live feed URL carrying a
  query string loses it on Test/RC (`/x/manifest.json?a=1` → `/x/launcher-test/manifest.json`). Harmless today
  (`launcher.config.json` has no query) but it silently changes what is fetched.
- NEW-FINDING: `folder_key` (`/root/work/launcher-165-server/tauri/core/src/channels.rs:188-196`) compares folders as strings; `/a/b` and `/a/../b` or a symlinked
  alias pass the "one folder per channel" check and land two channels in one game tree.
- NEW-FINDING: `site::TEST_SITE` is plain HTTP on a LAN address (`/root/work/launcher-165-server/tauri/core/src/site.rs:18`) and Test/RC sign-in sends the session
  cookie over it (`/root/work/launcher-165-server/tauri/core/tests/site.rs:132-155`). Acceptable for a LAN test box, but it is the only place a session travels in
  clear text.
- NEW-FINDING: `tauri/docs/nginx-dev-tier.md:16` still says "Nothing below has been applied", while `RUNBOOK.md`
  records the Live pass rule ON 2026-11-01 — the doc's status line is stale.
- NEW-FINDING: `docs/architecture/launcher/README.md:68` points at `docs/architecture/ops/hosts.md`, which does not
  exist (`docs/architecture/ops/` holds only `README.md`).

## State

Working. Evidence: `probe_channels`' two-user fallback is exercised by `/root/work/launcher-165-server/tauri/core/src/channels.rs:302-313` and
`tauri/core/tests/feed.rs:306,669`; the origin-bound credential logic has dedicated redirect tests
(`tauri/core/tests/feed.rs:316,362`); the download-pass path has been live since 1.6.1 (`RUNBOOK.md` records the Live
pass rule ON 2026-11-01 02:59:53 UTC and RC/Test 1.6.5 published over 1.6.4, with "without a key, all three feeds and
the RC exe answer 401"). `channels.rs`, `http.rs` and `site.rs` are reached from the shipped `launcher.rs`, not a dead
path.

## Open questions

- Is the `slumsdev` user actually configured on the live nginx today, or only in the unapplied dev-tier doc
  (`tauri/docs/nginx-dev-tier.md:16`)? The launcher behaves correctly either way (Live wins), but the RC picker would
  never appear for the owner's key. Not answerable from this repo.
- Ticket 106 wants the shared key retired; nothing here shows how the launcher would be built without `accessKey`
  while still opening Live. `/root/work/launcher-165-server/tools/publish.mjs:445` refuses an Electron build without one, and `publishTauriLauncher`
  (`/root/work/launcher-165-server/tools/publish.mjs:493`) has no equivalent check — is that deliberate for the Tauri hand-over?
- `docs/HANDOFF.md` names the Test channel once in passing (`docs/HANDOFF.md:120`) and neither file mentions the Basic
  user names or the dev tier, so a reader of those alone would not know RC exists. Worth a pointer, not a rewrite.
