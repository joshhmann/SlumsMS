# Signing in to the website with a game account

Owner, 2026-10-26: the database and the Slums Market are for players, behind a sign-in with their game accounts.
There is one way in, on the site and in the launcher: the **game username and password** (ticket 105). New friends
sign up with an **invite code** that a GM makes in game (`!invite`). The earlier `@weblogin` code typed in game is gone.
The website never checks or stores a password and never reads the game database: the game server checks it.

## How it works

1. `/login/` (public page) has two forms. **Sign in** sends `POST /api/login/password {"user", "pass"}`; **sign up**
   sends `POST /api/login/signup {"code", "user", "pass"}`. Both need a JSON body and `X-Slums: 1`, and a browser
   Origin must match the Host. The launcher sends the same calls with no Origin.
2. The login service (`tools/site/login_service.py`, 127.0.0.1:8094) checks the sign-up rules (username 4-12 letters
   or digits, password 6-23 printable characters with no spaces: the game's login box takes 23) and 10 tries a minute
   per address. It then asks **the game server's auth API** on localhost (`POST /verify` or `/signup`, header
   `X-Slums-Auth` from `/etc/slums-auth/secret`) and sends a salted hash of the address (`ipHash`, salt
   `/etc/slums-login/ip-salt`), never the address. The API spec is in ticket 105 (`docs/tickets/105-invite-signup.md`
   in the server repo). Sign-in sends any name the game might have: older accounts may break the sign-up rules.
3. On success the service sets a signed session cookie for 30 days (HttpOnly, SameSite=Lax, Secure). The session
   carries the account id and name, the account's characters on Bera (for the Buy/Sell board) and the GM flag.
   Errors are generic: "Wrong username or password.", "That invite code doesn't work.", "That username is taken.",
   "Too many wrong tries. Try again in 15 minutes." and so on.
4. nginx asks `GET /api/auth/check` before serving `/database/` and `/market/`; anyone not signed in is sent to
   `/login/?next=...`. `/secret/` (the hidden Slums-chan stickers, theme.js) is checked the same way and simply
   answers 401 when not signed in.

