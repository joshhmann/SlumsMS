# Website launch checklist (one sitting with the owner)

Everything here is built and tested on branch `site-160-theme`. **Each numbered step changes something people can
see or the site's web server, so each needs the owner's "go" for that step.** The site launches **the same night as
1.6.0** (owner, 2026-10-26): World needs the site export, the market needs the market export, and the sign-in needs
the game's auth API (ticket 105), all three in 1.6.0. There is one way in: the game username and password, and new
friends sign up with an invite code (`!invite`); `@weblogin` is gone (owner, 2026-10-26).

**Order on the night** (the step numbers below are what each does, not the order):
1. `deploy-build-164.sh` (the copy-only part of step 6: puts `tools/site` in `/opt/slums-site-build`).
2. Step 2 (sign-in service) and **step 2b (market service, its user and `/var/lib/slums-market/{inbox,outbox}`)**.
   **Both must be done before the 1.6.0 deploy.** The 1.6.0 unit turns on `-Dslums.board=true`, and the game then
   writes `/var/lib/slums-market/outbox`. If the folder isn't there yet, the game makes it without the
   `slums-market` group and the site can't read the board.
3. The 1.6.0 deploy (release tools, the owner runs `deploy.sh`). It brings the auth API for sign-in (step 3), the board,
   and the market and site exports.
4. A few minutes later, once the exports have written their first files: step 4 (nginx guard), then step 1 (public
   site with World), then step 5 (Slums Street and the market), then step 6 (the two timers).
Until step 5 publishes `database/search-index.json` and `market/untradeable.json`, the market service turns down
every post and watch ("isn't on Slums Street"); nobody can reach it before step 4 anyway.

What players get: **World** (public: live world, "what's happening" feed, rankings, Bera economy), and for signed-in
players **Slums Street** (the database, at `/database/`) and the **Slums Market** (`/market/`).

Where things are today (read on 2026-10-26):
- 164 serves the site with nginx, `server { listen 8092; root /srv/slums-site; }` in
  `/etc/nginx/sites-available/slums-site`, plus `/api/report` to `slums-report` on 127.0.0.1:8093.
- HTTPS for `theslumsms.asslorde.com` ends at the **owner's Caddy**, on the owner's side of the network, which
  reverse-proxies to 164's nginx on 8092. The owner confirmed on 2026-10-26 that Caddy serves the site for now, so
  step 1 needs no change on the Caddy side. Nobody but the owner has its config.
- Live shows the old look from branch `site` (`788cb52ad`), with no robots rules, and it says MapleStory.

## Before the sitting (agent, no go needed)
```
git -C /root/work/SlumsMS-site pull
python3 -m unittest discover -s tools/site -p 'test_*.py'      # all green
python3 tools/site/build.py                                    # public: dist/site
python3 tools/site/build.py --world                            # public + World (sample data): dist/site
python3 tools/site/build.py --private --world                  # with Slums Street + Slums Market: dist/site-private
```
Show the owner: home, a patch note, a guide page, report, 404 (light and dark, phone), World, Slums Street and the
market.

Real data for the publish, copied read-only from live once 1.6.0 has run for a few minutes (reading live files is fine):
```
mkdir -p cache/live-export && rsync -a "root@192.168.0.164:/opt/mapleroot/MapleRoot Full Repack/Server/data/site" \
    "root@192.168.0.164:/opt/mapleroot/MapleRoot Full Repack/Server/data/market" cache/live-export/
```

## 1. Publish the new public site
What it changes: the new look and brand art, the "Slums Story" wording, search engines turned away (robots.txt plus
noindex on every page), the `/login/` page and World. Slums Street and the market are not in it.
```
python3 tools/site/build.py --world --site-data cache/live-export/site --market-data cache/live-export/market
grep -rl samplebar dist/site | wc -l                              # 0: no sample data goes out
ssh root@192.168.0.164 'cp -a /srv/slums-site /srv/slums-site.bak-$(date -u +%Y%m%dT%H%M%SZ)'
rsync -a --delete dist/site/ root@192.168.0.164:/srv/slums-site/
```
Check:
```
curl -s https://theslumsms.asslorde.com/robots.txt               # User-agent: * / Disallow: /
curl -s https://theslumsms.asslorde.com/ | grep -c 'noindex'     # 1
curl -s https://theslumsms.asslorde.com/ | grep -ci 'maplestory' # 0
curl -s https://theslumsms.asslorde.com/world/ | grep -c 'Online now'   # 1
```
Undo: `rsync -a --delete /srv/slums-site.bak-<stamp>/ /srv/slums-site/` on 164.

## 2. Install the sign-in service (not reachable from outside yet)
Follow `docs/site/LOGIN.md`, "Install on 164": user and group `slums-login`, `/var/lib/slums-login`, the
**owner** makes the key (`head -c 48 /dev/urandom > /etc/slums-login/key`; nobody prints or copies it) and the
address salt `/etc/slums-login/ip-salt` the same way, the unit `slums-login.service`. For password sign-in and invite
sign-up (ticket 105) the **owner** also makes the game's auth secret `/etc/slums-auth/secret` (read by the game server
and, through group `slums-login`, by the sign-in; LOGIN.md has the commands). Without it, sign-in and sign-up answer
"Sign-in is down right now". Check: `curl -s http://127.0.0.1:8094/api/login/health` gives `{"ok": true}`.
Undo: `systemctl disable --now slums-login`.

