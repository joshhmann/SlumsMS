# Launcher site and account

Sign-in, invite-code sign-up and the session the launcher keeps afterwards. Three parties: the launcher
(`/root/work/launcher-165-server`), the site's login service (`/root/work/SlumsMS-site-164-street/tools/site/login_service.py`)
and the game server's loopback auth API (`src/main/java/soloMapling/site/auth/`). The launcher only ever talks to the
site; the site only ever talks to the game server; the game server owns the account rows.

## Purpose

Let a player prove they own a game account from inside the launcher — with the game username and password, or with an
invite code for a new account — and then keep a revocable session for "signed in as <character>", price-alert counts
and the patch server's download pass. The launcher must never hold a password, and the site must never hold the game
database: the game server remains the only place a password is checked (`tools/site/login_service.py:17-19`).

## Entry points

| Entry | Where |
|---|---|
| `account_sign_in` / `account_sign_up` / `account_sign_out` Tauri commands | `tauri/app/src/launcher/account.rs:220,225,230`, registered `tauri/app/src/main.rs:82-84` |
| `open_market` / `open_forgot` / `open_join` / `open_report` (browser only) | `tauri/app/src/launcher/account.rs:236,246,255,266` |
| Bridge names `signIn`, `signUp`, `signOut`, `openMarket`, `openForgot`, `openJoin` | `tauri/ui/bridge.js:18` |
| Sign-in dialog (two tabs: Username / New account) | `tauri/ui/index.html:155-173`, logic `tauri/ui/account.mjs:32-129` |
| Startup ask + 10-minute re-check | `tauri/app/src/launcher.rs:393` (bounded 10 s) and `:398-405` (`AVAILABLE_INTERVAL`, `:32`) |
| `!qa` step `account` / `account-store` | `tauri/app/src/launcher.rs:1026-1030` |
| Dev CLI `site-me`, `site-signin`, `site-signup`, `site-signout` | `tauri/core/examples/cli.rs:78-112` |
| Site `POST /api/login/password`, `POST /api/login/signup` | `tools/site/login_service.py:571` |
| Site `GET /api/login/me`, `POST /api/login/logout`, `POST /api/login/download-pass` | `login_service.py:552,566,573` |
| Game `POST /signup`, `POST /verify` on loopback | `src/main/java/soloMapling/site/auth/SiteAuthApi.java:241-243`; started from `src/main/java/net/server/Server.java:1043` |
| GM/player commands that make codes: `!invite`, `!invite list`, `!invite revoke`; `@invite` for players | `src/main/java/client/command/commands/gm3/InviteCommand.java:23-60` |
| Resets: `!resetpw list|approve|deny` | `src/main/java/client/command/commands/gm3/ResetPwCommand.java:17-34`; `src/main/java/client/command/CommandsExecutor.java:499` |

Config switches that start any of it: `-Dslums.auth.port` (unset = off) and `-Dslums.auth.secretFile` on the game
server; `SLUMS_LOGIN_AUTH_URL` / `--auth-url` and `SLUMS_LOGIN_AUTH_SECRET_FILE` on the site; `--site-url=` on a
launcher dev build. Details in `## Switches`.

## How it works

### A. Password sign-in (the common path)

1. The dialog submits `{code, user, pass}` (`tauri/ui/account.mjs:120`), blanks every `input[type=password]`
   immediately (`:121`, helper `:41`), then checks only for empty fields (`:25-30`). `value.pass = ''` at `:125`.
2. `bridge.signIn(value)` → `account_sign_in` → `Launcher::account_form(form, false)`
   (`tauri/app/src/launcher/account.rs:171`). Length caps there are 32 for the user, 64 for the password
   (`:46-54`); the game server makes the real rules.
3. `site::sign_in_with_password` (`tauri/core/src/site.rs:165`) builds `{"user","pass"}` (`:158-161`) and
   `account_call` (`:132`) POSTs it to `<base>/api/login/password` with `Content-Type: application/json` and
   `X-Slums: 1`. No `Origin` header, no cookie jar, redirects disabled (`client()`, `:56-68`).
