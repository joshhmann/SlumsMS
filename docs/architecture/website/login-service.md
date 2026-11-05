# Login and accounts

## Purpose

One account for the website, the launcher and the game. `tools/site/login_service.py` (site repo) is the only thing a
browser or the launcher talks to; it holds no database connection and no password. Every password question is answered
by the game server's auth API (`SiteAuthApi`), which uses the same hashes the game's login screen uses
(`client/AccountPasswords.java`). The site's part of the work is a signed session cookie plus three small text files.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `GET/POST /api/login/*` | nginx → 127.0.0.1:8094 | `tools/site/slums-login.nginx.conf:9` |
| `GET /api/auth/check`, `/api/auth/admin`, `/api/auth/pass` | nginx `auth_request` (internal) | `tools/site/slums-login.nginx.conf:26`, `:91`, `slums-pass.nginx.conf:20` |
| `/street/`, `/market/`, `/secret/` | `auth_request /api/auth/check` | `tools/site/slums-login.nginx.conf:38-64` |
| `/admin/`, `/admin/data/` | `auth_request /api/auth/admin` | `tools/site/slums-login.nginx.conf:98-107` |
| `slums-login.service` | systemd, `Type=simple` | `tools/site/slums-login.service:1-9` |
| game-side HTTP listener | `Server.init` calls `SiteAuthApi.startIfEnabled()` | `src/main/java/net/server/Server.java:1043` |
| launcher sign-in | Sign in / Sign up form | `tauri/app/src/launcher/account.rs:171-186` |
| `!invite` (gm3) / `!resetpw` (gm3) | `CommandsExecutor` registration | `src/main/java/client/command/CommandsExecutor.java:242`, `:499` |

No timers, no cron, no watched directory: the service is request-driven only.

## How it works

1. **Sign in.** `docs/site/assets/login.js:36` POSTs `{"user","pass"}` to `/api/login/password` with `X-Slums: 1`.
   `account_sign_in` (`tools/site/login_service.py:597`) refuses a body without the header and a mismatched `Origin`
   (`json_body`, `:583`), then applies the per-address limiter (`:601`).
2. `sign_in_broken` (`:363`) only rejects what can never be a game login (name 1-64, password 1-64 printable); older
   accounts are allowed to break the sign-up rules. It POSTs `{"user","pass","ipHash"}` to the game
   (`GameAuth.call`, `:408`), header `X-Slums-Auth` from the secret file, `ipHash` = HMAC-SHA256(salt, address)[:32]
   (`:404`).
