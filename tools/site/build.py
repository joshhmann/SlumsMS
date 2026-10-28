#!/usr/bin/env python3
"""Build the public SlumsMS website into dist/site/ (static files only). Standard library only.

    python3 tools/site/build.py                 # the PUBLIC site: dist/site/ and dist/slums-site.tar.gz, checked
    python3 tools/site/build.py --out DIR --no-tar
    python3 tools/site/build.py --world         # also World (live world, feed, rankings, economy) from the site export
    python3 tools/site/build.py --private       # also Slums Street (database) and Slums Market, into dist/site-private/
                                                # (on 164 those two sit behind the sign-in, LOGIN.md)
    python3 tools/site/build.py --section notes --out DIR   # rebuild one section inside an existing build
    python3 -m http.server -d dist/site 8000    # preview

Code: common.py (paths, layout, megaphone rail, inline markdown) and one module per section in sections/:
notes (News), guide (Guides), pages (home, 404, report, login), world (public, needs the server's site export), and
the signed-in database (Slums Street) and market (Slums Market).

Sources (docs/site/):
    home.html                 the home page body; {{latest_*}} is filled from the newest release
    releases/<version>.md     one file per release: front matter, then simple markdown (see below)
    assets/                   copied as-is (tokens.css, site.css, theme.js, fonts/, leaf.svg)
    secret/                   the hidden Slums-chan stickers; private builds only (signed in, theme.js)
    guide-overrides/<p>.html  replaces a wiki page's body on the public site
    guide-fixes.json          small text fixes for the wiki copy (each must still match, or the build stops)
The guide is a copy of the generated player wiki (docs/wiki/*.html, built by docs/wiki/build.py), restyled, with
owner-check notes, source notes and empty sections removed.

Release file format:
    ---
    version: 1.5.1
    title: Short name of the release
    date: November 2026
    summary: One or two sentences for the home page card and the patch notes list.
    ---
    Intro paragraph.
    > A callout line (**bold**, `code` and [links](url) work everywhere).
    ## Section title {#anchor}          (add .cards inside the braces to show the list as cards)
    - [New] A bullet. Tags: [New] [Fix] [Change] [Same]; a bullet can also have no tag.
      An indented line continues the bullet.
To add a release: write docs/site/releases/<version>.md, run this script, publish (docs/site/DEPLOY.md).
The build fails if the output has a forbidden string or a broken link (tools/site/check.py).
"""
import argparse
import os
import shutil
import sys
import tarfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import check  # noqa: E402
import common  # noqa: E402
from common import REPO, SRC, esc  # noqa: E402
from sections import admin, database, dbdata, guide, market, notes, pages, world  # noqa: E402

# kept here so older callers and the tests can keep using build.<name>
from common import inline, layout, meta_comment, slug  # noqa: E402,F401
from sections.guide import clean_wiki, wiki_body, wiki_nav  # noqa: E402,F401
from sections.notes import TAGS, parse_release, render_release  # noqa: E402,F401

PUBLIC = ["notes", "guide", "pages"]
PRIVATE = ["database", "market", "admin"]  # behind the sign-in (LOGIN.md); admin/ for GM level 3+ only
LIVE = ["world"]  # public, but only with --world: it needs the server's site export (or its samples)
HS_DATA = None  # --hs-data: the build_data.py output for the database (default: sections/dbdata.py DEFAULT_DIR)
DASHBOARD_WEB = None  # --dashboard-web: the staff dashboard to put in admin/ (default: tools/dashboard/web)
MARKET_DATA = None  # --market-data: the server's market export (default: sections/market.py DEFAULT_DIR, the samples)
SITE_DATA = None  # --site-data: the server's site export, Server/data/site (default: sections/world.py DEFAULT_DIR)


def copy_assets(out, private=False):
    shutil.copytree(os.path.join(SRC, "assets"), os.path.join(out, "assets"), dirs_exist_ok=True)
    if private:  # the secret stickers: signed-in players only (nginx asks the login service, like database/)
        shutil.copytree(os.path.join(SRC, "secret"), os.path.join(out, "secret"), dirs_exist_ok=True)
    # friends only: search engines are asked to stay out (owner, 2026-10-26); every page also says noindex
    shutil.copyfile(os.path.join(SRC, "robots.txt"), os.path.join(out, "robots.txt"))


def set_context(rels, sections):
    """The nav and the megaphone rail depend on which sections this build has and on the newest releases."""
    common.BUILT.clear()
    common.BUILT.update(s for s in sections if s in common.OPTIONAL)
    common.RAIL[:] = [(f'<em>{esc(r["version"])}</em> {esc(r["title"])}', f'patch-notes/{r["version"]}/')
                      for r in rels[:3]]
    if "market" in sections:  # the NX rate and the latest sales lead the rail
        common.RAIL[:0] = market.rail_lines(market.load(MARKET_DATA), dbdata.load(HS_DATA))
    common.RAIL.extend((t, None) for t in common.RAIL_EXTRA)


def build_section(name, out, rels, site_root=None):
    if name == "notes":
        return notes.build(out)
    if name == "guide":
        return guide.build(out, HS_DATA)
    if name == "database":
        return database.build(out, HS_DATA)
    if name == "market":
        return market.build(out, dbdata.load(HS_DATA), MARKET_DATA, site_root)
    if name == "admin":
        return admin.build(out, DASHBOARD_WEB)
    if name == "world":
        return world.build(out, SITE_DATA, MARKET_DATA, dbdata.load(HS_DATA) if "database" in common.BUILT else None)
    if name == "pages":
        pages.build_home(out, rels)
        pages.build_report(out)
        pages.build_login(out)
        pages.build_forgot(out)
        pages.build_join(out)
        return None
    raise SystemExit(f"unknown section {name!r}")


