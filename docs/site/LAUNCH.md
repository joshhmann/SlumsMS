# Website launch checklist (one sitting with the owner)

Everything here is built and tested on branch `site-160-theme`. **Each numbered step changes something people can
see or the site's web server, so each needs the owner's "go" for that step.** The site launches **the same night as
1.6.0** (owner, 2026-10-26): World needs the site export, the market needs the market export, and the sign-in needs
`@weblogin`, all three in 1.6.0.

**Order on the night** (the step numbers below are what each does, not the order):
1. `deploy-build-164.sh` (the copy-only part of step 6: puts `tools/site` in `/opt/slums-site-build`).
2. Step 2 (sign-in service) and **step 2b (market service, its user and `/var/lib/slums-market/{inbox,outbox}`)**.
   **Both must be done before the 1.6.0 deploy.** The 1.6.0 unit turns on `-Dslums.board=true`, and the game then
   writes `/var/lib/slums-market/outbox`. If the folder isn't there yet, the game makes it without the
   `slums-market` group and the site can't read the board.
3. The 1.6.0 deploy (release tools, the owner runs `deploy.sh`). It brings `@weblogin` (step 3), the board, and the
   market and site exports.
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
Follow `docs/site/LOGIN.md`, "Install on 164": user and group `slums-login`, `/var/lib/slums-login/approvals`, the
**owner** makes the key (`head -c 48 /dev/urandom > /etc/slums-login/key`; nobody prints or copies it) and the
address salt `/etc/slums-login/ip-salt` the same way, the unit `slums-login.service`. For password sign-in and invite
sign-up (ticket 105) the **owner** also makes the game's auth secret `/etc/slums-auth/secret` (read by the game server
and, through group `slums-login`, by the sign-in; LOGIN.md has the commands). Without it those two answer "Sign-in is
down right now" and `@weblogin` still works. Check: `curl -s http://127.0.0.1:8094/api/login/health` gives `{"ok": true}`.
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
Undo: `systemctl disable --now slums-market`, then `userdel slums-market` and `rm -r /var/lib/slums-market` if wanted
(that deletes every watchlist, alert and post).

## 3. Turn on `@weblogin` on live (part of the 1.6.0 deploy)
Live starts with `-Dslums.weblogin.dir=/var/lib/slums-login/approvals` (TEST sets its own,
`/var/lib/slums-login-test/approvals`, read only by the TEST sign-in on 8194 with its own key: see
`tools/site/test164/README.md`. A TEST code can never sign anyone in to the real site), and with the board's inbox and outbox flags set to `/var/lib/slums-market/inbox` and
`/var/lib/slums-market/outbox` (names in the "Site board contract"). Live runs as root, so no group change is needed. Check: in game, `@weblogin ABCDEF` replies
"Website sign-in sent", and a file `/var/lib/slums-login/approvals/ABCDEF.json` appears (it expires harmlessly).

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
Check: signed out, `/database/` and `/market/` redirect to `/login/`; signed in through `@weblogin`, they open, and
on a price page "Watch" works; a test post on the Buy/Sell board shows on the board NPC at the FM entrance.
**From now on every publish from 187 uses `--private --world` and `--exclude market/ --exclude world/`** (DEPLOY.md
says so too).

## 6. Keep the market (15 min) and World (2 min) fresh (timers on 164)
Live writes `Server/data/market` every 15 min once `-Dslums.market.export=true` is on, and `Server/data/site`
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
Check: `market/index.html` changes within 15 minutes and `world/index.html` within 2, and the "as of" times match
the exports.
Undo: `systemctl disable --now slums-site-market.timer slums-site-world.timer`.

## 7. Afterwards
- The megaphone rail's "sold" lines on non-market pages only change on a full publish (steps 1/5); the market pages
  refresh every 15 minutes and World every 2.
- Banned accounts: write their account ids to `/var/lib/slums-login/revoked.txt` to sign them out of the website.
- Rotating the key (`/etc/slums-login/key`) signs everyone out.

## Settled (owner, 2026-10-26)
- **Caddy serves `theslumsms.asslorde.com` for now.** Caddy's `reverse_proxy` passes `Host`, `X-Forwarded-For` and
  `X-Forwarded-Proto` by default, which is all the site needs: the sign-in cookies are always marked Secure (visitors
  arrive over HTTPS through Caddy), the login redirect is relative (`absolute_redirect off`), and the report form's and
  sign-in's per-address limits read `X-Forwarded-For`. Only if the owner's Caddy config strips those headers would
  anything need changing; the step 4 check (sign in through `@weblogin`, then open `/database/`) would show it.
- **Henesys green stays the default accent**, also with the orange-and-navy Slums-chan art; blue and orange stay
  player choices in the header.

## Owner decisions still open
1. Before 1.6.0 is live, should the (signed-in) market show TEST data, clearly marked, or stay off?
2. Is the whole site friends-only behind the sign-in, or only the database and market (today's plan)?
3. Maple radio (game music on the site): yes or no. It would also sit behind the sign-in.
