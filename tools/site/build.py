#!/usr/bin/env python3
"""Build the public SlumsMS website into dist/site/ (static files only). Standard library only.

    python3 tools/site/build.py                 # build dist/site/ and dist/slums-site.tar.gz, then check them
    python3 tools/site/build.py --out DIR --no-tar
    python3 -m http.server -d dist/site 8000    # preview

Sources (docs/site/):
    home.html                 the home page body; {{latest_*}} is filled from the newest release
    releases/<version>.md     one file per release: front matter, then simple markdown (see below)
    assets/                   copied as-is (site.css, leaf.svg)
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
import html
import json
import os
import re
import shutil
import sys
import tarfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import check  # noqa: E402

SRC = os.path.join(REPO, "docs", "site")
WIKI = os.path.join(REPO, "docs", "wiki")
SITE = "SlumsMS"
esc = html.escape


# ── layout ───────────────────────────────────────────────────────────────────────────────────────

NAV = [("index.html", "Home", "home"), ("patch-notes/", "Patch notes", "notes"), ("guide/", "Guide", "guide"),
       ("report/", "Report", "report"), ("index.html#play", "Play", "play")]


def layout(title, body, root, section, description="", base=""):
    """root: relative path from this page to the site root ('' or '../' ...)."""
    links = "".join(
        f'<a href="{root}{href}"{" aria-current=page" if key == section else ""}>{esc(label)}</a>'
        for href, label, key in NAV)
    full = SITE if title == SITE else f"{title} - {SITE}"
    desc = f'\n<meta name="description" content="{esc(description)}">' if description else ""
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">{f'\n<base href="{base}">' if base else ""}
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(full)}</title>{desc}
<meta name="referrer" content="no-referrer">
<link rel="icon" href="{root}assets/leaf.svg" type="image/svg+xml">
<link rel="stylesheet" href="{root}assets/site.css">
</head>
<body>
<a class="skip" href="#main">Skip to content</a>
<header class="site-head">
  <a class="brand" href="{root}index.html"><img src="{root}assets/leaf.svg" alt="" width="30" height="30"><b>Slums<span>MS</span></b></a>
  <nav class="site-nav" aria-label="Site">{links}</nav>
</header>
<main id="main">
{body}
</main>
<footer class="site-foot">
  <span>SlumsMS is a small private MapleStory v83 world for friends. A fan project, not affiliated with Nexon.</span>
  <span><a href="{root}patch-notes/">Patch notes</a> &middot; <a href="{root}guide/">Player guide</a></span>
</footer>
</body>
</html>
"""


