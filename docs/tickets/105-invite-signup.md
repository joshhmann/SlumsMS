# 105: Invite-code sign-up and account sign-in for the site and launcher

**Area:** Server / Site / Launcher
**Kind:** FEATURE, 1.6.0 (owner 2026-10-26)
**Status:** in progress (player `@invite` added in 1.6.1, see the end)
**Related:** 104 (launcher login), docs/site/LOGIN.md in SlumsMS-site (`@weblogin`), 107 (password resets)

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
   (the same hash check as the login screen). The result is the same revocable site session `@weblogin` gave.
   `@weblogin` is dropped (owner 2026-10-26): one way in, the game username and password. The command is no longer
   registered; its `revoked.txt` (in `-Dslums.weblogin.dir`) still revokes sessions.
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

Revoked sign-ins: `-Dslums.auth.revoked=/var/lib/slums-login/revoked.txt` on live and `/var/lib/slums-login-test/revoked.txt` on TEST, the same file the login service reads (falls back to `<slums.weblogin.dir>/revoked.txt` when unset).

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
characters on the main world, Bera, world id 1 (not bots), oldest first, as `@weblogin` sends them. A new account has no characters.

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

**Rules** (sign-up): user 4-12 letters/digits; password `AUTO_REGISTER_MIN_PASSWORD_LENGTH` (6) to 23 printable ASCII
characters, no spaces. The server's login screen itself checks no length or characters (only the staff-name rule and
the minimum password length), so these are the site's proposal; 23 is the v83 client's password box (measured by the owner 2026-10-28; earlier notes said 12, which was wrong). Sign-in looks up
any existing account name (up to 64 characters), since older accounts may break the sign-up rules.