### 2b. Install the market service (watchlist, alerts, Buy/Sell board; not reachable from outside yet)
Owner's go needed: it adds a system user, folders and a unit on 164. After step 2, and after `deploy-build-164.sh`
(step 6) has put `tools/site` in `/opt/slums-site-build`. What the folders are for (`docs/site/LOGIN.md`, "The market
service"): the site writes `inbox/`, the game server writes `outbox/`. They sit outside the live server folder because
`Server/` is root-only (mode 700, read 2026-10-26), and live runs as root, so the game server needs no group change.
```
useradd --system --no-create-home --shell /usr/sbin/nologin -G slums-login slums-market   # reads the sign-in key
install -d -o slums-market -g slums-market -m 2750 /var/lib/slums-market
install -d -o slums-market -g slums-market -m 2750 /var/lib/slums-market/inbox    # the site writes, root reads
install -d -o root -g slums-market -m 2750 /var/lib/slums-market/outbox           # root writes, the site reads
install -m 0644 /opt/slums-site-build/tools/site/slums-market.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now slums-market
```
Check: `curl -s http://127.0.0.1:8095/api/market/health` gives `{"ok": true}`; `/var/lib/slums-market/inbox/posts.json`
appears with an empty list; `sudo -u slums-market touch /var/lib/slums-market/outbox/x` fails (the site can't write
the game's folder). Live's 1.6.0 start line points the board's inbox and outbox flags at these two folders (step 3).
The service makes its SQLite store `/var/lib/slums-market/market.db` on first start, with every table it needs,
including `cards` (the Slums-chan cards each account has found, "My cards"; nothing to migrate or restart).
Check it's there: `python3 -c "import sqlite3; print(sorted(r[0] for r in sqlite3.connect('/var/lib/slums-market/market.db').execute('select name from sqlite_master where type=\'table\'')))"`
lists `alerts, cards, notes_seen, posts, seen_files, watch`. Signed out, `curl -s http://127.0.0.1:8095/api/market/cards`
gives `{"ok": false, "error": "Sign in first."}` (status 401); that answer is the same for any path without a
sign-in, so the signed-in check of the endpoint is in step 5.
After live 1.6.0 is up and its first 15-minute board cycle has run: `ls -l /var/lib/slums-market/outbox/` shows group
`slums-market` on `trade-ratings.json` and the board files (`board.json`, `offers.json`, ...). The game writes them 0640
and never chowns, so the setgid folder gives them that group and the market service reads them through it; a file in
group `root` means the folder was made without `-g slums-market -m 2750` (fix the folder, then wait one cycle).
Undo: `systemctl disable --now slums-market`, then `userdel slums-market` and `rm -r /var/lib/slums-market` if wanted
(that deletes every watchlist, alert and post).

## 3. The game's auth API and the board on live (part of the 1.6.0 deploy)
Live 1.6.0 serves the auth API on 127.0.0.1 only, checking `/etc/slums-auth/secret` (step 2). The live unit's start
line gets all three (the API is off when `slums.auth.port` is unset; note `secretFile`, not `secret`):
```
-Dslums.auth.port=8186 -Dslums.auth.secretFile=/etc/slums-auth/secret -Dslums.auth.revoked=/var/lib/slums-login/revoked.txt
```
The revoked file is the same one the sign-in and market services read, so signing an account out (or a ban written there) stops it
both on the site and at sign-in; without it the server would look inside the old `@weblogin` folder and the two would
disagree. It also starts with the board's inbox and outbox flags set to `/var/lib/slums-market/inbox` and
`/var/lib/slums-market/outbox` (names in the "Site board contract"). Live runs as root, so no group change is needed. TEST has its own
(`-Dslums.auth.port=8196 -Dslums.auth.secretFile=/etc/slums-auth-test/secret -Dslums.auth.revoked=/var/lib/slums-login-test/revoked.txt`), the TEST sign-in on 8194 with its own key (`tools/site/test164/README.md`), so a TEST
account can never sign anyone in to the real site. Check:
`curl -s -X POST -H 'X-Slums: 1' -H 'Content-Type: application/json' -d '{"user":"nobody1","pass":"wrongpw"}' http://127.0.0.1:8094/api/login/password`
gives 401 "Wrong username or password." (503 "down" means the API or its secret isn't there yet).
The same release sets `AUTOMATIC_REGISTER: false` in live's config.yaml (the login screen stops making accounts; new
friends sign up with an invite code) and runs migration 036 (`invite_codes`). Make a first code in game with
`!invite 1d <note>` and try a sign-up before telling friends.

## 4. Guard the database and market in nginx
Add `tools/site/slums-login.nginx.conf`: part 1 (the `slumsapi` rate zone) in the http block next to the report form's
zone, part 2 inside the `listen 8092` server block. Then:
```
nginx -t && systemctl reload nginx
curl -sI http://127.0.0.1:8092/database/index.html | head -3   # 302 to /login/?next=...
```
Undo: remove the snippet, `nginx -t && systemctl reload nginx`.