def write(out, rel, text):
    path = os.path.join(out, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def meta_comment(text):
    """Leading '<!-- k: v | k: v -->' comment -> (dict, rest)."""
    m = re.match(r"\s*<!--(.*?)-->\s*", text, re.S)
    meta = {}
    if not m:
        return meta, text
    for part in m.group(1).split("|"):
        if ":" in part:
            k, v = part.split(":", 1)
            meta[k.strip()] = v.strip()
    return meta, text[m.end():]


# ── release markdown ─────────────────────────────────────────────────────────────────────────────

TAGS = {"new": "New", "fix": "Fix", "change": "Change", "same": "Same"}


def inline(s):
    s = esc(s, quote=True)
    codes = []

    def keep(m):
        codes.append(m.group(1))
        return f"\x00{len(codes) - 1}\x00"

    s = re.sub(r"`([^`]+)`", keep, s)
    s = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", s)
    s = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<i>\1</i>", s)
    s = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', s)
    return re.sub("\x00(\\d+)\x00", lambda m: f"<code>{codes[int(m.group(1))]}</code>", s)


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-") or "section"


def parse_release(path):
    text = read(path)
    m = re.match(r"---\n(.*?)\n---\n", text, re.S)
    if not m:
        raise SystemExit(f"{path}: needs front matter between --- lines")
    meta = {}
    for line in m.group(1).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            meta[k.strip()] = v.strip()
    for key in ("version", "title", "date", "summary"):
        if not meta.get(key):
            raise SystemExit(f"{path}: front matter is missing '{key}'")
    if os.path.basename(path) != meta["version"] + ".md":
        raise SystemExit(f"{path}: file name must be the version ({meta['version']}.md)")

    blocks = []  # ("p", text) ("quote", text) ("h2", title, id, cards) ("h3", text) ("li", tag, text)
    for raw in text[m.end():].splitlines():
        line = raw.rstrip()
        if not line.strip():
            blocks.append(("blank",))
            continue
        if raw.startswith("  ") and blocks and blocks[-1][0] in ("li", "p", "quote"):
            b = blocks[-1]
            blocks[-1] = b[:-1] + (b[-1] + " " + line.strip(),)
            continue
        if line.startswith("## "):
            title, attrs = line[3:], ""
            am = re.search(r"\s*\{([^}]*)\}\s*$", title)
            if am:
                title, attrs = title[:am.start()], am.group(1)
            ident = re.search(r"#([\w-]+)", attrs)
            blocks.append(("h2", title.strip(), ident.group(1) if ident else slug(title), ".cards" in attrs))
        elif line.startswith("### "):
            blocks.append(("h3", line[4:].strip()))
        elif line.startswith("- "):
            item = line[2:].strip()
            tm = re.match(r"\[(\w+)\]\s+", item)
            tag = None
            if tm and tm.group(1).lower() in TAGS:
                tag, item = tm.group(1).lower(), item[tm.end():]
            elif tm:
                raise SystemExit(f"{path}: unknown tag [{tm.group(1)}] (use {', '.join(TAGS.values())})")
            blocks.append(("li", tag, item))
        elif line.startswith("> "):
            if blocks and blocks[-1][0] == "quote":
                blocks[-1] = ("quote", blocks[-1][1] + " " + line[2:].strip())
            else:
                blocks.append(("quote", line[2:].strip()))
        elif blocks and blocks[-1][0] == "p":
            blocks[-1] = ("p", blocks[-1][1] + " " + line.strip())
        else:
            blocks.append(("p", line.strip()))
    meta["blocks"] = [b for b in blocks if b[0] != "blank"]
    meta["key"] = tuple(int(x) if x.isdigit() else x for x in re.split(r"[.\-]", meta["version"]))
    meta["counts"] = {t: sum(1 for b in meta["blocks"] if b[0] == "li" and b[1] == t) for t in TAGS}
    return meta


def render_release(rel):
    intro, sections, cur = [], [], None
    for b in rel["blocks"]:
        if b[0] == "h2":
            cur = {"title": b[1], "id": b[2], "cards": b[3], "blocks": []}
            sections.append(cur)
        else:
            (cur["blocks"] if cur else intro).append(b)
    ids = [s["id"] for s in sections]
    if len(ids) != len(set(ids)):
        raise SystemExit(f"release {rel['version']}: two sections share an id")

    def blocks_html(blocks, cards=False):
        out, items = [], []

        def flush():
            if not items:
                return
            if cards:
                out.append('<div class="hl-grid">')
                for _, _, text in items:
                    hm = re.match(r"\*\*(.+?)\*\*\s*(.*)", text)
                    head, rest = (hm.group(1).rstrip("."), hm.group(2)) if hm else ("", text)
                    out.append(f'<article class="hl"><h3>{inline(head)}</h3><p>{inline(rest)}</p></article>')
                out.append("</div>")
            else:
                out.append('<ul class="items">')
                for _, tag, text in items:
                    pill = f'<span class="tag {tag}">{TAGS[tag]}</span>' if tag else ""
                    out.append(f"<li>{pill}<div>{inline(text)}</div></li>")
                out.append("</ul>")
            items.clear()

        for b in blocks:
            if b[0] == "li":
                items.append(b)
                continue
            flush()
            if b[0] == "p":
                out.append(f"<p>{inline(b[1])}</p>")
            elif b[0] == "quote":
                out.append(f'<div class="callout"><p>{inline(b[1])}</p></div>')
            elif b[0] == "h3":
                out.append(f"<h3>{inline(b[1])}</h3>")
        flush()
        return "\n".join(out)

    toc = "".join(f'<li><a href="#{s["id"]}">{inline(s["title"])}</a></li>' for s in sections)
    body = [f'<div class="notes-head"><p class="eyebrow">Patch notes</p>'
            f'<h1>SlumsMS <span class="ver">{esc(rel["version"])}</span></h1>'
            f'<p class="sub">{esc(rel["title"])}</p><p class="meta">{esc(rel["date"])}</p></div>',
            f'<div class="notes">{blocks_html(intro)}',
            f'<ul class="toc" aria-label="Sections">{toc}</ul>']
    for s in sections:
        body.append(f'<section id="{esc(s["id"])}"><h2>{inline(s["title"])}</h2>'
                    f'{blocks_html(s["blocks"], s["cards"])}</section>')
    body.append("</div>")
    return "\n".join(body)


def counts_text(rel):
    c = rel["counts"]
    parts = [f"{c['new']} new"] if c["new"] else []
    if c["change"]:
        parts.append(f"{c['change']} changes")
    if c["fix"]:
        parts.append(f"{c['fix']} fixes")
    return "".join(f"<span>{p}</span>" for p in parts)


def build_releases(out):
    rdir = os.path.join(SRC, "releases")
    rels = [parse_release(os.path.join(rdir, n)) for n in os.listdir(rdir) if n.endswith(".md")]
    if not rels:
        raise SystemExit("no releases in docs/site/releases/")
    rels.sort(key=lambda r: r["key"], reverse=True)
    for i, rel in enumerate(rels):
        pager = ['<nav class="pager" aria-label="Releases">']
        pager.append(f'<a class="btn" href="../">&larr; All patch notes</a>')
        if i + 1 < len(rels):
            older = rels[i + 1]["version"]
            pager.append(f'<a class="btn" href="../{esc(older)}/">Older: {esc(older)}</a>')
        if i > 0:
            newer = rels[i - 1]["version"]
            pager.append(f'<a class="btn" href="../{esc(newer)}/">Newer: {esc(newer)}</a>')
        pager.append("</nav>")
        body = render_release(rel) + "\n" + "".join(pager)
        write(out, f"patch-notes/{rel['version']}/index.html",
              layout(f"{rel['version']} patch notes", body, "../../", "notes", rel["summary"]))
    items = "".join(
        f'<li><a class="rel" href="{esc(r["version"])}/"><span class="v">{esc(r["version"])}</span>'
        f'<span class="t">{esc(r["title"])}</span><span class="d">{esc(r["date"])} &middot; '
        f'{esc(r["summary"])}</span><span class="c">{counts_text(r)}</span></a></li>' for r in rels)
    body = ('<div class="notes-head"><p class="eyebrow">SlumsMS</p><h1>Patch notes</h1>'
            '<p class="sub">Every release, newest first.</p></div>'
            f'<ul class="rel-list">{items}</ul>')
    write(out, "patch-notes/index.html", layout("Patch notes", body, "../", "notes",
                                                "Every SlumsMS release, newest first."))
    return rels


# ── the guide (a restyled copy of the player wiki) ──────────────────────────────────────────────

def wiki_body(text, name):
    m = re.search(r'<div class="box-b">\n(.*)\n</div></div>\n</main>', text, re.S)
    t = re.search(r"<h1>(.*?)</h1>", text)
    if not m or not t:
        raise SystemExit(f"docs/wiki/{name}: layout changed; update wiki_body() in tools/site/build.py")
    return html.unescape(t.group(1)), m.group(1)


def wiki_nav(text):
    nav = re.search(r'<nav class="side">(.*?)</nav>', text, re.S)
    return re.findall(r'<a href="([\w.-]+\.html)"[^>]*>(.*?)</a>', nav.group(1)) if nav else []


def clean_wiki(body):
    body = re.sub(r'<(p|div) class="(?:owner|src)">.*?</\1>\s*', "", body, flags=re.S)
    body = re.sub(r'\s*<span class="owner">.*?</span>', "", body, flags=re.S)
    # drop a heading whose section ended up empty
    body = re.sub(r"<h2[^>]*>(?:(?!</h2>).)*</h2>\s*(?=<h2|\Z)", "", body, flags=re.S)
    return body.strip() + "\n"


def build_guide(out):
    cfg = json.loads(read(os.path.join(SRC, "guide-fixes.json")))
    skip = set(cfg.get("skip", []))
    pages = sorted(n for n in os.listdir(WIKI) if n.endswith(".html") and n not in skip)
    if "index.html" not in pages:
        raise SystemExit("docs/wiki/index.html is missing; run docs/wiki/build.py")
    nav = [(f, t) for f, t in wiki_nav(read(os.path.join(WIKI, "index.html"))) if f in pages]
    fixes = cfg.get("fixes", [])
    used = set()
    overrides = os.path.join(SRC, "guide-overrides")
    for name in pages:
        title, body = wiki_body(read(os.path.join(WIKI, name)), name)
        for i, fx in enumerate(fixes):
            if fx["page"] == name:
                if fx["find"] not in body:
                    raise SystemExit(f"guide-fixes.json: {name} no longer contains {fx['find']!r}; "
                                     "update or remove that fix")
                body = body.replace(fx["find"], fx["replace"])
                used.add(i)
        ov = os.path.join(overrides, name)
        if os.path.exists(ov):
            body = meta_comment(read(ov))[1]
        for s in skip:  # links to skipped pages: the changelog is the patch notes now
            body = body.replace(f'href="{s}"', 'href="../patch-notes/"')
        body = clean_wiki(body)
        links = "".join(f'<li><a href="{f}"{" aria-current=page" if f == name else ""}>{esc(html.unescape(t))}'
                        f'</a></li>' for f, t in nav)
        links += '<li><a href="../patch-notes/">Patch notes</a></li>'
        page = (f'<div class="guide"><nav class="guide-nav" aria-label="Guide pages"><p>Player guide</p>'
                f'<ul>{links}</ul></nav><article class="doc"><h1>{esc(title)}</h1>\n{body}</article></div>')
        label = "Player guide" if name == "index.html" else title
        write(out, f"guide/{name}", layout(label, page, "../", "guide"))
    unused = [fixes[i]["page"] for i in range(len(fixes)) if i not in used]
    if unused:
        raise SystemExit(f"guide-fixes.json: fixes for pages that aren't built: {unused}")
    return pages


# ── home, 404, assets ────────────────────────────────────────────────────────────────────────────

def build_home(out, rels):
    meta, body = meta_comment(read(os.path.join(SRC, "home.html")))
    latest = rels[0]
    for k, v in {"latest_version": latest["version"], "latest_title": latest["title"],
                 "latest_summary": latest["summary"], "latest_date": latest["date"],
                 "latest_url": f"patch-notes/{latest['version']}/"}.items():
        body = body.replace("{{" + k + "}}", esc(v))
    left = re.findall(r"\{\{\w+\}\}", body)
    if left:
        raise SystemExit(f"home.html: unknown placeholders {left}")
    write(out, "index.html", layout(SITE, body, "", "home", meta.get("description", "")))
    # 404 page: the server sends it for a missing URL at any depth, so <base href="/"> anchors its relative links.
    nf = ('<div class="notfound"><h1>Nothing here</h1><p>This page wandered off into the Sleepywood dungeon.</p>'
          '<p><a class="btn primary" href="index.html">Back to the home page</a></p></div>')
    write(out, "404.html", layout("Not found", nf, "", "", base="/"))


def build_report(out):
    body = read(os.path.join(SRC, "report.html"))
    write(out, "report/index.html", layout("Report a bug or request a feature", body, "../", "report",
                                           "Tell the SlumsMS developers about a bug or an idea."))


def copy_assets(out):
    shutil.copytree(os.path.join(SRC, "assets"), os.path.join(out, "assets"))


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


def build(out, tar_path=None):
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    copy_assets(out)
    rels = build_releases(out)
    guide = build_guide(out)
    build_home(out, rels)
    build_report(out)
    problems = check.check_all(out)
    if problems:
        for p in problems:
            print("FAIL", p, file=sys.stderr)
        raise SystemExit(f"site check failed with {len(problems)} problem(s); nothing was packed")
    if tar_path:
        make_tar(out, tar_path)
    return rels, guide


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(REPO, "dist", "site"))
    ap.add_argument("--tar", default=os.path.join(REPO, "dist", "slums-site.tar.gz"))
    ap.add_argument("--no-tar", action="store_true")
    args = ap.parse_args()
    rels, guide = build(args.out, None if args.no_tar else args.tar)
    n = sum(len(fs) for _, _, fs in os.walk(args.out))
    print(f"built {args.out}: home, {len(rels)} release(s) ({', '.join(r['version'] for r in rels)}), "
          f"{len(guide)} guide pages, {n} files; check passed")
    if not args.no_tar:
        print(f"packed {args.tar} ({os.path.getsize(args.tar):,} bytes)")


if __name__ == "__main__":
    main()
