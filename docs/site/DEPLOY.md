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

In this order; each step has its check, and stop at the first one that fails. `S=<UTC stamp>` for the backups. The
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
   ```
   Check: `systemctl is-active slums-site-market.timer slums-site-world.timer` says `inactive` twice.
2. Code and build. On 187, `tools/site/deploy-build-164.sh` copies the 1.6.1 code, data and icon cache
   (`cache/site-icons`) to `/opt/slums-site-build` on 164 (it builds nothing). Then on 164, check the staff dashboard
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
3. Copy the site: `rsync -a --delete /var/tmp/slums-site-161/ /srv/slums-site/` (market/ and World too: the build has
   them fresh).
4. Services (unit files in `/opt/slums-site-build/tools/site/`). Before installing a unit, `diff` it against the
   installed one: only the lines named here may differ.
   - The Flask market app. Flask comes from Ubuntu, no pip or venv: `dpkg -l python3-flask` shows `ii ... 3.0.2`
     (installed on 164 for TEST, checked 2026-10-29); if not, `apt install python3-flask`. Install the new unit
     (`install -m 0644 tools/site/slums-market.service /etc/systemd/system/`): its ExecStart is `market_app.py` (same
     port 8095, URLs, cookie and database as `market_service.py`), and it has no `SLUMS_MARKET_EXCHANGE` line, so the
     exchange stays off.
   - `slums-login`: `install -m 0644 tools/site/login_service.py /opt/slums-login/` (a GM signed in over 12 h ago
     gets 401, so `/admin/` asks again).
   - `slums-report`: `install -m 0644 tools/site/report_service.py tools/site/report_export.py /opt/slums-report/`
     and `install -m 0644 tools/site/slums-report.service /etc/systemd/system/` (three new Environment lines: the
     server version from `/srv/slums-site/world/online.json`, the site's `build.json`, the staff port 8097).
   - `systemctl daemon-reload`, restart the three, then `curl -s 127.0.0.1:8094/api/login/health`,
     `127.0.0.1:8095/api/market/health` and `127.0.0.1:8093/api/report/health` all give `{"ok": true}`;
     `journalctl -u slums-market -n 3` says `market service (Flask) ... exchange off`; `ss -ltn | grep 8097` shows the
     staff reader. Signed in, a watch added on a database page shows on `/market/mine` (the watchlist survived).
5. nginx: add part 3 of `tools/site/slums-login.nginx.conf` (clean URLs, `/admin/`, `/admin/data/`,
   `/api/report/staff/`, `@signagain`) and the `try_files` line in `/database/` and `/market/`; `nginx -t && systemctl
   reload nginx`. The download-pass part (`slums-pass.nginx.conf`, ticket 106, `satisfy any`) belongs to the patch
   host's server block and is its own step in the launcher release.
6. Start the rebuilds again: `systemctl start slums-site-market.service slums-site-world.service` (one run each, now),
   then `systemctl start slums-site-market.timer slums-site-world.timer` (every 2 minutes). Checks:
   `systemctl is-failed slums-site-market.service slums-site-world.service` says `inactive` twice (not `failed`);
   `grep -c data-xc-nav /srv/slums-site/market/board.html` gives 1; `grep -o '"version": "[^"]*"'
   /srv/slums-site/world/online.json` shows the server version.
7. Checks through nginx (`B=http://127.0.0.1:8092`):
   - `curl -sI $B/market/exchange.html` gives 301 to `/market/exchange`; `curl -sI $B/guide/` gives 200.
   - signed out: `curl -sI $B/admin/` gives 302 to `/login/?again=1&next=/admin/`; `curl -s -o /dev/null -w
     '%{http_code}' $B/api/report/staff/list` gives 404.
   - signed in as a player: `/admin/` gives 404; `/api/market/exchange/book` gives `"open": false`; the page
     `/market/exchange` shows only "The Slums Exchange isn't open yet", with Slums-chan's neutral lines (no "LEAF to
     the moon!"); the Market menu on any market page has no Slums Exchange link.
   - signed in as a GM 3+ (the owner): `/admin/`, `/admin/invites` and `/admin/reports` open.
   - a database page shows its icons; a test report sent from `/report/` shows server 1.6.1 in the staff Reports tab.

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

## 4. Add a release's patch notes

1. Write `docs/site/releases/<version>.md` (copy the last one for the format: front matter with `version`,
   `title`, `date` and `summary`, then `## Sections` with `- [New]`, `[Fix]`, `[Change]` or `[Same]` bullets).
   Player-facing notes only: no QA lists, internal notes, hashes or test-server details.
2. Run the build. The newest version becomes the home page's "latest patch" card and tops the patch notes list.
3. Publish (step 3).

The player guide is copied from the generated wiki in `docs/wiki/`. Refresh that first (`python3 docs/wiki/build.py`)
when its sources change. Public-only text changes for it live in `docs/site/guide-fixes.json` and
`docs/site/guide-overrides/`.

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