4. Make the base: `site_base()` → `--site-url` if given, else `site::site_for(channel)` (`account.rs:58-67`,
   `site.rs:25-30`). `site_url()` (`site.rs:34-45`) refuses anything but HTTPS, loopback HTTP, or the exact TEST
   origin.
5. Site `json_body()` (`login_service.py:583-595`) requires `X-Slums: 1`, rejects a mismatched browser `Origin`,
   and accepts only a JSON object of 1..1024 bytes. Then `account_sign_in("password")` (`:597`): per-IP limiter →
   `429 RATE_LIMITED`; `sign_in_broken(user,pw)` shape check (`:363-369`) → `400 {error, rule}`; then
   `auth.call("/verify", {"user","pass"}, ip)` (`:613`).
6. `GameAuth.call` (`login_service.py:408-425`) re-reads the secret and IP salt **by path on every call**, adds
   `ipHash = HMAC-SHA256(salt, ip)[:32]`, and POSTs to `SLUMS_LOGIN_AUTH_URL/verify` with
   `X-Slums-Auth: <secret>` (8 s timeout). No URL set, no secret, or no salt → `None`.
7. Game `SiteAuthApi.handle` (`SiteAuthApi.java:204`) compares the secret in constant time (`authorized`,
   `:353-357`) **before** routing — wrong secret = `401 UNAUTHORIZED`, nothing else runs. `route` (`:215`) then
   enforces POST, `≤4096` bytes, JSON, and the path set (`:215-231`).
8. `SiteAccounts.verify` (`SiteAccounts.java:221`): rate limit (10/ip/min, 120 global), name/password length,
   15-minute lock after 5 fails, `store.findAccount`, `refused()` for banned/tempban/bot-id/template/revoked
   (`:269-272`), then `AccountPasswords.checkHashedOnly` — the plaintext is only ever compared, never stored
   (`:250-260`). Success → `store.profile(id)` for `gmLevel` + character list.
9. Answer `200 {ok, account_id, account, gm, gm_level, characters}` (`SiteAuthApi.java:360-376`), or
   `{ok:false, error, rule?}`.
10. Site `signed_in()` (`login_service.py:625-631`) mints the session (`Sessions.issue`, `:139-153`: payload keys
    `a`,`n`,`c`,`iat`,`exp`,`sid`, optional `cs`,`g`,`gl`; `b64(json)+"."+b64(HMAC-SHA256)`), and answers
    `200 {"ok":true,"account":…,"character":…}` plus `Set-Cookie: slums_session=<token>; Max-Age=2592000;
    Path=/; HttpOnly; SameSite=Lax[; Secure]` (`make_handler` flags, `:507-512`).
11. Launcher `account_call` (`site.rs:132-156`) reads that `Set-Cookie` by hand (`set_cookie`, `:90-96`), requires
    the token to pass `valid_token` (`:85-87`: ≤4096 bytes, `[A-Za-z0-9-_.]`), and returns
    `Account::SignedIn{token, character}` — falling back to the account name when the account has no character yet
    (`:143-147`).
12. `signed_in()` (`account.rs:154`) saves the token (`token_store::save`), logs
    "Website: signed in to the {Live|TEST} site with a password." (`:158`), pushes `{status:"signed-in", site,
    character}` to the page, asks for a download pass, then for the alert badge.
13. `refresh_account` (`account.rs:126-151`) is what runs at start and every 10 minutes: load the token → show
    `checking:true` → `site::me` (GET `/api/login/me` with `Cookie: slums_session=…`, `site.rs:176-191`). `Some` →
    refresh pass + badge; `None` → delete the token and say "The website signed you out."; network error → keep the
    token and show `offline:true`.

### B. Invite-code sign-up

