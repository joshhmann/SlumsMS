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
  `{"code": "K7Q4ZP", "account_id": 123, "account": "loginname", "character": "Momo", "characters": ["Momo", "MomoAlt"], "gm": 0, "time": 1793001642.5}`.
  `characters` (1.6.0 board): the account's IGNs in world Bera, so the site can let the player post as any of them;
  `gm` is the GM level (the site gives GMs the board's take-down).
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

## Safety notes
- The key never leaves 164. Never print it, copy it or commit it. Changing it signs everyone out.
- Codes are 6 characters from a 32-letter set (about 1 billion codes) and live for 10 minutes; the game command is
  rate-limited. Brute force is not a practical risk.
- Code phishing: if someone gets a friend to type *their* code, that person's browser is signed in as the friend.
  The sign-in only opens read-only pages (database, market), so the harm is small. The page says to only type a
  code you got yourself.
- The service listens on 127.0.0.1 only, logs only the method and path (no addresses or cookies), and answers
  nothing but the endpoints above.
