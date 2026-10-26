"""Guides: a restyled copy of the generated player wiki (docs/wiki/*.html, built by docs/wiki/build.py), with
owner-check notes, source notes and empty sections removed."""
import html
import json
import os
import re

import common
from common import SRC, WIKI, esc, layout, meta_comment, read, write


# An item icon in front of each guide link, like the old fan-site menus (icons: docs/site/assets/icons, icons.py --chrome).
GUIDE_ICONS = {"index.html": 4001126, "getting-started.html": 4031180, "rates.html": 9000000, "hubs.html": 5030000,
               "getting-around.html": 2030000, "jobs.html": 1302000, "leveling.html": 4000000, "travel.html": 4031045,
               "rebirths.html": 2340000, "regulars.html": 5072000, "shops.html": 5220000, "commands.html": 5390000,
               "money.html": 9000003, "faq.html": 2000005, "guidebook.html": 4161001, "patch-notes": 5071000}


def db_links(data):
    """Table-cell text -> database page, for the guide's cross-links. "Street: Map" matches a map exactly (several
    copies of a town: the lowest id); a bare name matches a monster or a map whose name is unique."""
    links, names = {}, {}
    for kind, folder in (("maps", "maps"), ("mobs", "mobs")):
        for ident in sorted(data[kind]):
            rec = data[kind][ident]
            names.setdefault(rec["name"], set()).add((folder, ident))
            if kind == "maps" and rec.get("street"):
                links.setdefault(f'{rec["street"]}: {rec["name"]}', (folder, ident))
    for name, hits in names.items():
        if len(hits) == 1 and name not in links:
            links[name] = next(iter(hits))
    return links


def link_cells(body, links):
    def repl(m):
        text = html.unescape(m.group(1))
        # the guide sometimes names the region ("Leafre: ...") where the data has the street; then the name alone
        hit = links.get(text) or (links.get(text.split(": ", 1)[1]) if ": " in text else None)
        return f'<td><a href="../database/{hit[0]}/{hit[1]}.html">{m.group(1)}</a></td>' if hit else m.group(0)
    return re.sub(r"<td>([^<]*[A-Za-z][^<]*)</td>", repl, body)


def icon(page, root):
    i = GUIDE_ICONS.get(page)
    return f'<span class="ico"><img src="{root}assets/icons/{i}.png" alt=""></span>' if i else '<span class="ico"></span>'


def wiki_body(text, name):
    m = re.search(r'<div class="box-b">\n(.*)\n</div></div>\n</main>', text, re.S)
    t = re.search(r"<h1>(.*?)</h1>", text)
    if not m or not t:
        raise SystemExit(f"docs/wiki/{name}: layout changed; update wiki_body() in tools/site/build.py")
    return html.unescape(t.group(1)), m.group(1)


# The public site names the game "Slums Story" and never MapleStory, Nexon or the client version (owner, 2026-10-26;
# check.py enforces it). Applied to the guide copy in order, after guide-fixes.json; the wiki keeps its own wording.
PUBLIC_WORDING = [
    ("small, friends-only MapleStory v83 world", "small, friends-only Slums Story world"),
    ("The old v83 Free Market", "The old Free Market"),
    ("MapleStory v83", "Slums Story"), ("GMS v83", "the original game"), ("post-v83", "newer"),
    ("from GMS", "from the original game"), ("than GMS", "than the original game"), ("in GMS", "in the original game"),
    ("GMS", "the original game"),
    ("as they did in v83", "as they did in the original game"), ("than in v83", "than in the original game"),
    ("The client is v83", "The client is the classic one"), ("v83", "classic"), ("MapleStory", "Slums Story"),
]


def public_wording(body):
    for old, new in PUBLIC_WORDING:
        body = body.replace(old, new)
    return body


def wiki_nav(text):
    nav = re.search(r'<nav class="side">(.*?)</nav>', text, re.S)
    return re.findall(r'<a href="([\w.-]+\.html)"[^>]*>(.*?)</a>', nav.group(1)) if nav else []


def clean_wiki(body):
    body = re.sub(r'<(p|div) class="(?:owner|src)">.*?</\1>\s*', "", body, flags=re.S)
    body = re.sub(r'\s*<span class="owner">.*?</span>', "", body, flags=re.S)
    # drop a heading whose section ended up empty
    body = re.sub(r"<h2[^>]*>(?:(?!</h2>).)*</h2>\s*(?=<h2|\Z)", "", body, flags=re.S)
    return body.strip() + "\n"


def build(out, data_dir=None):
    cfg = json.loads(read(os.path.join(SRC, "guide-fixes.json")))
    skip = set(cfg.get("skip", []))
    pages = sorted(n for n in os.listdir(WIKI) if n.endswith(".html") and n not in skip)
    if "index.html" not in pages:
        raise SystemExit("docs/wiki/index.html is missing; run docs/wiki/build.py")
    nav = [(f, t) for f, t in wiki_nav(read(os.path.join(WIKI, "index.html"))) if f in pages]
    fixes = cfg.get("fixes", [])
    used = set()
    # cross-links into the database only in a build that has it (private until 1.6.0 is live)
    cross = {}
    if "database" in common.BUILT:
        from sections import dbdata
        cross = db_links(dbdata.load(data_dir))
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
        body = public_wording(clean_wiki(body))
        if cross:
            body = link_cells(body, cross)
        links = "".join(f'<li><a href="{f}"{" aria-current=page" if f == name else ""}>{icon(f, "../")}'
                        f'{esc(html.unescape(t))}</a></li>' for f, t in nav)
        links += f'<li><a href="../patch-notes/">{icon("patch-notes", "../")}Patch notes</a></li>'
        page = (f'<div class="guide"><nav class="guide-nav panel" aria-label="Guide pages"><p class="bar">Player guide</p>'
                f'<ul class="iconnav">{links}</ul></nav><article class="doc"><h1>{esc(title)}</h1>\n{body}</article></div>')
        label = "Player guide" if name == "index.html" else title
        write(out, f"guide/{name}", layout(label, page, "../", "guide"))
    unused = [fixes[i]["page"] for i in range(len(fixes)) if i not in used]
    if unused:
        raise SystemExit(f"guide-fixes.json: fixes for pages that aren't built: {unused}")
    return pages