## 5. Publish the database and market (behind the sign-in)
Only after step 4 is checked, so the pages are never open to everyone.
```
python3 tools/site/build.py --private --world --site-data cache/live-export/site --market-data cache/live-export/market
grep -rl samplebar dist/site-private | wc -l                      # 0
rsync -a --delete --exclude market/ --exclude world/ dist/site-private/ root@192.168.0.164:/srv/slums-site/
rsync -a dist/site-private/market/ root@192.168.0.164:/srv/slums-site/market/    # the first time only
```
`--exclude market/ --exclude world/` because step 6 keeps those two fresh on 164.
Check: signed out, `/database/` and `/market/` redirect to `/login/`; signed in with a game username and password, they open, and
on a price page "Watch" works; a test post on the Buy/Sell board shows on the board NPC at the FM entrance.
Cards (`GET /api/market/cards`, signed in): `/market/cards.html` says "N of 15 found" with no "didn't load" bar (that
bar means the GET failed). Type `slums` on any page (not in a text box): the card pops up, and after a reload My cards
counts it. In the browser's network tab, `api/market/cards` answers 200 with `"found"` and `"total": 15`.
**From now on every publish from 187 uses `--private --world` and `--exclude market/ --exclude world/`** (DEPLOY.md
says so too).

## 6. Keep the market and World fresh (timers on 164, both every 2 min)
Live writes `Server/data/market` (FM listings every 2 min, sales and prices every 15) once `-Dslums.market.export=true` is on, and `Server/data/site`
(feed every 2 min, world every 60 s, leaderboards every 15 min) from `PublicSiteExport`, both in 1.6.0. Two timers
rebuild only their own section from them: a one-section rebuild writes beside the live folder, swaps it in with two
renames, checks the new section and swaps back if the check fails. The commands the timers run (the units add
`flock`):
```
python3 tools/site/build.py --private --world --section market --out /srv/slums-site \
    --market-data "/opt/mapleroot/MapleRoot Full Repack/Server/data/market" \
    --site-data "/opt/mapleroot/MapleRoot Full Repack/Server/data/site" --hs-data /opt/slums-site-build/hiddenstreet
python3 tools/site/build.py --private --world --section world ...   # same arguments
```
Units: `tools/site/slums-site-market.service` and `.timer` (oneshot, nice 19, idle I/O, writes only under
`/srv/slums-site`, reads the export and `/opt/slums-site-build` read-only). World works the same way:
`tools/site/slums-site-world.service` and `.timer`, every 2 minutes (`--section world`, reading `Server/data/site`;
about 1.6 s and 220 MB per run here). Both take turns through one `flock`, so neither checks links while the other
swaps its folder in. `tools/site/deploy-build-164.sh` (try
`--dry-run` first) copies `tools/site/`, `docs/site/`, `docs/wiki/` and the dataset to `/opt/slums-site-build`;
`cache/site-icons` is not needed (the market reuses the database's icons already published).
```
cp tools/site/slums-site-{market,world}.{service,timer} /etc/systemd/system/    # from /opt/slums-site-build
systemctl daemon-reload && systemctl enable --now slums-site-market.timer slums-site-world.timer
systemctl start slums-site-market.service slums-site-world.service
journalctl -u slums-site-market -u slums-site-world -n 10
```
Check: `market/listings.html` and `world/index.html` change within 2 minutes, and the "as of" times match
the exports.
Undo: `systemctl disable --now slums-site-market.timer slums-site-world.timer`.

## 7. Afterwards
- The megaphone rail's "sold" lines on non-market pages only change on a full publish (steps 1/5); the market pages
  refresh every 2 minutes (FM listings; sales and prices change every 15) and World every 2.
- Banned accounts: write their account ids to `/var/lib/slums-login/revoked.txt` to sign them out of the website.
- Rotating the key (`/etc/slums-login/key`) signs everyone out.

## Settled (owner, 2026-10-26)
- **Caddy serves `theslumsms.asslorde.com` for now.** Caddy's `reverse_proxy` passes `Host`, `X-Forwarded-For` and
  `X-Forwarded-Proto` by default, which is all the site needs: the sign-in cookies are always marked Secure (visitors
  arrive over HTTPS through Caddy), the login redirect is relative (`absolute_redirect off`), and the report form's and
  sign-in's per-address limits read `X-Forwarded-For`. Only if the owner's Caddy config strips those headers would
  anything need changing; the step 4 check (sign in with a game account, then open `/database/`) would show it.
- **Henesys green stays the default accent**, also with the orange-and-navy Slums-chan art; blue and orange stay
  player choices in the header.

## Owner decisions still open
1. Before 1.6.0 is live, should the (signed-in) market show TEST data, clearly marked, or stay off?
2. Is the whole site friends-only behind the sign-in, or only the database and market (today's plan)?
3. Maple radio (game music on the site): yes or no. It would also sit behind the sign-in.
