"""Guides: a restyled copy of the generated player wiki (docs/wiki/*.html, built by docs/wiki/build.py), with
owner-check notes, source notes and empty sections removed."""
import html
import json
import os
import re

from common import SRC, WIKI, esc, layout, meta_comment, read, write


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


def build(out):
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
