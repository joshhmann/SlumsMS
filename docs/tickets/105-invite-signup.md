# 105: Invite-code sign-up and account sign-in for the site and launcher

**Area:** Server / Site / Launcher
**Kind:** FEATURE, 1.6.0 (owner 2026-10-26)
**Status:** in progress
**Related:** 104 (launcher login), docs/site/LOGIN.md in SlumsMS-site (`@weblogin`)

Source: the owner, 2026-10-26: "So our main source for login creation is only in the client login screen. Is that
enough? Or should we opt into making accounts so long as you have an invite code", then "Like creating one in the
launcher/website. Thus we can login to the launcher".

## Why

The site goes public with 1.6.0, and today anyone can make accounts from the game's login screen (any number per IP,
1 minute apart). The server should stay friends-only.

## What

1. **Sign up** on the site or in the launcher: invite code + username + password (same rules as the game: length,
   allowed characters, name not taken, not a bot's name). The game server creates the account. Neither the site nor
   the launcher writes the database.
2. **Sign in** on the site or in the launcher with the game username and password, checked by the game server
   (the same hash check as the login screen). The result is the same revocable site session `@weblogin` gives.
   `@weblogin` stays as a fallback.
3. **Login screen auto-register off** once this is live (config flag). Existing accounts log in as before.
4. **Invite codes:** single use, one account each. Only GM accounts make them, in game (owner 2026-10-26): the maker
   picks how long the code stays valid, `!invite <1d|7d|30d|forever> [note]` (7d when no duration is given) prints a
   code; `!invite list` shows codes, their expiry, who used them and when; `!invite revoke <code>` (GM level 3, like `!ban`). Strangers can't
   join without a code. Players don't get codes in 1.6.0.

## Shape

- The game server serves a small auth API on **127.0.0.1 only** (TEST and live each their own port), used only by
  the site's login service: `POST /signup {code, user, pass, ipHash}` and `POST /verify {user, pass, ipHash}` (see
  "Server API" below). A shared secret file (root 600, made on the host, never printed) is checked on every call.
  Rate limits: per IP (passed by the login service) and overall; 5 failed sign-ins per account per 15 min locks
  sign-in for 15 min.
- Passwords travel only over HTTPS on live (Caddy). The launcher never stores a password, only the session token.
- Errors are generic to the user ("wrong username or password", "that code doesn't work").
- Logged: sign-ups (code, account, time, IP hash), failed sign-ins (count only).

## Owners

- Server (game auth API, invite codes, `!invite`, auto-register flag): main session.
- Site pages and login service calls: slumsms-site-0b.
- Launcher sign-up / sign-in screens: src-15 (with 104 level 1).

## Done when

A new friend with a code signs up in the launcher, signs in, and logs into the game with the same username and
password; a used, expired or made-up code is refused; the login screen no longer creates accounts; all of it passes
on TEST before the 1.6.0 release.

## Server API (built 2026-10-26, branch 160-invite)

For the site's login service (slumsms-site-0b). The game server serves it on **127.0.0.1 only**, from two daemon
threads (never a game thread); database work goes through the server's connection pool.

**Turning it on** (JVM properties; off when `slums.auth.port` is unset):

| | port | secret file |
|---|---|---|
| TEST | `-Dslums.auth.port=8196` | `-Dslums.auth.secretFile=/etc/slums-auth-test/secret` |
| live | `-Dslums.auth.port=8186` | `-Dslums.auth.secretFile=/etc/slums-auth/secret` |

The secret file holds one line, at least 16 characters, made on the host (never printed or committed; on live the
owner makes it, group `slums-login`, mode 0640, because the login service runs as `slums-login`; on TEST the
coordinator makes it). The server only reads it, never checks owner or mode, and strips surrounding whitespace and
newlines, as the login service does for the header. Missing, unreadable or shorter: the API does not start and the
log says why (never the secret). The secret is read once at start.

**Every request:** header `X-Slums-Auth: <the secret>` (compared in constant time), `POST`, a JSON body of at most
4096 bytes. Missing or wrong secret: `401 {"ok":false,"error":"UNAUTHORIZED"}` (checked before anything else).
Other paths: 404; other methods: 405; bad JSON: 400; too big: 413.