Same path with three differences: body is `{"code","user","pass"}` (`site.rs:170-172`), the site cleans the code
(strip spaces and dashes, uppercase, `login_service.py:385-386`), rejects anything outside 4..32 with
`403 BAD_CODE`, then calls `/signup`. The game's `SiteAccounts.signup` (`:167-219`) normalises the code
(`InviteCodes.normalize`, `InviteCodes.java:141-157`), requires an OPEN invite row, rejects taken names, applies the
per-IP sign-up cooldown (`AUTO_REGISTER_IP_COOLDOWN_MINUTES`), hashes with `AccountPasswords.hashForNewAccount`, and
creates account + code-use + inviter link in one transaction (`AuthStore.java:285-340`) — the conditional `UPDATE`
takes the row lock, so two sign-ups with one code cannot both win. The site then issues a session exactly as in
step 10, so sign-up ends signed in.

Codes come from `!invite` (GM 3) or `@invite` (level 30+, 2 per rolling 7 days); `InviteCodes.display` shows
`ABCDE-FGHJK` and the database stores 10 characters (`database/migrations/036-invite-codes.sql:16-31`).

### C. Sign-out

`account_sign_out` (`account.rs:187-199`) loads the token, POSTs `/api/login/logout` (the answer is ignored —
`site.rs:258-262`), clears the token file and the in-memory pass, and logs "signed out of the {site} site." The site
side (`login_service.py:566-570`) records the session id in `revoked-sessions.txt` (`Sessions.end`) so a copied cookie
stops working, and clears the cookie.

### D. What goes where

| Destination | Carried |
|---|---|
| Launcher → site | `{user,pass}` or `{code,user,pass}` as JSON + `X-Slums: 1`; afterwards only `Cookie: slums_session=<token>` |
| Site → game (`127.0.0.1`) | `{user,pass,ipHash}` or `{code,user,pass,ipHash}` + `X-Slums-Auth: <secret>` — the only hop that sees the password |
| Launcher → patch server | `Authorization: Basic …` (access key, unrelated) and `X-Slums-Pass` (the download pass), only on the feed's own origin (`tauri/core/src/http.rs:18,62-95,138-146`) |
| Launcher → game client | nothing. `config.ini` gets only `[config] host`/`port` (`tauri/core/src/game_config.rs:112-120`); the token never reaches the game |

## Data

- **Launcher token file** `site-session-{live|test|dev}.bin` in the data dir (`site.rs:317-319`; data dir defaults to
  `app.path().app_config_dir()`, `launcher.rs:174-179`). Contents: `"{origin}\n{token}\n{character}"` (`:332`),
  written to `*.bin.tmp` and renamed, mode `0600` on unix. On Windows the bytes are DPAPI-sealed with entropy
  `b"SlumsMS Launcher website session"` (`:363-364`, DPAPI call `:376-397`); elsewhere they are plaintext (`:401-408`, asserted by a test).
  Load requires the sealed origin to equal the current site's origin and the token to still be valid (`:351-357`).
- **`settings.json`** holds feed/key/channel/folders only (`tauri/core/src/settings.rs:33-64`) — no password, no token.
- **Site, on disk:** session key `/etc/slums-login/key` (≥32 bytes, `login_service.py:811-816`), pass key
  `/etc/slums-login/pass-key` (must differ from the session key, `:823-831`), `revoked.txt`,
  `revoked-sessions.txt`, `revoked-before.txt` (all under `/var/lib/slums-login`, `:125-131`). No database.
- **Game server, in the database:** `accounts` (inserted by `AuthStore.java:132`), `invite_codes`
  (`036-invite-codes.sql:16-31`: `used_ip_hash`, never a raw IP), `password_resets` (`037-password-resets.sql:19-34`).
- **In memory only:** the download pass (`launcher.rs:111`, `site.rs:211-221`), the IP limiter windows
  (`SiteAccounts.java:88-100`), the site's sliding-window limiters (`login_service.py:471-485`).