**Forgotten passwords** (ticket 107; there's no email, an admin approves each reset). `/forgot/` (public) asks
`POST /api/login/reset/request {"user", "contact"}`: the game server answers the same for any name, and the request
(id and secret) stays in an HttpOnly cookie `slums_reset` in that browser. The page asks `POST /api/login/reset/status`
every 10 s while it waits. An admin approves in game (`!resetpw approve <account>`); then the page sends
`POST /api/login/reset/complete {"pass"}` (6-23 characters). On success **every session of the account is signed out**
(its id and the time go in `/var/lib/slums-login/revoked-before.txt`; sessions issued up to then stop working, in the
market service too) and this browser is signed in afresh. The launcher's "Forgot password?" opens `/forgot/`.

Sessions are HMAC-SHA256 signed with `/etc/slums-login/key`. **Sign out** (`POST /api/login/logout`) ends that session
on the server too: its id goes in `/var/lib/slums-login/revoked-sessions.txt` until it would have expired, and the
market service reads the same file. Signing someone out everywhere: put their account id in
`/var/lib/slums-login/revoked.txt` (one per line). The game server can write banned accounts there too.

## Install on 164 (owner's go: it changes the public site's web server)

```
useradd --system --no-create-home --shell /usr/sbin/nologin slums-login
install -d -o slums-login -g slums-login -m 0750 /var/lib/slums-login
install -d -m 0750 -g slums-login /etc/slums-login
head -c 48 /dev/urandom > /etc/slums-login/key && chgrp slums-login /etc/slums-login/key && chmod 0640 /etc/slums-login/key
# the address salt, and read access to the game's auth secret (the owner makes /etc/slums-auth/secret with 1.6.0)
head -c 32 /dev/urandom > /etc/slums-login/ip-salt && chgrp slums-login /etc/slums-login/ip-salt && chmod 0640 /etc/slums-login/ip-salt
chgrp slums-login /etc/slums-auth /etc/slums-auth/secret && chmod 0750 /etc/slums-auth && chmod 0640 /etc/slums-auth/secret
install -d /opt/slums-login && install -m 0644 tools/site/login_service.py /opt/slums-login/
install -m 0644 tools/site/slums-login.service /etc/systemd/system/ && systemctl daemon-reload
systemctl enable --now slums-login
curl -s http://127.0.0.1:8094/api/login/health        # {"ok": true}
```
The game server's auth API (127.0.0.1:8186, ticket 105) and its secret `/etc/slums-auth/secret` come with 1.6.0; the
owner makes the secret. Until both exist, sign-in and sign-up answer "Sign-in is down right now". Then add `tools/site/slums-login.nginx.conf` inside the site's
nginx server block, `nginx -t && systemctl reload nginx`, and publish the private build (`dist/site-private`).

## The market service (watchlist, alerts, Buy/Sell board)

`tools/site/market_service.py` (127.0.0.1:8095) checks the same session cookie with the same key (read only) and
keeps its own store in `/var/lib/slums-market/market.db`. It talks to the game server only through files: it writes
`/var/lib/slums-market/inbox/{posts,alerts}.json` and reads `/var/lib/slums-market/outbox/` (board, fired alerts,
rejected and removed posts), which the game server writes. Both folders sit outside the live server folder because
`Server/` on 164 is root-only (mode 700), and so the site never writes inside it. The board, watchlist and alerts never move items or mesos. Limits per account: 20 watched items, 10 alerts, 5 open
posts (3 days each), 30 changes a minute. Writes need a JSON body and the header `X-Slums: 1`, and a browser Origin
must match the Host, so another website can't post for a signed-in player.

For the board, the session carries the account's characters on Bera, so a post can be made as any of them (a new
account with no character yet can't post). A GM session can take down any post.

```
The install commands are LAUNCH.md step 2b. Live runs as root, so it can read the inbox and write the outbox with no
group change; it is started with the two paths (the flags are in the "Site board contract",
docs/design/track-d-plan.md). The nginx snippet already routes `/api/market/`.

## Safety notes
- The key never leaves 164. Never print it, copy it or commit it. Changing it signs everyone out.
- Passwords pass through the login service only in memory, over HTTPS on live (Caddy), and are never logged or stored.
  The launcher keeps only the session token.
- Password guessing: 10 tries a minute per address here, and the game server locks a name for 15 minutes after 5
  wrong tries (names that don't exist lock the same way, so a lock reveals nothing).
- A stolen session cookie works until it expires, the player signs out, or the account goes in `revoked.txt`.
  The board can't move items or mesos; the game checks every board post, and the player or a GM can take a post
  down. The Slums Exchange (1.6.2) can: a cookie can place and cancel exchange orders for that account's
  characters and NX, with the game doing the moving (items only from a character that is logged in). So (b005):
  - Exchange actions need a sign-in at most 12 h old (owner, 2026-10-29): older, the market app answers 403 `REAUTH`
    and the page links to `/login/?again=1&next=...`, which shows the forms even when signed in. Every exchange
    request also carries `siteAuthAt` (the cookie's `iat` in epoch ms), and the game refuses a stale one too.
  - The game keeps its own record of password sign-ins (12 h; a cookie can't make one) and answers `REAUTH` with
    `data.game: true` when it has none, e.g. after every game restart. The page sends the player to the same
    `/login/?again=1`, and that sign-in records one.
  - Password step: every site sell, and site buys once an account's password-free buys pass 10,000,000 mesos in 12
    h, need the password for that one order. The preview says `passwordRequired`, or the confirm answers
    `PASSWORD_REQUIRED`. The confirm page then sends `{token, pass}` to `POST /api/login/exchange-approve` here, which
    passes it with the session's account id and name to the game's `/exchange/approve`. The game checks it like a
    sign-in (the same rate limit and lockout, and it must be this account's password) and approves that preview
    token; the confirm then goes through as usual. The password is never logged, kept or written to the exchange
    inbox. It's not a cap: with the password every order goes through.
  - The game keeps NX orders in a band around the official rate (`PRICE_BAND`). There is no daily NX cap (owner:
    no daily caps; the 1% fee each side stays).
- Staff Bots page (1.6.2, ticket 157; `admin/bots`): `GET /api/login/staff/inspect/bots/<cid>`, `/maps/<id>` and
  `/positions` here pass on to the game's read-only bot inspect endpoint on the same host's loopback (the server's
  `docs/design/inspect-api.md`), only for an admin session (GM 3+, signed in at most 12 h ago) and 120 calls a minute
  per account. The game's secret (`X-Slums-Inspect`, read from `SLUMS_LOGIN_INSPECT_SECRET_FILE`) stays here and never
  reaches a browser; redirects are never followed; only a loopback `SLUMS_LOGIN_INSPECT_URL` is used. Nothing it
  reaches can change the game. Off on live until the owner says go (the unit's lines are commented out).
- The service listens on 127.0.0.1 only, logs only the method and path (no addresses or cookies), and answers
  nothing but the endpoints above.