**Sign-in safety:** sign-in compares hashes only (bcrypt, SHA-1, SHA-512) and never a plain-text stored value (the
login screen still does, for old accounts; the bot template's "no login" marker is plain text). Banned and bot
accounts (`SMBotBase`, any account owning the template character `-Dsolomapling.template-id` or a character at or
above the bot id base, any account id in the bot range) are refused before any password comparison; a password over
64 characters (`VERIFY_PASS_MAX`) is never compared. Anything shorter is compared in full, as the game login does: the
game's password box takes up to 23 (a 13-character account signs in there; its first 12 are refused), so sign-in
never truncates; sign-up and reset cap new passwords at 23 (`PASS_MAX`). A legacy (SHA) match is upgraded to bcrypt when `BCRYPT_MIGRATION` is on, as the
login screen does.

**IP:** the service sends only `ipHash` (salted on its side); the server never sees a raw IP. It is stored on the used
code and logged with the sign-up. Missing: counted as one shared "none".

**Logged:** sign-ups (`Site sign-up: account '<name>' (id N) with invite ABCDE-FGHJK from ipHash <hash>`), lockouts
and the failed sign-in count; never a password, the secret, or a request body.

**Storage:** migration `036-invite-codes` (table `invite_codes`, times epoch ms, `expires_at` 0 = never).

**Login screen auto-register:** `AUTOMATIC_REGISTER` in config.yaml (already there). Live sets it to `false` with
1.6.0; existing accounts log in as before.

## Player invites (1.6.1, owner 2026-10-28, branch 161-invite)

`@invite` lets players bring friends. Any account with a character of level 30 or more (the typing character's level
or the highest saved level on the account; bots never count) makes a one-use code that never expires, up to **2 per
rolling 7 days** (a code stops counting 7 days after it was made; cancelled codes still count). `@invite list` shows
the account's own codes as `ABCDE-FGHJK  unused` / `used` / `cancelled by staff`, never who used them. GMs of level 3+
keep `!invite` (durations, notes, list of everyone's, revoke) and are unlimited; their codes don't use a player's week.
Gm levels 1-2 get the player rules.

Storage: migration `040-player-invites` (with rollback). `invite_codes.created_by_char` (the character; `!invite`
records it too), `invite_codes.self_serve` (1 for `@invite`, the weekly count), index `(created_by_account,
created_at)`. `accounts.invited_by_account` / `invited_by_char` are set in the sign-up transaction from the code, and
back-filled for already-used codes (account only). Staff-only: nothing shows the inviter to players or the site.
Abuse bounds: the level gate, 2 a week per account, ordinary sign-up IP limits; revoke any unused code with
`!invite revoke`. Tests: InviteCommandTest, InviteCodesTest, InviteRegisteredTest; migration run up twice, down twice
and up again on a throwaway MySQL 8.0.

## Site invite page (1.6.1, owner 2026-10-28, branch 161-siteinvites)

Source: the owner, 2026-10-28: a GM invite page on the website, and players can list and make their own codes on the
site under the same rule as `@invite`. Server side only here; the page is slumsms-site-0b's.

Three more routes on the same auth API, **same rules as `/verify` and `/signup`**: 127.0.0.1 only, header
`X-Slums-Auth: <the secret>` (401 `UNAUTHORIZED` first), `POST`, JSON body up to 4096 bytes (404 / 405 / 400 / 413 as
above). Every answer below is **HTTP 200**, success or error; errors are `{"ok":false,"error":"<CODE>"}`.

The site sends the **signed-in account's id** (`account_id`, a JSON integer, the one `/verify` returned) and nothing
about rank: the server decides GM or player from the database every time. **GM** = the account's highest
`characters.gm` is 3 or more (the same `gm_level` `/verify` returns; `!invite` needs 3 in game). GM levels 1-2 get the
player rules, as in game. Banned, temp-banned, bot accounts, the bot template, accounts whose site sign-in is revoked
(`revoked.txt`), unknown ids and a missing or non-integer `account_id` are all `REFUSED` on every route (the same check
as sign-in). The site should still only call these for a signed-in session and never pass an id the user typed.

Codes come back in display form `ABCDE-FGHJK`; codes sent in may have any case, dashes or spaces (the site sends
them normalised). **Times are unix seconds (UTC)**; `null` means "not set" (`expiresAt: null` = never expires). Keys
are camelCase, as the site's pages expect (the older `/verify` routes keep their snake_case).

**Site's error mapping** (slumsms-site-0b): `NOT_ELIGIBLE` / `REFUSED` 403, `WEEKLY_LIMIT` 429, `NOT_FOUND` 404,
`UNAVAILABLE` 503. Two more errors exist that it should handle: `ALREADY_USED` and `ALREADY_CANCELLED` (cancel; 409
fits), and `BAD_INPUT` with `rule:"life"` (GM create, 400).

### `POST /invite/create` `{"account_id":123, "life":30, "note":"for Bob"}`

- **GM:** `!invite`'s rules: `life` is a whole number of days **1-90** (a JSON number, or text `"30"` / `"30d"`),
  or `"forever"`; missing, null or empty means 7 days. `note` is cut to 100 characters (the site sends at most 64;
  control characters are dropped). No limit, never counts toward anyone's week.
  `{"ok":true,"code":"ABCDE-FGHJK","expiresAt":1802592000,"gm":true}` (`expiresAt` null for `forever`).
  Anything else in `life` (0, 91, 1.5, "2y"): `{"ok":false,"error":"BAD_INPUT","rule":"life"}`.
- **Player:** exactly `@invite` (the same code path, `InviteRules.makePlayerCode`): one-use, never expires, needs a
  character of level 30+ on the account (the saved level; the site can't see an online character's unsaved level),
  at most 2 per rolling 7 days counted together with codes made in game (cancelled ones still count). `life` and
  `note` are ignored. `{"ok":true,"code":"ABCDE-FGHJK","expiresAt":null,"gm":false,"left":1}` (`left`: codes it
  may still make this week, 0-2).
- Errors: `NOT_ELIGIBLE` (no level 30+ character), `WEEKLY_LIMIT` (2 in the last 7 days), `REFUSED`, `UNAVAILABLE`
  (database), `BAD_INPUT` + `rule:"life"` (GM only).

### `POST /invite/list` `{"account_id":123, "page":1, "limit":50}`

Newest first. `limit` is the page size (default 50, at most 100). `page` is 1-based; without it `offset` (default 0)
is used. Every answer carries `total`, `limit`, `offset`, `page`, `pages` (at least 1), `more` (true when a later page
has codes) and `next` (the next page number, or null).

- **GM:** every code.
  ```
  {"ok":true,"gm":true,"codes":[
    {"code":"ABCDE-FGHJK","status":"used","createdAt":1800000000,"expiresAt":1800604800,"usedBy":"Newfriend",
     "usedAt":1800000100,"cancelled":false,"cancelledAt":null,"cancelledBy":null,"owner":"Boss","ownerAccount":1,
     "playerCode":false,"note":"for Bob"}, ...],
   "total":37,"limit":50,"offset":0,"page":1,"pages":1,"more":false,"next":null}
  ```
  `status`: `open`, `used`, `expired`, `cancelled`. `owner` is the maker's character name for codes made in game and
  the account name for codes made on the site; `usedBy` is the new account's name (null when unused); `playerCode`
  is true for `@invite` / player-site codes (the ones that count toward a week); `cancelledBy` is who cancelled it (a
  player cancelling their own code shows as `<account> (own)`).
- **Player:** only codes this account made (in game or on the site). No `owner`, no `note`, and `usedBy` is only a
  boolean (never who used it).
  ```
  {"ok":true,"gm":false,"codes":[
    {"code":"ABCDE-FGHJK","status":"unused","createdAt":1800000000,"expiresAt":null,"usedBy":false,"usedAt":null,"cancelled":false},
    {"code":"MNPQR-STUVW","status":"used","createdAt":1799900000,"expiresAt":null,"usedBy":true,"usedAt":1799950000,"cancelled":false}],
   "left":1,"eligible":true,"total":2,"limit":50,"offset":0,"page":1,"pages":1,"more":false,"next":null}
  ```
  `status`: `unused`, `used`, `cancelled` (they cancelled it), `cancelled_by_staff`, `expired` (only possible for a
  code an account made with `!invite` while it was a GM). `left`: codes it may still make this week (0-2);
  `eligible`: the account has a level 30+ character now (show the create button).
- Errors: `REFUSED`, `UNAVAILABLE`.

### `POST /invite/cancel` `{"account_id":123, "code":"ABCDEFGHJK"}`

- **GM:** any unused code. **Player:** only a code that account made; anyone else's code (and any code that doesn't
  exist or isn't a code) answers `NOT_FOUND`, so players can't probe codes.
- `{"ok":true,"result":"CANCELLED","code":"ABCDE-FGHJK"}`
- Errors: `NOT_FOUND`, `ALREADY_USED`, `ALREADY_CANCELLED`, `REFUSED`, `UNAVAILABLE`. A cancelled player code still
  counts toward that account's week (as `!invite revoke` does in game), so cancel-and-remake can't beat the limit.

**Differences from the site's draft shapes:** `left` is also on the create answer; `gm`, `status`, `eligible`,
`total`, `limit`, `offset`, `ownerAccount`, `playerCode`, `cancelledAt` and `cancelledBy` are extra (ignore what you
don't need); `cancelled` and player `usedBy` are always present (booleans) rather than omitted; GM `usedBy` is null
when unused; the cancel errors `ALREADY_USED` / `ALREADY_CANCELLED` need a mapping; `life` above 90 days is
`BAD_INPUT`, and `"forever"` is accepted although the page doesn't send it.

### Logged and stored

Every create and cancel is logged with the acting account id, never a request body or the secret:
`Invite ABCDE-FGHJK made by player Pal (account 2, char 0) via site (account 2), 1 of 2 this week`,
`Invite ABCDE-FGHJK made by GM Boss (account 1, char 0) via site (GM account 1) (30d): for Bob`,
`Invite ABCDE-FGHJK cancelled on the site by player account 2 (Pal)`. In-game `@invite` / `!invite` log the same
lines with `via @invite` / `via !invite`, and `!invite revoke` now logs the account id too.

Same `invite_codes` table (migrations 036 and 040), **no new migration**. Site-made codes have `created_by_char` 0
(no character), so an account that signs up with one gets `invited_by_char` 0 and the right `invited_by_account`.
`@invite list` in game shows the player's own site cancels as `cancelled` (staff cancels stay `cancelled by staff`).

Turning it on: nothing new. The routes exist wherever the auth API runs (`-Dslums.auth.port`).

Code: `SiteInvites` (routes), `InviteRules` (the one place both the game commands and the site make codes; the week's
count and the insert share a lock so the game and the site can't both take the last slot), `SiteAuthApi`. Tests:
`SiteInvitesTest` (secret, GM vs player rules, level gate, weekly limit and rolling, list shapes and paging, cancel
rules, refused accounts, database down, unix seconds, page/offset paging, life 1-90 days), `InviteCommandTest` (a code made in game counts on the site and the other
way round; a player's own site cancel reads `cancelled` in game).