### Where a password or token touches disk or logs

- Password: lives in the JS form value, then in `Credentials` (wiped by `Drop`, `account.rs:29-34`), then in the
  outbound JSON body. `site::wipe` overwrites the bytes with volatile writes (`site.rs:123-129`). The launcher wipes
  right after the call returns (`account.rs:180`). **Never written to `settings.json` or the token file.**
- `launcher.log`: appends `"{iso} {message}"`, rotates at 1 MiB (`launcher.rs:265-276`). Account messages are
  "signed in to the {site} site with a password / with a new account", "signed out of the {site} site", "got a
  download pass (N h left)", "no download pass this time (…)", "the {site} site ended this sign-in" — no password,
  no token, no pass value.
- Site stderr: only `"{method} {path}"` (`login_service.py:519`), `"invite {action} by account {id}"` (`:729`, never
  the code), exchange approvals by account id (`:758-760`). The key/secret/salt are read by path and never printed.
- Site → game hop is plain HTTP over loopback and **carries the plaintext password** (`login_service.py:410-414`).
  That is inside one host; the launcher→site hop is HTTPS on live and plain HTTP on TEST (no Caddy, no TLS there).
- The token exists on disk only in the `.bin` file above, and on the wire only as the `slums_session` cookie to the
  site's own origin. `DownloadPass` Debug prints only the expiry (`site.rs:217-221`, asserted at `:480`).

## Switches

| Switch | Home | Default | Live | TEST |
|---|---|---|---|---|
| `-Dslums.auth.port` | `System.getProperty`, `SiteAuthApi.java:106` | unset = API off | `8186` (`docs/release/1.6.4.conf:229`) | `8196` (`docs/tickets/105-invite-signup.md:60-67`) |
| `-Dslums.auth.secretFile` | `SiteAuthApi.java:111,173-183` | empty → off, needs ≥16 chars | `/etc/slums-auth/secret` (`1.6.4.conf:229`) | `/etc/slums-auth-test/secret` |
| `-Dslums.auth.revoked` | `SiteAccounts.java:116,126-133` | `revoked.txt` beside `slums.weblogin.dir` | `/var/lib/slums-login/revoked.txt` (`1.6.4.conf:229`) | `/var/lib/slums-login-test/revoked.txt` |
| `SLUMS_LOGIN_PORT` | `login_service.py:820` | `8094` | `8094` (`tools/site/slums-login.service`) | `8194` (`tools/site/test164/slums-login-test.service:11`) |
| `SLUMS_LOGIN_AUTH_URL` | `login_service.py:817` | `""` = sign-in answers 503 | `http://127.0.0.1:8186` | `http://127.0.0.1:8196` (`:17`) |
| `SLUMS_LOGIN_AUTH_SECRET_FILE` | `login_service.py:808` | `/etc/slums-auth/secret` | same | `/etc/slums-auth-test/secret` (`:18`) |
| `SLUMS_LOGIN_IP_SALT_FILE` | `login_service.py:810` | `/etc/slums-login/ip-salt` | same | `/etc/slums-login-test/ip-salt` (`:19`) |
| `SLUMS_LOGIN_SECURE` | `login_service.py:821` | `1` (adds `; Secure`) | `1` (Caddy terminates TLS) | `0` (`:13`) — no TLS on TEST |
| `SLUMS_LOGIN_PROXY_HOPS` | `login_service.py:503` | `1` | `1` | `0` (`:15`) |
| `--site-url=` | `launcher.rs:77` | unset | n/a | dev only: `http://` + `127.0.0.1`\|`localhost`, nothing else |
| `AUTO_REGISTER_MIN_PASSWORD_LENGTH` / `AUTO_REGISTER_IP_COOLDOWN_MINUTES` | `config.yaml` | 6 / 1 min | value unknown (config not read) | TEST: unknown |