def finish(out, tar_path, private=False, only=None):
    problems = check.check_all(out, private, only)
    if problems:
        for p in problems:
            print("FAIL", p, file=sys.stderr)
        raise SystemExit(f"site check failed with {len(problems)} problem(s); nothing was packed")
    if tar_path:
        make_tar(out, tar_path)


def sections_for(private, live):
    return PUBLIC + (LIVE if live else []) + (PRIVATE if private else [])


def build(out, tar_path=None, private=False, live=False):
    sections = sections_for(private, live)
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    copy_assets(out, private)
    rels = notes.releases()
    set_context(rels, sections)
    results = {name: build_section(name, out, rels) for name in sections}
    common.clean_links(out)
    finish(out, tar_path, private)
    return results["notes"], results["guide"]


def rebuild_section(name, out, private=False, live=False):
    """Rebuild one section in place (the market every 15 minutes, World every 2), then check that section."""
    if not os.path.isfile(os.path.join(out, "index.html")):
        raise SystemExit(f"{out} has no full build yet; run a full build first")
    rels = notes.releases()
    set_context(rels, sections_for(private, live or name == "world"))
    copy_assets(out, private)
    folder = {"notes": "patch-notes", "guide": "guide", "database": "database", "market": "market",
              "world": "world", "admin": "admin"}.get(name)
    if not folder:  # pages: a few single files, written in place
        build_section(name, out, rels)
        common.clean_links(out)
        finish(out, None, private)
        return
    # build beside the live folder, swap it in with two renames, check, and swap back if the check fails,
    # so a visitor never sees a half-written section (the market timer on 164 does this every 15 minutes)
    stage = os.path.join(out, f".stage-{folder}")
    shutil.rmtree(stage, ignore_errors=True)
    os.makedirs(stage)
    build_section(name, stage, rels, site_root=out)
    common.clean_links(stage, folder)
    live, old = os.path.join(out, folder), os.path.join(out, f".old-{folder}")
    shutil.rmtree(old, ignore_errors=True)
    if os.path.isdir(live):
        os.rename(live, old)
    os.rename(os.path.join(stage, folder), live)
    shutil.rmtree(stage, ignore_errors=True)
    try:
        finish(out, None, private, only=folder)
    except SystemExit:
        shutil.rmtree(live, ignore_errors=True)
        if os.path.isdir(old):
            os.rename(old, live)
        raise
    shutil.rmtree(old, ignore_errors=True)


def make_tar(out, tar_path):
    os.makedirs(os.path.dirname(tar_path), exist_ok=True)

    def norm(ti):
        ti.uid = ti.gid = 0
        ti.uname = ti.gname = "root"
        ti.mode = 0o755 if ti.isdir() else 0o644
        return ti

    with tarfile.open(tar_path, "w:gz") as tf:
        for dirpath, dirs, files in os.walk(out):
            dirs.sort()
            for name in sorted(files):
                path = os.path.join(dirpath, name)
                tf.add(path, arcname=os.path.join("slums-site", os.path.relpath(path, out)), filter=norm)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out")
    ap.add_argument("--tar")
    ap.add_argument("--no-tar", action="store_true")
    ap.add_argument("--private", action="store_true", help="include the private sections (Database, Market)")
    ap.add_argument("--section", help="rebuild only this section inside an existing --out")
    ap.add_argument("--hs-data", help="Hidden Street dataset folder for the database section")
    ap.add_argument("--market-data", help="market export folder (default: the samples from tools/site/market_sample.py)")
    ap.add_argument("--dashboard-web", help="the staff dashboard copied into admin/ (default: tools/dashboard/web)")
    ap.add_argument("--world", action="store_true", help="include World (live world, feed, rankings, economy)")
    ap.add_argument("--site-data", help="site export folder, Server/data/site (default: tools/site/site_sample.py's)")
    args = ap.parse_args()
    global HS_DATA, MARKET_DATA, SITE_DATA, DASHBOARD_WEB
    HS_DATA, MARKET_DATA, SITE_DATA = args.hs_data, args.market_data, args.site_data
    DASHBOARD_WEB = args.dashboard_web
    out = args.out or os.path.join(REPO, "dist", "site-private" if args.private else "site")
    if args.section:
        rebuild_section(args.section, out, args.private, args.world)
        print(f"rebuilt section {args.section} in {out}; check passed")
        return
    tar = None if args.no_tar else (args.tar or os.path.join(
        REPO, "dist", "slums-site-private.tar.gz" if args.private else "slums-site.tar.gz"))
    rels, guide_pages = build(out, tar, args.private, args.world)
    n = sum(len(fs) for _, _, fs in os.walk(out))
    print(f"built {out}{' (private)' if args.private else ''}: home, {len(rels)} release(s) "
          f"({', '.join(r['version'] for r in rels)}), {len(guide_pages)} guide pages, {n} files; check passed")
    if tar:
        print(f"packed {tar} ({os.path.getsize(tar):,} bytes)")


if __name__ == "__main__":
    main()