3. `SiteAuthApi.handle` (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:204`) checks the secret in constant time
   (`authorized`, `:353`, `MessageDigest.isEqual`) before anything else, then `route` (`:215`) whitelists `/signup`,
   `/verify`, `/reset/*`, `/invite/*`, `/exchange/approve`, 4096-byte bodies, POST only.
4. `SiteAccounts.verify` (`SiteAccounts.java:221`): per-IP and global rate limit (`allowRequest`, `:307`), name lockout
   (`:231`), `refused` for banned/bot/template/revoked accounts after a dummy bcrypt so the timing matches (`:269`),
   `AccountPasswords.checkHashedOnly` (`:251`), legacy SHA-1/SHA-512 upgraded to bcrypt in place (`:257`,
   `AuthStore.upgradeHash:378`), then `profile` for GM level and characters (`AuthStore.java:388`).
5. The reply is `{"ok":true,"account_id","account","gm","gm_level","characters"}` (`SiteAuthApi.body`, `:360`), status
   200; only `RATE_LIMITED` becomes 429 (`:247`).
6. `signed_in` (`login_service.py:625`) mints `Sessions.issue` (`:139`): `base64url(json).base64url(HMAC-SHA256(key,
   body))` with `{a,n,c,iat,exp,sid,cs,g,gl}`, `exp = iat + 30 d`. Nothing is stored server-side; the answer is 200
   `{"ok":true,"account","character"}` plus `Set-Cookie slums_session=…; Max-Age=2592000; Path=/; HttpOnly;
   SameSite=Lax; Secure` (`:508`).
7. **Sign up.** Same call with `{"code","user","pass"}`; `rule_broken` (`:372`) enforces 4-12 `[A-Za-z0-9]` and 6-23
   printable, then `SiteAccounts.signup` (`:167`): invite code normalised (`InviteCodes.normalize:141`), consumed and the
   account inserted in one transaction (`AuthStore.createAccountWithInvite:285`, row lock on the name), bcrypt via
   `AccountPasswords.hashForNewAccount:63`.
8. **Sign out.** `POST /api/login/logout` writes `<sid> <exp>` to `revoked-sessions.txt` (`Sessions.end:237`) so a copied
   cookie dies everywhere, and clears the cookie (`login_service.py:566-570`).
9. **Reset.** `/forgot/` → `reset("request")` (`:633`): the game stores a row with `SHA-256(browser secret)` and returns
   `request_id.secret`, kept in the `slums_reset` cookie for 7 days; the page polls `status`; a GM runs
   `!resetpw approve <account>` (`PasswordResets.java:262` onward for completion). On success the site calls
   `sessions.end_all(account_id)` (`:678`) — every older session of that account dies, in the market service too — and
   signs the browser in fresh with `not_before` = that mark.
10. **Download pass.** `POST /api/login/download-pass` (`:680`) issues `dp1.<b64 json>.<b64 hmac>` signed with its own
    key, 12 h or the session's expiry, whichever is first (`Passes.issue:292`). nginx on the patch host asks
    `GET /api/auth/pass`, which answers 204 + `X-Slums-Account`, or 401 (`:549-551`).
11. **Exchange password step.** `POST /api/login/exchange-approve` (`:732`) forwards `{account_id,user,pass,token}` to
    `/exchange/approve`; the game re-verifies the password against the session's account (`SiteAuthApi.approve:262`),
    then `Exchange.approve` (`src/main/java/soloMapling/Economy/exchange/Exchange.java:1602`) marks the preview token.
    The site sets `body["pass"] = None` the moment the call returns (`:751`).
12. **Launcher.** `site::sign_in_with_password` / `sign_up` (`tauri/core/src/site.rs:165`, `:170`) send the same bodies
    with no `Origin`; the token goes to `token_store` sealed (DPAPI on Windows), 0600, per site
    (`site.rs:326-360`). `site::me` (`:176`) checks it on start-up; a session the site rejects is deleted
    (`tauri/app/src/launcher/account.rs:134-145`).

## Data

| Thing | Where | Contract |
|---|---|---|
| session cookie `slums_session` | browser only | `{a,n,c,iat,exp,sid,cs[],g,gl}` signed, 30 d (`login_service.py:139-153`) |
| reset cookie `slums_reset` | browser only | `"<request_id>.<secret>"`, 7 d (`:653`) |
| `revoked.txt` | `/var/lib/slums-login/` | one account id per line; blocks sessions and sign-in |
| `revoked-sessions.txt` | beside `revoked.txt` | `<sid> <exp>`, pruned on write (`Sessions.end:237`), read by the market service |
| `revoked-before.txt` | beside `revoked.txt` | `<account> <epoch>`; sessions with `iat <= mark` die (`Sessions.end_all:214`) |
| `invite_codes` | MySQL | migration `database/migrations/036-invite-codes.sql:16`; one row per code, `used_at` set in the creating transaction |
| `password_resets` | MySQL | migration `database/migrations/037-password-resets.sql`; decoys for unknown names store `account_id 0` and an HMAC key, never the typed name |
| session token in the launcher | `data_dir/site-session-<tag>.bin` | origin + token + character, sealed, 0600 (`site.rs:326`) |
| `/etc/slums-auth/secret` | host | one line ≥16 chars, read by the game at boot (`SiteAuthApi.readSecret:173`) and by the site on every call (`login_service.py:397`) |

## Switches

| Switch | Default | Meaning |
|---|---|---|
| `-Dslums.auth.port` | unset = off (`SiteAuthApi.java:105-108`) | live 8186, TEST 8196 (`docs/release/1.6.4.conf:229`) |
| `-Dslums.auth.secretFile` | required | unreadable or <16 chars: warn and do not listen (`:173-184`) |
| `-Dslums.auth.revoked` | `<slums.weblogin.dir>/revoked.txt` (`SiteAccounts.java:126`) | live `/var/lib/slums-login/revoked.txt` |
| `SLUMS_LOGIN_AUTH_URL` | empty = 503 on sign-in | live `http://127.0.0.1:8186` (`slums-login.service:14`) |
| `SLUMS_LOGIN_PORT` / `_SECURE` / `_PROXY_HOPS` | 8094 / `1` / `1` | TEST 8194 / `0` / `0` (`test164/slums-login-test.service:11-15`) |
| `SLUMS_LOGIN_KEY_FILE` | `/etc/slums-login/key`, ≥32 bytes, required | rotating it signs everyone out |
| `SLUMS_LOGIN_PASS_KEY_FILE` | missing = passes off (503) | must differ from the session key (`login_service.py:830-832`) |
| `BCRYPT_MIGRATION` | true (`config.example.yaml:87`) | upgrades legacy hashes at sign-in |
| `AUTOMATIC_REGISTER` | true in the example, false on live (`docs/site/LAUNCH.md:120`) | in-game account creation; the site's sign-up is independent |
| `-Dslums.exchange.siteAuthMaxHours` / `.siteAuthGame` / `.stepUp` | 12 / true / true (`NxGuard.java:61`, `SiteReauth.java:42-43`) | how old a site sign-in may be for exchange actions |
| `-Dslums.inspect.port` + `SLUMS_LOGIN_INSPECT_*` | both off | staff Bots page (`slums-login.service:17-21`, "OFF on live") |

## Invariants

1. The secret is checked before routing and in constant time (`SiteAuthApi.java:204`, `:353`); the site never sends it
   anywhere but the game's loopback and never to a redirect (`login_service.py:428`, `Inspect:433`).
2. A password never reaches a log, a file or the site's memory longer than the forwarded call: `log_message` writes only
   method + path (`:518`), `exchange_approve` wipes the field (`:751`), `SiteAccounts` logs account, invite and `ipHash`
   only (`:212`), and `Client.login` compares hashes only (`src/main/java/client/Client.java:735`).
3. Sign-up rules are checked before any code is consumed, and a bad code is answered before a taken name, so names
   cannot be probed (`SiteAccounts.java:167-200`; `test_login.py:479`).
4. Unknown names, banned accounts and wrong passwords all answer `WRONG_LOGIN`, and unknown names lock exactly like real
   ones (`SiteAccounts.verify:231-247`, `failed:274`).
5. The account id in a session is the only identity: invites, exchange approval and inspect carry `s["a"]`, never a name
   (`login_service.py:706`, `:750`, `:772`).
6. A session is valid only if the MAC matches, `exp` has not passed, the account is not in `revoked.txt`, the `sid` is not
   in `revoked-sessions.txt`, and `iat` is after any `revoked-before.txt` mark (`Sessions.check:170-190`).

## Origin

- `tools/site/login_service.py`, `slums-login.service`, `tools/site/test_login.py`, the nginx snippet and
  `docs/site/LOGIN.md` all start at `93bbca9bb` (2026-10-26, "Website sign-in with a game account: a code typed in game,
  no password on the web"), then `f97196261` the same day for password sign-in (ticket 105, site side). 19 commits to the
  service on `site-164-street`.
- Server: `src/main/java/soloMapling/site/auth/*` and `src/main/java/client/AccountPasswords.java` start at `c15ba5355`
  (2026-10-26, "Ticket 105: invite-code sign-up and password sign-in API for the site"); 16 commits. `PasswordResets`
  `30e365b5f`, `SiteInvites` `c64abe0ab`.
- Launcher `tauri/core/src/site.rs` starts at `75bfebc` (2026-10-26, "Launcher: website sign-in, … (tickets 104 level 1
  and 105)").
- Classification **custom**: there is no site tooling and no `AccountPasswords.java` in the upstream tree
  (`/root/workspace/cosmicms` has only `tools/{SQL,input,output}`).

## Tests and QA

| Layer | What runs | Covers |
|---|---|---|
| site unit | `tools/site/test_login.py` (31 tests, `FakeGameAuth:60`) | cookie signing/expiry/revocation, admin 12 h rule, inspect proxy, forward-limit (audit W11), reset flows, sign-out everywhere |
| site unit | `tools/site/test_download_pass.py` (7 tests) | 12 h and session-capped passes, forged/other-key tokens, revoked and reset lists, no pass key → 503, nginx path |
| site unit | `tools/site/test_admin.py` (4 tests) | staff dashboard HTML only; asserts the Bots page calls `api/login/staff/inspect/` and never carries `X-Slums-Inspect` (`:72-73`); imports no `login_service` |
| server unit | 83 `@Test` in `src/test/java/soloMapling/site/auth/` (`SiteAuthApiTest` 13, `SiteAccountsTest` 20, `PasswordResetsTest` 22, `SiteInvitesTest` 10, `InviteCodesTest` 8, `SiteAuthExchangeTest` 4, `SiteAuthQaTest` 4, `RevokedFileTest`, `AuthStoreWorldTest`) | secret check, 401/404/405/413, rate limits, 23-char passwords, lockout, legacy upgrade, banned/bot refusal, loopback-only |
| server unit | `src/test/java/client/AccountPasswordsTest.java` (2), `InviteCommandTest` (15), `ResetPwCommandTest` (4) | the game login and the site agree on hashes |
| auto-QA | `t-au-1` (`AutoQaCatalog.java:1248`, `Probes160.authFlow:896-960`) | HTTP without the secret must be 401, then invite → sign-up → reset → `!resetpw` → sign-in, in-process via `SiteAuthQa` |
| TEST | `tools/site/test164/qa.py:310-340` | sign-out, `revoked.txt` takes effect at once, launcher sign-in with no Origin, `/api/login/start` is gone |

Not covered: a rotated `slums-auth` secret (the game reads it once); a ban ending an *existing* site session; clock skew
between the two hosts; the live Caddy→nginx hop count; `test_admin.py` never exercises the service.

## Known issues

- **NEW-FINDING:** `docs/site/LOGIN.md:38` ("The game server can write banned accounts there too"), `LAUNCH.md:182` and
  `docs/design/site-login-contract.md:20` describe a writer that does not exist — nothing under `src/main/java` writes
  `revoked.txt`; grep finds only readers (`WebLoginCommand.isRevoked:154`,
  `src/main/java/soloMapling/site/auth/SiteAccounts.java:116`). Ban propagation is therefore only "next password check",
  via `accounts.banned` (`SiteAccounts.refused:269`).
- **NEW-FINDING:** a banned player keeps a working site session for up to 30 days. The cookie is self-contained and only
  `revoked.txt` blocks it (`Sessions.check:182`), so the ban lands at the next sign-in, sign-out or operator edit, not at
  ban time; the market service and the market board inherit this.
- **NEW-FINDING:** rotating the shared secret is a two-step change with a silent failure mode. The game reads it once at
  boot (`SiteAuthApi.readSecret:173`) while the site re-reads on every call (`login_service.py:397`), so a new file
  makes every sign-in answer "Wrong username or password." (`ERRORS` has no `UNAUTHORIZED` key, so the default at
  `:619-620` applies) until the game restarts. Sign-up answers 503 instead.
- **NEW-FINDING:** a site clock more than 5 minutes ahead of the game makes every exchange request answer `REAUTH`
  forever: `iat` is taken from the site's clock (`Sessions.issue:141`) and compared with
  `at > now + NxGuard.SKEW_MS` (`ExchangeSite.reauth:227`), `SKEW_MS` = 5 min (`NxGuard.java:30`).
- **NEW-FINDING:** `Sessions.revoked`/`before`/`ended` re-read and re-parse their files on every call with no caching
  (`:155`, `:205`, `:228`), unlike `Passes`, which caches on `(mtime,size,inode)` (`:268`); `nginx auth_request` calls
  this for every gated page.
- 105 (invite sign-up and password sign-in), 106 (download pass, "open, 1.6.1"), 107 (password resets, "server part
  built; site pages to do") are the tracking tickets; `STATUS-1.6.4.md` carries no login card of its own.
- `docs/site/SECRETS.md` is about Slums-chan cards, not credentials; it records the TEST host as `192.168.0.111`
  while the launcher pins `192.168.0.164` (`site.rs:18`).

## State

**working** for sign-up, sign-in, sign-out, reset, download pass, exchange approval and the admin gate: both test suites
pass (`python3 -m unittest test_login test_download_pass` → 38 tests OK, 25.4 s, 2026-11-05), the server suite covers the
contract method by method, and every route is wired in nginx and in both units.

**partial** for ban propagation: the account ban reaches sign-in (`SiteAccounts.java:269`,
`src/main/java/client/Client.java:731`) but not live sessions, because the promised writer of `revoked.txt` is missing
(NEW-FINDING above). No evidence in this review that any of it has been exercised against live.

## Open questions

- Is the live `slums-auth/secret` file shared by anything other than the login service? The unit gives the service
  `ReadOnlyPaths=-/etc/slums-auth` (`slums-login.service:30`) but the game's unit line is the only other consumer found.
- Does live really have one proxy hop? `SLUMS_LOGIN_PROXY_HOPS` is unset in the live unit, so the default 1 is what runs
  with Caddy in front; TEST sets 0 for the same reason (`test164/slums-login-test.service:15`). A changed front end
  silently re-keys every rate limit.
- `docs/site/LOGIN.md:23` still names `/database/`, which nginx now 301s to `/street/`
  (`tools/site/slums-login.nginx.conf:34-36`); the page is stale, not the code.