`docs/architecture/switches.md` documents no `slums.auth` row at all (81 lines, no match for "auth"); the triple is
documented instead at `docs/release/1.6.4.conf:228-231` ("Kept from 1.6.2, not re-addable here … (the site's sign-in
calls them)") and in `docs/architecture/server/site-and-dashboard.md:131-133`.

**If the game's auth port or secret changes and the site is not updated:**

- Wrong port / API not listening: `GameAuth.call` gets a connection error and returns `None`
  (`login_service.py:418-424`), so `/api/login/password` answers `503 "Sign-in is down right now. Try again in a
  bit."` (`:616-617` + `DOWN`, `:339`), and the launcher shows that sentence.
- Wrong or short secret file: the game refuses to start the listener at all and logs "Site auth API not started:
  -Dslums.auth.secretFile is missing, unreadable or shorter than 16 characters" (`SiteAuthApi.java:112-115`);
  if it is running with a different secret, every route answers `401 UNAUTHORIZED` before routing (`:205-207`).
- The site has no mapping for `UNAUTHORIZED` in its `ERRORS` table (`login_service.py:330-338`), so it falls through
  to the fallback at `:619-620`: password sign-in then answers **`401 "Wrong username or password."`** for every
  player — a secret rotation failure is indistinguishable from a wrong password. See `## Known issues`.
- A changed site port only matters to nginx (`127.0.0.1:8094` live, `127.0.0.1:8194` TEST); the launcher's Live
  origin is fixed at `https://theslumsms.asslorde.com`.

## Invariants

1. **The launcher never persists a password.** Enforced by wiping in `site::wipe` (`site.rs:123-129`), `Drop` on
   `Credentials` (`account.rs:29-34`), and the UI clearing the field before it validates or sends
   (`/root/work/launcher-165-server/tauri/ui/account.mjs:41,121,125`). A test asserts a lone `type="password"` input and `autocomplete="off"`
   (`test/tauri-account.test.mjs:39`).
2. **A session goes only to the site that issued it.** `site_url` (`site.rs:34-45`) allow-lists HTTPS, loopback and
   the exact TEST origin; the token file seals the origin and `load` refuses a mismatch (`:351-357`, tested at
   `:452-467`); the HTTP client follows no redirects and keeps no cookie jar (`:56-68`); `valid_token` rejects
   anything that could inject a header or a second cookie (`:85-87`).
3. **The secret is checked before anything else, and never leaves the host.** `SiteAuthApi.handle` returns
   `401` before routing (`:204-207`); the site reads the secret file per call and prints it nowhere; the inspect
   client refuses redirects so the header cannot be replayed (`login_service.py:427-430`).
4. **The web tier never touches the game DB.** It only calls the loopback API (`login_service.py:389-425`); the
   account row is written by `AuthStore.createAccountWithInvite` inside the game server (`AuthStore.java:285-340`).
5. **An invite is single-use under concurrency.** `CONSUME` is a conditional UPDATE on `used_at = 0 AND revoked_at = 0
   AND expiry` (`AuthStore.java:121-122`) whose row lock serialises competing sign-ups; the loser gets `CODE_GONE`.
6. **Revocation wins within one poll.** `Sessions.check` consults `revoked.txt`, ended sids and the reset mark on
   every request (`login_service.py:170-189`); the launcher notices within `AVAILABLE_INTERVAL` (10 min) via
   `/api/login/me`.
7. **Sign-in answers are generic.** Wrong password, unknown user and a banned account all produce
   `401 "Wrong username or password."` (`SiteAccounts.java:245-246,269-272`); the account id never leaves the game.
8. **No credential in `config.ini`.** Only `[config] host` and `port` are merged (`game_config.rs:112-120`).

## Origin

