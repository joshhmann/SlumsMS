# Website launch checklist (one sitting with the owner)

Everything here is built and tested on branch `site-160-theme`. **Each numbered step changes something people can
see or the site's web server, so each needs the owner's "go" for that step.** Steps 1 and 2 can happen any time;
3 to 7 need 1.6.0 (the `@weblogin` command and the market export) on live.

Where things are today (read on 2026-10-26):
- 164 serves the site with nginx, `server { listen 8092; root /srv/slums-site; }` in
  `/etc/nginx/sites-available/slums-site`, plus `/api/report` to `slums-report` on 127.0.0.1:8093.
- HTTPS for `theslumsms.asslorde.com` ends on a proxy in front of 164 (not on 164 itself). **Confirm which machine
  that is** before step 4; the sign-in cookies need the visitor to arrive over HTTPS.
- Live shows the old look from branch `site` (`788cb52ad`), with no robots rules, and it says MapleStory.

## Before the sitting (agent, no go needed)
```
git -C /root/work/SlumsMS-site pull
python3 -m unittest discover -s tools/site -p 'test_*.py'      # all green
python3 tools/site/build.py                                    # public: dist/site
python3 tools/site/build.py --private                          # with database + market: dist/site-private
```
Show the owner: home, a patch note, a guide page, report, 404 (light and dark, phone), the database and the market.

## 1. Publish the new public site
What it changes: the new look and brand art, the "Slums Story" wording, search engines turned away (robots.txt plus
noindex on every page), and the `/login/` page. The database and market are not in it.
```
ssh root@192.168.0.164 'cp -a /srv/slums-site /srv/slums-site.bak-$(date -u +%Y%m%dT%H%M%SZ)'
rsync -a --delete dist/site/ root@192.168.0.164:/srv/slums-site/
```
Check:
```
curl -s https://theslumsms.asslorde.com/robots.txt               # User-agent: * / Disallow: /
curl -s https://theslumsms.asslorde.com/ | grep -c 'noindex'     # 1
curl -s https://theslumsms.asslorde.com/ | grep -ci 'maplestory' # 0
```
Undo: `rsync -a --delete /srv/slums-site.bak-<stamp>/ /srv/slums-site/` on 164.

## 2. Install the sign-in service (not reachable from outside yet)
Follow `docs/site/LOGIN.md`, "Install on 164": user and group `slums-login`, `/var/lib/slums-login/approvals`, the
**owner** makes the key (`head -c 48 /dev/urandom > /etc/slums-login/key`; nobody prints or copies it), the unit
`slums-login.service`. Check: `curl -s http://127.0.0.1:8094/api/login/health` gives `{"ok": true}`.
Undo: `systemctl disable --now slums-login`.

## 3. Turn on `@weblogin` on live (part of the 1.6.0 deploy)
Live starts with `-Dslums.weblogin.dir=/var/lib/slums-login/approvals` (TEST never sets it, so TEST accounts can
never sign anyone in). The game server's user joins group `slums-login`. Check: in game, `@weblogin ABCDEF` replies
"Website sign-in sent", and a file `/var/lib/slums-login/approvals/ABCDEF.json` appears (it expires harmlessly).

## 4. Guard the database and market in nginx
Add `tools/site/slums-login.nginx.conf` inside the `listen 8092` server block. Then:
```
nginx -t && systemctl reload nginx
curl -sI http://127.0.0.1:8092/database/index.html | head -3   # 302 to /login/?next=...
```
Undo: remove the snippet, `nginx -t && systemctl reload nginx`.

## 5. Publish the database and market (behind the sign-in)
Only after step 4 is checked, so the pages are never open to everyone.
```
python3 tools/site/build.py --private --market-data <copy of live's data/market>
rsync -a --delete --exclude market/ dist/site-private/ root@192.168.0.164:/srv/slums-site/
```
`--exclude market/` because step 6 keeps `market/` fresh on 164. The first time, copy `market/` once too
(`rsync -a dist/site-private/market/ root@192.168.0.164:/srv/slums-site/market/`).
Check: signed out, `/database/` and `/market/` redirect to `/login/`; signed in through `@weblogin`, they open.
**From now on every publish from 187 uses `--exclude market/`** (DEPLOY.md says so too).

## 6. Keep the market fresh every 15 minutes (timer on 164)
Live writes `Server/data/market` every 15 min once `-Dslums.market.export=true` is on (1.6.0). A timer rebuilds only
the market pages from it. **Still to build before launch:** a small deploy of `tools/site` and the dataset to 164
(`/opt/slums-site-build`), a `slums-site-market.timer` (every 15 min, `nice -n 19`), and a build into a staging folder
then a rename, so visitors never see a half-written market. The command it runs:
```
python3 tools/site/build.py --private --section market --out /srv/slums-site \
    --market-data "/opt/mapleroot/MapleRoot Full Repack/Server/data/market" --hs-data /opt/slums-site-build/hiddenstreet
```
Check: `market/index.html` changes within 15 minutes, and the "as of" time matches the export.

## 7. Afterwards
- The megaphone rail's "sold" lines on non-market pages only change on a full publish (steps 1/5); the market pages
  themselves refresh every 15 minutes.
- Banned accounts: write their account ids to `/var/lib/slums-login/revoked.txt` to sign them out of the website.
- Rotating the key (`/etc/slums-login/key`) signs everyone out.

## Owner decisions still open
1. Before 1.6.0 is live, should the (signed-in) market show TEST data, clearly marked, or stay off?
2. Is the whole site friends-only behind the sign-in, or only the database and market (today's plan)?
3. Maple radio (game music on the site): yes or no. It would also sit behind the sign-in.
