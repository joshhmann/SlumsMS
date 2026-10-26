#!/usr/bin/env python3
"""Build the public SlumsMS website into dist/site/ (static files only). Standard library only.

    python3 tools/site/build.py                 # the PUBLIC site: dist/site/ and dist/slums-site.tar.gz, checked
    python3 tools/site/build.py --out DIR --no-tar
    python3 tools/site/build.py --private       # also Database and Market, into dist/site-private/ (never rsync it
                                                # to the public folder before 1.6.0 is live)
    python3 tools/site/build.py --section notes --out DIR   # rebuild one section inside an existing build
    python3 -m http.server -d dist/site 8000    # preview

Code: common.py (paths, layout, megaphone rail, inline markdown) and one module per section in sections/:
notes (News), guide (Guides), pages (home, 404, report); database and market join in later steps.

Sources (docs/site/):
    home.html                 the home page body; {{latest_*}} is filled from the newest release
    releases/<version>.md     one file per release: front matter, then simple markdown (see below)
    assets/                   copied as-is (tokens.css, site.css, theme.js, fonts/, leaf.svg)
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
from sections import guide, notes, pages  # noqa: E402

# kept here so older callers and the tests can keep using build.<name>
from common import inline, layout, meta_comment, slug  # noqa: E402,F401
from sections.guide import clean_wiki, wiki_body, wiki_nav  # noqa: E402,F401
from sections.notes import TAGS, parse_release, render_release  # noqa: E402,F401

PUBLIC = ["notes", "guide", "pages"]
PRIVATE = []  # "database", "market": private until 1.6.0 is live (plan steps 5 and 7)


def copy_assets(out):
    shutil.copytree(os.path.join(SRC, "assets"), os.path.join(out, "assets"), dirs_exist_ok=True)


def set_context(rels, sections):
    """The nav and the megaphone rail depend on which sections this build has and on the newest releases."""
    common.BUILT.clear()
    common.BUILT.update(s for s in sections if s in common.OPTIONAL)
    common.RAIL[:] = [(f'<em>{esc(r["version"])}</em> {esc(r["title"])}', f'patch-notes/{r["version"]}/')
                      for r in rels[:3]]
    common.RAIL.extend((t, None) for t in common.RAIL_EXTRA)


def build_section(name, out, rels):
    if name == "notes":
        return notes.build(out)
    if name == "guide":
        return guide.build(out)
    if name == "pages":
        pages.build_home(out, rels)
        pages.build_report(out)
        return None
    raise SystemExit(f"unknown section {name!r}")


def finish(out, tar_path, private=False):
    problems = check.check_all(out, private)
    if problems:
        for p in problems:
            print("FAIL", p, file=sys.stderr)
        raise SystemExit(f"site check failed with {len(problems)} problem(s); nothing was packed")
    if tar_path:
        make_tar(out, tar_path)


def build(out, tar_path=None, private=False):
    sections = PUBLIC + (PRIVATE if private else [])
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    copy_assets(out)
    rels = notes.releases()
    set_context(rels, sections)
    results = {name: build_section(name, out, rels) for name in sections}
    finish(out, tar_path, private)
    return results["notes"], results["guide"]


def rebuild_section(name, out, private=False):
    """Rebuild one section in place, e.g. the market every 15 minutes, then check the whole output again."""
    if not os.path.isfile(os.path.join(out, "index.html")):
        raise SystemExit(f"{out} has no full build yet; run a full build first")
    rels = notes.releases()
    set_context(rels, PUBLIC + (PRIVATE if private else []))
    copy_assets(out)
    build_section(name, out, rels)
    finish(out, None, private)


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
    args = ap.parse_args()
    out = args.out or os.path.join(REPO, "dist", "site-private" if args.private else "site")
    if args.section:
        rebuild_section(args.section, out, args.private)
        print(f"rebuilt section {args.section} in {out}; check passed")
        return
    tar = None if args.no_tar else (args.tar or os.path.join(
        REPO, "dist", "slums-site-private.tar.gz" if args.private else "slums-site.tar.gz"))
    rels, guide_pages = build(out, tar, args.private)
    n = sum(len(fs) for _, _, fs in os.walk(out))
    print(f"built {out}{' (private)' if args.private else ''}: home, {len(rels)} release(s) "
          f"({', '.join(r['version'] for r in rels)}), {len(guide_pages)} guide pages, {n} files; check passed")
    if tar:
        print(f"packed {tar} ({os.path.getsize(tar):,} bytes)")


if __name__ == "__main__":
    main()
