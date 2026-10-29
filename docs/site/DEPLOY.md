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

## 3b. Release night for the 1.6.1 site (owner's go for each step)

In this order; each step has its check, and stop at the first one that fails.

1. Back up: `cp -a /srv/slums-site /srv/slums-site.bak-<UTC stamp>` and the nginx site file
   (`cp -p /etc/nginx/sites-available/slums-site{,.pre-161}`).
2. Build on 164 with `tools/site/deploy-build-164.sh` (it now copies `cache/site-icons`), then compare before the
   copy: `ls <build>/database/icons/items | wc -l` matches `ls /srv/slums-site/database/icons/items | wc -l` (about
   4,971), and the same for `mobs` and `npcs`.
3. Copy the site: `rsync -a --delete --exclude market/ --exclude world/ <build>/ /srv/slums-site/`.
4. Services (unit files in `tools/site/`):
   - The Flask market app. Flask comes from Ubuntu, no pip or venv: `dpkg -l python3-flask` shows `ii ... 3.0.2`
     (installed on 164 for TEST, checked 2026-10-29); if not, `apt install python3-flask`. Then `cp -p
     /etc/systemd/system/slums-market.service{,.pre-161}` and install the new unit: its ExecStart is
     `market_app.py` (same port 8095, URLs, cookie and database as `market_service.py`), and it has no
     `SLUMS_MARKET_EXCHANGE` line, so the exchange stays off.
   - `slums-login`: the new login_service.py (a GM signed in over 12 h ago gets 401, so `/admin/` asks again).
   - `slums-report`: copy the new report_service.py to `/opt/slums-report/`, plus the two new Environment lines.
   - `systemctl daemon-reload`, restart the three, then `curl -s 127.0.0.1:8094/api/login/health`,
     `127.0.0.1:8095/api/market/health` and `127.0.0.1:8093/api/report/health` all give `{"ok": true}`;
     `journalctl -u slums-market -n 3` says `market service (Flask) ... exchange off`; `ss -ltn | grep 8097` shows the
     staff reader. Signed in, a watch added on a database page shows on `/market/mine` (the watchlist survived).
   - Roll back the market alone: `mv /etc/systemd/system/slums-market.service{.pre-161,}`, `systemctl
     daemon-reload && systemctl restart slums-market` (market_service.py on the same database; watchlists, alerts,
     posts and cards are kept). The exchange page then shows an error instead of "not open yet".
5. nginx: add part 3 of `tools/site/slums-login.nginx.conf` (clean URLs, `/admin/`, `/admin/data/`,
   `/api/report/staff/`, `@signagain`) and the `try_files` line in `/database/` and `/market/`; `nginx -t && systemctl
   reload nginx`. The download-pass part (`slums-pass.nginx.conf`, ticket 106, `satisfy any`) belongs to the patch
   host's server block and is its own step in the launcher release.
6. Checks through nginx (`B=http://127.0.0.1:8092`):
   - `curl -sI $B/market/exchange.html` gives 301 to `/market/exchange`; `curl -sI $B/guide/` gives 200.
   - signed out: `curl -sI $B/admin/` gives 302 to `/login/?again=1&next=/admin/`; `curl -s -o /dev/null -w
     '%{http_code}' $B/api/report/staff/list` gives 404.
   - signed in as a player: `/admin/` gives 404; `/api/market/exchange/book` gives `"open": false`; the page
     `/market/exchange` shows only "The Slums Exchange isn't open yet".
   - signed in as a GM 3+ (the owner): `/admin/`, `/admin/invites` and `/admin/reports` open.
   - a database page shows its icons.
Undo: the backups from step 1, the old ExecStart (market_service.py) and `systemctl reload nginx`.

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
