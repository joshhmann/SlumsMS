# Signing in to the website with a game account

Owner, 2026-10-26: the database and the Slums Market are for players, behind a sign-in linked to their game accounts.
The website never sees a game password and never reads the game database: the player proves who they are by typing a
code **in game**.

## How it works

1. `/login/` (public page) asks `POST /api/login/start`. The login service (`tools/site/login_service.py`) makes a
   six-letter code such as `K7Q4ZP` (no 0/O/1/I), good for 10 minutes, and gives this browser a private "pending"
   cookie for it.
2. The player types `@weblogin K7Q4ZP` in game.
3. **The game server** checks the code's shape and writes an approval file (contract below).
4. The page polls `GET /api/login/poll` every 3 s. When the approval is there, the service deletes it (a code works
   once) and sets a signed session cookie for 30 days, HttpOnly, SameSite=Lax, Secure.
5. nginx asks `GET /api/auth/check` before serving `/database/` and `/market/`; anyone not signed in is sent to
   `/login/?next=...`.

Sessions are HMAC-SHA256 signed with `/etc/slums-login/key` and nothing about them is stored on the server. Signing
someone out early: put their account id in `/var/lib/slums-login/revoked.txt` (one per line). The game server can
write banned accounts there too.

## What the game server needs to do (server side, not in this repo)

`@weblogin <CODE>` for any logged-in player:
- `CODE` must be 6 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (upper-case it first). Anything else: reply
  "That isn't a website code." and write nothing.
- Write `<approvals>/<CODE>.json` atomically (write `.tmp`, then rename) with mode 0640, group `slums-login`:
  `{"code": "K7Q4ZP", "account_id": 123, "account": "loginname", "character": "Momo", "gm": 0, "time": 1793001642.5}`.
  `time` is Unix seconds and is **required**; the service refuses approvals older than 10 minutes or without it.
- Reply "Website sign-in sent. Go back to your browser." Rate-limit it per account (say 5 a minute) so nobody can
  guess codes by spamming the command.
- The approvals folder is `/var/lib/slums-login/approvals` on 164. The game server's user needs write access to it
  (group `slums-login`, mode 2770); nothing else is shared.
- Bots must never run it (they have no account).
- Optional: write banned account ids to `/var/lib/slums-login/revoked.txt`.

## Install on 164 (owner's go: it changes the public site's web server)

```
useradd --system --no-create-home --shell /usr/sbin/nologin slums-login
install -d -o slums-login -g slums-login -m 2770 /var/lib/slums-login/approvals
install -d -m 0750 -g slums-login /etc/slums-login
head -c 48 /dev/urandom > /etc/slums-login/key && chgrp slums-login /etc/slums-login/key && chmod 0640 /etc/slums-login/key
install -d /opt/slums-login && install -m 0644 tools/site/login_service.py /opt/slums-login/
install -m 0644 tools/site/slums-login.service /etc/systemd/system/ && systemctl daemon-reload
systemctl enable --now slums-login
curl -s http://127.0.0.1:8094/api/login/health        # {"ok": true}
```
Add the game server's user to the `slums-login` group. Then add `tools/site/slums-login.nginx.conf` inside the site's
nginx server block, `nginx -t && systemctl reload nginx`, and publish the private build (`dist/site-private`).

## The market service (watchlist, alerts, Buy/Sell board)

`tools/site/market_service.py` (127.0.0.1:8095) checks the same session cookie with the same key (read only) and
keeps its own store in `/var/lib/slums-market/market.db`. It talks to the game server only through files: it writes
`/var/lib/slums-market/inbox/{posts,alerts}.json` and reads `Server/data/site-outbox/` (board, fired alerts, rejected
and removed posts). Nothing on the site moves items or mesos. Limits per account: 20 watched items, 10 alerts, 5 open
posts (3 days each), 30 changes a minute. Writes need a JSON body and the header `X-Slums: 1`, and a browser Origin
must match the Host, so another website can't post for a signed-in player.

For the board, the sign-in approval may carry `"characters": [...]` (the account's characters on Bera) so a post can
be made as any of them; without it, a post is made as the character that typed `@weblogin`. `"gm" > 0` lets that
session take down any post.

```
useradd --system --no-create-home --shell /usr/sbin/nologin -G slums-login slums-market
install -d -o slums-market -g slums-market -m 2750 /var/lib/slums-market /var/lib/slums-market/inbox
install -m 0644 tools/site/slums-market.service /etc/systemd/system/ && systemctl daemon-reload
systemctl enable --now slums-market
curl -s http://127.0.0.1:8095/api/market/health       # {"ok": true}
```
The game server's user joins group `slums-market` (to read the inbox) and starts with the inbox and outbox paths
(the server side's flags are in the "Site board contract", docs/design/track-d-plan.md). The nginx snippet already
routes `/api/market/`.

## Safety notes
- The key never leaves 164. Never print it, copy it or commit it. Changing it signs everyone out.
- Codes are 6 characters from a 32-letter set (about 1 billion codes) and live for 10 minutes; the game command is
  rate-limited. Brute force is not a practical risk.
- Code phishing: if someone gets a friend to type *their* code, that person's browser is signed in as the friend.
  Since 1.6.0 that is more than reading pages: they could post on the Buy/Sell board under the friend's IGN, or
  change the friend's watchlist and alerts. Nothing can move items or mesos, the game checks every post, and the
  friend can take a post down, or a GM can; banning or listing the account in `revoked.txt` signs the browser out.
  The page says to only type a code you got yourself.
- The service listens on 127.0.0.1 only, logs only the method and path (no addresses or cookies), and answers
  nothing but the endpoints above.
