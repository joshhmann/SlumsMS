# Working on the website from your own computer

The whole site builds from this repo with Python 3 (standard library only). Nothing here talks to the game servers,
so it's safe to play with: you see your changes at http://127.0.0.1:8780/ and nothing goes live until it's merged and
published (DEPLOY.md, LAUNCH.md).

## Once

1. Install **Git**, **Python 3.12** (on Windows tick "Add python.exe to PATH") and **Claude Code**.
2. Get the repo and the site branch, then make your own branch so nobody's work gets overwritten:
   ```
   git clone https://github.com/joshhmann/SlumsMS.git
   cd SlumsMS
   git checkout site-160-theme
   git checkout -b owner-tweaks
   ```
3. Make the sample World and market data (the real ones come from the game server):
   ```
   python tools/site/site_sample.py
   python tools/site/market_sample.py
   ```
4. Optional, the item icons for Slums Street (63 MB, not in git; without them item pages show empty icon slots):
   copy `/root/work/SlumsMS-site/cache/site-icons` from 187 into `cache/site-icons` here, e.g.
   `scp -r root@192.168.0.187:/root/work/SlumsMS-site/cache/site-icons cache/`.

## Every time

```
python tools/site/build.py --private --world --out dist/preview --no-tar
python tools/site/preview.py --dir dist/preview
```
Open http://127.0.0.1:8780/. Sign in with any username and the password `preview1`; sign up with the invite code
`PREVIEW`. Rebuild (the first command) after each change and reload the page. `python tools/site/build.py --out
dist/site --no-tar` builds just the public pages (no Slums Street, market or World), which is faster.

## What's where

| You want to change | Edit |
|---|---|
| The home page | `docs/site/home.html` |
| Colours, light/dark, accents | `docs/site/assets/tokens.css` |
| Layout and looks | `docs/site/assets/site.css` |
| Slums-chan pictures | `docs/site/assets/brand/` (home pictures are listed in `home.html`); the secrets are in `docs/site/secret/` and `docs/site/assets/theme.js` |
| Patch notes | `docs/site/releases/<version>.md` |
| Guides | `docs/wiki/` (and `docs/site/guide-overrides/`) |
| Sign-in, sign-up, forgot password | `docs/site/login.html`, `docs/site/forgot.html`, `docs/site/assets/login.js`, `forgot.js` |

The build checks every page (no private addresses, no game-company names, working links); a FAIL line says what and
where. Tests: `python -m unittest discover -s tools/site -p "test_*.py"`.

## With Claude Code

Start Claude Code in the repo folder. It reads `CLAUDE.md`, the same rules the other sessions follow (live is yours,
secrets never pass through it, publishing needs your go). Ask it things like "make the home page's green a bit
darker" and look at the preview. When you like it: commit, push your branch, and tell the release lead to merge it.
