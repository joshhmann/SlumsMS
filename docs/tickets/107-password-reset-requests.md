# 107: Forgotten passwords, reset after a GM approves the request

**Area:** Server / Site / Launcher
**Kind:** FEATURE, 1.6.0 (owner 2026-10-26)
**Status:** server part built (branch 160-resetpw); site pages to do
**Related:** 105 (invite sign-up and site sign-in, the auth API this extends)

Source: the owner, 2026-10-26: forgotten passwords are handled by the owner (a GM) approving a request, with no email
and no codes passed around.

## Why

With 105 the site and launcher sign in with the game password, and the login screen no longer creates accounts. A
friend who forgets their password has no way back in. We have no email addresses and don't want any, so a person
(a GM) decides.

## What

1. **Ask** on the site: "Forgot your password?" takes the account name and a contact (a Discord name or anything a GM
   can reach them at, 64 characters at most, PG-13). The site always says "Asked. A GM will get back to you", for
   every name, so names can't be probed. The browser keeps a random secret (a cookie) that only it has.
2. **GMs** (level 3+) online get a yellow notice when a request comes in. `!resetpw list` shows the pending ones:
   account, when (PT), contact, the first characters of the ipHash. The GM reaches the player at the contact, makes
   sure it is really them (for example, asks for their characters' names), then `!resetpw approve <account>` or
   `!resetpw deny <account>`. Both are logged with the GM's name. The notice says when the account is a GM's.
3. **Set** a new password: the browser that asked sees "approved" and gets a new-password form (the sign-up rules).
   That signs the player straight in and signs the account out everywhere else on the site and launcher.
4. **Times:** a request lives 24 hours; an approval 2 hours, and never past the request's 24. One open request per
   account: a new one replaces the old (an approved, unused one too). Nothing changes in game: nobody is kicked.
5. **Dashboard:** the Accounts panel shows pending, approved (used) and denied counts and the latest 10 requests
   (account, time, state, who decided, contact). No secrets or hashes.

## Shape

- Three routes on the 105 auth API (127.0.0.1 only, the same `X-Slums-Auth` secret, JSON body at most 4096 bytes,
  the same 401/404/405/400/413 answers). Only the site's login service calls them.
- The game server stores the request (migration 037); the site stores only the request id and the secret in that
  browser. The secret is stored on the server only as its SHA-256.
- The login service owns sessions: the server does not write the revoked file (`-Dslums.auth.revoked`). The site
  revokes the account's sessions itself (a revoked-before mark) after a reset; the server's `revoke_sessions: true` is
  informational only.

## Owners

- Server (API, table, `!resetpw`, GM notice, dashboard): main session (built).
- Site pages and login service calls: slumsms-site-0b.
- Launcher: a "Forgot password?" link that opens the site page (src-15, optional).

## Done when

On TEST: a request for a real account shows up for an online GM, `!resetpw approve` lets that browser set a new
password, the old password stops working and the new one works in game; a request for a made-up or bot name looks the
same to the site over time (pending, then expired) and no GM sees it; a denied, expired, replaced or used request can't set a password; another
browser (no secret) can't.

## Server API (built 2026-10-26, branch 160-resetpw)

Same API as 105 (see "Server API" in docs/tickets/105-invite-signup.md for turning it on, the secret, the 401/404/
405/400/413 answers and the caller's timeout). A server without migration 037 answers `/reset/request` as usual but
stores nothing (every request then reads `expired`), and `/reset/complete` never succeeds (`!resetpw` says requests aren't available).

### `POST /reset/request`

`{"user":"Forgetful","contact":"bob#1234","ipHash":"<salted hash>"}`

Answer, **for every name** (real, unknown, banned, temp-banned, bot, bot template, revoked site sign-in):
`200 {"ok":true,"request_id":"<32 hex chars>","secret":"<43 chars, base64url>"}`. Both match `[\w-]+`; the secret is
base64url (no padding) of 32 random bytes, so within the site's 8-128 characters. The contact is sent only here.

For a real, resettable account (the same refusals as `/verify`) the request is stored as pending, any older open one
for the account is replaced, and online GMs of level 3+ are told.

**Decoys** (fixed 2026-10-26 after the site's QA found `/reset/status` told names apart): for every other name (unknown,
banned, temp-banned, bot, bot template, revoked) a decoy is stored the same way: account id 0, keyed by a salted HMAC
of the name (never the name itself), no contact or IP, state pending. It reads `pending` until 24 h pass and then
`expired`; `/reset/complete` answers `NOT_APPROVED` and then `EXPIRED`; a second request for the same name replaces the
first. That is exactly what a real request looks like while no GM has acted. Decoys send no GM notice, never show in
`!resetpw list`, can't be approved or denied, and aren't counted or listed on the dashboard. Storing and the GM notice
happen on a background thread, and the refusal check runs for unknown names too, so the answers take about as long;
status and complete do the same lookup and hash compare for both. Rate limits count per ipHash and overall, never per
name, so they reveal nothing either. Only a GM's decision (approved, denied) makes a real request look different, and
only the browser holding the secret can see that.

The site keeps `request_id` and `secret` in that browser only (an HttpOnly, Secure, SameSite=Strict cookie, 24 h) and
never logs the secret.

Errors (the same for every name, so they reveal nothing):

| error | when |
|---|---|
| `BAD_INPUT` + `rule` | `user_length` (empty or over 64), `contact_length` (empty after cleaning, or over 64 characters), `contact_words` (fails the PG-13 guard) |
| `RATE_LIMITED` (HTTP 429) | over 5 requests an hour from one ipHash, or 30 an hour overall |
| `UNAVAILABLE` | the server could not reach the database |

Contact cleaning: control characters become spaces, emoji and other characters outside the basic plane are dropped,
runs of spaces fold into one, ends trimmed. What is left is shown to GMs as typed.

### `POST /reset/status`

`{"request_id":"...","secret":"...","ipHash":"<optional>"}` →
`200 {"ok":true,"status":"pending"|"approved"|"denied"|"expired"}` (the field is `status`).

`expired` also covers: unknown id, a wrong or missing secret, the random answer for an unknown name, a used request,
a replaced request, a pending request over 24 h, and an approval over 2 h (or past the request's 24 h). `denied`
stays `denied`. Over 30 a minute from one ipHash or 300 a minute overall: `429 {"ok":false,"error":"RATE_LIMITED"}`.
Polling every 15-30 s while the page is open is fine.

### `POST /reset/complete`

`{"request_id":"...","secret":"...","pass":"newpass1","ipHash":"<salted hash>"}`

Only while the request is `approved` and fresh. Sets the password with the sign-up rules and hashing (bcrypt when the
server's `BCRYPT_MIGRATION` is on, as live has it; the same as `/signup`), marks the request used (in one transaction,
so it works once), and answers as `/verify` does, plus `revoke_sessions`:

`200 {"ok":true,"account_id":123,"account":"Forgetful","gm":false,"gm_level":0,"characters":["Slum"],"revoke_sessions":true}`

`revoke_sessions` is informational: the site revokes every earlier site and launcher session of `account_id` itself
(a revoked-before mark) and issues the new one. Nobody adds the account to `revoked.txt`: that list blocks site
sign-in for good (105). Nothing happens in game (a player who is logged in stays logged in).

Errors:

| error | when |
|---|---|
| `BAD_INPUT` + `rule` | `pass_length`, `pass_chars` (the sign-up rules: `AUTO_REGISTER_MIN_PASSWORD_LENGTH` (6) to 23 printable ASCII, no spaces). Checked first; the request stays usable. |
| `NOT_APPROVED` | still pending (wait), or denied |
| `USED` | this request already set a password (the right secret is needed to learn this) |
| `EXPIRED` | the request is past 24 h, the approval past its 2 h, replaced by a newer request, the account banned since, an unknown id, or a wrong secret |
| `RATE_LIMITED` (HTTP 429) | over 10 tries an hour from one ipHash, or 60 an hour overall |
| `UNAVAILABLE` | the server could not reach the database |

### What the site says

- After asking: "If that account exists, a GM will look at it. Keep this page (or come back in this browser) to see
  the answer." Never "no such account".
- `pending`: "Waiting for a GM." `approved`: the new-password form ("you have 2 hours"). `denied`: "A GM said no.
  Reach out on Discord." `expired`: "This request is no longer valid. You can ask again."

### Logged (server)

Requests for real accounts (account, id, the first 8 characters of the request id, the first 6 of the ipHash),
approvals and denials (with the GM), completions (with the approving GM). Never a password, a secret or its hash,
the contact, or a typed name that is not an account.

### Storage

Migration `037-password-resets` (table `password_resets`, times epoch ms; `state` pending, approved, denied, used or
replaced; `secret_hash` is the SHA-256 of the secret; no password column). Rollback `037-password-resets.rollback.sql`.

### GM commands (level 3)

- `!resetpw list`: pending requests, oldest first (15 at most): `Forgetful  Oct 26 14:05 PT  contact: bob#1234  ip 3fa9c1  (expires in 21h)`.
- `!resetpw approve <account>`: the account's newest pending request; the reply repeats the contact.
- `!resetpw deny <account>`: the account's newest pending or approved (unused) request.