| Path | First commit | Reading |
|---|---|---|
| `tauri/core/src/site.rs` | `75bfebc` "Launcher: website sign-in, 'signed in as', invite sign-up (tickets 104 level 1 and 105)" (2026-10-26) | **custom** |
| `tauri/app/src/launcher/account.rs` | `75bfebc` (same commit) | **custom** |
| `tauri/ui/account.mjs` | `75bfebc`; later `e69327e` (`/join/`) | **custom** |
| `tauri/core/src/http.rs` (pass header) | `bd3df88`, pass added `a5d8cf1` | **custom** |
| `tools/site/login_service.py` | `93bbca9bb` "Website sign-in with a game account: a code typed in game, no password on the web" (2026-10-26), then `f97196261` (ticket 105) | **custom** (site repo) |
| `src/main/java/soloMapling/site/auth/SiteAuthApi.java`, `SiteAccounts.java`, `AuthStore.java`, `InviteCodes.java` | `c15ba5355` "Ticket 105: invite-code sign-up and password sign-in API for the site" (2026-10-26) | **custom** — no upstream counterpart; the base v83 server has no site tier at all |

Tickets: `docs/tickets/104-launcher-login.md` (level 1 shipped, level 3 = real auto-login, not built),
`105-invite-signup.md`, `106-launcher-download-pass.md`, `107-password-reset-requests.md`.

## Tests and QA

- **Launcher Rust** `tauri/core/src/site.rs:411-489`: `live_signs_in_to_the_live_site_and_test_and_rc_to_the_test_site`,
  `a_session_goes_only_to_https_loopback_or_the_exact_test_origin`, `tokens_are_url_safe_base64_with_at_most_a_dot`,
  `the_token_file_is_per_site_and_never_read_back_for_another_site`, `names_are_printable_and_short`,
  `a_download_pass_needs_a_clean_pass_and_a_future_expiry`.
- **Launcher JS** `test/tauri-account.test.mjs` (7 tests): strip strings, `formProblem`, every bridge command present
  in `generate_handler!`, `autocomplete="off"` with exactly one password input, tabs `['password','signup']` and no
  in-game code flow, join-link visibility.
- **Site** `tools/site/test_login.py` (31 tests, `python3 -m unittest discover -s tools/site -p 'test_*.py'`) covers
  token forgery/expiry/revocation, the generic 401/423 bodies, sign-up rules before anything is sent, the removed
  code flow (`test_the_code_flow_is_gone`), origin/`X-Slums` gating, per-IP limits and a made-up
  `X-Forwarded-For`. `tools/site/test_download_pass.py` (7 tests) covers pass lifetime, forged keys, and revocation.
- **Game server** `src/test/java/soloMapling/site/auth/`: `SiteAccountsTest`, `SiteAuthApiTest`, `SiteAuthExchangeTest`,
  `SiteAuthQaTest`, `AuthStoreWorldTest`, `InviteCodesTest`, `SiteInvitesTest`, `PasswordResetsTest`, `RevokedFileTest`
  (plus `MemoryAuthStore`, `MemoryResetStore` fixtures).
- **Auto-QA** `t-au-1` "Site invite, sign-up, sign-in and GM-approved password reset"
  (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1248`, probe `Probes160.authFlow`,
  `src/main/java/soloMapling/autoqa/Probes160.java:904`): SKIPs when the API is not listening, proves `/verify`
  without the secret is 401, then does invite → signup → verify → reset request → approve → complete, and deletes the
  throwaway account.
- **`!qa` scenarios** `src/main/java/soloMapling/qa/SiteScenarios.java:18-23` has only `sf-1` (the site export). **No
  `!qa` scenario exercises sign-in, sign-up or the token file.**
- **TEST site QA** `tools/site/test164/qa.py:325-340` checks a launcher-shaped POST with no `Origin`, that
  `/api/login/start` is gone (404), `/api/login/me`, the badge, and sign-out revocation; `:351-366` checks sign-up
  rules against the real TEST auth API.

**Not covered:** no test drives launcher → site → game end to end (the Rust tests never open a socket; the site tests
use fake auth answers); nothing asserts the launcher's TEST origin still answers, so the `192.168.0.164` constant
(`site.rs:18`) cannot fail a test; nothing covers a secret mismatch (the `UNAUTHORIZED` fall-through above); the
`!qa` layer has no account scenario.

## Known issues

- **NEW-FINDING: Test/RC launcher builds sign in to a host the TEST site left at 1.6.3.** `TEST_SITE` is still
  `http://192.168.0.164:8192` (`tauri/core/src/site.rs:18`, asserted at `:417-418`, documented at
  `tauri/README.md:146`), while the TEST site installs to `SLUMS_TEST_HOST`, default `192.168.0.111`
  (`tools/site/test164/install.sh:6,11`), and nginx listens on `@TEST_HOST@:8192`
  (`tools/site/test164/nginx.conf:3,33`). Sign-in from a Test/RC build therefore fails with "Could not reach the
  website." unless the player's launcher was built with the new host.