**Caller (site login service):** `Content-Type: application/json`, 8 s timeout; it reads the JSON body from 200 and
4xx alike. Codes are made from uppercase letters and digits only (no 0, O, 1, I) and shown as `ABCDE-FGHJK`; the
caller sends them uppercased with spaces and dashes removed, and the server normalises the same way.

**Single use, always** (owner 2026-10-26): every code makes one account; `forever` only means no expiry date. There
are no multi-use codes.

**`POST /signup`** `{"code":"ABCDE-FGHJK","user":"Newfriend","pass":"hunter22","ipHash":"<salted hash>"}`
creates the account with the code, in one transaction (the code is consumed only together with the new account, so
two sign-ups with one code make one account). Codes ignore case, dashes and spaces.

**`POST /verify`** `{"user":"Newfriend","pass":"hunter22","ipHash":"<salted hash>"}` checks the game password.

**Success** (both): `200 {"ok":true,"account_id":123,"account":"Newfriend","gm":false,"gm_level":0,"characters":["Slum","Alt"]}`.
`account` is the stored spelling; `gm` is `gm_level > 0`, where `gm_level` is the highest `characters.gm` on the
account (what the version gate reads; `@weblogin` sends the same number as `gm`); `characters` are the account's
characters on world 0 (not bots), oldest first, as `@weblogin` sends them. A new account has no characters.

**Errors:** `{"ok":false,"error":"<CODE>"}`, HTTP 200 except `RATE_LIMITED` (429):

| error | when |
|---|---|
| `BAD_CODE` | unknown, used, expired or revoked code (one code, nothing leaks). Checked before the name, so names can't be probed without a code. |
| `NAME_TAKEN` | an account with that name exists (any case) |
| `BAD_INPUT` + `rule` | `user_length`, `user_chars`, `pass_length`, `pass_chars`, `reserved` (a staff-looking name, the login screen's rule), `bot_name` (a bot holds the name). Checked first. |
| `WRONG_LOGIN` | no such account, wrong password, **banned or temp-banned** (folded in), the bot template / bot accounts, or the account's `@weblogin` sign-in revoked (`revoked.txt`) |
| `LOCKED` | 5 failed sign-ins for that name within 15 min lock it for 15 min (names that don't exist lock the same way, so a lock reveals nothing) |
| `RATE_LIMITED` | over 10 requests a minute from one ipHash, 120 a minute overall, 10 bad codes an hour from one ipHash, or a second sign-up from one ipHash within `AUTO_REGISTER_IP_COOLDOWN_MINUTES` (1) |
| `UNAVAILABLE` | the server could not reach the database (or migration 036 is missing) |

**Rules** (sign-up): user 4-12 letters/digits; password `AUTO_REGISTER_MIN_PASSWORD_LENGTH` (6) to 12 printable ASCII
characters, no spaces. The server's login screen itself checks no length or characters (only the staff-name rule and
the minimum password length), so these are the site's proposal; 12 is the v83 client's password box. Sign-in looks up
any existing account name (up to 64 characters), since older accounts may break the sign-up rules.

**Sign-in safety:** sign-in compares hashes only (bcrypt, SHA-1, SHA-512) and never a plain-text stored value (the
login screen still does, for old accounts; the bot template's "no login" marker is plain text). Banned and bot
accounts (`SMBotBase`, any account owning the template character `-Dsolomapling.template-id` or a character at or
above the bot id base, any account id in the bot range) are refused before any password comparison; a password over
12 characters is never compared. A legacy (SHA) match is upgraded to bcrypt when `BCRYPT_MIGRATION` is on, as the
login screen does.

**IP:** the service sends only `ipHash` (salted on its side); the server never sees a raw IP. It is stored on the used
code and logged with the sign-up. Missing: counted as one shared "none".

**Logged:** sign-ups (`Site sign-up: account '<name>' (id N) with invite ABCDE-FGHJK from ipHash <hash>`), lockouts
and the failed sign-in count; never a password, the secret, or a request body.

**Storage:** migration `036-invite-codes` (table `invite_codes`, times epoch ms, `expires_at` 0 = never).

**Login screen auto-register:** `AUTOMATIC_REGISTER` in config.yaml (already there). Live sets it to `false` with
1.6.0; existing accounts log in as before.
