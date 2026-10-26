# Hosting the SlumsMS website

The site is plain static files: no database, no PHP, no Node, nothing to run. These steps put it on a fresh
Debian LXC at `theslumsms.asslorde.com`, served from `/srv/slums-site`.

## 1. Build

On the build machine, from the repo root (Python 3, standard library only):

```
python3 tools/site/build.py
```

This writes the **public** site to `dist/site/` and `dist/slums-site.tar.gz`. The Database and Market sections are
private until 1.6.0 is live: `python3 tools/site/build.py --private` builds them into `dist/site-private/` instead, and
the public build's check fails if one of them, or a link to one, ever ends up in `dist/site/`. Only ever rsync
`dist/site/` to the public folder. `--section <name> --out <dir>` rebuilds one section inside an existing build. It also checks the output and stops (and packs nothing) if a
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

**Once the market is live** (docs/site/LAUNCH.md step 5), a timer on 164 owns `/srv/slums-site/market/`. Every
publish from 187 must then keep its hands off it: `rsync -a --delete --exclude market/ ...`. Without rsync, copy the tarball instead:

```
scp dist/slums-site.tar.gz root@<lxc-address>:/tmp/
ssh root@<lxc-address> 'rm -rf /srv/slums-site/* && tar -xzf /tmp/slums-site.tar.gz -C /srv/slums-site --strip-components=1'
```

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