- **NEW-FINDING: a secret mismatch is reported as a wrong password.** `401 UNAUTHORIZED` from the game is absent from
  the site's `ERRORS` map (`tools/site/login_service.py:330-338`), so `:619-620` falls back to
  `WRONG_LOGIN` for password sign-in — operators see normal-looking failed logins during a rotation.
- **NEW-FINDING: `docs/architecture/switches.md` has no `slums.auth.*` row** even though the three flags are the
  only way the site reaches the game; only `docs/release/1.6.4.conf:229` and
  `docs/architecture/server/site-and-dashboard.md:131-133` carry them.
- b017 (S3, Launcher): Windows Defender flags a newly built launcher exe — process, not code
  (`/root/work/STATUS-1.6.4.md:70`). It affects the shipped exe this flow lives in.
- o04 (later, Ops): move client and launcher builds from Silvia to tarkov2 (`/root/work/STATUS-1.6.4.md:130`).
- No open card covers site sign-in, token storage or the auth API — the three findings above are unclaimed.

## State

**working** for Live, **partial** for Test/RC.

Evidence: the full chain exists on both sides with matching field names (`site.rs:165-172` ↔ `login_service.py:608-612`
↔ `SiteAuthApi.java:238-241`), the live unit carries the three `-Dslums.auth.*` flags (`1.6.4.conf:229`,
re-confirmed `docs/release/1.6.2-deploy-steps.md:200-201`, `docs/release/1.6.3-deploy-steps.md:121-122`), the live
site points at `http://127.0.0.1:8186` (`tools/site/slums-login.service`), and `t-au-1` exercises invite → sign-up →
sign-in → reset on TEST. Partial because the launcher's TEST origin is stale (known issue 1) and because no test spans
the two processes.

## Open questions

- Non-Windows builds store the session token in plaintext (`site.rs:401-408`, asserted by the test at `:458`). Every
  shipped build is Windows, so this is dev-only — but is a Linux/macOS launcher ever intended? If so the token needs a
  keyring, not a `0600` file.
- The launcher's password cap is 64 (`account.rs:51`) while the game's is 23 (`SiteAccounts.java:55`). The 24–64 range
  can only ever come back as `pass_length`; should the UI cap at 23 to say so before the round trip?
- `t-au-1` runs `/signup` and `/verify` in-process after the secret check, so it never proves the secret plumbing that
  live depends on (`Probes160.java:904-940`). Is a loopback HTTP probe with a throwaway secret acceptable, or is the
  secret deliberately kept out of the QA path?
- `/api/login/me` is polled every 10 minutes; the session TTL is 30 days (`login_service.py:103`). Is a 10-minute
  revocation latency (a ban or `revoked.txt` edit) acceptable, given the download pass lives 12 hours
  (`:255`)?
- The site's `json_body` accepts a request with no `Origin` at all (`login_service.py:588-590`), which is what the
  launcher relies on. Anything else on the LAN can POST the same way over plain HTTP on TEST, since `X-Slums: 1` is
  a public constant. Is TEST-only acceptable, or should the launcher send something the site can verify?
