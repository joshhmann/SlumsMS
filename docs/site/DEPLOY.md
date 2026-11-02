# Hosting the SlumsMS website

The site is plain static files: no database, no PHP, no Node, nothing to run. These steps put it on a fresh
Debian LXC at `theslumsms.asslorde.com`, served from `/srv/slums-site`.

## 1. Build

On the build machine, from the repo root (Python 3, standard library only):

```
python3 tools/site/build.py
```

This writes the **public** site to `dist/site/` and `dist/slums-site.tar.gz`. `--world` adds World (live world, feed,
rankings, economy; public) from the server's site export, `--site-data` (samples from `tools/site/site_sample.py` by
default). Slums Street (the database) and the Slums Market are for signed-in players: `--private` builds them into
`dist/site-private/`, which only goes to 164 once nginx guards `/database/` and `/market/` (LAUNCH.md step 4), and
the public build's check fails if one of them, or a link to one, ever ends up in `dist/site/`. `--section <name> --out <dir>` rebuilds one section inside an existing build. It also checks the output and stops (and packs nothing) if a
page has a broken link, loads anything from another website, or contains something private (LAN addresses,
test-server ports, the patch site's name, QA tooling; the list is in `tools/site/check.py`).

Tests: `python3 -m unittest discover -s tools/site -p 'test_*.py'`. Preview: `python3 -m http.server -d dist/site 8000`.

## 2. Prepare the LXC (once)

- DNS: point an A (and AAAA, if the LXC has IPv6) record for `theslumsms.asslorde.com` at the address the
  internet reaches the LXC on. Forward ports 80 and 443 to it if it sits behind NAT. Port 80 must be reachable for
  the HTTPS certificate.
- Make the folder and let your deploy user write to it:

```
apt update && apt install -y rsync
mkdir -p /srv/slums-site
```

Then pick **one** web server.

### Option A: Caddy (recommended: automatic HTTPS)

```
apt install -y caddy
```

Put this in `/etc/caddy/Caddyfile` (replacing the example site):

```
theslumsms.asslorde.com {
	root * /srv/slums-site
	file_server
	encode gzip
	handle_errors {
		@404 expression {err.status_code} == 404
		rewrite @404 /404.html
		file_server
	}
	header {
		X-Content-Type-Options nosniff
		Referrer-Policy no-referrer
		X-Frame-Options DENY
		-Server
	}
	@assets path /assets/*
	header @assets Cache-Control "public, max-age=86400"
}
```

```
systemctl reload caddy
```

Caddy gets and renews the Let's Encrypt certificate by itself and redirects http to https.

### Option B: nginx

```
apt install -y nginx certbot python3-certbot-nginx
```

`/etc/nginx/sites-available/slums-site`:

```
server {
    listen 80;
    listen [::]:80;
    server_name theslumsms.asslorde.com;
    root /srv/slums-site;
    index index.html;
    error_page 404 /404.html;
    location / { try_files $uri $uri/ =404; }
    location /assets/ { add_header Cache-Control "public, max-age=86400"; }
    add_header X-Content-Type-Options nosniff;
    add_header Referrer-Policy no-referrer;
    add_header X-Frame-Options DENY;
    server_tokens off;
    gzip on;
    gzip_types text/css image/svg+xml;
}
```

```
ln -s /etc/nginx/sites-available/slums-site /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
certbot --nginx -d theslumsms.asslorde.com    # adds HTTPS and the http->https redirect, renews by timer
```

## 3. Publish an update

Build, then copy the output over. `--delete` removes pages that no longer exist; the trailing slashes matter.

```
python3 tools/site/build.py
rsync -av --delete dist/site/ root@<lxc-address>:/srv/slums-site/
```

No reload is needed: the web server serves the new files right away.

**Once the market and World are live** (docs/site/LAUNCH.md steps 5 and 6), timers on 164 own
`/srv/slums-site/market/` and `/srv/slums-site/world/`. Every publish from 187 must then be a `--private --world` build
and keep its hands off both: `rsync -a --delete --exclude market/ --exclude world/ ...`. Before that copy, check the
build has the icons (`ls <build>/database/icons/items | wc -l` is in the thousands, not 0): a build made without
`cache/site-icons` has empty icon slots, and the `--delete` removes the live icons (it happened on 2026-10-28 at
21:50 UTC; restored from the backup at 02:53 UTC). Without rsync, copy the tarball instead:

```
scp dist/slums-site.tar.gz root@<lxc-address>:/tmp/
ssh root@<lxc-address> 'rm -rf /srv/slums-site/* && tar -xzf /tmp/slums-site.tar.gz -C /srv/slums-site --strip-components=1'
```

## 3b. Release night for the 1.6.2 site (owner's go for each step)

The 1.6.1 site work ships with 1.6.2 (owner, 2026-10-30: no separate 1.6.1); "1.6.1" below means that work.

Do this after the 1.6.2 game is up on live (its deploy turns the game's exchange on): the website's exchange asks the
game for every order, and its password step goes to the game's sign-in API. In this order; each step has its check,
and stop at the first one that fails. `S=<UTC stamp>` for the backups. The
market and World rebuild timers, and the market service, run the code in `/opt/slums-site-build`, so the timers stay
stopped from step 1 until nginx knows the 1.6.1 URLs (step 6). World shows numbers a few minutes old meanwhile.

1. Stop the rebuilds and back up everything the night changes:
   ```
   systemctl stop slums-site-market.timer slums-site-world.timer
   cp -a /srv/slums-site /srv/slums-site.bak-$S
   cp -a /opt/slums-site-build /opt/slums-site-build.pre-161
   cp -p /opt/slums-login/login_service.py{,.pre-161}
   for f in report_service.py report_export.py; do cp -p /opt/slums-report/$f{,.pre-161}; done
   for u in slums-market slums-login slums-report; do cp -p /etc/systemd/system/$u.service{,.pre-161}; done
   cp -p /etc/nginx/sites-available/slums-site{,.pre-161}
   [ -e /etc/nginx/conf.d/slums-report.conf ] && cp -p /etc/nginx/conf.d/slums-report.conf /root/slums-report.conf.pre-161
   ```
   The report form's file is backed up **outside** `conf.d`: a copy left in there (`slums-report.conf.pre-161`) is
   loaded as a second config file and `nginx -t` stops on the duplicate `map`. `/root` is the place.
   Check: `systemctl is-active slums-site-market.timer slums-site-world.timer` says `inactive` twice.
2. Code and build. On 187, `tools/site/deploy-build-164.sh` copies the 1.6.1 code, data and icon cache
   (`cache/site-icons`) to `/opt/slums-site-build` on 164 (it builds nothing). "Data" includes
   `data/site/extra-icon-items.txt`, the market items with no Slums Street page (`sections/dbdata.py`
   `EXTRA_ICON_ITEMS`); release night shipped without it and three icons had to be copied by hand. Then on 164, check the staff dashboard
   is there (`ls /srv/slums-dashboard/web/index.html`; admin/ is built from the installed dashboard, like TEST's
   `/srv/slums-dashboard-test/web`) and build into a new folder:
   ```
   cd /opt/slums-site-build && python3 tools/site/build.py --private --world --no-tar --out /var/tmp/slums-site-161 \
     --market-data "/opt/mapleroot/MapleRoot Full Repack/Server/data/market" \
     --site-data "/opt/mapleroot/MapleRoot Full Repack/Server/data/site" \
     --hs-data /opt/slums-site-build/hiddenstreet --dashboard-web /srv/slums-dashboard/web
   ```
   It ends with "check passed". Compare before the copy: `ls /var/tmp/slums-site-161/database/icons/items | wc -l`
   matches `ls /srv/slums-site/database/icons/items | wc -l` (about 4,971), and the same for `mobs` and `npcs`.
3. Swap the site in with two renames (never an rsync or `rm -rf` into the live folder, audit W10): visitors see the
   old site or the new one, never half of each.
   ```
   cp -a /var/tmp/slums-site-161 /srv/slums-site.new-$S
   mv /srv/slums-site /srv/slums-site.old-$S && mv /srv/slums-site.new-$S /srv/slums-site
   ```
   Check: `ls /srv/slums-site/index.html`. Keep `/srv/slums-site.old-$S` until step 7 passes (step 1's backup too).
   Later full builds do the same on their own: `build.py` builds beside `--out` and swaps in only after the check.
4. Services (unit files in `/opt/slums-site-build/tools/site/`). Before installing a unit, `diff` it against the
   installed one: only the lines named here may differ.
   - The Flask market app. Flask comes from Ubuntu, no pip or venv: `dpkg -l python3-flask` shows `ii ... 3.0.2`
     (installed on 164 for TEST, checked 2026-10-29); if not, `apt install python3-flask`. Install the new unit
     (`install -m 0644 tools/site/slums-market.service /etc/systemd/system/`): its ExecStart is `market_app.py` (same
     port 8095, URLs, cookie and database as `market_service.py`), and `SLUMS_MARKET_EXCHANGE=on`: the Slums Exchange
     opens (owner, 2026-10-30). The diff against the installed unit shows those two lines and the comment.
   - The NX desk (1.6.3, ticket 162) is `SLUMS_MARKET_NX_DESK=off|mock|on`, off unless the unit sets it: `on` makes the
     exchange's NX tab a quote panel read from `nx-desk.json` in the same outbox (`API.md`), `mock` the same panel off
     the fixtures in `tools/site/testdata/nx-desk` with no game. A build with `--nx mock|on` bakes the mode into the
     static page; the service needs the matching setting. Set it on TEST before the game writes the file.
   - `slums-login`: `install -m 0644 tools/site/login_service.py /opt/slums-login/` (a GM signed in over 12 h ago
     gets 401, so `/admin/` asks again).
   - `slums-report`: `install -m 0644 tools/site/report_service.py tools/site/report_export.py /opt/slums-report/`
     and `install -m 0644 tools/site/slums-report.service /etc/systemd/system/` (three new Environment lines: the
     server version from `/srv/slums-site/world/online.json`, the site's `build.json`, the staff port 8097).
   - `systemctl daemon-reload`, restart the three, then `curl -s 127.0.0.1:8094/api/login/health`,
     `127.0.0.1:8095/api/market/health` and `127.0.0.1:8093/api/report/health` all give `{"ok": true}`;
     `journalctl -u slums-market -n 3` says `market service (Flask) ... exchange on`; `ss -ltn | grep 8097` shows the
     staff reader. Signed in, a watch added on a database page shows on `/market/mine` (the watchlist survived).
5. nginx, in three places (each is a paste of a numbered part of the file named):
   - `tools/site/slums-login.nginx.conf` part 3 into the site's `server { }`: clean URLs, `/admin/`, `/admin/data/`,
     `/api/report/staff/`, `@signagain`, and `absolute_redirect off;` at the server level (part 3a). Without that line
     every clean-URL redirect (and `try_files`' own slash redirect) answers `http://<host>:8092/...`, the internal
     port; it goes in the server block, not in a location. Add the `try_files` line to `/database/` and `/market/` too.
   - `tools/site/slums-report.nginx.conf` part 1 (the `map` that keys the report limit on the right-most
     `X-Forwarded-For` entry, audit W11) into `/etc/nginx/conf.d/slums-report.conf`: the visitor, not the front proxy.
     That file also needs the server-level `absolute_redirect off;` if part 3a was not pasted into this block for some
     reason; one line covers every location in the block.
   Then `nginx -t && systemctl reload nginx`. The download-pass part (`slums-pass.nginx.conf`, ticket 106, `satisfy any`)
   belongs to the patch host's server block and is its own step in the launcher release.
6. Start the rebuilds again: `systemctl start slums-site-market.service slums-site-world.service` (one run each, now),
   then `systemctl start slums-site-market.timer slums-site-world.timer` (every 2 minutes). Checks:
   `systemctl is-failed slums-site-market.service slums-site-world.service` says `inactive` twice (not `failed`);
   `grep -c data-xc-nav /srv/slums-site/market/board.html` gives 1; `grep -o '"version": "[^"]*"'
   /srv/slums-site/world/online.json` shows the server version.
7. Checks through nginx (`B=http://127.0.0.1:8092`):
   - `curl -sI $B/market/exchange.html` gives 301 to `/market/exchange`; `curl -sI $B/guide/` gives 200.
   - signed out: `curl -sI $B/admin/` gives 302 to `/login/?again=1&next=/admin/`; `curl -s -o /dev/null -w
     '%{http_code}' $B/api/report/staff/list` gives 404.
   - signed in as a player: `/admin/` gives 404; `/api/market/exchange/book` gives `"open": true`; the Market menu
     on any market page shows the Slums Exchange link; `/market/exchange` shows the five tickers and the order form.
     A buy preview of 100 NX answers within about 30 s (the game reads the inbox every few seconds; the first pass
     comes about 30 s after the game starts). A sell preview asks for the password (b005); "Never mind" closes it.
     Nothing is confirmed on live for the check.
   - signed in as a GM 3+ (the owner): `/admin/`, `/admin/invites` and `/admin/reports` open.
   - a database page shows its icons; a test report sent from `/report/` shows server 1.6.2 in the staff Reports tab.
8. Staff Bots page (ticket 157), only with the owner's go for bot inspect on live (the game's 1.6.2 deploy starts it
   with `-Dslums.inspect=true -Dslums.inspect.port=8098 -Dslums.inspect.secretFile=/etc/slums-inspect/secret`; that
   file, made once by the owner, group `slums-login`, mode 0640). In `/etc/systemd/system/slums-login.service` add
   (or uncomment) the two `SLUMS_LOGIN_INSPECT_*` lines; the live unit had neither, only the repo's copy has them
   commented out. Then add `-/etc/slums-inspect` to `ReadOnlyPaths`, and
   `systemctl daemon-reload && systemctl restart slums-login`. Checks: `journalctl -u slums-login -n 2` ends
   `bot inspect http://127.0.0.1:8098`; signed in as GM 3+, `/admin/bots` lists the busiest maps and opens a bot.
   Without the go, skip it: the Bots page then says bot inspect is off, and nothing else changes.

### Website rollback (all of it, or the market alone)

Not rehearsed on a copy; every step is a restore of a step-1 backup. In this order:
```
systemctl stop slums-site-market.timer slums-site-world.timer
mv /opt/slums-site-build /opt/slums-site-build.161-failed && cp -a /opt/slums-site-build.pre-161 /opt/slums-site-build
mv /srv/slums-site /srv/slums-site.161-failed && cp -a /srv/slums-site.bak-$S /srv/slums-site
cp -p /opt/slums-login/login_service.py.pre-161 /opt/slums-login/login_service.py
for f in report_service.py report_export.py; do cp -p /opt/slums-report/$f.pre-161 /opt/slums-report/$f; done
for u in slums-market slums-login slums-report; do cp -p /etc/systemd/system/$u.service.pre-161 /etc/systemd/system/$u.service; done
systemctl daemon-reload && systemctl restart slums-login slums-report slums-market
cp -p /root/slums-report.conf.pre-161 /etc/nginx/conf.d/slums-report.conf && nginx -t
cp -p /etc/nginx/sites-available/slums-site.pre-161 /etc/nginx/sites-available/slums-site && nginx -t && systemctl reload nginx
systemctl start slums-site-market.timer slums-site-world.timer
```
Checks: the three /health URLs give `{"ok": true}`; `/market/exchange.html` gives 200 again (the 1.6.0 page); after
2 minutes `systemctl is-failed slums-site-market.service` says `inactive` and `grep -c data-xc-nav
/srv/slums-site/market/board.html` gives 0 (the 1.6.0 code rebuilt it). Sign-ins survive (same key and cookie);
reports sent in between stay in the reports file. Keep the `.161-failed` folders until the cause is known.

The market alone: `cp -p /etc/systemd/system/slums-market.service.pre-161 /etc/systemd/system/slums-market.service`,
`systemctl daemon-reload && systemctl restart slums-market`. That runs market_service.py on the same database
(watchlists, alerts, posts and cards are kept), from the 1.6.1 code in `/opt/slums-site-build`, which still has it.
The exchange page then shows an error instead of "not open yet".

### Release night 2026-11-01: what went differently

Not a step; what this section now says because the night itself went this way. (Dates are UTC.)
- **`conf.d/slums-report.conf` was edited on live but had no backup.** The copy was made inside `conf.d`, where a second
  file is loaded as config. Step 1 now backs it up to `/root` and the rollback restores it.
- **`absolute_redirect off;` was added by hand.** Every clean-URL redirect had been answering with `http://<host>:8092/`;
  it is now part of part 3a, at the server level.
- **Three market icons were missing** until `data/site/extra-icon-items.txt` was copied to `/opt/slums-site-build` by
  hand: `deploy-build-164.sh` skipped `data/site`. Both copy scripts carry it now and stop if it is missing.
- **The `SLUMS_LOGIN_INSPECT_*` lines were not in the live unit**, not even commented, so step 8 says "add (or
  uncomment)".
- **The guide pages were a release behind** the patch notes; section 4 makes refreshing them a step of every release.

## 3c. Release night for the 1.6.3 site (owner's go for each step)

Smaller than 1.6.2's: since live's 1.6.2 build only the market service changed (`exchange_desk.py`, `market_app.py`:
the NX desk's quote panel and its URLs), plus the guide, the patch notes and the PELIXIR label. The login and report
services, their units and nginx are unchanged (the `absolute_redirect off;` line is on live since 2026-11-01).
**Do this after the 1.6.3 game is up on live with the desk on** (`-Dslums.exchange.desk=true`, a house account): the
game then writes `nx-desk.json` to the market outbox (`/var/lib/slums-market/outbox`). `S=<UTC stamp>`.

1. Backups, rebuilds stopped (about 1 min):
   ```
   systemctl stop slums-site-market.timer slums-site-world.timer
   cp -a /srv/slums-site /srv/slums-site.bak-$S
   mv /opt/slums-site-build /opt/slums-site-build.pre-163 && cp -a /opt/slums-site-build.pre-163 /opt/slums-site-build
   for u in slums-market slums-site-market; do cp -p /etc/systemd/system/$u.service /root/$u.service.pre-163; done
   ```
   Check: both timers `inactive`; `ls /opt/slums-site-build.pre-163/DEPLOYED_COMMIT`.
2. Code and build (about 5 min). On 187: `tools/site/deploy-build-164.sh` (it now also copies `data/site/`, the item
   icon list). On 164, build into a new folder with the desk on:
   ```
   cd /opt/slums-site-build && SLUMS_MARKET_NX_DESK=on python3 tools/site/build.py --private --world --no-tar \
     --out /var/tmp/slums-site-163 \
     --market-data "/opt/mapleroot/MapleRoot Full Repack/Server/data/market" \
     --site-data "/opt/mapleroot/MapleRoot Full Repack/Server/data/site" \
     --hs-data /opt/slums-site-build/hiddenstreet --dashboard-web /srv/slums-dashboard/web
   ```
   Checks: it ends "check passed"; `grep -c 'data-nxdesk="on"' /var/tmp/slums-site-163/market/exchange.html` is 1; the
   icon counts of `database/icons/{items,mobs,npcs}` match `/srv/slums-site` (4,971 / 1,627 / 3,415 on 2026-11-01).
3. Swap the site in with two renames (seconds):
   ```
   cp -a /var/tmp/slums-site-163 /srv/slums-site.new-$S
   mv /srv/slums-site /srv/slums-site.old-$S && mv /srv/slums-site.new-$S /srv/slums-site
   ```
   Check: `grep -c "1.6.3" /srv/slums-site/index.html` is 1 or more.
4. The desk mode in both units, the market service restarted (about 1 min). Diff first: only the NX desk lines differ.
   ```
   cd /opt/slums-site-build
   for u in slums-market slums-site-market; do diff /etc/systemd/system/$u.service tools/site/$u.service; done
   install -m 0644 tools/site/slums-market.service tools/site/slums-site-market.service /etc/systemd/system/
   systemctl daemon-reload && systemctl restart slums-market
   ```
   Checks: `curl -s 127.0.0.1:8095/api/market/health` is `{"ok":true}`; `journalctl -u slums-market -n 3` says
   `exchange on`.
5. Rebuilds on again: `systemctl start slums-site-market.service` (one run now), then
   `systemctl start slums-site-market.timer slums-site-world.timer`. Check: the run is `inactive` (not failed) and
   `grep -c 'data-nxdesk="on"' /srv/slums-site/market/exchange.html` is still 1 after it (the rebuild kept the mode).
6. Checks through nginx (about 5 min, `B=http://127.0.0.1:8092`):
   - signed out: `curl -s -o /dev/null -w '%{http_code}' $B/api/market/exchange/nx/quote` is 401;
     `curl -sI $B/patch-notes/1.6.3/` is 200; `curl -sI $B/guide/money` is 200.
   - signed in as a player (the owner, in a browser): Slums Market → Slums Exchange opens on NX with "Buy NX at" and
     "Sell NX at", the tab strip shows PELIXIR, the help is folded; a **preview** of 100 NX shows a held price and a
     countdown (and for a sale, the password box). The owner decides whether a real live trade is made; nothing is
     confirmed for the check otherwise. LEAF/CHAOS/WS/PELIXIR still show their order books.
   - `/guide/money` has the NX desk section; `/patch-notes/1.6.3/` is the newest on the home page.
   Total about 15 minutes.

### 1.6.3 website rollback
- **The desk panel alone** (about 2 min; the site stays 1.6.3): set `SLUMS_MARKET_NX_DESK=off` in both units (or put
  back `/root/slums-market.service.pre-163` and `/root/slums-site-market.service.pre-163`), `systemctl daemon-reload &&
  systemctl restart slums-market && systemctl start slums-site-market.service`. The NX tab is 1.6.2's order book again
  after that rebuild. Use this if the game's desk is off or misbehaves.
- **The whole site** (about 3 min): stop both timers; `mv /srv/slums-site /srv/slums-site.163-failed-$S && cp -a
  /srv/slums-site.bak-$S /srv/slums-site`; `mv /opt/slums-site-build /opt/slums-site-build.163-failed && mv
  /opt/slums-site-build.pre-163 /opt/slums-site-build`; put back both units from `/root/*.pre-163`; daemon-reload,
  restart slums-market, start the timers. Checks: market health ok; the home page's latest patch is 1.6.2.

## 4. Add a release's patch notes

1. Write `docs/site/releases/<version>.md` (copy the last one for the format: front matter with `version`,
   `title`, `date` and `summary`, then `## Sections` with `- [New]`, `[Fix]`, `[Change]` or `[Same]` bullets).
   Player-facing notes only: no QA lists, internal notes, hashes or test-server details.
2. Run the build. The newest version becomes the home page's "latest patch" card and tops the patch notes list.
3. Publish (step 3).
4. **Refresh the player guide in the same release** (the owner's rule, 2026-11-01: a release is not done while the
   guide still describes the one before it).
   - Bring the generated pages' data over from the release branch (the exports under `docs/wiki/data/` that the game
     fills; a release branch that ran ahead has newer ones than the copy here).
   - Add the release's new features to the pages that describe them, in `docs/wiki/src/*.html`.
   - Add a changelog row for this version to `docs/wiki/src/changelog.html`, the same version as
     `docs/site/releases/<version>.md`.
   - Then `python3 docs/wiki/build.py`. Check the row is in `docs/wiki/changelog.html`: the site's guide is that build,
     and the release's version appears there or the guide is a release behind. `tools/site/test_deploy.py` checks it.
   - Wrong in-game text: the guide says what the game says. Fix the page, or put public-only wording in
     `docs/site/guide-fixes.json` / `docs/site/guide-overrides/`, and say so in the release note rather than leaving a
     number the game does not show.

The player guide is copied into the site from the generated wiki in `docs/wiki/`; `docs/site/guide-fixes.json` and
`docs/site/guide-overrides/` hold public-only text changes for it.

## 5. The theme

Style A "Megaphone", picked by the owner on 2026-10-26.
- `assets/tokens.css` holds every colour, font and density, once. There are three theme states: the system setting
  (`prefers-color-scheme`) by default, or `data-theme="light"` / `"dark"` from the header switch.
- `assets/site.css` uses only those variables. The "read" density is for home, news, guides and the report form; the
  "data" density (`.data`, `table.t`) is for the database and market pages.
- Accent: **Henesys green** by default (owner, 2026-10-26). Players can pick Ultramarine or Maple orange with the
  three swatches in the header (`data-accent="blue"` / `"orange"`); each accent is designed for light and dark. The
  header leaf is inline SVG and takes the accent; the favicon `assets/leaf.svg` is green.
- `assets/theme.js` runs both switches and remembers the choices in the browser. Without JavaScript they stay
  hidden: the system setting decides light or dark, and the accent is green.
- Fonts are self-hosted in `assets/fonts/` (Bricolage Grotesque, Instrument Sans, JetBrains Mono; Latin subset,
  variable weight, from Fontsource 5.3.0) under the OFL, whose text is in `assets/fonts/OFL.txt`. The site still loads
  nothing from other websites.
- The megaphone rail under the header lists the three newest releases plus `RAIL_EXTRA` in `tools/site/build.py`.
